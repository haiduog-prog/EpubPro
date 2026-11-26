package com.epubpro.core.reader.tts

import android.content.ContextWrapper
import com.epubpro.core.designsystem.R
import com.epubpro.core.tts.SherpaTtsEngine
import com.epubpro.core.tts.VoiceModelDownloader
import com.epubpro.domain.model.TtsChunk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PiperTtsEngineWrapperTest {

    private lateinit var sherpaTtsEngine: FakeSherpaTtsEngine
    private lateinit var downloader: FakeVoiceModelDownloader
    private lateinit var piperWrapper: PiperTtsEngineWrapper

    private class FakeVoiceModelDownloader : VoiceModelDownloader(ContextWrapper(null)) {
        var isDownloaded = false
        override fun isModelDownloaded(modelName: String): Boolean = isDownloaded
        override fun isEspeakDataReady(): Boolean = true
        override suspend fun downloadEspeakNgDataIfNeeded(onProgress: (Float) -> Unit) {}
        override fun getModelPath(modelName: String): String = "/fake/model.onnx"
        override fun getTokensPath(modelName: String): String = "/fake/tokens.txt"
        override fun getEspeakDataDir(): String = "/fake/espeak-data"
    }

    private class FakeSherpaTtsEngine : SherpaTtsEngine(ContextWrapper(null)) {
        var isInitialized = false
        var synthGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        var synthStarted = java.util.concurrent.CountDownLatch(1)
        var releaseCount = java.util.concurrent.atomic.AtomicInteger(0)
        var releasedDuringSynthesis = java.util.concurrent.atomic.AtomicBoolean(false)
        private val inSynthesis = java.util.concurrent.atomic.AtomicBoolean(false)

        override suspend fun initialize(onnxPath: String, tokensPath: String, lexiconPath: String, dataDirPath: String) {
            isInitialized = true
        }
        override suspend fun synthesize(text: String, speed: Float): ByteArray {
            inSynthesis.set(true)
            synthStarted.countDown()
            try {
                synthGate?.await()
            } finally {
                inSynthesis.set(false)
            }
            return ByteArray(100)
        }
        override suspend fun playPcm(pcm: ByteArray, onAudioStarted: () -> Unit) { onAudioStarted() }
        override fun stop() {}
        override fun release() {
            if (inSynthesis.get()) {
                releasedDuringSynthesis.set(true)
            }
            releaseCount.incrementAndGet()
            isInitialized = false
        }
    }

    @Before
    fun setUp() {
        sherpaTtsEngine = FakeSherpaTtsEngine()
        downloader = FakeVoiceModelDownloader()
        piperWrapper = PiperTtsEngineWrapper(sherpaTtsEngine, downloader, TestTtsMessages)
    }

    @Test
    fun `initialize without voice selected returns error callback without recursion`() {
        var errorReported: String? = null

        piperWrapper.initialize(
            onReady = {},
            onError = { error -> errorReported = error }
        )

        assertEquals(TestTtsMessages.text(R.string.tts_error_ai_voice_not_selected), errorReported)
    }

    @Test
    fun `speak without voice selected returns error callback directly`() {
        var errorReported: String? = null
        val chunk = TtsChunk(id = 0, paragraphIndex = 0, text = "Xin chào")

        piperWrapper.speak(
            chunk = chunk,
            onChunkStart = {},
            onChunkDone = {},
            onError = { error -> errorReported = error }
        )

        assertEquals(TestTtsMessages.text(R.string.tts_error_ai_voice_not_selected), errorReported)
    }

    @Test
    fun `initialize with un-downloaded voice model returns error callback without infinite recursion`() {
        downloader.isDownloaded = false
        piperWrapper.setVoice("ngoc_ngan")

        var errorReported: String? = null
        var readyCalled = false

        piperWrapper.initialize(
            onReady = { readyCalled = true },
            onError = { error -> errorReported = error }
        )

        Thread.sleep(200)

        assertFalse(readyCalled)
        assertEquals(TestTtsMessages.text(R.string.tts_error_ai_model_not_downloaded), errorReported)
    }

    @Test
    fun `speak triggers initialization and invokes error callback when model not downloaded without recursion`() {
        downloader.isDownloaded = false
        piperWrapper.setVoice("ngoc_ngan")

        var errorReported: String? = null
        val chunk = TtsChunk(id = 1, paragraphIndex = 0, text = "Đoạn văn thử nghiệm")

        piperWrapper.speak(
            chunk = chunk,
            onChunkStart = {},
            onChunkDone = {},
            onError = { error -> errorReported = error }
        )

        Thread.sleep(200)

        assertEquals(TestTtsMessages.text(R.string.tts_error_ai_model_not_downloaded), errorReported)
    }

    @Test
    fun `speak initializes engine successfully and plays speech when model is ready`() {
        downloader.isDownloaded = true
        piperWrapper.setVoice("ngoc_ngan")

        var chunkStarted = false
        val chunk = TtsChunk(id = 1, paragraphIndex = 0, text = "Đoạn văn thử nghiệm thành công")

        piperWrapper.speak(
            chunk = chunk,
            onChunkStart = { chunkStarted = true },
            onChunkDone = {},
            onError = {}
        )

        Thread.sleep(300)

        assertTrue(sherpaTtsEngine.isInitialized)
        assertTrue(chunkStarted)
    }

    @Test
    fun `initialize with incompatible language returns error without crash`() {
        downloader.isDownloaded = true
        piperWrapper.setVoice("ngoc_ngan") // ngoc_ngan is "vi"
        piperWrapper.setLanguage("en")     // Change language to English, voiceId becomes null

        var errorReported: String? = null
        piperWrapper.initialize(
            onReady = {},
            onError = { error -> errorReported = error }
        )

        assertEquals(TestTtsMessages.text(R.string.tts_error_ai_voice_not_selected), errorReported)
    }

    @Test
    fun `stop and shutdown cleanly without exceptions`() {
        piperWrapper.stop()
        piperWrapper.shutdown()
        assertFalse(sherpaTtsEngine.isInitialized)
    }

    @Test
    fun `setVoice waits for active synthesis before releasing native engine`() {
        downloader.isDownloaded = true
        piperWrapper.setVoice("ngoc_ngan")
        val readyLatch = java.util.concurrent.CountDownLatch(1)
        piperWrapper.initialize(onReady = { readyLatch.countDown() }, onError = {})
        assertTrue(readyLatch.await(3, java.util.concurrent.TimeUnit.SECONDS))

        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        sherpaTtsEngine.synthGate = gate
        piperWrapper.speak(
            chunk = TtsChunk(id = 10, paragraphIndex = 0, text = "Đang tổng hợp"),
            onChunkStart = {},
            onChunkDone = {},
            onError = {}
        )
        assertTrue(sherpaTtsEngine.synthStarted.await(3, java.util.concurrent.TimeUnit.SECONDS))

        piperWrapper.setVoice(null)
        gate.complete(Unit)
        Thread.sleep(250)

        assertFalse("release() ran while synthesize() was still active", sherpaTtsEngine.releasedDuringSynthesis.get())
        assertTrue("release() should run after synthesis finishes", sherpaTtsEngine.releaseCount.get() >= 1)
    }

    @Test
    fun `switching voice while synthesizing does not release newly initialized voice`() {
        downloader.isDownloaded = true
        piperWrapper.setVoice("ngoc_ngan")
        val readyA = java.util.concurrent.CountDownLatch(1)
        piperWrapper.initialize(onReady = { readyA.countDown() }, onError = {})
        assertTrue(readyA.await(3, java.util.concurrent.TimeUnit.SECONDS))

        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        sherpaTtsEngine.synthGate = gate
        piperWrapper.speak(
            chunk = TtsChunk(id = 1, paragraphIndex = 0, text = "Đang đọc giọng A"),
            onChunkStart = {},
            onChunkDone = {},
            onError = {}
        )
        assertTrue(sherpaTtsEngine.synthStarted.await(3, java.util.concurrent.TimeUnit.SECONDS))

        // Đổi sang giọng B ngay khi giọng A còn kẹt trong native generate()
        sherpaTtsEngine.synthGate = null
        piperWrapper.setVoice("ban_mai")
        val readyB = java.util.concurrent.CountDownLatch(1)
        piperWrapper.initialize(onReady = { readyB.countDown() }, onError = {})
        assertTrue(readyB.await(3, java.util.concurrent.TimeUnit.SECONDS))

        // Giọng A kết thúc sau khi giọng B đã init -> coroutine release cũ không được hủy giọng B
        gate.complete(Unit)
        Thread.sleep(250)

        assertTrue("Newly initialized voice B was mistakenly released", sherpaTtsEngine.isInitialized)
        assertEquals(0, sherpaTtsEngine.releaseCount.get())
    }

    @Test
    fun `consecutive setVoice calls without playback still release previous loaded model`() {
        downloader.isDownloaded = true
        piperWrapper.setVoice("ngoc_ngan")
        val readyA = java.util.concurrent.CountDownLatch(1)
        piperWrapper.initialize(onReady = { readyA.countDown() }, onError = {})
        assertTrue(readyA.await(3, java.util.concurrent.TimeUnit.SECONDS))

        // Đổi liên tiếp A -> B -> C mà không gọi speak()
        piperWrapper.setVoice("ban_mai")
        piperWrapper.setVoice("quang_minh")
        Thread.sleep(250)

        assertFalse("Old native model A should have been released", sherpaTtsEngine.isInitialized)
        assertEquals(1, sherpaTtsEngine.releaseCount.get())
    }
}
