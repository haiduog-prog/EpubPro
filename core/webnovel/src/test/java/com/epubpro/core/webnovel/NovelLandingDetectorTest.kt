package com.epubpro.core.webnovel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Kiểm tra chọn chương đầu từ các liên kết thực trên trang giới thiệu. */
class NovelLandingDetectorTest {
    /** Hỗ trợ cả đường dẫn chuong-1 và chapter-1, không dựng URL dự đoán. */
    @Test
    fun findsFirstChapterFromActualLinks() {
        for (segment in listOf("chuong", "chapter")) {
            val html = "<title>Truyện</title><a href='/book/$segment-2'>Chương 2</a>" +
                "<a href='/book/$segment-1'>Chương 1</a>"
            assertEquals("https://example.com/book/$segment-1", NovelLandingDetector.detect(html, "https://example.com/book")?.firstChapter?.url)
        }
    }

    /** Danh sách không có chương đầu cần người dùng lựa chọn; trang chương không bị coi là danh sách. */
    @Test
    fun leavesIncompleteListForUserAndIgnoresChapterPage() {
        val links = "<a href='/book/chuong-3'>3 Ba</a><a href='/book/chuong-2'>2 Hai</a>"
        val landing = NovelLandingDetector.detect("<title>Truyện</title>$links", "https://example.com/book")!!
        assertNull(landing.firstChapter)
        assertEquals(listOf(2, 3), landing.chapters.map { it.number })
        assertNull(NovelLandingDetector.detect("<title>Chương 2 - Truyện</title>$links", "https://example.com/book/chuong-2"))
    }

    /** Nhiều link chương 1 khác nhau không được tự chọn. */
    @Test
    fun doesNotGuessBetweenMultipleFirstChapters() {
        val html = "<title>Truyện</title><a href='/book/chapter-1'>Chapter 1</a>" +
            "<a href='/book/chuong-1'>Chương 1</a>"
        assertNull(NovelLandingDetector.detect(html, "https://example.com/book")?.firstChapter)
    }

    /** Trang có metadata sách nhưng không có link chương không được xử lý như chương truyện. */
    @Test
    fun recognizesBookMetadataEvenWithoutLinks() {
        val html = "<title>Truyện</title><script type='application/ld+json'>{\"@type\":\"Book\"}</script>"
        assertEquals(emptyList<NovelChapterLink>(), NovelLandingDetector.detect(html, "https://example.com/book")?.chapters)
    }
}
