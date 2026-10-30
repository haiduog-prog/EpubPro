package com.epubpro.core.webnovel

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * Bóc tách nội dung chính của trang truyện/bài viết bằng heuristic kiểu Readability, viết lại trên jsoup.
 *
 * Chạy hoàn toàn ngoài WebView (không inject JavaScript vào trang lạ) và thuần JVM nên kiểm thử được bằng
 * fixture HTML. Khác Readability gốc, thuật toán chấm cả văn bản nằm trực tiếp trong `<div>` ngăn bằng `<br>`,
 * kiểu trình bày phổ biến của các site truyện chữ.
 */
object ArticleExtractor {
    /** Số ký tự tối thiểu để coi là đã tìm thấy nội dung chương. */
    const val MIN_CONTENT_CHARS = 200

    private const val REMOVED_TAGS =
        "script,style,noscript,iframe,form,nav,header,footer,aside,button,select,input,textarea,svg,canvas,template,object,embed"
    private const val MAX_REMOVABLE_TEXT = 3000
    private val negativePattern = Regex(
        "comment|footer|sidebar|side-bar|menu|\\bnav|breadcrumb|share|social|related|recommend|banner|advert|" +
            "\\bads?\\b|ads-|popup|modal|pagination|pager|login|signup|rating|\\btags?\\b|toolbar",
        RegexOption.IGNORE_CASE
    )
    private val positivePattern = Regex(
        "chapter|content|article|\\btext|story|entry|post-body|reading|reader|novel|chr-c|box-chap|\\btxt",
        RegexOption.IGNORE_CASE
    )
    private val candidateTags = setOf("div", "article", "section", "main", "td", "body")
    private val inlineTags = setOf(
        "span", "em", "i", "b", "strong", "font", "u", "small", "big", "sup", "sub", "a", "mark", "s", "ruby", "rt", "label"
    )
    private val blockTags = setOf(
        "p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "li", "blockquote", "section", "article", "pre", "tr", "dd", "dt", "main"
    )
    private val titleSeparators = Regex("\\s+[-–—|_]\\s+|\\s*[|_]\\s*")

    /**
     * Bóc tách nội dung từ HTML.
     *
     * @param html Mã HTML đầy đủ của trang.
     * @param url URL tuyệt đối của trang, dùng để phân giải liên kết.
     * @return Nội dung đã bóc tách, hoặc null nếu không đủ [MIN_CONTENT_CHARS] ký tự.
     */
    fun extract(html: String, url: String): WebArticle? = extract(Jsoup.parse(html, url), url)

    /**
     * Bóc tách nội dung từ tài liệu jsoup đã phân tích.
     *
     * Tài liệu bị sửa đổi (xóa phần rác), caller không nên dùng lại sau khi gọi.
     *
     * @param document Tài liệu HTML.
     * @param url URL tuyệt đối của trang.
     * @return Nội dung đã bóc tách, hoặc null nếu không tìm thấy nội dung chính.
     */
    fun extract(document: Document, url: String): WebArticle? {
        // Liên kết chương thường nằm trong nav/footer nên phải dò trước khi dọn rác.
        val links = ChapterLinkDetector.detect(document, url)
        val pageTitle = document.title().trim()
        val headingTitle = document.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotEmpty() }
        val explicitChapterTitle = document.selectFirst(".chapter-title, #chapter-title, [itemprop=headline]")
            ?.text()?.trim()?.takeIf { it.isNotEmpty() }
        val novelTitle = document.selectFirst("meta[property=og:novel:book_name]")?.attr("content")?.trim()
            ?.takeIf { it.isNotEmpty() }
        val author = document.selectFirst("meta[property=article:author], meta[property=og:novel:author]")
            ?.attr("content")?.trim()?.takeIf { it.isNotEmpty() }

        cleanDocument(document)
        val body = document.body() ?: return null
        val best = pickBestCandidate(body) ?: return null
        val paragraphs = toParagraphs(best).toMutableList()

        val chapterTitle = explicitChapterTitle
            ?: titleParts(pageTitle).firstOrNull { it.matches(Regex("(?i)^(chương|chuong|chapter)\\s+\\d+.*")) }
            ?: headingTitle
            ?: best.selectFirst("h1, h2, h3")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: titleParts(pageTitle).firstOrNull()
            ?: pageTitle
        if (paragraphs.firstOrNull()?.equals(chapterTitle, ignoreCase = true) == true) paragraphs.removeAt(0)
        if (paragraphs.sumOf { it.length } < MIN_CONTENT_CHARS) return null

