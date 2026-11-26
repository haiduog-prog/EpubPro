package com.epubpro.core.webnovel

import java.net.URI
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * Tìm ảnh bìa chuẩn của một truyện web từ HTML của site nguồn.
 *
 * Site truyện thường không gắn bìa ở trang chương mà ở trang giới thiệu truyện (`og:image`,
 * `itemprop=image`, JSON-LD `Book`). Luồng dùng: [findLandingUrl] từ trang chương → tải trang giới thiệu →
 * [findCoverUrl]. Lớp chỉ phân tích HTML, không tải mạng.
 */
object WebNovelCoverFinder {
    private val logoHint = Regex("(?i)(logo|favicon|icon|avatar|banner|default|no[-_]?image|placeholder)")
    private val ldImage = Regex("\"image\"\\s*:\\s*(?:\\[\\s*)?(?:\\{[^}]*?\"url\"\\s*:\\s*)?\"([^\"]+)\"")
    private val ldBookType = Regex("\"@type\"\\s*:\\s*\"(?:Book|Novel|CreativeWork)\"", RegexOption.IGNORE_CASE)

    /**
     * Tìm URL trang giới thiệu truyện từ trang một chương.
     *
     * Chọn liên kết cùng host có đường dẫn là thư mục cha gần nhất của đường dẫn chương
     * (ví dụ `/truyen-x/` cho `/truyen-x/chuong-2/`), thường là breadcrumb "tên truyện".
     * Không có liên kết nào khớp thì trả `null`, không tự đoán URL.
     *
     * @param chapterHtml HTML trang chương.
     * @param chapterUrl URL trang chương (dùng để phân giải liên kết tương đối và so host).
     * @return URL trang giới thiệu, hoặc `null` nếu không tìm thấy.
     */
    fun findLandingUrl(chapterHtml: String, chapterUrl: String): String? {
        val chapter = parseHttpUri(chapterUrl) ?: return null
        val chapterPath = normalizedPath(chapter.path)
        val doc = Jsoup.parse(chapterHtml, chapterUrl)
        return doc.select("a[href]")
            .asSequence()
            .mapNotNull { link -> link.absUrl("href").substringBefore('#').takeIf { it.isNotBlank() } }
            .mapNotNull { url -> parseHttpUri(url)?.let { url to it } }
            .filter { (_, uri) -> uri.host.equals(chapter.host, ignoreCase = true) && uri.rawQuery == null }
            .map { (url, uri) -> url to normalizedPath(uri.path) }
            .filter { (_, path) -> path != "/" && path != chapterPath && chapterPath.startsWith(path) }
            .maxByOrNull { (_, path) -> path.length }
            ?.first
    }

    /**
     * Tìm URL ảnh bìa trong một trang (ưu tiên trang giới thiệu truyện).
     *
     * Thứ tự ưu tiên: `meta[property=og:image]`, `meta[name=twitter:image]`, phần tử `itemprop=image`,
     * ảnh trong JSON-LD kiểu `Book`/`Novel`, `link[rel=image_src]`. Bỏ qua ảnh có tên giống logo/icon/ảnh mặc định
     * và URL không phải http(s).
     *
     * @param html HTML của trang.
     * @param pageUrl URL trang để phân giải đường dẫn tương đối.
     * @return URL tuyệt đối của ảnh bìa, hoặc `null` nếu trang không khai báo ảnh phù hợp.
     */
    fun findCoverUrl(html: String, pageUrl: String): String? {
        val doc = Jsoup.parse(html, pageUrl)
        return candidates(doc)
            .mapNotNull { raw -> resolve(doc, raw) }
            .firstOrNull { url -> !logoHint.containsMatchIn(url.substringAfterLast('/').substringBefore('?')) }
    }

    /**
     * Liệt kê các ứng viên ảnh bìa theo thứ tự ưu tiên.
     *
     * @param doc Tài liệu HTML đã parse.
     * @return Chuỗi giá trị thô (có thể tương đối) của các ứng viên.
     */
    private fun candidates(doc: Document): Sequence<String> = sequence {
        doc.select("meta[property=og:image], meta[property=og:image:url], meta[property=og:image:secure_url]")
            .forEach { yield(it.attr("content")) }
        doc.select("meta[name=twitter:image], meta[name=twitter:image:src]").forEach { yield(it.attr("content")) }
        doc.select("[itemprop=image]").forEach { element ->
            yield(element.attr("content").ifBlank { element.attr("src") }.ifBlank { element.attr("href") })
        }
        doc.select("script[type=application/ld+json]")
            .map { it.data() }
            .filter { ldBookType.containsMatchIn(it) }
            .forEach { json -> ldImage.findAll(json).forEach { yield(it.groupValues[1].replace("\\/", "/")) } }
        doc.select("link[rel=image_src]").forEach { yield(it.attr("href")) }
    }.map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * Phân giải một giá trị ảnh thành URL tuyệt đối http(s).
     *
     * @param doc Tài liệu chứa base URL.
     * @param raw Giá trị thô lấy từ thuộc tính.
     * @return URL tuyệt đối, hoặc `null` nếu không phải http(s) (ví dụ `data:`).
     */
    private fun resolve(doc: Document, raw: String): String? {
        val absolute = runCatching { URI(doc.location()).resolve(raw.replace(" ", "%20")).toString() }
            .getOrNull() ?: return null
        return absolute.takeIf { parseHttpUri(it) != null }
    }

    /**
     * Parse URL và chỉ nhận scheme http/https có host.
     *
     * @param url URL cần kiểm tra.
     * @return [URI] hợp lệ, hoặc `null`.
     */
    private fun parseHttpUri(url: String): URI? = runCatching { URI(url) }.getOrNull()
        ?.takeIf { it.scheme?.lowercase() in setOf("http", "https") && !it.host.isNullOrBlank() }

    /**
     * Chuẩn hoá đường dẫn về dạng có `/` cuối để so sánh tiền tố theo từng đoạn.
     *
     * @param path Đường dẫn URL, có thể null.
     * @return Đường dẫn kết thúc bằng `/`.
     */
    private fun normalizedPath(path: String?): String = path.orEmpty().ifEmpty { "/" }.trimEnd('/') + "/"
}
