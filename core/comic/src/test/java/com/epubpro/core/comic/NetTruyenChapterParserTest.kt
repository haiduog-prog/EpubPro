package com.epubpro.core.comic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit test cho [NetTruyenChapterParser].
 *
 * Mỗi test case load fixture HTML từ `src/test/resources/` và kiểm tra hành vi parser.
 * Không dùng mock framework — thuần JUnit4.
 */
class NetTruyenChapterParserTest {

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    /**
     * Đọc file fixture từ classpath resources.
     *
     * @param name Tên file trong `src/test/resources/`.
     * @return Nội dung file dưới dạng String.
     */
    private fun loadFixture(name: String): String =
        javaClass.getResourceAsStream("/$name")
            ?.reader(Charsets.UTF_8)
            ?.readText()
            ?: error("Không tìm thấy fixture: $name")

    /** URL trang giả lập cho fixture 218 trang. */
    private val url218 = "https://nettruyen.gg/truyen-tranh/chi-ta-nhin-thay-huong-dan/chuong-1"

    /** URL trang giả lập cho fixture 16 trang. */
    private val url16 = "https://nettruyen.gg/truyen-tranh/phuong-thuc-bao-thu/chuong-1"

    /** URL trang giả lập cho fixture mixed attrs. */
    private val urlMixed = "https://nettruyen.gg/truyen-tranh/truyen-mix/chuong-1"

    /** URL trang giả lập cho fixture no images. */
    private val urlNoImages = "https://nettruyen.gg/truyen-tranh/truyen-khong-anh/chuong-1"

    /** URL trang giả lập cho fixture UI images. */
    private val urlUiImages = "https://nettruyen.gg/truyen-tranh/truyen-ui/chuong-1"

    // ---------------------------------------------------------------------------
    // Test cases
    // ---------------------------------------------------------------------------

    /**
     * Fixture 218 trang phải trả về đúng 218 URL ảnh, mỗi URL bắt đầu bằng CDN NetTruyen.
     */
    @Test
    fun parse218Pages_returnsAllImages() {
        val html = loadFixture("nettruyen_218pages.html")
        val info = NetTruyenChapterParser.parse(html, url218)

        assertEquals("Phải có đúng 218 trang", 218, info.pages.size)
        info.pages.forEach { url ->
            assertTrue(
                "Mỗi URL phải bắt đầu bằng https://cdn1.nettruyen.gg/: $url",
                url.startsWith("https://cdn1.nettruyen.gg/")
            )
        }
    }

    /**
     * Nút "Chương sau" trong fixture 218 trang trỏ lại chính URL trang hiện tại →
     * [ComicChapterInfo.nextChapterUrl] phải là null (loop detection).
     */
    @Test
    fun parse218Pages_loopLinkSetToNull() {
        val html = loadFixture("nettruyen_218pages.html")
        val info = NetTruyenChapterParser.parse(html, url218)

        assertNull(
            "nextChapterUrl phải null vì link trỏ lại chính trang hiện tại (vòng lặp)",
            info.nextChapterUrl
        )
    }

    /**
     * Fixture 16 trang có link chương sau và chương trước hợp lệ.
     */
    @Test
    fun parse16Pages_hasNextChapter() {
        val html = loadFixture("nettruyen_16pages.html")
        val info = NetTruyenChapterParser.parse(html, url16)

        assertEquals("Phải có đúng 16 trang", 16, info.pages.size)
        assertNotNull("nextChapterUrl phải có giá trị", info.nextChapterUrl)
        assertNotNull("previousChapterUrl phải có giá trị", info.previousChapterUrl)
    }

    /**
     * Danh sách ảnh từ fixture 16 trang không được có URL trùng nhau.
     */
    @Test
    fun parse16Pages_noDuplicateUrls() {
        val html = loadFixture("nettruyen_16pages.html")
        val info = NetTruyenChapterParser.parse(html, url16)

        val distinct = info.pages.distinct()
        assertEquals(
            "Không được có URL ảnh trùng: ${info.pages.size} tổng vs ${distinct.size} unique",
            distinct.size,
            info.pages.size
        )
    }

