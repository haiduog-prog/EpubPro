package com.epubpro.core.webnovel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Kiểm thử bóc tách nội dung và dò liên kết chương trên HTML mẫu. */
class ArticleExtractorTest {
    private val longLine = "Đây là một câu văn khá dài trong chương truyện để vượt ngưỡng nội dung tối thiểu. "

    /** Site truyện kiểu `<div>` + `<br>`: lấy đúng nội dung, bỏ menu/bình luận, nhận link chương sau/trước. */
    @Test
    fun extractsBrSeparatedNovelChapter() {
        val html = """
            <html><head><title>Chương 12: Gặp lại - Kiếm Đạo Độc Tôn - TruyenSite</title></head>
            <body>
              <div class="menu"><a href="/">Trang chủ</a><a href="/the-loai">Thể loại</a></div>
              <h1>Chương 12: Gặp lại</h1>
              <div class="chapter-nav">
                <a href="/kiem-dao/chuong-11.html">Chương trước</a>
                <a href="/kiem-dao/">Mục lục</a>
                <a href="/kiem-dao/chuong-13.html">Chương sau</a>
              </div>
              <div id="chapter-c">${longLine}<br>${longLine}<br><br>${longLine}<script>ads()</script></div>
              <div class="comments">Bình luận: hay quá, hay quá, hay quá, hay quá, hay quá.</div>
            </body></html>
        """.trimIndent()

        val article = ArticleExtractor.extract(html, "https://example.com/kiem-dao/chuong-12.html")

        assertNotNull(article)
        article!!
        assertEquals("Chương 12: Gặp lại", article.title)
        assertEquals(3, article.paragraphs.size)
        assertTrue(article.paragraphs.none { it.contains("Bình luận") || it.contains("ads()") })
        assertEquals("https://example.com/kiem-dao/chuong-13.html", article.links.next)
        assertEquals("https://example.com/kiem-dao/chuong-11.html", article.links.previous)
        assertEquals("https://example.com/kiem-dao/", article.links.tableOfContents)
        assertEquals("Kiếm Đạo Độc Tôn", article.novelTitle)
    }

    /** Bài viết dạng `<p>` trong `<article>`, liên kết `rel=next` và bỏ qua sidebar nhiều link. */
    @Test
    fun extractsParagraphArticleWithRelNext() {
        val html = """
            <html><head><title>第一章 开始</title><link rel="next" href="/book/2.html"></head>
            <body>
              <aside class="sidebar"><a href="/a">A</a><a href="/b">B</a></aside>
              <article class="post-body"><p>$longLine</p><p>$longLine</p><p>$longLine</p></article>
              <a href="https://other.com/next">下一章</a>
            </body></html>
        """.trimIndent()

        val article = ArticleExtractor.extract(html, "https://example.com/book/1.html")!!

        assertEquals(3, article.paragraphs.size)
        assertEquals("https://example.com/book/2.html", article.links.next)
    }

    /** Trang quá ít chữ không được coi là chương. */
    @Test
    fun returnsNullWhenContentTooShort() {
        assertNull(ArticleExtractor.extract("<html><body><div>Ngắn</div></body></html>", "https://example.com/"))
    }

    /** Liên kết chỉ khác số cuối trong URL được nhận là chương kế tiếp/liền trước. */
    @Test
    fun comparesSequentialUrls() {
        assertEquals(1, ChapterLinkDetector.compareSequentialUrls("https://a.com/c/12.html", "https://a.com/c/13.html"))
        assertEquals(-1, ChapterLinkDetector.compareSequentialUrls("https://a.com/c/12.html", "https://a.com/c/11.html"))
        assertEquals(0, ChapterLinkDetector.compareSequentialUrls("https://a.com/c/12.html", "https://a.com/d/13.html"))
    }
}
