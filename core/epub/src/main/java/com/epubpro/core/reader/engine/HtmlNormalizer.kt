package com.epubpro.core.reader.engine

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

object HtmlNormalizer {

    private val BLOCK_TAGS = setOf(
        "address", "article", "aside", "blockquote", "body", "center", "dd", "details",
        "dialog", "dir", "div", "dl", "dt", "fieldset", "figcaption", "figure", "footer",
        "form", "h1", "h2", "h3", "h4", "h5", "h6", "header", "hgroup", "hr", "li",
        "main", "menu", "nav", "ol", "p", "pre", "section", "summary", "table", "tbody",
        "td", "tfoot", "th", "thead", "tr", "ul"
    )

    private val RECURSIVE_CONTAINERS = setOf(
        "body", "section", "article", "main", "div", "blockquote", "li", "td", "th", "center", "aside", "figure"
    )

    /**
     * Chuẩn hóa nội dung HTML EPUB thiếu thẻ `<p>` (như sách chuyển đổi từ TXT hoặc text thô).
     * Tự động bọc các đoạn văn cách nhau bởi `<br>` hoặc dấu xuống dòng vào thẻ `<p>` hợp lệ
     * mà không làm hỏng cấu trúc các khối block khác (danh sách, bảng, pre, tiêu đề).
     *
     * Áp dụng đường dẫn xử lý nhanh (fast-path) kiểm tra chuỗi để trả về ngay nếu tài liệu đã có sẵn từ 3 thẻ `<p>` trở lên.
     *
     * @param htmlContent Chuỗi HTML thô cần chuẩn hóa.
     * @return Chuỗi HTML đã được chuẩn hóa cấu trúc đoạn văn.
     */
    fun normalize(htmlContent: String): String {
        if (htmlContent.isBlank()) return htmlContent

        // Fast-path: Nếu tài liệu đã có từ 3 thẻ <p> trở lên, trả về ngay lập tức (tránh chi phí phân tích Jsoup)
        if (hasAtLeastThreeParagraphTags(htmlContent)) {
            return htmlContent
        }

        try {
            val doc = Jsoup.parse(htmlContent)
            val body = doc.body() ?: return htmlContent

            val pTags = body.select("p")
            // If the document already has 3 or more <p> tags, assume it's properly structured
            if (pTags.size >= 3) {
                return htmlContent
            }

            if (body.select("br").isEmpty()) {
                replaceLineBreaksInBodyTextNodes(body)
            }

            fun isBlock(element: Element): Boolean {
                val tag = element.tagName().lowercase()
                return tag in BLOCK_TAGS || element.tag().isBlock
            }

            fun wrapInP(parent: Element) {
                val children = parent.childNodes().toList()
                if (children.isEmpty()) return

                val currentGroup = mutableListOf<Node>()

                fun flushGroup() {
                    if (currentGroup.isNotEmpty()) {
                        val shouldWrap = currentGroup.any { node ->
                            node is Element || (node is TextNode && node.text().isNotBlank())
                        }
                        if (shouldWrap) {
                            val p = Element("p")
                            currentGroup.first().before(p)
                            currentGroup.forEach { p.appendChild(it) }
                        }
                        currentGroup.clear()
                    }
                }

                for (node in children) {
                    if (node is Element) {
                        val tag = node.tagName().lowercase()
                        if (isBlock(node)) {
                            flushGroup()
                            if (tag in RECURSIVE_CONTAINERS) {
                                wrapInP(node)
                            }
                        } else if (tag == "br") {
                            flushGroup()
                            node.remove()
                        } else {
                            currentGroup.add(node)
                        }
                    } else {
                        currentGroup.add(node)
                    }
                }
                flushGroup()
            }

            wrapInP(body)

            return doc.outerHtml()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return htmlContent
    }

    private val LINE_BREAK_PRESERVED_TAGS = setOf("pre", "code", "script", "style")

    /**
     * Thay thế các ký tự xuống dòng (`\r?\n`) bên trong các [TextNode] của `body` bằng thẻ `<br>`
     * mà không làm hỏng thuộc tính thẻ nhiều dòng hoặc nội dung bên trong `<pre>`, `<code>`, `<script>`, `<style>`.
     *
     * @param root Phần tử gốc (`body`) cần duyệt và tách dòng.
     */
    private fun replaceLineBreaksInBodyTextNodes(root: Element) {
        val textNodes = mutableListOf<TextNode>()
        fun collectTextNodes(node: Node) {
            if (node is TextNode) {
                if (node.wholeText.contains('\n') || node.wholeText.contains('\r')) {
                    textNodes.add(node)
                }
            } else if (node is Element) {
                if (node.tagName().lowercase() !in LINE_BREAK_PRESERVED_TAGS) {
                    for (child in node.childNodes()) {
                        collectTextNodes(child)
                    }
                }
            }
        }
        collectTextNodes(root)

        val lineBreakRegex = Regex("(\\r?\\n)+")
        for (textNode in textNodes) {
            val whole = textNode.wholeText
            val parts = whole.split(lineBreakRegex)
            if (parts.size <= 1) continue
            for (index in parts.indices) {
                val part = parts[index]
                if (part.isNotEmpty()) {
                    textNode.before(TextNode(part))
                }
                if (index < parts.lastIndex) {
                    textNode.before(Element("br"))
                }
            }
            textNode.remove()
        }
    }

    /**
     * Kiểm tra nhanh sự xuất hiện của ít nhất 3 thẻ mở `<p>` trong chuỗi HTML mà không cần khởi tạo Jsoup Document.
     *
     * @param html Chuỗi mã HTML cần kiểm tra.
     * @return `true` nếu tìm thấy từ 3 thẻ `<p>` hợp lệ trở lên, ngược lại trả về `false`.
     */
    private fun hasAtLeastThreeParagraphTags(html: String): Boolean {
        var count = 0
        var startIndex = 0
        val length = html.length
        while (startIndex < length) {
            val idx = html.indexOf("<p", startIndex, ignoreCase = true)
            if (idx == -1) break
            val charAfter = html.getOrNull(idx + 2)
            if (charAfter == null || charAfter.isWhitespace() || charAfter == '>' || charAfter == '/') {
                count++
                if (count >= 3) return true
            }
            startIndex = idx + 2
        }
        return false
    }
}
