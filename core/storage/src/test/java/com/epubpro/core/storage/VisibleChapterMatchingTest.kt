package com.epubpro.core.storage

import com.epubpro.core.webnovel.ChapterLinks
import com.epubpro.core.webnovel.WebArticle
import com.epubpro.core.webnovel.WebNovelChapter
import org.junit.Assert.assertEquals
import org.junit.Test

/** Kiểm tra nhận diện chương đã có trong EPUB mới và EPUB cũ. */
class VisibleChapterMatchingTest {
    private val source = WebNovelSource("https://example.org/1", "https://example.org/3", null)
    private val chapters = listOf(
        WebNovelChapter("Một", listOf("Nội dung một")),
        WebNovelChapter("Hai", listOf("Nội dung hai"), "https://example.org/2"),
        WebNovelChapter("Ba", listOf("Nội dung ba"))
    )

    /** URL nguồn, nội dung cũ và mốc cuối trả đúng vị trí; URL lạ không bị nhận nhầm. */
    @Test
    fun matchesOnlyKnownChapterEvidence() {
        val article = { url: String, text: String -> WebArticle(url, "Chương", null, listOf(text), ChapterLinks()) }
        assertEquals(1, findVisibleChapterIndex(article("https://example.org/2#top", "Khác"), source, chapters))
        assertEquals(0, findVisibleChapterIndex(article("https://example.org/old", "Nội dung một"), source, chapters))
        assertEquals(2, findVisibleChapterIndex(article("https://example.org/3", "Khác"), source, chapters))
        assertEquals(-1, findVisibleChapterIndex(article("https://example.org/4", "Khác"), source, chapters))
    }
}
