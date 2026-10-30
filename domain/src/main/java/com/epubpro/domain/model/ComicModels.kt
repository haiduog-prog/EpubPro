package com.epubpro.domain.model

/**
 * Chế độ hiển thị trang của Reader truyện tranh.
 *
 * Nhãn hiển thị nằm trong string resource ở tầng UI, không đặt trong enum domain.
 */
enum class ComicReadingMode {
    /** Lật từng trang, trang sau nằm bên phải (truyện tranh phương Tây, manhua). */
    LEFT_TO_RIGHT,

    /** Lật từng trang, trang sau nằm bên trái (manga Nhật). */
    RIGHT_TO_LEFT,

    /** Cuộn dọc liên tục, các trang nối nhau (webtoon). */
    WEBTOON
}

/** Tiền tố locator lưu vị trí trang truyện tranh trong [ReadingProgress.currentCfi]. */
const val COMIC_LOCATOR_PREFIX = "cbz:page="

/**
 * Tạo locator cho một trang truyện tranh.
 *
 * @param pageIndex Chỉ số trang 0-based.
 * @return Chuỗi locator dạng `cbz:page=<n>`.
 */
fun comicLocator(pageIndex: Int): String = "$COMIC_LOCATOR_PREFIX$pageIndex"

/**
 * Đọc chỉ số trang từ locator truyện tranh.
 *
 * @param locator Giá trị `currentCfi` đã lưu.
 * @return Chỉ số trang 0-based, hoặc null nếu locator không thuộc truyện tranh.
 */
fun parseComicLocator(locator: String?): Int? =
    locator?.takeIf { it.startsWith(COMIC_LOCATOR_PREFIX) }
        ?.removePrefix(COMIC_LOCATOR_PREFIX)
        ?.toIntOrNull()
        ?.takeIf { it >= 0 }

/** Ngôn ngữ chữ trên trang truyện tranh cần nhận diện (OCR). */
enum class ComicSourceLanguage {
    CHINESE,
    JAPANESE,
    KOREAN,
    ENGLISH
}

/** Cách dịch chữ đã nhận diện sang tiếng Việt. */
enum class ComicTranslationEngine {
    /** ML Kit Translate chạy trên máy (cần tải gói ngôn ngữ một lần). */
    ON_DEVICE,

    /** Convert Hán-Việt bằng từ điển người dùng đã nhập; chỉ áp dụng cho tiếng Trung. */
    HAN_VIET
}

/**
 * Một bóng thoại đã nhận diện và dịch. Tọa độ chuẩn hóa 0..1 theo kích thước trang để hiển thị
 * đúng vị trí ở mọi mức zoom và chế độ đọc.
 *
 * @property original Văn bản gốc nhận diện được.
 * @property translated Bản dịch tiếng Việt.
 * @property left Cạnh trái.
 * @property top Cạnh trên.
 * @property right Cạnh phải.
 * @property bottom Cạnh dưới.
 */
data class TranslatedBubble(
    val original: String,
    val translated: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)
