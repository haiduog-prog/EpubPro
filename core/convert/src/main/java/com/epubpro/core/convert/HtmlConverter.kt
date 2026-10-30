package com.epubpro.core.convert

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeVisitor

/**
 * Convert nội dung chương HTML: chỉ thay chữ trong text node, giữ nguyên cấu trúc thẻ, class và ảnh để
 * pipeline sanitize/CSS/phân trang của Reader hoạt động như với bản gốc.
 */
object HtmlConverter {
    private val skippedParents = setOf("script", "style", "rt", "rp", "code", "pre", "title")
    private val sentenceBoundaryTags = setOf(
        "p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "li", "br", "blockquote", "td", "th", "dt", "dd",
        "section", "article", "body"
    )

    /**
     * Convert toàn bộ chữ Hán trong HTML.
     *
     * @param html HTML chương gốc.
     * @param converter Bộ convert đã nạp từ điển.
     * @return HTML đã convert (không pretty-print để không đổi khoảng trắng).
     */
    fun convert(html: String, converter: HanVietConverter): String {
        val document = Jsoup.parse(html)
        document.outputSettings().prettyPrint(false).charset(Charsets.UTF_8)
        convertDocument(document, converter)
        return document.outerHtml()
    }

    /**
     * Duyệt cây DOM theo thứ tự tài liệu, giữ trạng thái "đầu câu" xuyên qua các text node trong cùng khối.
     *
     * @param document Tài liệu cần convert tại chỗ.
     * @param converter Bộ convert.
     */
    private fun convertDocument(document: Document, converter: HanVietConverter) {
        var sentenceStart = true
        var afterWord = false
        document.body().traverse(object : NodeVisitor {
            override fun head(node: Node, depth: Int) {
                when (node) {
                    is Element -> if (node.normalName() in sentenceBoundaryTags) {
                        sentenceStart = true
                        afterWord = false
                    }
                    is TextNode -> {
                        val parentName = (node.parent() as? Element)?.normalName()
                        if (parentName in skippedParents) return
                        val original = node.wholeText
                        if (original.isBlank()) {
                            if (original.isNotEmpty()) afterWord = false
                            return
                        }
                        if (!needsConversion(original)) {
                            if (original.any { it.isLetterOrDigit() }) sentenceStart = false
                            afterWord = !original.last().isWhitespace()
                            return
                        }
                        val result = converter.convert(original, sentenceStart, afterWord)
                        node.text(result.text)
                        sentenceStart = result.endsSentence
                        afterWord = result.endsWithWord
                    }
                }
            }

            override fun tail(node: Node, depth: Int) = Unit
        })
    }

    /**
     * Kiểm tra chuỗi có chữ Hán hoặc dấu câu toàn góc cần chuyển.
     *
     * @param text Chuỗi cần kiểm tra.
     * @return true nếu cần chạy bộ convert.
     */
    private fun needsConversion(text: String): Boolean =
        text.any { HanVietConverter.mapPunctuation(it) != it.toString() } ||
            text.codePoints().anyMatch { Character.UnicodeScript.of(it) == Character.UnicodeScript.HAN }
}

/**
 * Nhận diện văn bản tiếng Trung để gợi ý bật Convert.
 */
object ChineseTextDetector {
    private const val CHINESE_THRESHOLD = 0.3
    private const val MAX_KANA_RATIO = 0.05

    /**
     * Kiểm tra văn bản có phần lớn là chữ Hán và không phải tiếng Nhật (kana).
     *
     * @param text Mẫu văn bản (nên lấy vài nghìn ký tự đầu chương).
     * @return true nếu nhiều khả năng là tiếng Trung.
     */
    fun isLikelyChinese(text: String): Boolean {
        var letters = 0
        var han = 0
        var kana = 0
        text.codePoints().forEach { codePoint ->
            if (!Character.isLetter(codePoint)) return@forEach
            letters++
            when (Character.UnicodeScript.of(codePoint)) {
                Character.UnicodeScript.HAN -> han++
                Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> kana++
                else -> Unit
            }
        }
        if (letters == 0) return false
        return han.toDouble() / letters >= CHINESE_THRESHOLD && kana.toDouble() / letters <= MAX_KANA_RATIO
    }
}
