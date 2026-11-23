package com.epubpro.core.reader.filter

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.Locale

/**
 * Bộ làm sạch HTML cho nội dung sách EPUB.
 *
 * Loại bỏ triệt để các mã độc, active script, iframe, form, event handlers (on*),
 * và URL nguy hiểm (javascript:, vbscript:) trước khi nạp vào Android WebView.
 */
object EpubHtmlSanitizer {

    /** Phiên bản của bộ lọc làm sạch HTML EPUB. */
    const val CURRENT_SANITIZER_VERSION = 1

    private val BLOCKED_TAGS = setOf(
        "script",
        "iframe",
        "frame",
        "frameset",
        "object",
        "embed",
        "applet",
        "form",
        "input",
        "button",
        "select",
        "textarea",
        "style",
        "base",
        "meta",
        "noscript",
        "template",
        "set",
        "animate",
        "animateMotion",
        "animateTransform",
        "foreignObject"
    )

    private val URL_ATTRIBUTES = listOf(
        "href",
        "src",
        "action",
        "data",
        "formaction",
        "xlink:href",
        "srcset",
        "poster",
        "background",
        "ping",
        "to",
        "values",
        "from"
    )

    private val SAFE_DATA_IMAGE_PREFIXES = listOf(
        "data:image/png",
        "data:image/jpeg",
        "data:image/jpg",
        "data:image/gif",
        "data:image/webp"
    )

    /**
     * Làm sạch mã HTML/XHTML của chương sách và trả về kiểu dữ liệu đóng gói an toàn [SanitizedEpubHtml].
     *
     * @param html Chuỗi HTML gốc cần xử lý.
     * @return Đối tượng [SanitizedEpubHtml] đã được làm sạch an toàn.
     */
    fun sanitize(html: String): SanitizedEpubHtml {
        if (html.isBlank()) return SanitizedEpubHtml.EMPTY

        val doc = Jsoup.parse(html)
        doc.outputSettings().apply {
            syntax(Document.OutputSettings.Syntax.html)
            charset(Charsets.UTF_8)
            prettyPrint(false)
        }

        // 1. Loại bỏ các thẻ độc hại / active tags
        for (tag in BLOCKED_TAGS) {
            doc.select(tag).remove()
        }

        // 2. Loại bỏ toàn bộ thẻ link bên ngoài
        doc.select("link").remove()

        // 3. Quét và làm sạch tất cả elements
        val allElements = doc.allElements
        for (element in allElements) {
            sanitizeElement(element)
        }

        return SanitizedEpubHtml(doc.outerHtml())
    }

    private fun sanitizeElement(element: Element) {
        val attributesToRemove = mutableListOf<String>()

        for (attribute in element.attributes()) {
            val attrName = attribute.key.lowercase(Locale.ROOT)
            val attrValue = attribute.value.trim()

            // 3.1. Loại bỏ các event attributes (onclick, onerror, onload, onmouseover, ...)
            if (attrName.startsWith("on")) {
                attributesToRemove.add(attribute.key)
                continue
            }

            // 3.2. Loại bỏ inline style trên html/body để nhường quyền kiểm soát cho CssInjector
            if (element.normalName() in listOf("html", "body") && attrName == "style") {
                attributesToRemove.add(attribute.key)
                continue
            }

            // 3.3. Ngăn chặn DOM clobbering đối với ReaderJsBridge hoặc namespace epubpro-
            if ((attrName == "id" || attrName == "name") &&
                (attrValue.equals("ReaderJsBridge", ignoreCase = true) ||
                    attrValue.startsWith("epubpro-", ignoreCase = true))
            ) {
                attributesToRemove.add(attribute.key)
                continue
            }

            // 3.4. Kiểm tra các thuộc tính URL
            if (attrName in URL_ATTRIBUTES) {
                if (attrName == "srcset") {
                    val candidates = attrValue.split(',').map { it.trim().substringBefore(' ').trim() }
                    if (candidates.any { isDangerousUrl(it) }) {
                        attributesToRemove.add(attribute.key)
                    }
                } else if (isDangerousUrl(attrValue)) {
                    attributesToRemove.add(attribute.key)
                }
            }
        }

        for (attrKey in attributesToRemove) {
            element.removeAttr(attrKey)
        }
    }

    private fun isDangerousUrl(url: String): Boolean {
        if (url.isBlank()) return false

        // Bỏ qua các ký tự khoảng trắng hoặc điều khiển đặc biệt
        val normalized = url.replace("""[\s\u0000-\u001F]""".toRegex(), "").lowercase(Locale.ROOT)

        // Chặn các scheme nguy hiểm
        if (normalized.startsWith("javascript:") ||
            normalized.startsWith("vbscript:") ||
            normalized.startsWith("file:") ||
            normalized.startsWith("content:") ||
            normalized.startsWith("intent:")
        ) {
            return true
        }

        // Với data: URI, chỉ cho phép các định dạng ảnh raster an toàn (chặn data:image/svg+xml)
        if (normalized.startsWith("data:")) {
            return SAFE_DATA_IMAGE_PREFIXES.none { prefix ->
                normalized.startsWith("$prefix;") || normalized.startsWith("$prefix,")
            }
        }

        return false
    }
}
