package com.epubpro.core.webnovel

import java.net.URI
import org.jsoup.Jsoup

/** Liên kết chương trên trang giới thiệu truyện. */
data class NovelChapterLink(val title: String, val url: String, val number: Int)

/** Kết quả nhận diện trang giới thiệu và các chương được trang cung cấp. */
data class NovelLanding(val chapters: List<NovelChapterLink>, val firstChapter: NovelChapterLink?)

/** Nhận diện trang giới thiệu dựa trên liên kết, không tự tạo đường dẫn chương. */
object NovelLandingDetector {
    private val chapterNumber = Regex("(?i)^(?:chương|chuong|chapter)\\s*0*(\\d+)\\b|^0*(\\d+)\\s+")
    private val pageChapter = Regex("(?i)^(?:chương|chuong|chapter)\\s*\\d+\\b")

    /**
     * Tìm các liên kết chương cùng host trên trang giới thiệu; trang chương không được coi là danh sách.
     *
     * @param html HTML của trang đang hiển thị.
     * @param url URL trang gốc để kiểm tra host và phân giải liên kết.
     * @return Danh sách chương nếu nhận diện được trang giới thiệu, hoặc null.
     */
    fun detect(html: String, url: String): NovelLanding? {
        val page = runCatching { URI(url) }.getOrNull() ?: return null
        val doc = Jsoup.parse(html, url)
        val title = doc.title().trim()
        if (pageChapter.containsMatchIn(title) || doc.select("h1").any { pageChapter.containsMatchIn(it.text().trim()) }) return null
        val chapters = doc.select("a[href]").mapNotNull { link ->
            val label = link.text().trim().replace(Regex("\\s+"), " ")
            val match = chapterNumber.find(label) ?: return@mapNotNull null
            val number = (match.groupValues[1].ifEmpty { match.groupValues[2] }).toIntOrNull() ?: return@mapNotNull null
            if (number <= 0) return@mapNotNull null
            val target = link.absUrl("href").substringBefore('#')
            val uri = runCatching { URI(target) }.getOrNull() ?: return@mapNotNull null
            if (uri.scheme?.lowercase() !in listOf("http", "https") || !uri.host.equals(page.host, true) || target == url.substringBefore('#')) return@mapNotNull null
            NovelChapterLink(label, target, number)
        }.distinctBy { it.url }.sortedBy { it.number }
        if (chapters.isEmpty()) {
            val bookMetadata = doc.select("script[type=application/ld+json]").any {
                Regex("\"@type\"\\s*:\\s*\"(?:Book|Novel)\"", RegexOption.IGNORE_CASE).containsMatchIn(it.data())
            } || doc.selectFirst("meta[property=og:type]")?.attr("content")?.equals("book", true) == true
            return if (bookMetadata) NovelLanding(emptyList(), null) else null
        }
        val parentPath = page.path.orEmpty().trimEnd('/') + "/"
        val first = chapters.filter { it.number == 1 && URI(it.url).path.orEmpty().startsWith(parentPath) }.singleOrNull()
        return NovelLanding(chapters, first)
    }
}
