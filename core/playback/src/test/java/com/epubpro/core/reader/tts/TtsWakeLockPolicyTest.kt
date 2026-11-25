package com.epubpro.core.reader.tts

import com.epubpro.domain.model.TtsChunk
import com.epubpro.domain.model.TtsPlayerState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsWakeLockPolicyTest {
    private val chunk = TtsChunk(id = 1, paragraphIndex = 0, text = "Xin chào")

    @Test
    fun `holds wake lock while playing preparing or loading chapter`() {
        assertTrue(TtsWakeLockPolicy.shouldHold(playing()))
        assertTrue(TtsWakeLockPolicy.shouldHold(preparing()))
        assertTrue(TtsWakeLockPolicy.shouldHold(TtsPlayerState.Loading))
    }

    @Test
    fun `releases wake lock when not reading`() {
        assertFalse(TtsWakeLockPolicy.shouldHold(TtsPlayerState.Idle))
        assertFalse(TtsWakeLockPolicy.shouldHold(paused()))
        assertFalse(TtsWakeLockPolicy.shouldHold(TtsPlayerState.Completed(bookId = "b", chapterIndex = 1)))
        assertFalse(TtsWakeLockPolicy.shouldHold(TtsPlayerState.Error("lỗi")))
    }

    @Test
    fun `acquires when not held and refreshes only after interval`() {
        assertTrue(TtsWakeLockPolicy.shouldAcquire(isHeld = false, lastAcquireAtMs = 1_000L, nowMs = 1_001L))
        assertFalse(TtsWakeLockPolicy.shouldAcquire(isHeld = true, lastAcquireAtMs = 1_000L, nowMs = 1_000L + 59_999L))
        assertTrue(
            TtsWakeLockPolicy.shouldAcquire(
                isHeld = true,
                lastAcquireAtMs = 1_000L,
                nowMs = 1_000L + TtsWakeLockPolicy.REFRESH_INTERVAL_MS
            )
        )
    }

    @Test
    fun `refresh interval is shorter than hold timeout`() {
        assertTrue(TtsWakeLockPolicy.REFRESH_INTERVAL_MS < TtsWakeLockPolicy.HOLD_TIMEOUT_MS)
    }

    private fun playing() = TtsPlayerState.Playing(currentChunkIndex = 0, totalChunks = 1, currentChunk = chunk)
    private fun preparing() = TtsPlayerState.Preparing(currentChunkIndex = 0, totalChunks = 1, currentChunk = chunk)
    private fun paused() = TtsPlayerState.Paused(currentChunkIndex = 0, totalChunks = 1, currentChunk = chunk)
}
