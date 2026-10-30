package com.epubpro.core.webnovel

import com.epubpro.core.reader.engine.EpubPackageStructureParser
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Kiểm thử EPUB sinh ra đọc được bằng parser của app và crawler đi theo link chương sau. */
class EpubWriterAndCrawlerTest {
    private val paragraph = "Nội dung chương đủ dài để vượt qua ngưỡng tối thiểu của bộ bóc tách văn bản. "

    /** EPUB có mimetype đứng đầu, parser của Reader đọc đúng tiêu đề, tác giả và thứ tự chương. */
    @Test
    fun writtenEpubIsParsedByReaderParser() {
        val target = Files.createTempFile("webnovel", ".epub").toFile()
        try {
            EpubWriter.write(
                target,
                EpubMetadata(title = "Truyện <Thử>", author = "Tác giả & Co"),
                listOf(
                    WebNovelChapter("Chương 1", listOf("Một", "Hai")),
                    WebNovelChapter("Chương 2", listOf("Ba"))
                )
            )
            ZipFile(target).use { zip ->
                assertEquals("mimetype", zip.entries().nextElement().name)
                val parsed = EpubPackageStructureParser.parseStructure(zip)
                assertEquals("Truyện <Thử>", parsed.title)
                assertEquals("Tác giả & Co", parsed.author)
                assertEquals(2, parsed.orderedEntries.size)
                assertTrue(parsed.orderedEntries.first().name.endsWith("chapter_0001.xhtml"))
            }
        } finally {
            target.delete()
            File(target.parentFile, "${target.name}.part").delete()
        }
    }

    /** Đọc lại chương từ EPUB do EpubWriter tạo để nối thêm chương mới, kể cả ký tự cần escape. */
    @Test
    fun readChaptersRoundTrip() {
        val target = Files.createTempFile("webnovel", ".epub").toFile()
        val chapters = listOf(
            WebNovelChapter("Chương 1 <mở đầu>", listOf("Một & hai", "Ba"), "https://example.com/1?a=1&b=2"),
            WebNovelChapter("Chương 2", listOf("Bốn"))
        )
        try {
            EpubWriter.write(target, EpubMetadata(title = "T", identifier = "urn:uuid:fixed"), chapters)

            assertEquals(chapters, EpubWriter.readChapters(target))
        } finally {
            target.delete()
        }
    }

    /** Kết quả crawl ghi URL chương cuối; tìm chương kế tiếp từ URL đó cho lần tải thêm. */
    @Test
    fun crawlerReportsLastChapterAndFindsNext() = runTest {
        val pages = mapOf(
            "https://a.com/1" to page("https://a.com/2"),
            "https://a.com/2" to page("https://a.com/3"),
            "https://a.com/3" to page("https://a.com/4")
        )
        val crawler = WebNovelCrawler(fetch = { url -> pages.getValue(url) }, politeDelayMs = 1L)

        val result = crawler.crawl("https://a.com/1", maxChapters = 2)

        assertEquals("https://a.com/2", result.lastChapterUrl)
        assertEquals("https://a.com/3", crawler.findNextChapterUrl("https://a.com/2"))
    }

    /** Lỗi truy cập chương cuối phải được báo lỗi thay vì coi là hết chương. */
    @Test
    fun findNextReportsFetchFailure() = runTest {
        val crawler = WebNovelCrawler(fetch = { throw WebFetchException(403, "forbidden") }, maxRetries = 0)
        try {
            crawler.findNextChapterUrl("https://a.com/1")
            throw AssertionError("Expected WebFetchException")
        } catch (_: WebFetchException) {
            // Lỗi mạng được phân biệt với trường hợp không có chương kế tiếp.
        }
    }

    /** Giữ chương đã tải và báo lý do lỗi khi chương kế tiếp không truy cập được. */
    @Test
    fun crawlKeepsCompletedChapterWhenNextFetchFails() = runTest {
        val crawler = WebNovelCrawler(fetch = { url ->
            if (url.endsWith("/1")) page("https://a.com/2") else throw WebFetchException(403, "forbidden")
        }, politeDelayMs = 1L, maxRetries = 0)
        val result = crawler.crawl("https://a.com/1", 10)
        assertEquals(1, result.chapters.size)
        assertEquals("https://a.com/1", result.lastChapterUrl)
        assertEquals(CrawlStopReason.FETCH_FAILED, result.stoppedReason)
        assertEquals("https://a.com/2", result.failedUrl)
    }

    /** Crawler dừng khi gặp vòng lặp và thử lại sau lỗi 429. */
    @Test
    fun crawlerFollowsNextLinksAndRetriesRateLimit() = runTest {
        var rateLimited = false
        val pages = mapOf(
            "https://a.com/1" to page("https://a.com/2"),
            "https://a.com/2" to page("https://a.com/3"),
            "https://a.com/3" to page("https://a.com/1")
        )
        val crawler = WebNovelCrawler(
            fetch = { url ->
                if (url == "https://a.com/2" && !rateLimited) {
                    rateLimited = true
                    throw WebFetchException(429, "rate limited")
                }
                pages.getValue(url)
            },
            politeDelayMs = 1L
        )

        val result = crawler.crawl("https://a.com/1", maxChapters = 10)

        assertEquals(3, result.chapters.size)
        assertEquals(CrawlStopReason.LOOP_DETECTED, result.stoppedReason)
    }

    /** Crawler dừng đúng giới hạn số chương. */
    @Test
    fun crawlerStopsAtLimit() = runTest {
        val crawler = WebNovelCrawler(fetch = { url -> page(url + "x") }, politeDelayMs = 1L)

        val result = crawler.crawl("https://a.com/start", maxChapters = 2)

        assertEquals(2, result.chapters.size)
        assertEquals(CrawlStopReason.LIMIT_REACHED, result.stoppedReason)
    }

    /**
     * Tạo HTML chương giả có liên kết chương sau.
     *
     * @param next URL chương sau.
     * @return HTML trang.
     */
    private fun page(next: String): String =
        "<html><body><div class='content'>$paragraph<br>$paragraph<br>$paragraph</div>" +
            "<a href='$next'>Chương sau</a></body></html>"
}
