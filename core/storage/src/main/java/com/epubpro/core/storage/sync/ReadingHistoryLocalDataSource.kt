package com.epubpro.core.storage.sync

import androidx.room.withTransaction
import com.epubpro.core.database.AppDatabase
import com.epubpro.core.database.entity.BookEntity
import com.epubpro.core.database.entity.ReadingProgressEntity
import com.epubpro.domain.sync.ReadingHistoryRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** Adapter Room/local files cho ledger tiến độ đọc, không thay metadata thư viện. */
@Singleton
class ReadingHistoryLocalDataSource @Inject constructor(
    private val database: AppDatabase,
    private val stateStore: SyncStateStore
) {
    /**
     * Tạo snapshot tiến độ local và gắn khóa truyện dùng chung giữa thiết bị.
     *
     * @param deviceId UUID ổn định của thiết bị hiện tại.
     * @return Các tiến độ đang có trong Room, đã ghép với book key ổn định.
     */
    suspend fun readRecords(deviceId: String): List<ReadingHistoryRecord> = withContext(Dispatchers.IO) {
        val (books, progresses) = database.withTransaction {
            database.bookDao().getAllBooksDirect() to database.bookDao().getAllReadingProgressDirect()
        }
        val bookKeys = resolveBookKeys(books)
        val booksById = books.associateBy(BookEntity::id)
        progresses.mapNotNull { progress ->
            val book = booksById[progress.bookId] ?: return@mapNotNull null
            val bookKey = bookKeys[book.id] ?: return@mapNotNull null
            ReadingHistoryRecord(
                bookKey = bookKey,
                currentCfi = progress.currentCfi,
                chapterIndex = progress.chapterIndex,
                pageIndex = progress.pageIndex,
                progressPercentage = progress.progressPercentage,
                totalChapters = progress.totalChapters,
                lastReadAt = book.lastReadAt.coerceAtLeast(0L),
                updatedAt = progress.updatedAt.coerceAtLeast(0L),
                updatedByDeviceId = deviceId
            )
        }
    }

    /**
     * Áp dụng tiến độ đã gộp cho sách local có book key tương ứng.
     *
     * @param records Bản ghi thắng theo `updatedAt`.
     */
    suspend fun applyRecords(records: List<ReadingHistoryRecord>) = withContext(Dispatchers.IO) {
        if (records.isEmpty()) return@withContext
        val books = database.bookDao().getAllBooksDirect()
        val bookKeys = resolveBookKeys(books)
        val booksById = books.associateBy(BookEntity::id)
        val idsByKey = books.mapNotNull { book -> bookKeys[book.id]?.let { it to book.id } }
            .groupBy({ it.first }, { it.second })

        database.withTransaction {
            records.forEach { record ->
                idsByKey[record.bookKey].orEmpty().forEach bookLoop@{ bookId ->
                    val snapshotBook = booksById[bookId] ?: return@bookLoop
                    val currentBook = database.bookDao().getBookById(bookId)
                    if (currentBook?.filePath != snapshotBook.filePath) return@bookLoop
                    val current = database.bookDao().getReadingProgressDirect(bookId)
                    if (current == null || record.updatedAt >= current.updatedAt) {
                        database.bookDao().saveReadingProgress(record.toEntity(bookId))
                        database.bookDao().updateLastRead(bookId, record.lastReadAt)
                    }
                }
            }
        }
    }

    /**
     * Ghép book key theo online ID hoặc SHA-256, đồng thời lưu cache có thể tái sử dụng.
     *
     * @param books Metadata sách cần định danh.
     * @return Ánh xạ book ID local sang khóa ổn định, bỏ qua sách không truy cập được.
     */
    private suspend fun resolveBookKeys(books: List<BookEntity>): Map<String, String> {
        val originalCache = stateStore.readBookFingerprintCache()
        val cache = originalCache.toMutableMap()
        val activeBookIds = books.mapTo(mutableSetOf(), BookEntity::id)
        cache.keys.retainAll(activeBookIds)
        val result = linkedMapOf<String, String>()
        books.forEach { book ->
            val onlineId = book.onlineNovelId?.takeIf(String::isNotBlank)
            if (onlineId != null) {
                cache.remove(book.id)
                result[book.id] = "online:$onlineId"
            } else {
                val key = localFileKey(book, cache)
                if (key != null) result[book.id] = key
            }
        }
        if (cache != originalCache) stateStore.saveBookFingerprintCache(cache)
        return result
    }

    /**
     * Tạo hoặc tái sử dụng fingerprint nội dung cho sách local.
     *
     * @param book Metadata sách local.
     * @param cache Cache đang được cập nhật trong lần sync này.
     * @return Khóa EPUB ổn định hoặc null nếu file không còn trên thiết bị.
     */
    private suspend fun localFileKey(
        book: BookEntity,
        cache: MutableMap<String, BookFingerprintCache>
    ): String? {
        val file = File(book.filePath)
        if (!file.isFile || file.length() <= 0L) return null
        val length = file.length()
        val modified = file.lastModified().coerceAtLeast(0L)
        val canonicalPath = file.canonicalPath
        val existing = cache[book.id]
        val fingerprint = if (
            existing != null &&
            existing.filePath == canonicalPath &&
            existing.fileLength == length &&
            existing.lastModified == modified
        ) {
            existing.sha256
        } else {
            sha256(file).also { cache[book.id] = BookFingerprintCache(canonicalPath, length, modified, it) }
        }
        return "epub:$fingerprint"
    }

    /**
     * Tính SHA-256 theo luồng để không nạp toàn bộ ebook vào RAM.
     *
     * @param file File ebook cần băm.
     * @return Chuỗi hexa SHA-256.
     */
    private suspend fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Gắn local book ID cho bản ghi Drive mà không thay khóa đồng bộ.
     *
     * @receiver Bản ghi ledger đã được chọn khi gộp.
     * @param bookId ID Room của sách trên thiết bị hiện tại.
     * @return Entity tương ứng để lưu trong database local.
     */
    private fun ReadingHistoryRecord.toEntity(bookId: String) = ReadingProgressEntity(
        bookId = bookId,
        currentCfi = currentCfi,
        chapterIndex = chapterIndex,
        pageIndex = pageIndex,
        progressPercentage = progressPercentage,
        totalChapters = totalChapters,
        updatedAt = updatedAt
    )
}
