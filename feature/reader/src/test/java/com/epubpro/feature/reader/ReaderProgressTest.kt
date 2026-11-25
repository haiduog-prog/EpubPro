package com.epubpro.feature.reader

import com.epubpro.core.reader.engine.EpubChapterHeader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReaderProgressTest {

    /**
     * Kiểm tra chapter override hợp lệ không phụ thuộc cờ mở trình phát TTS.
     */
    @Test
    fun validChapterOverrideIsAcceptedWithoutPlayerFlag() {
        assertEquals(3, resolveRequestedChapterIndex(3))
        assertNull(resolveRequestedChapterIndex(-1))
        assertNull(resolveRequestedChapterIndex(null))
    }

    /**
     * Kiểm tra trang cuối của chapter cuối đạt đúng 100 phần trăm.
     */
    @Test
    fun lastPageOfLastChapterReachesFullProgress() {
        val state = ReaderUiState(
            chapters = List(2) { index -> EpubChapterHeader(index, "Chapter", "chapter.xhtml") },
            currentChapterIndex = 1,
            currentPageInChapter = 10,
            totalPagesInChapter = 10
        )

        assertEquals(1f, calculateReaderProgress(state), 0.001f)
    }

    /**
     * Kiểm tra sách chỉ có một trang được xem là hoàn tất khi mở tới trang đó.
     */
    @Test
    fun singlePageBookReachesFullProgress() {
        val state = ReaderUiState(
            chapters = listOf(EpubChapterHeader(0, "Chapter", "chapter.xhtml")),
            currentPageInChapter = 1,
            totalPagesInChapter = 1
        )

        assertEquals(1f, calculateReaderProgress(state), 0.001f)
    }

    /**
     * Kiểm tra state rỗng không tạo ra tiến độ ảo 100 phần trăm.
     */
    @Test
    fun emptyReaderStateHasZeroProgress() {
        assertEquals(0f, calculateReaderProgress(ReaderUiState()), 0.001f)
    }

    /**
     * Kiểm tra chương cuối chưa đo số trang (vừa commit trong DOM) không bị coi là đã đọc xong cả sách.
     */
    @Test
    fun unknownTotalPagesOnLastChapterCountsOnlyToChapterStart() {
        val state = ReaderUiState(
            chapters = List(4) { index -> EpubChapterHeader(index, "Chapter", "chapter.xhtml") },
            currentChapterIndex = 3,
            currentPageInChapter = 1,
            totalPagesInChapter = UNKNOWN_TOTAL_PAGES
        )

        assertEquals(0.75f, calculateReaderProgress(state), 0.001f)
    }

    /**
     * Kiểm tra chapter một trang ở đầu sách chưa được tính là đã hoàn tất.
     */
    @Test
    fun firstSinglePageChapterHasZeroProgress() {
        val state = ReaderUiState(
            chapters = List(10) { index -> EpubChapterHeader(index, "Chapter", "chapter.xhtml") },
            currentChapterIndex = 0,
            currentPageInChapter = 1,
            totalPagesInChapter = 1
        )

        assertEquals(0f, calculateReaderProgress(state), 0.001f)
    }

    /**
     * Kiểm tra không ghi tiến độ định kỳ khi chương ngang chưa đo xong số trang, tránh ghi sai trang 1
     * lúc vừa lùi về trang cuối chương trước; riêng khi thoát màn đọc thì chỉ cho lưu nếu mở từ trang đầu.
     */
    @Test
    fun persistenceGuardSkipsUnmeasuredHorizontalPagesExceptFirstPageFlush() {
        val chapters = List(4) { index -> EpubChapterHeader(index, "Chapter", "chapter.xhtml") }
        val baseUnmeasured = ReaderUiState(
            chapters = chapters,
            currentChapterIndex = 2,
            currentPageInChapter = 1,
            initialPageRequest = 1,
            totalPagesInChapter = UNKNOWN_TOTAL_PAGES,
            isLoading = false
        )

        assertEquals(false, shouldPersistReadingProgress(baseUnmeasured, isFlush = false))
        assertEquals(true, shouldPersistReadingProgress(baseUnmeasured, isFlush = true))

        val backwardUnmeasured = baseUnmeasured.copy(initialPageRequest = Int.MAX_VALUE)
        assertEquals(false, shouldPersistReadingProgress(backwardUnmeasured, isFlush = false))
        assertEquals(false, shouldPersistReadingProgress(backwardUnmeasured, isFlush = true))

        val measured = backwardUnmeasured.copy(currentPageInChapter = 11, totalPagesInChapter = 11)
        assertEquals(true, shouldPersistReadingProgress(measured, isFlush = false))
        assertEquals(true, shouldPersistReadingProgress(measured, isFlush = true))
    }
}
