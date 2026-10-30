package com.epubpro.core.webnovel

/**
 * Nội dung chương/bài viết đã bóc tách từ một trang web.
 *
 * @property url URL trang nguồn.
 * @property title Tiêu đề chương (hoặc bài viết).
 * @property novelTitle Tên truyện đoán được từ trang, null nếu không xác định.
 * @property paragraphs Các đoạn văn bản thuần theo thứ tự đọc.
 * @property links Liên kết chương trước/sau/mục lục phát hiện được.
 */
data class WebArticle(
    val url: String,
    val title: String,
    val novelTitle: String?,
    val paragraphs: List<String>,
    val links: ChapterLinks
) {
    /** Tổng số ký tự nội dung, dùng để đánh giá chất lượng bóc tách. */
    val textLength: Int get() = paragraphs.sumOf { it.length }
}

/**
 * Liên kết điều hướng chương trên trang truyện.
 *
 * @property next URL chương sau.
 * @property previous URL chương trước.
 * @property tableOfContents URL trang mục lục.
 */
data class ChapterLinks(
    val next: String? = null,
    val previous: String? = null,
    val tableOfContents: String? = null
)

/**
 * Một chương đã tải để đóng gói EPUB.
 *
 * @property title Tiêu đề chương.
 * @property paragraphs Các đoạn văn bản thuần.
 */
data class WebNovelChapter(
    val title: String,
    val paragraphs: List<String>
)
