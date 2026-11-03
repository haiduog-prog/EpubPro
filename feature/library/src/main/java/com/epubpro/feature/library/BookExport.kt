package com.epubpro.feature.library

/** Số ký tự tối đa của tên file gợi ý khi xuất, tránh vượt giới hạn tên file của hệ thống. */
private const val MAX_EXPORT_NAME_CHARS = 100

/** Ký tự không hợp lệ trong tên file trên các hệ thống lưu trữ phổ biến. */
private val INVALID_FILE_NAME_CHARS = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")

/**
 * Định dạng file sách có thể xuất ra ngoài bộ nhớ riêng của app.
 *
 * @property extension Phần mở rộng file (không có dấu chấm).
 * @property mimeType MIME type truyền cho trình chọn nơi lưu của hệ thống.
 */
enum class BookExportFormat(val extension: String, val mimeType: String) {
    EPUB("epub", "application/epub+zip"),
    CBZ("cbz", "application/vnd.comicbook+zip")
}

/**
 * Xác định định dạng xuất của sách dựa trên file đang lưu trong app.
 *
 * @param filePath Đường dẫn file sách trong database.
 * @return Định dạng xuất, hoặc null nếu sách không có file cục bộ (ví dụ truyện tranh online lưu bằng URL).
 */
fun bookExportFormat(filePath: String): BookExportFormat? {
    if (filePath.contains("://")) return null
    val extension = filePath.substringAfterLast('.', "").lowercase()
    return BookExportFormat.entries.firstOrNull { it.extension == extension }
}

/**
 * Tạo tên file gợi ý khi xuất sách: lấy theo tên sách, bỏ ký tự không hợp lệ và giới hạn độ dài.
 *
 * @param title Tên sách.
 * @param format Định dạng xuất.
 * @return Tên file kèm phần mở rộng.
 */
fun bookExportFileName(title: String, format: BookExportFormat): String {
    val cleaned = title.replace(INVALID_FILE_NAME_CHARS, " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(MAX_EXPORT_NAME_CHARS)
        .trim()
        .trim('.')
    return "${cleaned.ifBlank { "book" }}.${format.extension}"
}
