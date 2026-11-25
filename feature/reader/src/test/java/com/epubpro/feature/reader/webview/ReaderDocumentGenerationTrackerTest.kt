package com.epubpro.feature.reader.webview

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderDocumentGenerationTrackerTest {

    @Test
    fun staleDocumentCannotCompletePendingTransition() {
        val tracker = ReaderDocumentGenerationTracker()
        val firstLoad = tracker.nextLoadGeneration()
        val destination = tracker.beginChapterTransition()

        assertFalse(tracker.completeIfExpected(firstLoad))
        assertTrue(tracker.completeIfExpected(destination))
    }

    @Test
    fun timeoutOnlyCancelsMatchingTransition() {
        val tracker = ReaderDocumentGenerationTracker()
        tracker.nextLoadGeneration()
        val destination = tracker.beginChapterTransition()

        assertFalse(tracker.cancelIfExpected(destination + 1))
        assertTrue(tracker.cancelIfExpected(destination))
        assertFalse(tracker.completeIfExpected(destination))
    }

    /**
     * Kiểm tra khi bấm chuyển chương liên tục dưới màn che, đích chờ dời sang document mới nhất:
     * document ở giữa không gỡ được màn che, chỉ document cuối cùng mới gỡ.
     */
    @Test
    fun newerLoadDuringTransitionRetargetsPendingDestination() {
        val tracker = ReaderDocumentGenerationTracker()
        tracker.nextLoadGeneration()
        tracker.beginChapterTransition()
        val firstDestination = tracker.nextLoadGeneration()
        val coalescedDestination = tracker.nextLoadGeneration()

        assertFalse(tracker.isPendingDestination(firstDestination))
        assertFalse(tracker.completeIfExpected(firstDestination))
        assertTrue(tracker.isPendingDestination(coalescedDestination))
        assertTrue(tracker.completeIfExpected(coalescedDestination))
    }

    /**
     * Kiểm tra gộp yêu cầu khi document đích đã nạp và đang layout: document đó không gỡ được màn che,
     * phải chờ document của lần nạp kế tiếp.
     */
    @Test
    fun coalescedRequestMovesPendingPastAlreadyLoadedDestination() {
        val tracker = ReaderDocumentGenerationTracker()
        tracker.beginChapterTransition()
        val loadedDestination = tracker.nextLoadGeneration()

        assertTrue(tracker.extendPendingToNextLoad())
        assertFalse(tracker.isPendingDestination(loadedDestination))
        assertFalse(tracker.completeIfExpected(loadedDestination))

        val coalescedDestination = tracker.nextLoadGeneration()
        assertTrue(tracker.completeIfExpected(coalescedDestination))
    }

    /**
     * Kiểm tra gộp yêu cầu trước khi document đích kịp nạp không đẩy đích chờ vượt quá lần nạp kế tiếp.
     */
    @Test
    fun coalescedRequestBeforeDestinationLoadKeepsNextLoadAsDestination() {
        val tracker = ReaderDocumentGenerationTracker()
        val destination = tracker.beginChapterTransition()

        assertTrue(tracker.extendPendingToNextLoad())
        assertTrue(tracker.isPendingDestination(destination))
        assertFalse(ReaderDocumentGenerationTracker().extendPendingToNextLoad())
    }

    /**
     * Kiểm tra load không liên quan khi không có chuyển chương đang chờ không tạo ra đích chờ mới.
     */
    @Test
    fun loadWithoutPendingTransitionDoesNotCreatePendingDestination() {
        val tracker = ReaderDocumentGenerationTracker()
        val generation = tracker.nextLoadGeneration()

        assertFalse(tracker.isPendingDestination(generation))
        assertFalse(tracker.cancelPending())
    }

    /**
     * Kiểm tra hết thời gian chờ vẫn hủy được lần chuyển chương dù đích đã bị dời.
     */
    @Test
    fun timeoutCancelsRetargetedTransition() {
        val tracker = ReaderDocumentGenerationTracker()
        tracker.beginChapterTransition()
        tracker.nextLoadGeneration()
        val latest = tracker.nextLoadGeneration()

        assertTrue(tracker.cancelPending())
        assertFalse(tracker.completeIfExpected(latest))
    }

    @Test
    fun everyLoadedDocumentGetsMonotonicGeneration() {
        val tracker = ReaderDocumentGenerationTracker()

        assertTrue(tracker.nextLoadGeneration() < tracker.nextLoadGeneration())
    }
}
