package com.epubpro.feature.reader

import com.epubpro.core.reader.engine.EpubChapterHeader
import com.epubpro.core.reader.filter.EpubHtmlSanitizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Kiểm tra chuyển state khi chương kề được commit ngay trong DOM của WebView (không reload).
 */
class ReaderAdjacentChapterCommitTest {

    private val chapters = List(5) { index -> EpubChapterHeader(index, "Chapter $index", "c$index.xhtml") }
    private val originalHtml = "<p>original 2</p>"
    private val sanitizedOriginal = EpubHtmlSanitizer.sanitize(originalHtml)

    private fun stateAtChapter2() = ReaderUiState(
        chapters = chapters,
        currentChapterIndex = 2,
        currentChapterHtml = originalHtml,
        sanitizedCurrentChapterHtml = sanitizedOriginal,
        previousChapterHtml = "<p>1</p>",
        nextChapterHtml = "<p>3</p>",
        currentPageInChapter = 11,
        initialPageRequest = 11,
        totalPagesInChapter = 11,
        firstVisibleParagraphIndex = 40,
        currentCfi = "epubpro:paragraph:40",
        isLoading = false
    )

    /**
     * Kiểm tra commit tiến cập nhật chương ngay, đánh dấu số trang chưa đo và xoay chương vừa rời
     * thành preview chương trước.
     */
    @Test
    fun forwardCommitUpdatesIndexImmediatelyAndRotatesLeavingChapterToPrevious() {
        val committed = commitAdjacentChapterState(stateAtChapter2(), direction = 1, leavingHtmlIsCurrent = true)

        assertNotNull(committed)
        committed!!
        assertEquals(3, committed.currentChapterIndex)
        assertEquals(UNKNOWN_TOTAL_PAGES, committed.totalPagesInChapter)
        assertEquals(1, committed.initialPageRequest)
        assertEquals(originalHtml, committed.previousChapterHtml)
        assertEquals(sanitizedOriginal, committed.sanitizedPreviousChapterHtml)
        assertNull(committed.nextChapterHtml)
        assertNull(committed.sanitizedNextChapterHtml)
        assertEquals("", committed.currentCfi)
    }

    /**
     * Kiểm tra commit lùi xoay chương vừa rời thành preview chương sau và yêu cầu trang cuối khi phải reload.
     */
    @Test
    fun backwardCommitRotatesLeavingChapterToNextAndRequestsLastPage() {
        val committed = commitAdjacentChapterState(stateAtChapter2(), direction = -1, leavingHtmlIsCurrent = true)!!

        assertEquals(1, committed.currentChapterIndex)
        assertEquals(Int.MAX_VALUE, committed.initialPageRequest)
        assertEquals(originalHtml, committed.nextChapterHtml)
        assertEquals(sanitizedOriginal, committed.sanitizedNextChapterHtml)
        assertNull(committed.previousChapterHtml)
    }

    /**
     * Kiểm tra chương đang xem bản AI vẫn được xoay bằng bản gốc, khớp với bản sẽ hiển thị khi nạp lại.
     */
    @Test
    fun aiVersionIsNotUsedAsRotatedPreview() {
        val state = stateAtChapter2().copy(
            aiChapterHtml = "<p>ai</p>",
            contentVersion = ReaderContentVersion.AI
        )

        val committed = commitAdjacentChapterState(state, direction = 1, leavingHtmlIsCurrent = true)!!

        assertEquals(originalHtml, committed.previousChapterHtml)
    }

    /**
     * Kiểm tra chương đang bật Convert được xoay bằng bản Convert và HTML sanitize tương ứng.
     */
    @Test
    fun convertedVersionIsUsedAsRotatedPreviewWhenEnabled() {
        val convertedSanitized = EpubHtmlSanitizer.sanitize("<p>converted</p>")
        val state = stateAtChapter2().copy(
            convertEnabled = true,
            convertedChapterHtml = "<p>converted</p>",
            sanitizedConvertedChapterHtml = convertedSanitized
        )

        val committed = commitAdjacentChapterState(state, direction = 1, leavingHtmlIsCurrent = true)!!

        assertEquals("<p>converted</p>", committed.previousChapterHtml)
        assertEquals(convertedSanitized, committed.sanitizedPreviousChapterHtml)
    }

    /**
     * Kiểm tra hai lần commit liên tiếp khi HTML chương trước chưa nạp xong: chỉ số chương tăng đúng hai bước
     * và không xoay HTML cũ (của chương khác) thành preview.
     */
    @Test
    fun consecutiveCommitsAdvanceTwiceWithoutRotatingStaleHtml() {
        val first = commitAdjacentChapterState(stateAtChapter2(), direction = 1, leavingHtmlIsCurrent = true)!!
        val second = commitAdjacentChapterState(first, direction = 1, leavingHtmlIsCurrent = false)!!

        assertEquals(4, second.currentChapterIndex)
        assertNull(second.previousChapterHtml)
        assertNull(second.sanitizedPreviousChapterHtml)
    }

    /**
     * Kiểm tra không commit khi hướng bằng 0 hoặc chương đích nằm ngoài danh sách.
     */
    @Test
    fun commitIsRejectedOutsideChapterBounds() {
        val atFirst = stateAtChapter2().copy(currentChapterIndex = 0)
        val atLast = stateAtChapter2().copy(currentChapterIndex = chapters.lastIndex)

        assertNull(commitAdjacentChapterState(atFirst, direction = -1, leavingHtmlIsCurrent = true))
        assertNull(commitAdjacentChapterState(atLast, direction = 1, leavingHtmlIsCurrent = true))
        assertNull(commitAdjacentChapterState(stateAtChapter2(), direction = 0, leavingHtmlIsCurrent = true))
    }

    /**
     * Kiểm tra HTML nạp nền xong không ghi đè vị trí trang JS đã báo và preview đã xoay vòng.
     */
    @Test
    fun backgroundLoadKeepsPositionReportedByJsAndRotatedPreviews() {
        val latest = commitAdjacentChapterState(stateAtChapter2(), direction = -1, leavingHtmlIsCurrent = true)!!
            .copy(
                currentPageInChapter = 11,
                initialPageRequest = 11,
                totalPagesInChapter = 11,
                firstVisibleParagraphIndex = 52,
                currentCfi = "epubpro:paragraph:52"
            )
        val loaded = latest.copy(
            currentChapterHtml = "<p>chapter 1</p>",
            previousChapterHtml = null,
            nextChapterHtml = null,
            sanitizedPreviousChapterHtml = null,
            sanitizedNextChapterHtml = null,
            currentPageInChapter = 1,
            initialPageRequest = Int.MAX_VALUE,
            totalPagesInChapter = 1,
            firstVisibleParagraphIndex = 0
        )

        val merged = keepCommittedChapterPosition(loaded, latest)

        assertEquals("<p>chapter 1</p>", merged.currentChapterHtml)
        assertEquals(11, merged.currentPageInChapter)
        assertEquals(11, merged.initialPageRequest)
        assertEquals(11, merged.totalPagesInChapter)
        assertEquals(52, merged.firstVisibleParagraphIndex)
        assertEquals("epubpro:paragraph:52", merged.currentCfi)
        assertEquals(originalHtml, merged.nextChapterHtml)
        assertEquals(sanitizedOriginal, merged.sanitizedNextChapterHtml)
    }
}
