package com.epubpro.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.epubpro.domain.model.AiChapterCache
import com.epubpro.domain.model.AiChapterStatus
import com.epubpro.domain.model.AiRule
import com.epubpro.domain.model.AiRuleAction
import com.epubpro.domain.model.AiRuleScope
import com.epubpro.domain.model.Book
import com.epubpro.domain.model.BookSourceFormat
import com.epubpro.domain.model.Bookmark
import com.epubpro.domain.model.Highlight
import com.epubpro.domain.model.ReadingProgress

@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey val id: String,
    val title: String,
    val author: String,
    val coverPath: String?,
    val filePath: String,
    val addedAt: Long,
    val lastReadAt: Long,
    @ColumnInfo(defaultValue = "0") val totalChapters: Int = 0,
    val onlineNovelId: String? = null,
    @ColumnInfo(defaultValue = "EPUB") val sourceFormat: String = BookSourceFormat.EPUB.name
) {
    fun toDomain() = Book(
        id = id,
        title = title,
        author = author,
        coverPath = coverPath,
        filePath = filePath,
        addedAt = addedAt,
        lastReadAt = lastReadAt,
        totalChapters = totalChapters,
        onlineNovelId = onlineNovelId,
        sourceFormat = runCatching { BookSourceFormat.valueOf(sourceFormat) }
            .getOrDefault(BookSourceFormat.EPUB)
    )
    companion object {
        fun fromDomain(book: Book) = BookEntity(
            id = book.id,
            title = book.title,
            author = book.author,
            coverPath = book.coverPath,
            filePath = book.filePath,
            addedAt = book.addedAt,
            lastReadAt = book.lastReadAt,
            totalChapters = book.totalChapters,
            onlineNovelId = book.onlineNovelId,
            sourceFormat = book.sourceFormat.name
        )
    }
}

@Entity(tableName = "reading_progress")
data class ReadingProgressEntity(
    @PrimaryKey val bookId: String,
    val currentCfi: String,
    val chapterIndex: Int,
    val pageIndex: Int = 1,
    val progressPercentage: Float,
    @ColumnInfo(defaultValue = "0") val totalChapters: Int = 0,
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = 0L
) {
    fun toDomain() = ReadingProgress(
        bookId = bookId,
        currentCfi = currentCfi,
        chapterIndex = chapterIndex,
        pageIndex = pageIndex,
        progressPercentage = progressPercentage,
        totalChapters = totalChapters,
        updatedAt = updatedAt
    )
    companion object {
        fun fromDomain(rp: ReadingProgress) = ReadingProgressEntity(
            bookId = rp.bookId,
            currentCfi = rp.currentCfi,
            chapterIndex = rp.chapterIndex,
            pageIndex = rp.pageIndex,
            progressPercentage = rp.progressPercentage,
            totalChapters = rp.totalChapters,
            updatedAt = rp.updatedAt
        )
    }
}

@Entity(tableName = "bookmarks")
data class BookmarkEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val chapterIndex: Int,
    val chapterTitle: String,
    val cfi: String,
    val createdAt: Long
) {
    fun toDomain() = Bookmark(id, bookId, chapterIndex, chapterTitle, cfi, createdAt)
    companion object {
        fun fromDomain(bm: Bookmark) = BookmarkEntity(bm.id, bm.bookId, bm.chapterIndex, bm.chapterTitle, bm.cfi, bm.createdAt)
    }
}

@Entity(tableName = "highlights")
data class HighlightEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val chapterIndex: Int,
    val startCfi: String,
    val endCfi: String,
    val selectedText: String,
    val colorHex: String,
    val note: String?,
    val createdAt: Long
) {
    fun toDomain() = Highlight(id, bookId, chapterIndex, startCfi, endCfi, selectedText, colorHex, note, createdAt)
    companion object {
        fun fromDomain(hl: Highlight) = HighlightEntity(hl.id, hl.bookId, hl.chapterIndex, hl.startCfi, hl.endCfi, hl.selectedText, hl.colorHex, hl.note, hl.createdAt)
    }
}

/**
 * Room FTS Virtual Table for Full-Text Search
 */
@Fts4
@Entity(tableName = "book_search_fts")
data class BookSearchEntity(
    @ColumnInfo(name = "rowid") @PrimaryKey val rowid: Int = 0,
    @ColumnInfo(name = "bookId") val bookId: String,
    @ColumnInfo(name = "chapterIndex") val chapterIndex: String,
    @ColumnInfo(name = "chapterTitle") val chapterTitle: String,
    @ColumnInfo(name = "textContent") val textContent: String
)
@Entity(
    tableName = "ai_rules",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("bookId")]
)
data class AiRuleEntity(
    @PrimaryKey val id: String,
    val scope: String,
    val bookId: String?,
    val source: String,
    val action: String,
    val replacement: String?,
    val caseSensitive: Boolean,
    val updatedAt: Long
) {
    fun toDomain() = AiRule(
        id = id,
        scope = AiRuleScope.valueOf(scope),
        bookId = bookId,
        source = source,
        action = AiRuleAction.valueOf(action),
        replacement = replacement,
        caseSensitive = caseSensitive,
        updatedAt = updatedAt
    )

    companion object {
        fun fromDomain(rule: AiRule) = AiRuleEntity(
            id = rule.id,
            scope = rule.scope.name,
            bookId = rule.bookId,
            source = rule.source,
            action = rule.action.name,
            replacement = rule.replacement,
            caseSensitive = rule.caseSensitive,
            updatedAt = rule.updatedAt
        )
    }
}

@Entity(
    tableName = "ai_chapter_cache",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["bookId", "chapterIndex"], unique = true)]
)
data class AiChapterCacheEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val chapterIndex: Int,
    val sourceHash: String,
    val configHash: String,
    val status: String,
    val filePath: String?,
    val modelId: String,
    val completedParts: Int,
    val totalParts: Int,
    val updatedAt: Long
) {
    fun toDomain() = AiChapterCache(
        id = id,
        bookId = bookId,
        chapterIndex = chapterIndex,
        sourceHash = sourceHash,
        configHash = configHash,
        status = AiChapterStatus.valueOf(status),
        filePath = filePath,
        modelId = modelId,
        completedParts = completedParts,
        totalParts = totalParts,
        updatedAt = updatedAt
    )

    companion object {
        fun fromDomain(cache: AiChapterCache) = AiChapterCacheEntity(
            id = cache.id,
            bookId = cache.bookId,
            chapterIndex = cache.chapterIndex,
            sourceHash = cache.sourceHash,
            configHash = cache.configHash,
            status = cache.status.name,
            filePath = cache.filePath,
            modelId = cache.modelId,
            completedParts = cache.completedParts,
            totalParts = cache.totalParts,
            updatedAt = cache.updatedAt
        )
    }
}
