package com.epubpro.core.database.repository

import com.epubpro.core.database.dao.AiChapterDao
import com.epubpro.core.database.dao.AiRuleDao
import com.epubpro.core.database.dao.BookDao
import com.epubpro.core.database.dao.BookmarkDao
import com.epubpro.core.database.dao.SearchDao
import com.epubpro.core.database.entity.*
import com.epubpro.domain.model.*
import com.epubpro.domain.repository.AiChapterRepository
import com.epubpro.domain.repository.AiRuleRepository
import com.epubpro.domain.repository.BookRepository
import com.epubpro.domain.repository.BookmarkRepository
import com.epubpro.domain.repository.SearchRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class BookRepositoryImpl @Inject constructor(
    private val bookDao: BookDao
) : BookRepository {
    override fun getAllBooks(): Flow<List<Book>> =
        bookDao.getAllBooks().map { entities -> entities.map { it.toDomain() } }

    override suspend fun getBookById(id: String): Book? =
        bookDao.getBookById(id)?.toDomain()

    override suspend fun insertBook(book: Book) =
        bookDao.insertBook(BookEntity.fromDomain(book))

    override suspend fun deleteBook(id: String) =
        bookDao.deleteBook(id)

    override suspend fun updateLastRead(id: String, timestamp: Long) =
        bookDao.updateLastRead(id, timestamp)

    override suspend fun updateCoverPathIfMissing(id: String, filePath: String, coverPath: String): Boolean =
        bookDao.updateCoverPathIfMissing(id, filePath, coverPath) > 0

    override fun getReadingProgress(bookId: String): Flow<ReadingProgress?> =
        bookDao.getReadingProgress(bookId).map { it?.toDomain() }

    override fun getAllReadingProgress(): Flow<List<ReadingProgress>> =
        bookDao.getAllReadingProgress().map { list -> list.map { it.toDomain() } }

    override suspend fun saveReadingProgress(progress: ReadingProgress) =
        bookDao.saveProgressAndLastRead(ReadingProgressEntity.fromDomain(progress))

    override suspend fun getLatestReadBook(): Book? =
        bookDao.getLatestReadBook()?.toDomain()

    override suspend fun getReadingProgressDirect(bookId: String): ReadingProgress? =
        bookDao.getReadingProgressDirect(bookId)?.toDomain()
}

class BookmarkRepositoryImpl @Inject constructor(
    private val bookmarkDao: BookmarkDao
) : BookmarkRepository {
    override fun getBookmarksForBook(bookId: String): Flow<List<Bookmark>> =
        bookmarkDao.getBookmarksForBook(bookId).map { list -> list.map { it.toDomain() } }

    override suspend fun addBookmark(bookmark: Bookmark) =
        bookmarkDao.addBookmark(BookmarkEntity.fromDomain(bookmark))

    override suspend fun deleteBookmark(id: String) =
        bookmarkDao.deleteBookmark(id)

    override fun getHighlightsForBook(bookId: String): Flow<List<Highlight>> =
        bookmarkDao.getHighlightsForBook(bookId).map { list -> list.map { it.toDomain() } }

    override suspend fun addHighlight(highlight: Highlight) =
        bookmarkDao.addHighlight(HighlightEntity.fromDomain(highlight))

    override suspend fun deleteHighlight(id: String) =
        bookmarkDao.deleteHighlight(id)
}

class SearchRepositoryImpl @Inject constructor(
    private val searchDao: SearchDao
) : SearchRepository {
    override suspend fun searchInBook(bookId: String, query: String): List<SearchResultItem> {
        val ftsQuery = "*$query*"
        return searchDao.searchInBook(bookId, ftsQuery).map { fts ->
            SearchResultItem(
                bookId = fts.bookId,
                chapterIndex = fts.chapterIndex.toIntOrNull() ?: 0,
                chapterTitle = fts.chapterTitle,
                snippet = fts.snippet
            )
        }
    }

    override suspend fun clearIndexForBook(bookId: String) =
        searchDao.clearIndexForBook(bookId)

    override suspend fun indexBookContent(
        bookId: String,
        chapters: List<Pair<Int, Pair<String, String>>>
    ) {
        val entities = chapters.map { (index, titleAndText) ->
            BookSearchEntity(
                bookId = bookId,
                chapterIndex = index.toString(),
                chapterTitle = titleAndText.first,
                textContent = titleAndText.second
            )
        }
        searchDao.insertSearchIndex(entities)
    }
}
class AiRuleRepositoryImpl @Inject constructor(
    private val aiRuleDao: AiRuleDao
) : AiRuleRepository {
    override fun observeRulesForBook(bookId: String): Flow<List<AiRule>> =
        aiRuleDao.observeRulesForBook(bookId).map { rules -> rules.map { it.toDomain() } }

    override suspend fun getRulesForBook(bookId: String): List<AiRule> =
        aiRuleDao.getRulesForBook(bookId).map { it.toDomain() }

    override suspend fun upsertRule(rule: AiRule) =
        aiRuleDao.upsertRule(AiRuleEntity.fromDomain(rule))

    override suspend fun deleteRule(ruleId: String) =
        aiRuleDao.deleteRule(ruleId)
}

class AiChapterRepositoryImpl @Inject constructor(
    private val aiChapterDao: AiChapterDao
) : AiChapterRepository {
    override suspend fun getChapterCache(bookId: String, chapterIndex: Int): AiChapterCache? =
        aiChapterDao.getChapterCache(bookId, chapterIndex)?.toDomain()

    override suspend fun upsertChapterCache(cache: AiChapterCache) =
        aiChapterDao.upsertChapterCache(AiChapterCacheEntity.fromDomain(cache))

    override suspend fun deleteChapterCache(bookId: String, chapterIndex: Int) =
        aiChapterDao.deleteChapterCache(bookId, chapterIndex)

    override suspend fun deleteBookCaches(bookId: String) =
        aiChapterDao.deleteBookCaches(bookId)
}
