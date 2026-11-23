package com.epubpro.core.database.repository

import com.epubpro.core.database.dao.FtsSearchResult
import com.epubpro.core.database.dao.SearchDao
import com.epubpro.core.database.entity.BookSearchEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchRepositoryImplTest {

    private class FakeSearchDao(
        private val results: List<FtsSearchResult> = emptyList()
    ) : SearchDao {
        var searchCalls = 0
        var lastBookId: String? = null
        var lastQuery: String? = null

        override suspend fun searchInBook(bookId: String, query: String): List<FtsSearchResult> {
            searchCalls++
            lastBookId = bookId
            lastQuery = query
            return results
        }

        override suspend fun insertSearchIndex(indices: List<BookSearchEntity>) = Unit

        override suspend fun moveIndex(stagedBookId: String, bookId: String) = Unit

        override suspend fun clearIndexForBook(bookId: String) = Unit
    }

    @Test
    fun `buildFts4MatchQuery strips leading wildcards and FTS operators`() {
        assertEquals("tu* tiên*", SearchRepositoryImpl.buildFts4MatchQuery("  tu tiên  "))
        assertEquals("chương* 1* mở* đầu*", SearchRepositoryImpl.buildFts4MatchQuery("chương 1: mở-đầu*"))
        assertEquals("Đấu* Phá*", SearchRepositoryImpl.buildFts4MatchQuery("\"Đấu Phá\""))
        assertEquals("and* or* not* near*", SearchRepositoryImpl.buildFts4MatchQuery("AND OR NOT NEAR"))
        assertNull(SearchRepositoryImpl.buildFts4MatchQuery("   :::***---\"\"_   "))
    }

    @Test
    fun `buildFts4MatchQuery normalizes NFD Vietnamese and splits underscores`() {
        // "Việt Nam" ở dạng Unicode NFD (e + circumflex + dot below)
        val nfdInput = "Vie\u0302\u0323t Nam"
        assertEquals("Việt* Nam*", SearchRepositoryImpl.buildFts4MatchQuery(nfdInput))
        assertEquals("chuong* 1* mo* dau*", SearchRepositoryImpl.buildFts4MatchQuery("chuong_1_mo_dau"))
    }

    @Test
    fun `searchInBook returns empty list without calling dao when query has no tokens`() = runBlocking {
        val searchDao = FakeSearchDao()
        val repository = SearchRepositoryImpl(searchDao)

        val results = repository.searchInBook("book-1", " : * \" - ")

        assertTrue(results.isEmpty())
        assertEquals(0, searchDao.searchCalls)
    }

    @Test
    fun `searchInBook sends sanitized FTS4 prefix query to dao`() = runBlocking {
        val searchDao = FakeSearchDao(
            results = listOf(
                FtsSearchResult(
                    bookId = "book-1",
                    chapterIndex = "0",
                    chapterTitle = "Chương 1",
                    textContent = "Nội dung",
                    snippet = "<b>Chương</b> <b>1</b>"
                )
            )
        )
        val repository = SearchRepositoryImpl(searchDao)

        val results = repository.searchInBook("book-1", "chương 1:")

        assertEquals(1, results.size)
        assertEquals(0, results.first().chapterIndex)
        assertEquals("book-1", searchDao.lastBookId)
        assertEquals("chương* 1*", searchDao.lastQuery)
    }
}
