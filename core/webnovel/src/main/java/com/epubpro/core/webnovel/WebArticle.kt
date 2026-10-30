package com.epubpro.core.webnovel

/**
 * Nội dung chương/bài viết đã bóc tách từ một trang web.
 *
 * @property url URL trang nguồn.
 * @property title Tiêu đề chương (hoặc bài viết).
 * @property novelTitle Tên truyện đoán được từ trang, null nếu không xác định.
 * @property paragraphs Các đoạn văn bản thuần theo thứ tự đọc.
 * @property links Liên kết chương trước/sau/mục lục phát hiện được.
 * @property author Tác giả truyện từ metadata chuyên biệt, null nếu không rõ.
 */
data class WebArticle(
    val url: String,
    val title: String,
    val novelTitle: String?,
    val paragraphs: List<String>,
    val links: ChapterLinks,
    val author: String? = null
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
 * @property sourceUrl URL trang nguồn của chương, null với EPUB cũ chưa lưu thông tin này.
 */
data class WebNovelChapter(
    val title: String,
    val paragraphs: List<String>,
    val sourceUrl: String? = null
)

/**
 * Kiểm tra nội dung chương lấy từ trang đang mở trước khi lưu vào EPUB.
 *
 * @param article Nội dung đã bóc tách từ trang hiện tại.
 * @return true khi nội dung đủ dài và có văn bản đọc được, không phải chuỗi ký hiệu tượng hình.
 */
fun isReadableWebChapter(article: WebArticle): Boolean {
    val text = article.paragraphs.joinToString(" ")
    if (text.length < ArticleExtractor.MIN_CONTENT_CHARS) return false
    val points = text.codePoints().toArray()
    val readable = points.count { Character.isLetterOrDigit(it) && it !in 0x13000..0x1345F }
    return readable >= ArticleExtractor.MIN_CONTENT_CHARS / 2 && readable * 4 >= points.size
}