    /**
     * Ảnh không có `data-src` trong fixture mixed attrs phải fallback dùng `src`.
     */
    @Test
    fun parseMixedAttrs_fallbackToSrc() {
        val html = loadFixture("nettruyen_mixed_attrs.html")
        val info = NetTruyenChapterParser.parse(html, urlMixed)

        val hasSrcFallback = info.pages.any { it.contains("001.jpg") }
        assertTrue("Ảnh 001.jpg (chỉ có src) phải được nhận vào pages", hasSrcFallback)
    }

    /**
     * Ảnh `src="data:image/..."` phải bị lọc khỏi danh sách trang.
     */
    @Test
    fun parseMixedAttrs_base64Filtered() {
        val html = loadFixture("nettruyen_mixed_attrs.html")
        val info = NetTruyenChapterParser.parse(html, urlMixed)

        val hasBase64 = info.pages.any { it.startsWith("data:") }
        assertTrue("Ảnh base64 data URI phải bị lọc", !hasBase64)
    }

    /**
     * Fixture không có ảnh phải ném [ComicChapterParseException].
     */
    @Test(expected = ComicChapterParseException::class)
    fun parseNoImages_throwsException() {
        val html = loadFixture("nettruyen_no_images.html")
        NetTruyenChapterParser.parse(html, urlNoImages)
    }

    /**
     * Fixture có 5 ảnh trang + 3 ảnh giao diện (logo, icon, banner nhỏ): chỉ 5 ảnh trang được giữ lại.
     */
    @Test
    fun parseUiImages_filtersIconsAndLogo() {
        val html = loadFixture("nettruyen_ui_images.html")
        val info = NetTruyenChapterParser.parse(html, urlUiImages)

        assertEquals(
            "Chỉ 5 ảnh trang thật phải được giữ lại, ảnh giao diện bị lọc",
            5,
            info.pages.size
        )
    }

    /**
     * Fixture 218 trang có `<select id="ImageList">` với 2 option → `availableServers.size == 2`.
     */
    @Test
    fun parseServers_returnsServerList() {
        val html = loadFixture("nettruyen_218pages.html")
        val info = NetTruyenChapterParser.parse(html, url218)

        assertEquals(
            "Phải có đúng 2 server từ <select id='ImageList'>",
            2,
            info.availableServers.size
        )
    }

    /**
     * `sourceHost` phải được trích xuất đúng từ URL trang.
     */
    @Test
    fun sourceHostExtracted() {
        val html = loadFixture("nettruyen_218pages.html")
        val info = NetTruyenChapterParser.parse(html, url218)

        assertEquals("sourceHost phải là 'nettruyen.gg'", "nettruyen.gg", info.sourceHost)
    }

    /**
     * Tiêu đề phải được parse đúng từ thẻ `<title>` của trang.
     */
    @Test
    fun titleExtractedFromPageTitle() {
        val html = loadFixture("nettruyen_218pages.html")
        val info = NetTruyenChapterParser.parse(html, url218)

        assertTrue(
            "title phải chứa nội dung từ thẻ <title>: '${info.title}'",
            info.title.isNotEmpty()
        )
        assertTrue(
            "title phải chứa tên truyện hoặc chương: '${info.title}'",
            info.title.contains("Chỉ Ta Nhìn Thấy", ignoreCase = true) ||
                info.title.contains("Chương 1", ignoreCase = true) ||
                info.title.contains("NetTruyen", ignoreCase = true)
        )
    }

    /**
     * Kiểm tra parse HTML thực tế từ NetTruyen lấy từ thiết bị Android.
     */
    @Test
    fun parseLiveScratchHtml() {
        val html = loadFixture("scratch_nettruyen.html")
        val info = NetTruyenChapterParser.parse(html, url16)
        assertEquals("Mẫu thực tế NetTruyen phải parse được 15 trang ảnh", 15, info.pages.size)
        info.pages.forEach { url ->
            assertTrue("Ảnh phải bắt đầu bằng https://image4.kcgsbok.com/: $url", url.startsWith("https://image4.kcgsbok.com/"))
        }
        assertEquals("Chương sau phải là chương 2", "https://nettruyen.gg/truyen-tranh/phuong-thuc-bao-thu/chuong-2", info.nextChapterUrl)
    }
}
