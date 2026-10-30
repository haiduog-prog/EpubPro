package com.epubpro.app.intent

/**
 * Trích liên kết web từ văn bản được chia sẻ (ví dụ "Tên chương https://site/chuong-1").
 */
object SharedLinkParser {
    private val urlPattern = Regex("https?://[^\\s\"'<>]+", RegexOption.IGNORE_CASE)
    private const val MAX_URL_LENGTH = 2048

    /**
     * Lấy URL http(s) đầu tiên trong văn bản chia sẻ.
     *
     * @param text Nội dung `EXTRA_TEXT`, có thể null.
     * @return URL đã bỏ dấu câu cuối, hoặc null nếu không có liên kết hợp lệ.
     */
    fun extractUrl(text: String?): String? {
        val match = text?.let { urlPattern.find(it) }?.value ?: return null
        return match.trimEnd('.', ',', ')', ']', '!', '?', ';', '”', '’')
            .takeIf { it.length in 10..MAX_URL_LENGTH }
    }
}
