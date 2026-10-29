package com.epubpro.core.database.dao

import androidx.room.*
import com.epubpro.core.database.entity.*
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Query("SELECT * FROM books ORDER BY lastReadAt DESC")
    fun getAllBooks(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun getBookById(id: String): BookEntity?

    /**
     * Đọc danh sách sách hiện tại cho một snapshot đồng bộ.
     *
     * @return Các sách trong thư viện tại thời điểm truy vấn.
     */
    @Query("SELECT * FROM books")
    suspend fun getAllBooksDirect(): List<BookEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBook(book: BookEntity)

    /**
     * Cập nhật bản ghi sẵn có, giữ các quan hệ phụ thuộc thay vì REPLACE xóa bản ghi cũ.
     *
     * @param book Metadata mới với ID nội bộ giữ nguyên.
     * @return Số bản ghi đã cập nhật.
     */
    @Update
    suspend fun updateBook(book: BookEntity): Int

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun deleteBook(id: String)

    @Query("UPDATE books SET lastReadAt = :timestamp WHERE id = :id")
    suspend fun updateLastRead(id: String, timestamp: Long)

    /**
     * Cập nhật cover khi bản ghi chưa có cover và vẫn trỏ tới file EPUB cũ.
     *
     * @param id Định danh sách.
     * @param filePath Đường dẫn file EPUB cần đối chiếu.
     * @param coverPath Đường dẫn cover mới.
     * @return Số bản ghi được cập nhật.
     */
    @Query("UPDATE books SET coverPath = :coverPath WHERE id = :id AND filePath = :filePath AND (coverPath IS NULL OR coverPath = '')")
    suspend fun updateCoverPathIfMissing(id: String, filePath: String, coverPath: String): Int

    @Query("SELECT * FROM reading_progress WHERE bookId = :bookId")
    fun getReadingProgress(bookId: String): Flow<ReadingProgressEntity?>

    @Query("SELECT * FROM reading_progress")
    fun getAllReadingProgress(): Flow<List<ReadingProgressEntity>>

    /**
     * Đọc mọi tiến độ hiện tại để ghép với metadata sách trước khi sync.
     *
     * @return Các bản ghi tiến độ hiện có.
     */
    @Query("SELECT * FROM reading_progress")
    suspend fun getAllReadingProgressDirect(): List<ReadingProgressEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveReadingProgress(progress: ReadingProgressEntity)

    /**
     * Lưu tiến độ và thời điểm đọc gần nhất nguyên tử, giữ timestamp tăng đơn điệu theo truyện.
     *
     * @param progress Tiến độ mới với `updatedAt` đã được thiết lập.
     */
    @Transaction
    suspend fun saveProgressAndLastRead(progress: ReadingProgressEntity) {
        val previous = getReadingProgressDirect(progress.bookId)
        val nextTimestamp = maxOf(
            progress.updatedAt,
            if (previous?.updatedAt == Long.MAX_VALUE) Long.MAX_VALUE else (previous?.updatedAt ?: 0L) + 1L
        )
        val updatedProgress = progress.copy(updatedAt = nextTimestamp)
        saveReadingProgress(updatedProgress)
        updateLastRead(updatedProgress.bookId, nextTimestamp)
    }

    @Query("""
        SELECT books.* FROM books
        INNER JOIN reading_progress ON reading_progress.bookId = books.id
        WHERE books.lastReadAt > 0 AND reading_progress.progressPercentage < 1.0
        ORDER BY books.lastReadAt DESC
        LIMIT 1
    """)
    suspend fun getLatestReadBook(): BookEntity?

    @Query("SELECT * FROM reading_progress WHERE bookId = :bookId LIMIT 1")
    suspend fun getReadingProgressDirect(bookId: String): ReadingProgressEntity?
}

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY createdAt DESC")
    fun getBookmarksForBook(bookId: String): Flow<List<BookmarkEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addBookmark(bookmark: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun deleteBookmark(id: String)

    @Query("SELECT * FROM highlights WHERE bookId = :bookId ORDER BY createdAt DESC")
    fun getHighlightsForBook(bookId: String): Flow<List<HighlightEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addHighlight(highlight: HighlightEntity)

    @Query("DELETE FROM highlights WHERE id = :id")
    suspend fun deleteHighlight(id: String)
}

data class FtsSearchResult(
    val bookId: String,
    val chapterIndex: String,
    val chapterTitle: String,
    val textContent: String,
    val snippet: String
)

@Dao
interface SearchDao {
    @Query("""
        SELECT bookId, chapterIndex, chapterTitle, textContent, snippet(book_search_fts, '<b>', '</b>', '...', -1, 30) as snippet 
        FROM book_search_fts 
        WHERE book_search_fts MATCH :query AND bookId = :bookId
    """)
    suspend fun searchInBook(bookId: String, query: String): List<FtsSearchResult>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSearchIndex(indices: List<BookSearchEntity>)

    /**
     * Chuyển chỉ mục đã chuẩn bị sang truyện đích trong transaction cập nhật EPUB.
     *
     * @param stagedBookId ID tạm dùng khi đánh chỉ mục.
     * @param bookId ID nội bộ của truyện được cập nhật.
     */
    @Query("UPDATE book_search_fts SET bookId = :bookId WHERE bookId = :stagedBookId")
    suspend fun moveIndex(stagedBookId: String, bookId: String)

    @Query("DELETE FROM book_search_fts WHERE bookId = :bookId")
    suspend fun clearIndexForBook(bookId: String)
}
@Dao
interface AiRuleDao {
    @Query("""
        SELECT * FROM ai_rules
        WHERE scope = 'GLOBAL' OR (scope = 'BOOK' AND bookId = :bookId)
        ORDER BY scope ASC, source COLLATE NOCASE
    """)
    fun observeRulesForBook(bookId: String): Flow<List<AiRuleEntity>>

    @Query("""
        SELECT * FROM ai_rules
        WHERE scope = 'GLOBAL' OR (scope = 'BOOK' AND bookId = :bookId)
        ORDER BY scope ASC, source COLLATE NOCASE
    """)
    suspend fun getRulesForBook(bookId: String): List<AiRuleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRule(rule: AiRuleEntity)

    @Query("DELETE FROM ai_rules WHERE id = :ruleId")
    suspend fun deleteRule(ruleId: String)
}

@Dao
interface AiChapterDao {
    @Query("SELECT * FROM ai_chapter_cache WHERE bookId = :bookId AND chapterIndex = :chapterIndex")
    suspend fun getChapterCache(bookId: String, chapterIndex: Int): AiChapterCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertChapterCache(cache: AiChapterCacheEntity)

    @Query("DELETE FROM ai_chapter_cache WHERE bookId = :bookId AND chapterIndex = :chapterIndex")
    suspend fun deleteChapterCache(bookId: String, chapterIndex: Int)

    @Query("DELETE FROM ai_chapter_cache WHERE bookId = :bookId")
    suspend fun deleteBookCaches(bookId: String)
}
