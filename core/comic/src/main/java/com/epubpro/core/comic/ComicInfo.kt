package com.epubpro.core.comic

/**
 * Metadata đọc từ file `ComicInfo.xml` (chuẩn ComicRack) nằm trong archive CBZ.
 *
 * @property title Tên tập/chương.
 * @property series Tên bộ truyện.
 * @property number Số tập/chương trong bộ, giữ nguyên dạng chuỗi (ví dụ `12.5`).
 * @property writer Tác giả kịch bản.
 * @property penciller Họa sĩ.
 * @property isRightToLeft true khi trường `Manga` là `YesAndRightToLeft`.
 */
data class ComicInfo(
    val title: String? = null,
    val series: String? = null,
    val number: String? = null,
    val writer: String? = null,
    val penciller: String? = null,
    val isRightToLeft: Boolean = false
)

/**
 * Bộ đọc `ComicInfo.xml` tối giản.
 *
 * ComicInfo là XML phẳng nên chỉ cần trích vài thẻ đơn; không dùng XML parser đầy đủ để tránh
 * rủi ro XXE/DTD từ file do người dùng nhập và để chạy được trong unit test JVM.
 */
object ComicInfoParser {
    private const val MAX_XML_CHARS = 256 * 1024

    /**
     * Phân tích nội dung `ComicInfo.xml`.
     *
     * @param xml Nội dung XML; phần vượt quá 256 KB bị bỏ qua.
     * @return Metadata đã trích xuất; các trường thiếu hoặc rỗng là null.
     */
    fun parse(xml: String): ComicInfo {
        val content = if (xml.length > MAX_XML_CHARS) xml.substring(0, MAX_XML_CHARS) else xml
        return ComicInfo(
            title = tag(content, "Title"),
            series = tag(content, "Series"),
            number = tag(content, "Number"),
            writer = tag(content, "Writer"),
            penciller = tag(content, "Penciller"),
            isRightToLeft = tag(content, "Manga").equals("YesAndRightToLeft", ignoreCase = true)
        )
    }

    /**
     * Lấy nội dung văn bản của thẻ đơn đầu tiên có tên [name].
     *
     * @param xml Nội dung XML.
     * @param name Tên thẻ (phân biệt hoa thường theo chuẩn ComicInfo).
     * @return Giá trị đã unescape và cắt khoảng trắng, hoặc null nếu không có/rỗng.
     */
    private fun tag(xml: String, name: String): String? {
        val match = Regex("<$name(?:\\s[^>]*)?>([^<]*)</$name\\s*>").find(xml) ?: return null
        return unescape(match.groupValues[1]).trim().takeIf { it.isNotEmpty() }
    }

    /**
     * Giải mã entity XML cơ bản và tham chiếu ký tự số.
     *
     * @param value Chuỗi còn entity.
     * @return Chuỗi đã giải mã; entity không hợp lệ được giữ nguyên.
     */
    private fun unescape(value: String): String {
        if (!value.contains('&')) return value
        return Regex("&(#x[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos);").replace(value) { match ->
            val entity = match.groupValues[1]
            when {
                entity == "amp" -> "&"
                entity == "lt" -> "<"
                entity == "gt" -> ">"
                entity == "quot" -> "\""
                entity == "apos" -> "'"
                entity.startsWith("#x") -> entity.substring(2).toIntOrNull(16)?.toCodePointString() ?: match.value
                else -> entity.substring(1).toIntOrNull()?.toCodePointString() ?: match.value
            }
        }
    }

    /**
     * Chuyển code point sang chuỗi, bỏ qua giá trị ngoài phạm vi Unicode.
     *
     * @receiver Code point cần chuyển.
     * @return Chuỗi ký tự hoặc null nếu code point không hợp lệ.
     */
    private fun Int.toCodePointString(): String? =
        if (Character.isValidCodePoint(this)) String(Character.toChars(this)) else null
}
