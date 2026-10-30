package com.epubpro.feature.browser

import java.net.URLEncoder

private val urlLike = Regex("^[\\p{L}0-9-]+(\\.[\\p{L}0-9-]+)+(:\\d+)?(/.*)?$")
private const val SEARCH_URL = "https://www.google.com/search?q="

/**
 * Chuyển nội dung ô địa chỉ thành URL cần mở.
 *
 * Chuỗi đã có scheme http/https được giữ nguyên; chuỗi giống tên miền được thêm `https://`; còn lại coi là
 * từ khóa tìm kiếm. Scheme khác (`file:`, `javascript:`, `intent:`...) bị chuyển thành tìm kiếm để không mở được.
 *
 * @param input Nội dung người dùng nhập.
 * @return URL http(s) để nạp, hoặc null nếu input rỗng.
 */
internal fun resolveBrowserAddress(input: String): String? {
    val text = input.trim()
    if (text.isEmpty()) return null
    val lower = text.lowercase()
    if (lower.startsWith("http://") || lower.startsWith("https://")) return text
    if (!text.contains(' ') && !text.contains(':') && urlLike.matches(text)) return "https://$text"
    if (!text.contains(' ') && Regex("^[\\p{L}0-9.-]+:\\d+(/.*)?$").matches(text)) return "https://$text"
    return SEARCH_URL + URLEncoder.encode(text, "UTF-8")
}

/**
 * Kiểm tra URL có được phép nạp trong trình duyệt tích hợp không (chỉ http/https).
 *
 * @param url URL đích.
 * @return true nếu scheme là http hoặc https.
 */
internal fun isAllowedBrowserUrl(url: String?): Boolean {
    val lower = url?.trim()?.lowercase() ?: return false
    return lower.startsWith("http://") || lower.startsWith("https://")
}
