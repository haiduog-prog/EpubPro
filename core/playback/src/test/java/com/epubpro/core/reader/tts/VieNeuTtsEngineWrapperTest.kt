package com.epubpro.core.reader.tts

import com.epubpro.core.tts.ChunkGap
import com.epubpro.core.tts.PreparedChunk
import com.epubpro.core.tts.TtsVoiceCatalog
import com.epubpro.core.tts.VieNeuDeviceSupportChecker
import com.epubpro.core.tts.VieNeuModelDownloader
import com.epubpro.core.tts.VieNeuNanoVoice
import com.epubpro.core.tts.VieNeuPcmPlayer
import com.epubpro.core.tts.VieNeuRuntimeManager
import com.epubpro.core.tts.VoiceCatalog
import com.epubpro.domain.model.AiTtsBackend
import com.epubpro.domain.model.TtsChunk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class VieNeuTtsEngineWrapperTest {

    private lateinit var testDispatcher: ExecutorCoroutineDispatcher

    private class FakeRuntimeManager(
        var supported: Boolean = true,
        var ready: Boolean = true,
    ) : VieNeuRuntimeManager(
        assetsJsonProvider = { "{}" },
        downloader = VieNeuModelDownloader { File("build/tmp/vieneu-test") },
        deviceSupportChecker = VieNeuDeviceSupportChecker(),
    ) {
        val preparedCount = AtomicInteger(0)
        val synthCount = AtomicInteger(0)
        @Volatile
        var lastConfig: String = ""
        var customChunksProvider: ((String) -> List<PreparedChunk>)? = null

        private val sampleVoice = VieNeuNanoVoice(
            name = "Minh Quân",
            description = "Nam · Bắc · Phong cách tự nhiên",
            gender = "male",
            speakerEmbedding = FloatArray(192) { 0.1f },
            style = Array(50) { FloatArray(256) { 0.01f } },
        )

        override fun isSupportedDevice(): Boolean = supported

        override fun isModelReady(): Boolean = ready

        override suspend fun loadVoiceCatalog(): VoiceCatalog =
            VoiceCatalog(defaultVoiceName = "Minh Quân", voices = listOf(sampleVoice))

        override suspend fun resolveVoice(voiceIdOrName: String?): VieNeuNanoVoice = sampleVoice

        override suspend fun ensureLoaded(threads: Int) = Unit

        override suspend fun prepareChunks(text: String, threads: Int, maxChars: Int): List<PreparedChunk> {
            preparedCount.incrementAndGet()
            customChunksProvider?.let { return it(text) }
            return listOf(PreparedChunk(text = text, phonemes = "ipa($text)", gapBefore = ChunkGap.FIRST))
        }

        override suspend fun synthesizeChunk(
            phonemes: String,
            voice: VieNeuNanoVoice,
            steps: Int,
            cfg: Float,
            sway: Float,
            speed: Float,
            threads: Int,
            seed: Long?,
            isCancelled: () -> Boolean,
        ): FloatArray {
            synthCount.incrementAndGet()
            lastConfig = "$steps/$cfg/$sway/$speed/$threads"
            return FloatArray(480) { 0.25f }
        }
    }

    private class FakePcmPlayer : VieNeuPcmPlayer() {
        val flushedCount = AtomicInteger(0)
        val writtenSilenceCount = AtomicInteger(0)
        var awaitGate: CompletableDeferred<Boolean>? = null
        private val epochCounter = AtomicInteger(1)
        private var framesWritten = 0L

        override fun ensureStarted(): Long = epochCounter.get().toLong()

        override fun writeSamples(
            samples: FloatArray,
            expectedEpoch: Long,
            isCancelled: () -> Boolean,
        ): Long {
            if (isCancelled() || expectedEpoch != epochCounter.get().toLong()) return -1L
            framesWritten += samples.size
            return framesWritten
        }

        override fun writeSilence(
            sampleCount: Int,
            expectedEpoch: Long,
            isCancelled: () -> Boolean,
        ): Long {
            if (isCancelled() || expectedEpoch != epochCounter.get().toLong()) return -1L
            writtenSilenceCount.addAndGet(sampleCount)
            framesWritten += sampleCount
            return framesWritten
        }

        override suspend fun awaitFramePlayed(
            targetFrame: Long,
            expectedEpoch: Long,
            isCancelled: () -> Boolean,
        ): Boolean {
            if (isCancelled() || expectedEpoch != epochCounter.get().toLong()) return false
            return awaitGate?.await() ?: (!isCancelled() && expectedEpoch == epochCounter.get().toLong())
        }

        override fun pauseAndFlush() {
            epochCounter.incrementAndGet()
            framesWritten = 0L
            flushedCount.incrementAndGet()
        }

        override fun close() = pauseAndFlush()
    }

    @Before
    fun setUp() {
        testDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    }

    @After
    fun tearDown() {
        testDispatcher.close()
    }

    @Test
    fun `initialize fails with message when device or model not ready`() {
        val runtime = FakeRuntimeManager(supported = false, ready = false)
        val wrapper = VieNeuTtsEngineWrapper(runtime, FakePcmPlayer(), testDispatcher)

        var readyCalled = false
        var errorMsg: String? = null
        wrapper.initialize(onReady = { readyCalled = true }, onError = { errorMsg = it })

        assertFalse(readyCalled)
        assertNotNull(errorMsg)

        runtime.supported = true
        errorMsg = null
        wrapper.initialize(onReady = { readyCalled = true }, onError = { errorMsg = it })
        assertFalse(readyCalled)
        assertNotNull(errorMsg)
    }

    @Test
    fun `speak replays pending speech once initialization finishes`() {
        val runtime = FakeRuntimeManager(supported = true, ready = true)
        val player = FakePcmPlayer()
        val wrapper = VieNeuTtsEngineWrapper(runtime, player, testDispatcher)

        val doneLatch = CountDownLatch(1)
        var startedId: Int? = null
        var doneId: Int? = null
        var error: String? = null
        wrapper.speak(
            chunk = TtsChunk(id = 7, paragraphIndex = 0, text = "Xin chào EpubPro."),
            onChunkStart = { startedId = it },
            onChunkDone = {
                doneId = it
                doneLatch.countDown()
            },
            onError = {
                error = it
                doneLatch.countDown()
            },
        )

        assertTrue("Speak did not finish in time", doneLatch.await(5, TimeUnit.SECONDS))
        assertNull(error)
        assertEquals(7, startedId)
        assertEquals(7, doneId)
        assertEquals(1, runtime.preparedCount.get())
        assertTrue(player.writtenSilenceCount.get() > 0)
    }

    @Test
    fun `punctuation only chunk triggers start and done cleanly without failure`() {
        val runtime = FakeRuntimeManager().apply {
            customChunksProvider = { listOf(PreparedChunk(text = "---", phonemes = "   ", gapBefore = ChunkGap.FIRST)) }
        }
        val wrapper = VieNeuTtsEngineWrapper(runtime, FakePcmPlayer(), testDispatcher)
        val readyLatch = CountDownLatch(1)
        wrapper.initialize(onReady = { readyLatch.countDown() }, onError = {})
        assertTrue(readyLatch.await(5, TimeUnit.SECONDS))

        val doneLatch = CountDownLatch(1)
        var startedId: Int? = null
        var doneId: Int? = null
        wrapper.speak(
            chunk = TtsChunk(id = 3, paragraphIndex = 0, text = "---"),
            onChunkStart = { startedId = it },
            onChunkDone = {
                doneId = it
                doneLatch.countDown()
            },
        )

        assertTrue(doneLatch.await(5, TimeUnit.SECONDS))
        assertEquals(3, startedId)
        assertEquals(3, doneId)
        assertEquals(0, runtime.synthCount.get())
    }

    @Test
    fun `prefetch avoids re-synthesizing matching chunk and invalidates on config change`() {
        val runtime = FakeRuntimeManager()
        val wrapper = VieNeuTtsEngineWrapper(runtime, FakePcmPlayer(), testDispatcher)
        val readyLatch = CountDownLatch(1)
        wrapper.initialize(onReady = { readyLatch.countDown() }, onError = {})
        assertTrue(readyLatch.await(5, TimeUnit.SECONDS))

        val next = TtsChunk(id = 2, paragraphIndex = 0, text = "Câu tiếp theo đã prefetch.")
        wrapper.prefetch(next)
        awaitDispatcherIdle()
        assertEquals(1, runtime.preparedCount.get())
        assertEquals(1, runtime.synthCount.get())

        val done1 = CountDownLatch(1)
        wrapper.speak(next, onChunkStart = {}, onChunkDone = { done1.countDown() })
        assertTrue(done1.await(5, TimeUnit.SECONDS))
        assertEquals(1, runtime.preparedCount.get())
        assertEquals(1, runtime.synthCount.get())

        wrapper.prefetch(next)
        awaitDispatcherIdle()
        assertEquals(2, runtime.preparedCount.get())

        wrapper.setSynthesisConfig(steps = 16, cfg = 3.0f, sway = 0f, threads = 2)
        val done2 = CountDownLatch(1)
        wrapper.speak(next, onChunkStart = {}, onChunkDone = { done2.countDown() })
        assertTrue(done2.await(5, TimeUnit.SECONDS))

        assertEquals(3, runtime.preparedCount.get())
        assertTrue(runtime.lastConfig.startsWith("16/3.0/0.0/"))
    }

    @Test
    fun `pause flushes player and suppresses onChunkDone callback`() {
        val runtime = FakeRuntimeManager()
        val gate = CompletableDeferred<Boolean>()
        val player = FakePcmPlayer().apply { awaitGate = gate }
        val wrapper = VieNeuTtsEngineWrapper(runtime, player, testDispatcher)
        val readyLatch = CountDownLatch(1)
        wrapper.initialize(onReady = { readyLatch.countDown() }, onError = {})
        assertTrue(readyLatch.await(5, TimeUnit.SECONDS))

        val startLatch = CountDownLatch(1)
        var done = false
        wrapper.speak(
            chunk = TtsChunk(id = 11, paragraphIndex = 0, text = "Câu đang phát dở."),
            onChunkStart = { startLatch.countDown() },
            onChunkDone = { done = true },
        )

        assertTrue(startLatch.await(5, TimeUnit.SECONDS))
        wrapper.pause()
        gate.complete(true)
        awaitDispatcherIdle()

        assertFalse(done)
        assertTrue(player.flushedCount.get() >= 1)
    }

    @Test
    fun `getAvailableVoices exposes 11 VIENEU_NANO entries only on supported arm64 devices`() {
        val supportedWrapper = VieNeuTtsEngineWrapper(
            FakeRuntimeManager(supported = true, ready = false),
            FakePcmPlayer(),
            testDispatcher,
        )
        val viVoices = supportedWrapper.getAvailableVoices("vi")

        assertEquals(11, viVoices.size)
        assertTrue(viVoices.all { it.aiBackend == AiTtsBackend.VIENEU_NANO && it.id.startsWith("vieneu:") })
        assertTrue(viVoices.none { it.isDownloaded })
        assertTrue(supportedWrapper.getAvailableVoices("en").isEmpty())

        val unsupportedWrapper = VieNeuTtsEngineWrapper(
            FakeRuntimeManager(supported = false, ready = false),
            FakePcmPlayer(),
            testDispatcher,
        )
        assertTrue(unsupportedWrapper.getAvailableVoices("vi").isEmpty())
    }

    private fun awaitDispatcherIdle() = runBlocking(testDispatcher) {}
}
