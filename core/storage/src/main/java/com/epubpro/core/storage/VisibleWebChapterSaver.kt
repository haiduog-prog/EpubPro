package com.epubpro.core.storage

import com.epubpro.core.reader.engine.EpubEngine
import com.epubpro.core.reader.engine.EpubPackageStructureParser
import com.epubpro.core.storage.worker.LocalBookReplacement
import com.epubpro.core.webnovel.EpubMetadata
import com.epubpro.core.webnovel.EpubWriter
import com.epubpro.core.webnovel.WebArticle
import com.epubpro.core.webnovel.WebNovelChapter
import com.epubpro.core.webnovel.isReadableWebChapter
import com.epubpro.domain.model.Book
import com.epubpro.domain.repository.BookRepository
import com.epubpro.domain.repository.SearchRepository
import java.io.File
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Kết quả lưu chương đọc được từ WebView. */
sealed interface VisibleChapterResult {
    /** Chương đã có hoặc đã được lưu vào sách, kèm vị trí chính xác. */
    data class Saved(val bookId: String, val chapterIndex: Int, val alreadyExists: Boolean) : VisibleChapterResult
    /** Không xác định được sách duy nhất; cần người dùng chọn. */
    data class ChooseBook(val books: List<Book>) : VisibleChapterResult
    /** Chương không liền sau chương cuối sách đã chọn. */
    data object NotAdjacent : VisibleChapterResult
    /** Trang chưa có nội dung hợp lệ. */
    data object NotReady : VisibleChapterResult
}

/** Lưu chương người dùng đang xem, bảo toàn sách và tiến độ khi bổ sung chương mới. */
@Singleton
class VisibleWebChapterSaver @Inject constructor(
    private val books: BookRepository,
    private val sources: WebNovelSourceStore,
    private val storage: EpubStorageManager,
    private val engine: EpubEngine,
    private val search: SearchRepository,
    private val replacement: LocalBookReplacement
) {
    /**
     * Lưu hoặc nhận diện chương đang hiển thị; không nối chương nhảy cóc.
     *
     * @param article Nội dung đã đọc từ WebView.
     * @param userAgent User-Agent của WebView cho lần tải tiếp.
     * @param selectedBookId ID sách người dùng chọn, hoặc null để tự nhận diện.
     * @param createNew true nếu người dùng chọn tạo sách riêng.
     * @return Kết quả lưu hoặc yêu cầu chọn sách.
     * @throws Exception Khi không thể đọc, ghi hoặc cập nhật EPUB.
     */
    suspend fun save(article: WebArticle, userAgent: String?, selectedBookId: String? = null, createNew: Boolean = false): VisibleChapterResult =
        withContext(Dispatchers.IO) {
            WebNovelMutationLock.mutex.withLock {
                if (!isReadableWebChapter(article)) return@withLock VisibleChapterResult.NotReady
                val candidates = books.getAllBooks().first().filter { sources.get(it.id) != null }
                val identified = mutableListOf<Pair<Book, Int>>()
                val adjacent = mutableListOf<Book>()
                for (book in candidates) {
                    if (selectedBookId != null && selectedBookId != book.id) continue
                    sources.reconcileReplacement(book.id, book.filePath)
                    val source = sources.get(book.id) ?: continue
                    val chapters = runCatching { EpubWriter.readChapters(File(book.filePath)) }.getOrNull() ?: continue
                    val index = findVisibleChapterIndex(article, source, chapters)
                    if (index >= 0) identified += book to index
                    else if (article.links.previous?.substringBefore('#') == source.lastChapterUrl.substringBefore('#')) adjacent += book
                }
                if (!createNew && identified.size > 1) return@withLock VisibleChapterResult.ChooseBook(identified.map { it.first })
                if (!createNew && identified.size == 1) return@withLock VisibleChapterResult.Saved(identified[0].first.id, identified[0].second, true)
                if (!createNew && adjacent.size > 1) return@withLock VisibleChapterResult.ChooseBook(adjacent)
                val target = if (createNew) null else adjacent.firstOrNull()
                if (!createNew && selectedBookId != null && target == null) return@withLock VisibleChapterResult.NotAdjacent
                if (!createNew && target == null && candidates.isNotEmpty()) return@withLock VisibleChapterResult.ChooseBook(candidates)
                val output = storage.createConvertedEpubFile()
                try {
                    val chapter = WebNovelChapter(article.title, article.paragraphs, article.url)
                    if (target == null) {
                        EpubWriter.write(output, EpubMetadata(title = article.novelTitle ?: article.title, author = article.author.orEmpty(), sourceUrl = article.url), listOf(chapter))
                        val parsed = engine.parseEpubMetadataStrict(output)
                        sources.save(parsed.id, WebNovelSource(article.url, article.url, userAgent))
                        books.insertBook(parsed)
                        engine.indexBookContent(output, parsed.id, search)
                        VisibleChapterResult.Saved(parsed.id, 0, false)
                    } else {
                        val source = checkNotNull(sources.get(target.id))
                        val old = File(target.filePath)
                        val chapters = EpubWriter.readChapters(old)
                        val identifier = checkNotNull(ZipFile(old).use { EpubPackageStructureParser.readPublicationIdentifier(it) })
                        EpubWriter.write(output, EpubMetadata(title = target.title, author = target.author.takeUnless { it == "Unknown Author" } ?: article.author.orEmpty(), sourceUrl = source.startUrl, identifier = identifier), chapters + chapter)
                        val parsed = engine.parseEpubMetadataStrict(output)
                        sources.prepareReplacement(target.id, output.absolutePath, source.copy(lastChapterUrl = article.url, userAgent = userAgent ?: source.userAgent))
                        replacement.replace(parsed, target.id, identifier, keepAiCache = true, expectedFilePath = old.absolutePath)
                        sources.reconcileReplacement(target.id, output.absolutePath)
                        VisibleChapterResult.Saved(target.id, chapters.size, false)
                    }
                } catch (error: Exception) {
                    withContext(NonCancellable) {
                        val id = target?.id ?: output.name
                        if (books.getBookById(id)?.filePath != output.absolutePath) output.delete()
                    }
                    throw error
                }
            }
        }
}
