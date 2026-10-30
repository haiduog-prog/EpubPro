package com.epubpro.core.comic

/**
 * Thông tin một chương truyện tranh đã phân tích từ HTML trang nguồn.
 *
 * @property sourceHost Host của nguồn (VD: "nettruyen.gg"), dùng làm namespace lưu tiến độ.
 * @property chapterUrl URL tuyệt đối của chương hiện tại.
 * @property title Tên truyện và chương kết hợp để hiển thị trên Reader.
 * @property pages Danh sách URL ảnh theo thứ tự DOM, đã lọc trùng và lọc ảnh giao diện.
 * @property pagesByServer URL ảnh theo từng server, giữ cùng thứ tự với [pages].
 * @property previousChapterUrl URL chương trước, null nếu không có hoặc phát hiện vòng lặp.
 * @property nextChapterUrl URL chương sau, null nếu không có hoặc phát hiện vòng lặp.
 * @property availableServers Danh sách server ảnh người dùng có thể chọn; rỗng nếu trang không cung cấp.
 * @property comicTitle Tên gốc của bộ truyện (bỏ phần chương), dùng hiển thị trên Kệ sách.
 * @property coverUrl URL ảnh bìa hoặc ảnh đại diện của bộ truyện.
 */
data class ComicChapterInfo(
    val sourceHost: String,
    val chapterUrl: String,
    val title: String,
    val pages: List<String>,
    val previousChapterUrl: String?,
    val nextChapterUrl: String?,
    val availableServers: List<ComicImageServer>,
    val comicTitle: String = title,
    val coverUrl: String? = null,
    val pagesByServer: Map<String, List<String>> = emptyMap()
)

/**
 * Một server ảnh của trang nguồn truyện tranh.
 *
 * @property id Định danh nội bộ (từ data attribute hoặc thứ tự xuất hiện).
 * @property label Tên hiển thị cho người dùng (VD: "Server 1", "CDN").
 */
data class ComicImageServer(val id: String, val label: String)

/**
 * Lỗi khi phân tích chương không tìm được ảnh hoặc cấu trúc HTML không nhận ra.
 *
 * @param message Mô tả lỗi kỹ thuật.
 */
class ComicChapterParseException(message: String) : Exception(message)