        return WebArticle(
            url = url,
            title = chapterTitle,
            novelTitle = novelTitle ?: guessNovelTitle(pageTitle, chapterTitle),
            paragraphs = paragraphs,
            links = links,
            author = author
        )
    }

    /**
     * Xóa thẻ không phải nội dung, phần tử ẩn và khối có class/id gợi ý rác (quảng cáo, bình luận, menu).
     *
     * @param document Tài liệu cần dọn.
     */
    private fun cleanDocument(document: Document) {
        document.select(REMOVED_TAGS).remove()
        document.select("[hidden], [aria-hidden=true], [style~=(?i)display\\s*:\\s*none], [style~=(?i)visibility\\s*:\\s*hidden]")
            .remove()
        document.body().select("*").toList().forEach { element ->
            if (element.parent() == null) return@forEach
            val idClass = element.id() + " " + element.className()
            if (idClass.isBlank()) return@forEach
            if (negativePattern.containsMatchIn(idClass) &&
                !positivePattern.containsMatchIn(idClass) &&
                element.text().length < MAX_REMOVABLE_TEXT
            ) {
                element.remove()
            }
        }
    }

    /**
     * Chọn phần tử chứa nhiều văn bản trực tiếp nhất, đã trừ mật độ liên kết và cộng/trừ theo class/id.
     *
     * @param body Thẻ body đã dọn.
     * @return Phần tử nội dung tốt nhất, hoặc null nếu không có văn bản.
     */
    private fun pickBestCandidate(body: Element): Element? {
        var best: Element? = null
        var bestScore = 0.0
        body.select(candidateTags.joinToString(",")).forEach { element ->
            val direct = directTextLength(element)
            if (direct <= 0) return@forEach
            val totalText = element.text().length.coerceAtLeast(1)
            val linkText = element.select("a").sumOf { it.text().length }
            val linkDensity = (linkText.toDouble() / totalText).coerceIn(0.0, 1.0)
            var score = direct * (1.0 - linkDensity)
            val idClass = element.id() + " " + element.className()
            if (positivePattern.containsMatchIn(idClass)) score *= 1.25
            if (negativePattern.containsMatchIn(idClass)) score *= 0.5
            if (score > bestScore) {
                bestScore = score
                best = element
            }
        }
        return best
    }

    /**
     * Đếm ký tự văn bản thuộc trực tiếp phần tử: text node, thẻ inline và các đoạn `<p>` con.
     *
     * Không tính văn bản nằm sâu trong `<div>` con để wrapper bao ngoài không thắng khối nội dung thật.
     *
     * @param element Phần tử cần đếm.
     * @return Số ký tự (đã bỏ khoảng trắng hai đầu).
     */
    private fun directTextLength(element: Element): Int = element.childNodes().sumOf { node ->
        when (node) {
            is TextNode -> node.text().trim().length
            is Element -> when (node.normalName()) {
                in inlineTags, "p" -> if (node.normalName() == "a") 0 else node.text().trim().length
                else -> 0
            }
            else -> 0
        }
    }

    /**
     * Chuyển phần tử nội dung thành danh sách đoạn văn bản thuần, ngắt đoạn tại thẻ khối và `<br>`.
     *
     * @param root Phần tử nội dung.
     * @return Các đoạn đã chuẩn hóa khoảng trắng, bỏ đoạn rỗng.
     */
    internal fun toParagraphs(root: Element): List<String> {
        val paragraphs = mutableListOf<String>()
        val current = StringBuilder()

        fun flush() {
            val text = normalizeWhitespace(current.toString())
            if (text.isNotEmpty()) paragraphs += text
            current.setLength(0)
        }

        fun walk(node: Node) {
            when (node) {
                is TextNode -> current.append(node.wholeText)
                is Element -> when (node.normalName()) {
                    "br" -> flush()
                    in blockTags -> {
                        flush()
                        node.childNodes().forEach(::walk)
                        flush()
                    }
                    "img", "hr" -> flush()
                    // Thẻ inline và thẻ lạ đều giữ nguyên chữ bên trong.
                    else -> node.childNodes().forEach(::walk)
                }
                else -> Unit
            }
        }

        root.childNodes().forEach(::walk)
        flush()
        return paragraphs
    }

    /**
     * Đoán tên truyện từ tiêu đề trang dạng "Chương 1 - Tên truyện - Tên site".
     *
     * @param pageTitle Nội dung thẻ `<title>`.
     * @param chapterTitle Tiêu đề chương đã xác định.
     * @return Tên truyện hoặc null nếu không tách được.
     */
    private fun guessNovelTitle(pageTitle: String, chapterTitle: String): String? {
        val parts = titleParts(pageTitle)
        if (parts.size < 2) return null
        return parts.firstOrNull { !it.equals(chapterTitle, ignoreCase = true) && !chapterTitle.contains(it) }
    }

    /**
     * Tách tiêu đề trang theo các dấu phân cách thường gặp.
     *
     * @param pageTitle Tiêu đề trang.
     * @return Các phần không rỗng.
     */
    private fun titleParts(pageTitle: String): List<String> =
        pageTitle.split(titleSeparators).map(String::trim).filter(String::isNotEmpty)

    /**
     * Chuẩn hóa khoảng trắng (kể cả NBSP và khoảng trắng toàn góc) về một dấu cách.
     *
     * @param value Chuỗi gốc.
     * @return Chuỗi đã gộp khoảng trắng và cắt hai đầu.
     */
    private fun normalizeWhitespace(value: String): String =
        value.replace(' ', ' ').replace('　', ' ').replace(Regex("\\s+"), " ").trim()
}
