package com.epubpro.core.storage.worker

import androidx.room.withTransaction
import com.epubpro.core.database.AppDatabase
import com.epubpro.core.reader.engine.EpubEngine
import com.epubpro.core.reader.engine.EpubPackageStructureParser
import com.epubpro.domain.model.Book
import com.epubpro.domain.repository.SearchRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile
import javax.inject.Inject

/** Chuẩn bị chỉ mục trước khi đổi metadata và cache trong cùng transaction Room. */
class LocalBookReplacement @Inject constructor(
    private val database: AppDatabase,
    private val epubEngine: EpubEngine,
    private val searchRepository: SearchRepository
) {
    /**
     * Thay EPUB của đúng truyện đã xác nhận, giữ ID nội bộ và các đánh dấu của người đọc.
     *
     * @param parsed Metadata của bản EPUB mới đã được kiểm tra.
     * @param bookId ID nội bộ được người dùng chọn.
     * @param identifier ID xuất bản bắt buộc trùng ở cả hai bản.
     * @return Metadata sau cập nhật.
     * @throws IllegalStateException Nếu truyện đã bị xóa, thay đổi hoặc ID không còn khớp.
     */
    suspend fun replace(parsed: Book, bookId: String, identifier: String): Book = withContext(Dispatchers.IO) {
        val dao = database.bookDao()
        val previous = checkNotNull(dao.getBookById(bookId))
        check(identifier.isNotBlank())
        check(ZipFile(parsed.filePath).use {
            EpubPackageStructureParser.readPublicationIdentifier(it)
        } == identifier)
        check(ZipFile(previous.filePath).use {
            EpubPackageStructureParser.readPublicationIdentifier(it)
        } == identifier)
        searchRepository.clearIndexForBook(parsed.id)
        epubEngine.indexBookContentResumable(File(parsed.filePath), parsed.id, searchRepository)
        database.withTransaction {
            val current = checkNotNull(dao.getBookById(bookId))
            check(current.filePath == previous.filePath)
            val updated = current.copy(
                title = parsed.title,
                author = parsed.author,
                coverPath = parsed.coverPath,
                filePath = parsed.filePath,
                totalChapters = parsed.totalChapters,
                sourceFormat = parsed.sourceFormat.name
            )
            check(dao.updateBook(updated) == 1)
            database.searchDao().clearIndexForBook(bookId)
            database.searchDao().moveIndex(parsed.id, bookId)
            database.aiChapterDao().deleteBookCaches(bookId)
            dao.getReadingProgressDirect(bookId)?.let { progress ->
                val chapter = progress.chapterIndex.coerceIn(0, (parsed.totalChapters - 1).coerceAtLeast(0))
                val nextTimestamp = maxOf(
                    System.currentTimeMillis(),
                    if (progress.updatedAt == Long.MAX_VALUE) Long.MAX_VALUE else progress.updatedAt + 1L
                )
                dao.saveReadingProgress(progress.copy(
                    chapterIndex = chapter,
                    currentCfi = if (chapter == progress.chapterIndex) progress.currentCfi else "",
                    pageIndex = if (chapter == progress.chapterIndex) progress.pageIndex else 1,
                    totalChapters = parsed.totalChapters,
                    progressPercentage = if (parsed.totalChapters > 0) chapter.toFloat() / parsed.totalChapters else 0f,
                    updatedAt = nextTimestamp
                ))
            }
            updated.toDomain()
        }
    }
}
