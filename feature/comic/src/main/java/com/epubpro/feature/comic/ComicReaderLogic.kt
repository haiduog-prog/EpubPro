package com.epubpro.feature.comic

import com.epubpro.domain.model.ComicReadingMode
import com.epubpro.domain.model.ReadingProgress
import com.epubpro.domain.model.parseComicLocator

/** Hành động ứng với một lần chạm trên trang truyện. */
internal enum class ComicTapAction {
    PREVIOUS,
    NEXT,
    TOGGLE_CONTROLS
}

/** Tỉ lệ chiều rộng/chiều cao của mỗi vùng chạm ở hai cạnh màn hình. */
private const val EDGE_TAP_ZONE_FRACTION = 0.3f

/**
 * Xác định hành động khi người dùng chạm vào trang.
 *
 * Chế độ lật trang chia theo chiều ngang (trái/giữa/phải) và đảo chiều khi đọc phải sang trái;
 * chế độ webtoon chia theo chiều dọc (trên lùi, dưới tiến).
 *
 * @param x Tọa độ ngang của điểm chạm.
 * @param y Tọa độ dọc của điểm chạm.
 * @param width Chiều rộng vùng nội dung.
 * @param height Chiều cao vùng nội dung.
 * @param mode Chế độ đọc hiện tại.
 * @return Hành động cần thực hiện.
 */
internal fun resolveComicTap(
    x: Float,
    y: Float,
    width: Float,
    height: Float,
    mode: ComicReadingMode
): ComicTapAction {
    if (width <= 0f || height <= 0f) return ComicTapAction.TOGGLE_CONTROLS
    if (mode == ComicReadingMode.WEBTOON) {
        return when {
            y < height * EDGE_TAP_ZONE_FRACTION -> ComicTapAction.PREVIOUS
            y > height * (1f - EDGE_TAP_ZONE_FRACTION) -> ComicTapAction.NEXT
            else -> ComicTapAction.TOGGLE_CONTROLS
        }
    }
    val leftAction = if (mode == ComicReadingMode.RIGHT_TO_LEFT) ComicTapAction.NEXT else ComicTapAction.PREVIOUS
    val rightAction = if (mode == ComicReadingMode.RIGHT_TO_LEFT) ComicTapAction.PREVIOUS else ComicTapAction.NEXT
    return when {
        x < width * EDGE_TAP_ZONE_FRACTION -> leftAction
        x > width * (1f - EDGE_TAP_ZONE_FRACTION) -> rightAction
        else -> ComicTapAction.TOGGLE_CONTROLS
    }
}

/**
 * Tính tỉ lệ hoàn thành; đứng ở trang cuối được tính là đọc xong.
 *
 * @param pageIndex Trang hiện tại 0-based.
 * @param pageCount Tổng số trang.
 * @return Giá trị trong khoảng 0..1.
 */
internal fun comicProgress(pageIndex: Int, pageCount: Int): Float {
    if (pageCount <= 0) return 0f
    return ((pageIndex.coerceIn(0, pageCount - 1) + 1).toFloat() / pageCount).coerceIn(0f, 1f)
}

/**
 * Xác định trang mở đầu từ tiến độ đã lưu.
 *
 * Ưu tiên locator `cbz:page=`; nếu không có thì dùng `chapterIndex` (truyện tranh lưu trang như chương
 * để bản ghi đồng bộ và Library hiển thị đúng).
 *
 * @param progress Tiến độ đã lưu, có thể null.
 * @param pageCount Tổng số trang hiện có.
 * @return Chỉ số trang hợp lệ 0-based.
 */
internal fun resolveInitialComicPage(progress: ReadingProgress?, pageCount: Int): Int {
    if (pageCount <= 0 || progress == null) return 0
    val page = parseComicLocator(progress.currentCfi) ?: progress.chapterIndex
    return page.coerceIn(0, pageCount - 1)
}

/**
 * Chọn chế độ đọc khi mở truyện.
 *
 * @param saved Chế độ người dùng đã chọn cho cuốn này.
 * @param metadataRightToLeft true khi `ComicInfo.xml` khai báo manga đọc phải sang trái.
 * @return Chế độ áp dụng.
 */
internal fun resolveComicReadingMode(saved: ComicReadingMode?, metadataRightToLeft: Boolean): ComicReadingMode =
    saved ?: if (metadataRightToLeft) ComicReadingMode.RIGHT_TO_LEFT else ComicReadingMode.LEFT_TO_RIGHT
