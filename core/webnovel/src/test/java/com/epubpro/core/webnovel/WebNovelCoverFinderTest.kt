package com.epubpro.core.webnovel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Kiểm tra tìm trang giới thiệu và ảnh bìa truyện web của [WebNovelCoverFinder]. */
class WebNovelCoverFinderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    /** Breadcrumb tên truyện (thư mục cha gần nhất) được chọn thay cho trang chủ hoặc danh mục. */
    @Test
    fun findLandingUrlPicksClosestParentOnSameHost() {
        val html = """
            <a href="/">Trang chủ</a>
            <a href="/doc-truyen/">Danh sách</a>
            <a href="/doc-truyen/cv/ngu-thu/">Ngự Thú</a>
            <a href="/doc-truyen/cv/ngu-thu/chuong-2">Chương sau</a>
            <a href="https://other.example/doc-truyen/cv/ngu-thu/">Site khác</a>
        """.trimIndent()

        assertEquals(
            "https://example.com/doc-truyen/cv/ngu-thu/",
            WebNovelCoverFinder.findLandingUrl(html, "https://example.com/doc-truyen/cv/ngu-thu/chuong-1")
        )
    }

    /** Kiểu truyenfull: chương có `/` cuối, breadcrumb không có `/` cuối vẫn khớp. */
    @Test
    fun findLandingUrlHandlesTrailingSlashDifferences() {
        val html = "<a href='https://truyen.example/truyen-dau-pha'>Đấu Phá</a>"

        assertEquals(
            "https://truyen.example/truyen-dau-pha",
            WebNovelCoverFinder.findLandingUrl(html, "https://truyen.example/truyen-dau-pha/chuong-2/")
        )
    }

    /** Không có liên kết thư mục cha thì không tự đoán URL; liên kết có query (phân trang) bị bỏ qua. */
    @Test
    fun findLandingUrlReturnsNullWithoutParentLink() {
        val html = "<a href='/'>Home</a><a href='/truyen-x?page=2'>Trang 2</a><a href='/truyen-y/'>Khác</a>"

        assertNull(WebNovelCoverFinder.findLandingUrl(html, "https://example.com/truyen-x/chuong-1"))
    }

    /** `og:image` được ưu tiên và đường dẫn tương đối được phân giải theo URL trang. */
    @Test
    fun findCoverUrlPrefersOpenGraphAndResolvesRelative() {
        val html = """
            <meta property="og:image" content="/images/covers/dau-pha.jpg">
            <img itemprop="image" src="https://cdn.example/other.jpg">
        """.trimIndent()

        assertEquals(
            "https://truyen.example/images/covers/dau-pha.jpg",
            WebNovelCoverFinder.findCoverUrl(html, "https://truyen.example/truyen-dau-pha/")
        )
    }

    /** Logo/ảnh mặc định bị bỏ qua, chuyển sang ứng viên kế tiếp (`itemprop=image`). */
    @Test
    fun findCoverUrlSkipsLogoCandidates() {
        val html = """
            <meta property="og:image" content="https://truyen.example/static/logo.png">
            <img itemprop="image" src="https://lh3.example/cover-ngu-thu=w250-h350">
        """.trimIndent()

        assertEquals(
            "https://lh3.example/cover-ngu-thu=w250-h350",
            WebNovelCoverFinder.findCoverUrl(html, "https://truyen.example/ngu-thu/")
        )
    }

    /** Ảnh trong JSON-LD kiểu Book được dùng khi trang không có meta ảnh. */
    @Test
    fun findCoverUrlReadsJsonLdBookImage() {
        val html = """
            <script type="application/ld+json">
            {"@context":"https://schema.org","@type":"Book","name":"Truyện","image":"https:\/\/cdn.example\/book.webp"}
            </script>
        """.trimIndent()

        assertEquals("https://cdn.example/book.webp", WebNovelCoverFinder.findCoverUrl(html, "https://example.com/truyen/"))
    }

    /** Trang không khai báo ảnh hoặc chỉ có ảnh `data:` thì trả null. */
    @Test
    fun findCoverUrlReturnsNullWithoutUsableImage() {
        assertNull(WebNovelCoverFinder.findCoverUrl("<title>Truyện</title>", "https://example.com/truyen/"))
        assertNull(
            WebNovelCoverFinder.findCoverUrl(
                "<meta property='og:image' content='data:image/png;base64,AAAA'>",
                "https://example.com/truyen/"
            )
        )
    }

    /** `dc:source` do [EpubWriter.write] ghi được đọc lại đúng. */
    @Test
    fun readSourceUrlReturnsWrittenSource() {
        val file = tempFolder.newFile("web.epub")
        EpubWriter.write(
            file,
            EpubMetadata(title = "Truyện", author = "Tác giả", language = "vi", sourceUrl = "https://example.com/truyen/chuong-1"),
            listOf(WebNovelChapter("Chương 1", listOf("Nội dung"), "https://example.com/truyen/chuong-1"))
        )

        assertEquals("https://example.com/truyen/chuong-1", EpubWriter.readSourceUrl(file))
    }

    /** EPUB không do app tạo (không có chương `OEBPS/text/chapter_*`) bị bỏ qua dù có `dc:source`. */
    @Test
    fun readSourceUrlIgnoresForeignEpubWithSource() {
        val file = tempFolder.newFile("store.epub")
        java.util.zip.ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("OEBPS/content.opf"))
            zip.write(
                ("<package><metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">" +
                    "<dc:source>https://www.gutenberg.org/ebooks/1342</dc:source></metadata></package>").toByteArray()
            )
            zip.closeEntry()
            zip.putNextEntry(java.util.zip.ZipEntry("OEBPS/Text/part0001.xhtml"))
            zip.write("<html/>".toByteArray())
            zip.closeEntry()
        }

        assertNull(EpubWriter.readSourceUrl(file))
    }

    /** File không phải EPUB không làm ném exception. */
    @Test
    fun readSourceUrlReturnsNullForInvalidFile() {
        val file = tempFolder.newFile("broken.epub").apply { writeText("not a zip") }

        assertNull(EpubWriter.readSourceUrl(file))
    }
}
