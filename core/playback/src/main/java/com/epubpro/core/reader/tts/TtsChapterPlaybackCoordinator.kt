package com.epubpro.core.reader.tts

import com.epubpro.core.reader.engine.EpubChapterHeader
import com.epubpro.core.reader.engine.EpubEngine
import com.epubpro.core.convert.HtmlConverter
import com.epubpro.core.storage.ConvertDictionaryStore
import com.epubpro.core.storage.EpubStorageManager
import com.epubpro.domain.model.AiChapterStatus
import com.epubpro.domain.model.Book
import com.epubpro.domain.model.ReadingProgress
import com.epubpro.domain.model.TtsChunk
import com.epubpro.domain.repository.AiChapterRepository
import com.epubpro.domain.repository.BookRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

data class TtsChapterContent(
    val chapterIndex: Int,
    val chapterTitle: String,
    val chunks: List<TtsChunk>,
    val usesAiContent: Boolean
)

class TtsChapterPlaybackCoordinator @Inject constructor(
    private val bookRepository: BookRepository,
    private val aiChapterRepository: AiChapterRepository,
    private val storageManager: EpubStorageManager,
    private val epubEngine: EpubEngine,
    private val convertStore: ConvertDictionaryStore
) {
    private data class Session(
        val book: Book,
        val file: File,
        val headers: List<EpubChapterHeader>,
        val preferAiContent: Boolean
    )

    private val preparationGeneration = AtomicLong(0L)

    @Volatile
    private var session: Session? = null

    suspend fun prepare(bookId: String, preferAiContent: Boolean) {
        val requestGeneration = preparationGeneration.incrementAndGet()
        withContext(Dispatchers.IO) {
            val current = session
            if (current?.book?.id == bookId && current.preferAiContent == preferAiContent) {
                return@withContext
            }
            val book = bookRepository.getBookById(bookId)
                ?: error("Không tìm thấy sách để tiếp tục TTS")
            val file = storageManager.getBookFile(book.filePath)
            require(file.isFile) { "Tệp EPUB không còn tồn tại" }
            val preparedSession = Session(
                book = book,
                file = file,
                headers = epubEngine.extractChapterHeaders(file),
                preferAiContent = preferAiContent
            )
            if (requestGeneration == preparationGeneration.get()) {
                session = preparedSession
            }
        }
    }

    suspend fun loadChapter(chapterIndex: Int): TtsChapterContent? = withContext(Dispatchers.IO) {
        val activeSession = refreshIfBookFileReplaced(session ?: error("Phiên TTS chưa được chuẩn bị"))
        val header = activeSession.headers.getOrNull(chapterIndex) ?: return@withContext null
        val sourceHtml = epubEngine.loadChapterHtml(activeSession.file, header.entryName)
        val aiHtml = if (activeSession.preferAiContent) {
            loadValidAiCache(activeSession.book.id, chapterIndex, sourceHtml)
        } else {
            null
        }
        val convertedHtml = if (aiHtml == null) loadConvertedHtml(activeSession.book.id, sourceHtml) else null
        val chunks = TtsTextParser.parseHtmlToChunks(aiHtml ?: convertedHtml ?: sourceHtml)
        TtsChapterContent(
            chapterIndex = chapterIndex,
            chapterTitle = header.title,
            chunks = chunks,
            usesAiContent = aiHtml != null
        )
    }

    suspend fun saveChapterProgress(chapterIndex: Int) {
        val activeSession = session ?: return
        val totalChapters = activeSession.headers.size.coerceAtLeast(1)
        saveReadingProgress(
            activeSession = activeSession,
            chapterIndex = chapterIndex,
            progressPercentage = chapterStartProgress(chapterIndex, totalChapters)
        )
    }

    suspend fun saveBookCompletedProgress() {
        val activeSession = session ?: return
        val totalChapters = activeSession.headers.size.coerceAtLeast(1)
        saveReadingProgress(
            activeSession = activeSession,
            chapterIndex = totalChapters - 1,
            progressPercentage = 1f
        )
    }

    private suspend fun saveReadingProgress(
        activeSession: Session,
        chapterIndex: Int,
        progressPercentage: Float
    ) {
        val totalChapters = activeSession.headers.size.coerceAtLeast(1)
        bookRepository.saveReadingProgress(
            ReadingProgress(
                bookId = activeSession.book.id,
                currentCfi = "",
                chapterIndex = chapterIndex,
                pageIndex = 1,
                progressPercentage = progressPercentage.coerceIn(0f, 1f),
                totalChapters = totalChapters
            )
        )
    }
    fun clear() {
        preparationGeneration.incrementAndGet()
        session = null
    }

    /**
     * Nạp lại phiên khi file EPUB của sách đã bị thay (ví dụ truyện web vừa tải thêm chương),
     * để TTS chạy nền đọc file mới thay vì file cũ đã bị xóa.
     *
     * @param current Phiên đang dùng.
     * @return Phiên mới nếu file đã đổi, ngược lại chính phiên hiện tại.
     */
    private suspend fun refreshIfBookFileReplaced(current: Session): Session {
        if (current.file.isFile) return current
        val book = bookRepository.getBookById(current.book.id) ?: return current
        val file = storageManager.getBookFile(book.filePath)
        if (!file.isFile) return current
        return Session(book, file, epubEngine.extractChapterHeaders(file), current.preferAiContent)
            .also { session = it }
    }

    /**
     * Convert chương bằng từ điển Hán-Việt khi sách đang bật Convert, để TTS chuyển chương ngầm
     * (màn hình tắt, Reader không hoạt động) vẫn đọc bản tiếng Việt giống Reader.
     *
     * @param bookId ID sách.
     * @param sourceHtml HTML gốc của chương.
     * @return HTML đã convert, hoặc null nếu không bật Convert, chưa có từ điển hoặc convert lỗi.
     */
    private suspend fun loadConvertedHtml(bookId: String, sourceHtml: String): String? {
        if (!convertStore.isConvertEnabled(bookId)) return null
        val converter = convertStore.createConverter(bookId) ?: return null
        return withContext(Dispatchers.Default) {
            try {
                HtmlConverter.convert(sourceHtml, converter)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
        }
    }

    private suspend fun loadValidAiCache(
        bookId: String,
        chapterIndex: Int,
        sourceHtml: String
    ): String? {
        val cache = aiChapterRepository.getChapterCache(bookId, chapterIndex)
            ?.takeIf { it.status == AiChapterStatus.COMPLETE }
            ?.takeIf { it.sourceHash == sha256(sourceHtml) }
            ?: return null
        return storageManager.readAiChapter(cache.filePath)
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

internal fun chapterStartProgress(chapterIndex: Int, totalChapters: Int): Float {
    if (totalChapters <= 0) return 0f
    val normalizedChapterIndex = chapterIndex.coerceIn(0, totalChapters - 1)
    return normalizedChapterIndex.toFloat() / totalChapters.toFloat()
}
