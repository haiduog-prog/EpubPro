package com.epubpro.core.reader.tts

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsChunkFailurePolicyTest {

    private val policy = TtsChunkFailurePolicy()

    @Test
    fun `ai error is retried once then read by native for that chunk`() {
        assertEquals(
            TtsChunkFailureDecision.RETRY_SAME_ENGINE,
            policy.onChunkFailed(KEY_1, TtsChunkFailureKind.ERROR, engineIsAi = true)
        )
        assertEquals(
            TtsChunkFailureDecision.NATIVE_FOR_THIS_CHUNK,
            policy.onChunkFailed(KEY_1, TtsChunkFailureKind.ERROR, engineIsAi = true)
        )
    }

    @Test
    fun `ai timeout is not retried`() {
        assertEquals(
            TtsChunkFailureDecision.NATIVE_FOR_THIS_CHUNK,
            policy.onChunkFailed(KEY_1, TtsChunkFailureKind.TIMEOUT, engineIsAi = true)
        )
    }

    @Test
    fun `three consecutive failing ai chunks switch the session to native`() {
        failAiChunk(KEY_1)
        policy.onChunkSucceeded(engineIsAi = false) // giọng hệ thống đọc thay đoạn 1
        failAiChunk(KEY_2)
        policy.onChunkSucceeded(engineIsAi = false)

        assertEquals(
            TtsChunkFailureDecision.RETRY_SAME_ENGINE,
            policy.onChunkFailed(KEY_3, TtsChunkFailureKind.ERROR, engineIsAi = true)
        )
        assertEquals(
            TtsChunkFailureDecision.NATIVE_FOR_SESSION,
            policy.onChunkFailed(KEY_3, TtsChunkFailureKind.ERROR, engineIsAi = true)
        )
    }

    @Test
    fun `successful ai chunk resets the consecutive ai failure count`() {
        failAiChunk(KEY_1)
        failAiChunk(KEY_2)
        policy.onChunkSucceeded(engineIsAi = true)

        assertEquals(
            TtsChunkFailureDecision.NATIVE_FOR_THIS_CHUNK,
            policy.onChunkFailed(KEY_3, TtsChunkFailureKind.TIMEOUT, engineIsAi = true)
        )
    }

    @Test
    fun `native fallback for a chunk gets its own retry`() {
        failAiChunk(KEY_1)

        assertEquals(
            TtsChunkFailureDecision.RETRY_SAME_ENGINE,
            policy.onChunkFailed(KEY_1, TtsChunkFailureKind.ERROR, engineIsAi = false)
        )
        assertEquals(
            TtsChunkFailureDecision.SKIP_CHUNK,
            policy.onChunkFailed(KEY_1, TtsChunkFailureKind.ERROR, engineIsAi = false)
        )
    }

    @Test
    fun `three consecutive failing native chunks stop playback`() {
        assertEquals(
            TtsChunkFailureDecision.SKIP_CHUNK,
            policy.onChunkFailed(KEY_1, TtsChunkFailureKind.TIMEOUT, engineIsAi = false)
        )
        assertEquals(
            TtsChunkFailureDecision.SKIP_CHUNK,
            policy.onChunkFailed(KEY_2, TtsChunkFailureKind.TIMEOUT, engineIsAi = false)
        )
        assertEquals(
            TtsChunkFailureDecision.STOP_WITH_ERROR,
            policy.onChunkFailed(KEY_3, TtsChunkFailureKind.TIMEOUT, engineIsAi = false)
        )
    }

    @Test
    fun `any successful chunk resets the native failure count`() {
        policy.onChunkFailed(KEY_1, TtsChunkFailureKind.TIMEOUT, engineIsAi = false)
        policy.onChunkFailed(KEY_2, TtsChunkFailureKind.TIMEOUT, engineIsAi = false)
        policy.onChunkSucceeded(engineIsAi = false)

        assertEquals(
            TtsChunkFailureDecision.SKIP_CHUNK,
            policy.onChunkFailed(KEY_3, TtsChunkFailureKind.TIMEOUT, engineIsAi = false)
        )
    }

    @Test
    fun `reset clears retry and consecutive counters`() {
        failAiChunk(KEY_1)
        failAiChunk(KEY_2)
        policy.reset()

        assertEquals(
            TtsChunkFailureDecision.RETRY_SAME_ENGINE,
            policy.onChunkFailed(KEY_3, TtsChunkFailureKind.ERROR, engineIsAi = true)
        )
        assertEquals(
            TtsChunkFailureDecision.NATIVE_FOR_THIS_CHUNK,
            policy.onChunkFailed(KEY_3, TtsChunkFailureKind.ERROR, engineIsAi = true)
        )
    }

    @Test
    fun `timeout covers synthesis base plus three times the estimated duration`() {
        assertEquals(45_000L + 30_000L, TtsChunkFailurePolicy.timeoutMs(10_000L, engineIsAi = true))
        assertEquals(20_000L + 30_000L, TtsChunkFailurePolicy.timeoutMs(10_000L, engineIsAi = false))
        assertEquals(20_000L, TtsChunkFailurePolicy.timeoutMs(-5L, engineIsAi = false))
    }

    @Test
    fun `service routes engine errors through the failure policy and cancels the watchdog`() {
        val source = findServiceSource().readText()
        val invalidateBody = source
            .substringAfter("private fun invalidatePlayback()")
            .substringBefore("private fun ensureEstimatedTimeline")
        assertTrue(invalidateBody.contains("chunkWatchdogJob?.cancel()"))

        val playBody = source
            .substringAfter("fun playCurrentChunk()")
            .substringBefore("private fun engineForCurrentChunk()")
        assertTrue(playBody.contains("startChunkWatchdog("))
        assertTrue(playBody.contains("kind = TtsChunkFailureKind.ERROR"))
        assertTrue(playBody.contains("chunkFailurePolicy.onChunkSucceeded(engineIsAi)"))
    }

    /**
     * Cho một đoạn AI lỗi qua hết lượt thử lại để tăng bộ đếm lỗi AI liên tiếp.
     *
     * @param key Khoá của đoạn lỗi.
     */
    private fun failAiChunk(key: Any) {
        policy.onChunkFailed(key, TtsChunkFailureKind.ERROR, engineIsAi = true)
        policy.onChunkFailed(key, TtsChunkFailureKind.ERROR, engineIsAi = true)
    }

    /**
     * Tìm file nguồn `TtsService.kt` dù test chạy từ thư mục module hay thư mục gốc repo.
     *
     * @return File nguồn của `TtsService`.
     */
    private fun findServiceSource(): File {
        val relative = "src/main/java/com/epubpro/core/reader/tts/TtsService.kt"
        return listOf(File(relative), File("core/playback/$relative")).first { it.exists() }
    }

    private companion object {
        val KEY_1 = 0 to 1
        val KEY_2 = 0 to 2
        val KEY_3 = 0 to 3
    }
}
