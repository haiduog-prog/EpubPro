package com.epubpro.feature.comic

import com.epubpro.domain.model.ComicReadingMode
import com.epubpro.domain.model.ReadingProgress
import com.epubpro.domain.model.comicLocator
import org.junit.Assert.assertEquals
import org.junit.Test

/** Kiểm thử logic thuần của Reader truyện tranh: vùng chạm, tiến độ, trang mở đầu và chế độ mặc định. */
class ComicReaderLogicTest {

    /** Đọc trái sang phải: chạm trái lùi, phải tiến, giữa bật/tắt thanh điều khiển. */
    @Test
    fun leftToRightTapZones() {
        val mode = ComicReadingMode.LEFT_TO_RIGHT
        assertEquals(ComicTapAction.PREVIOUS, resolveComicTap(10f, 500f, 1000f, 1000f, mode))
        assertEquals(ComicTapAction.NEXT, resolveComicTap(990f, 500f, 1000f, 1000f, mode))
        assertEquals(ComicTapAction.TOGGLE_CONTROLS, resolveComicTap(500f, 500f, 1000f, 1000f, mode))
    }

    /** Manga phải sang trái đảo chiều hai cạnh. */
    @Test
    fun rightToLeftTapZonesAreMirrored() {
        val mode = ComicReadingMode.RIGHT_TO_LEFT
        assertEquals(ComicTapAction.NEXT, resolveComicTap(10f, 500f, 1000f, 1000f, mode))
        assertEquals(ComicTapAction.PREVIOUS, resolveComicTap(990f, 500f, 1000f, 1000f, mode))
    }

    /** Webtoon chia vùng chạm theo chiều dọc. */
    @Test
    fun webtoonTapZonesAreVertical() {
        val mode = ComicReadingMode.WEBTOON
        assertEquals(ComicTapAction.PREVIOUS, resolveComicTap(10f, 10f, 1000f, 1000f, mode))
        assertEquals(ComicTapAction.NEXT, resolveComicTap(10f, 990f, 1000f, 1000f, mode))
        assertEquals(ComicTapAction.TOGGLE_CONTROLS, resolveComicTap(10f, 500f, 1000f, 1000f, mode))
    }

    /** Trang cuối được tính là hoàn thành và chỉ số ngoài phạm vi bị chặn. */
    @Test
    fun progressReachesOneOnLastPage() {
        assertEquals(0.25f, comicProgress(0, 4), 0.0001f)
        assertEquals(1f, comicProgress(3, 4), 0.0001f)
        assertEquals(1f, comicProgress(99, 4), 0.0001f)
        assertEquals(0f, comicProgress(0, 0), 0.0001f)
    }

    /** Ưu tiên locator `cbz:page=`, rơi về chapterIndex và chặn trong số trang hiện có. */
    @Test
    fun initialPageFromSavedProgress() {
        assertEquals(0, resolveInitialComicPage(null, 10))
        assertEquals(7, resolveInitialComicPage(progress(locator = comicLocator(7), chapterIndex = 2), 10))
        assertEquals(2, resolveInitialComicPage(progress(locator = "epubcfi(/6/2)", chapterIndex = 2), 10))
        assertEquals(9, resolveInitialComicPage(progress(locator = comicLocator(50), chapterIndex = 50), 10))
    }

    /** Lựa chọn của người dùng thắng metadata; không có lựa chọn thì theo cờ manga. */
    @Test
    fun readingModeDefaults() {
        assertEquals(ComicReadingMode.RIGHT_TO_LEFT, resolveComicReadingMode(null, metadataRightToLeft = true))
        assertEquals(ComicReadingMode.LEFT_TO_RIGHT, resolveComicReadingMode(null, metadataRightToLeft = false))
        assertEquals(ComicReadingMode.WEBTOON, resolveComicReadingMode(ComicReadingMode.WEBTOON, metadataRightToLeft = true))
    }

    /**
     * Tạo tiến độ giả cho test.
     *
     * @param locator Giá trị currentCfi.
     * @param chapterIndex Chỉ số chương/trang.
     * @return Bản ghi tiến độ.
     */
    private fun progress(locator: String, chapterIndex: Int) = ReadingProgress(
        bookId = "book",
        currentCfi = locator,
        chapterIndex = chapterIndex,
        progressPercentage = 0f
    )
}
