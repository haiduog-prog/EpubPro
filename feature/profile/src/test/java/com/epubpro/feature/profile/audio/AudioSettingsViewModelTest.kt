package com.epubpro.feature.profile.audio

import com.epubpro.core.reader.tts.TtsEngine
import com.epubpro.core.storage.TtsBubblePreferences
import com.epubpro.core.storage.TtsBubblePreferencesManager
import com.epubpro.core.storage.TtsPreferencesManager
import com.epubpro.core.tts.ChunkGap
import com.epubpro.core.tts.DownloadProgress
import com.epubpro.core.tts.PreparedChunk
import com.epubpro.core.tts.ResourceInventory
import com.epubpro.core.tts.SherpaTtsEngine
import com.epubpro.core.tts.TtsVoiceCatalog
import com.epubpro.core.tts.VieNeuDeviceSupportChecker
import com.epubpro.core.tts.VieNeuDownloadCoordinator
import com.epubpro.core.tts.VieNeuDownloadState
import com.epubpro.core.tts.VieNeuModelDownloader
import com.epubpro.core.tts.VieNeuNanoVoice
import com.epubpro.core.tts.VieNeuPcmPlayer
import com.epubpro.core.tts.VieNeuRuntimeManager
import com.epubpro.core.tts.VoiceCatalog
import com.epubpro.core.tts.VoiceModelDownloader
import com.epubpro.domain.model.AiTtsBackend
import com.epubpro.domain.model.TtsChunk
import com.epubpro.domain.model.TtsSettings
import com.epubpro.domain.model.TtsVoice
import com.epubpro.domain.model.VieNeuQualityPreset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

@OptIn(ExperimentalCoroutinesApi::class)
class AudioSettingsViewModelTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var mainDispatcher: TestDispatcher

    private class FakeSupportChecker(
        var supported: Boolean = true,
    ) : VieNeuDeviceSupportChecker() {
        override fun isSupported(): Boolean = supported
    }

    private class FakeDownloadCoordinator(
        downloader: VieNeuModelDownloader,
        supportChecker: VieNeuDeviceSupportChecker,
        dispatcher: CoroutineDispatcher,
        initialReady: Boolean = false,
    ) : VieNeuDownloadCoordinator(downloader, supportChecker, dispatcher) {
        private val fakeState = MutableStateFlow(
            if (initialReady) {
                readyState(downloader)
            } else {
                idleState(downloader)
            },
        )

        var startCount = 0
        var cancelCount = 0
        var clearCount = 0

        override val state: StateFlow<VieNeuDownloadState>
            get() = fakeState.asStateFlow()

        fun emitState(newState: VieNeuDownloadState) {
            fakeState.value = newState
        }

        override fun startDownload(): Boolean {
            startCount++
            return true
        }

        override fun cancelDownload() {
            cancelCount++
        }

        override fun clearModel() {
            clearCount++
        }

        companion object {
            fun idleState(downloader: VieNeuModelDownloader): VieNeuDownloadState.Idle =
                VieNeuDownloadState.Idle(
                    ResourceInventory(
                        modelDir = downloader.modelDir,
                        presentCount = 0,
                        totalCount = VieNeuModelDownloader.REQUIRED_ARTIFACTS.size,
                        existingBytes = 0L,
                        missingBytes = VieNeuModelDownloader.TOTAL_EXPECTED_BYTES,
                    ),
                )

            fun readyState(downloader: VieNeuModelDownloader): VieNeuDownloadState.Ready =
                VieNeuDownloadState.Ready(
                    ResourceInventory(
                        modelDir = downloader.modelDir,
                        presentCount = VieNeuModelDownloader.REQUIRED_ARTIFACTS.size,
                        totalCount = VieNeuModelDownloader.REQUIRED_ARTIFACTS.size,
                        existingBytes = VieNeuModelDownloader.TOTAL_EXPECTED_BYTES,
                        missingBytes = 0L,
                    ),
                )
        }
    }

    private class FakeRuntimeManager(
        downloader: VieNeuModelDownloader,
        supportChecker: VieNeuDeviceSupportChecker,
        var ready: Boolean = false,
    ) : VieNeuRuntimeManager(
        assetsJsonProvider = { "{}" },
        downloader = downloader,
        deviceSupportChecker = supportChecker,
    ) {
        private val sampleVoice = VieNeuNanoVoice(
            name = "Minh Quân",
            description = "Nam · Bắc · Tự nhiên",
            gender = "male",
            speakerEmbedding = FloatArray(192) { 0.1f },
            style = Array(50) { FloatArray(256) { 0.01f } },
        )

        var unloadCount = 0
        var synthCalls = 0
        var lastSteps = -1
        var lastCfg = -1f
        var lastSway = 0f
        var lastThreads = -1
        var lastSpeed = -1f
        var synthFailure: Throwable? = null
        var chunksToPrepare: List<PreparedChunk>? = null

        override fun isSupportedDevice(): Boolean = super.isSupportedDevice()

        override fun isModelReady(): Boolean = ready

        override suspend fun loadVoiceCatalog(): VoiceCatalog =
            VoiceCatalog(defaultVoiceName = "Minh Quân", voices = listOf(sampleVoice))

        override suspend fun resolveVoice(voiceIdOrName: String?): VieNeuNanoVoice = sampleVoice

        override suspend fun prepareChunks(text: String, threads: Int, maxChars: Int): List<PreparedChunk> {
            return chunksToPrepare ?: listOf(
                PreparedChunk(text = text, phonemes = "s i n c h a o", gapBefore = ChunkGap.FIRST),
            )
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
            synthFailure?.let { throw it }
            synthCalls++
            lastSteps = steps
            lastCfg = cfg
            lastSway = sway
            lastThreads = threads
            lastSpeed = speed
            return FloatArray(480) { 0.2f }
        }

        override suspend fun unload() {
            unloadCount++
        }
    }

    private class FakePcmPlayer : VieNeuPcmPlayer() {
        var playedSamples: FloatArray? = null
        var pauseAndFlushCount = 0
        var playGate: CompletableDeferred<Unit>? = null

        override suspend fun playOneShot(samples: FloatArray, isCancelled: () -> Boolean) {
            playedSamples = samples
            playGate?.await()
        }

        override fun pauseAndFlush() {
            pauseAndFlushCount++
        }

        override fun close() = Unit
    }

    private class FakeNativeTtsEngine : TtsEngine {
        override fun initialize(onReady: () -> Unit, onError: (String) -> Unit) = Unit
        override fun speak(
            chunk: TtsChunk,
            onChunkStart: (Int) -> Unit,
            onChunkDone: (Int) -> Unit,
            onError: (String) -> Unit,
        ) = Unit
        override fun pause() = Unit
        override fun resume() = Unit
        override fun stop() = Unit
        override fun setLanguage(language: String) = Unit
        override fun setSpeed(speed: Float) = Unit
        override fun setPitch(pitch: Float) = Unit
        override fun setVoice(voiceId: String?) = Unit
        override fun getAvailableVoices(language: String): List<TtsVoice> = emptyList()
        override fun shutdown() = Unit
    }

    private data class TestHarness(
        val viewModel: AudioSettingsViewModel,
        val settingsFlow: MutableStateFlow<TtsSettings>,
        val downloader: VieNeuModelDownloader,
        val coordinator: FakeDownloadCoordinator,
        val runtimeManager: FakeRuntimeManager,
        val pcmPlayer: FakePcmPlayer,
        val downloadedSherpaIds: MutableSet<String>,
    )

    @Before
    fun setUp() {
        mainDispatcher = StandardTestDispatcher()
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun buildHarness(
        initialSettings: TtsSettings = TtsSettings(),
        isVieNeuSupported: Boolean = true,
        isVieNeuReady: Boolean = false,
        initialSherpaDownloaded: Set<String> = emptySet(),
    ): TestHarness {
        val modelDir = tempFolder.newFolder("vieneu-model")
        val downloader = VieNeuModelDownloader { modelDir }
        val supportChecker = FakeSupportChecker(isVieNeuSupported)
        val coordinator = FakeDownloadCoordinator(
            downloader = downloader,
            supportChecker = supportChecker,
            dispatcher = mainDispatcher,
            initialReady = isVieNeuReady,
        )
        val runtimeManager = FakeRuntimeManager(
            downloader = downloader,
            supportChecker = supportChecker,
            ready = isVieNeuReady,
        )
        val settingsFlow = MutableStateFlow(initialSettings)
        val preferencesManager: TtsPreferencesManager = mock {
            on { this.settingsFlow } doReturn settingsFlow
            on { getSettings() } doAnswer { settingsFlow.value }
            on { saveSettings(any()) } doAnswer { invocation ->
                settingsFlow.value = invocation.getArgument(0)
                Unit
            }
        }
        val bubbleFlow = MutableStateFlow(TtsBubblePreferences())
        val bubblePreferencesManager: TtsBubblePreferencesManager = mock {
            on { preferencesFlow } doReturn bubbleFlow
            on { getPreferences() } doAnswer { bubbleFlow.value }
        }
        val downloadedSherpaIds = initialSherpaDownloaded.toMutableSet()
        val voiceModelDownloader: VoiceModelDownloader = mock {
            on { isModelDownloaded(any()) } doAnswer { invocation ->
                downloadedSherpaIds.contains(invocation.getArgument(0))
            }
        }
        val sherpaEngine: SherpaTtsEngine = mock()
        val pcmPlayer = FakePcmPlayer()

        val viewModel = AudioSettingsViewModel(
            voiceModelDownloader = voiceModelDownloader,
            ttsEngine = sherpaEngine,
            preferencesManager = preferencesManager,
            bubblePreferencesManager = bubblePreferencesManager,
            vieNeuDownloadCoordinator = coordinator,
            vieNeuRuntimeManager = runtimeManager,
            vieNeuDeviceSupportChecker = supportChecker,
            nativeTtsEngineFactory = { FakeNativeTtsEngine() },
            vieNeuPcmPlayer = pcmPlayer,
            resolveString = { resId, args ->
                if (args.isEmpty()) "res:$resId" else "res:$resId:${args.joinToString(",")}"
            },
        )
        return TestHarness(
            viewModel = viewModel,
            settingsFlow = settingsFlow,
            downloader = downloader,
            coordinator = coordinator,
            runtimeManager = runtimeManager,
            pcmPlayer = pcmPlayer,
            downloadedSherpaIds = downloadedSherpaIds,
        )
    }

    @Test
    fun `init preserves stored VieNeu voice and config on supported arm64 device`() = runTest {
        val stored = TtsSettings(
            isConfigured = true,
            isAiVoice = true,
            language = "vi",
            voiceId = "vieneu:Minh Quân",
            speed = 1.2f,
            vieNeuPreset = VieNeuQualityPreset.CUSTOM,
            vieNeuSteps = 12,
            vieNeuCfg = 2.5f,
            vieNeuSway = -0.4f,
            vieNeuThreads = 2,
        )
        val harness = buildHarness(initialSettings = stored, isVieNeuSupported = true, isVieNeuReady = true)
        advanceUntilIdle()

        val state = harness.viewModel.uiState.value
        assertTrue(state.isAiVoice)
        assertTrue(state.isVieNeuSupported)
        assertTrue(state.isVieNeuModelReady)
        assertEquals(AiTtsBackend.VIENEU_NANO, state.selectedAiBackend)
        assertEquals("vieneu:Minh Quân", state.selectedVoiceId)
        assertEquals(VieNeuQualityPreset.CUSTOM, state.vieNeuPreset)
        assertEquals(12, state.vieNeuSteps)
        assertEquals(2.5f, state.vieNeuCfg, 0.001f)
        assertEquals(-0.4f, state.vieNeuSway, 0.001f)
        assertEquals(2, state.vieNeuThreads)
        assertEquals(1.2f, state.speechSpeed, 0.001f)
    }

    @Test
    fun `init falls back when stored VieNeu voice runs on unsupported 32bit device`() = runTest {
        val stored = TtsSettings(
            isConfigured = true,
            isAiVoice = true,
            language = "vi",
            voiceId = "vieneu:Minh Quân",
        )
        val harness = buildHarness(initialSettings = stored, isVieNeuSupported = false)
        advanceUntilIdle()

        val state = harness.viewModel.uiState.value
        assertFalse(state.isVieNeuSupported)
        assertFalse(state.isAiVoice)
        assertEquals(AiTtsBackend.SHERPA_VITS, state.selectedAiBackend)
        assertNull(state.selectedVoiceId)
        assertFalse(harness.settingsFlow.value.isAiVoice)
        assertNull(harness.settingsFlow.value.voiceId)
    }

    @Test
    fun `switching between VieNeu and Sherpa backends updates voice list and persists selection`() = runTest {
        val sherpaId = TtsVoiceCatalog.aiVoices.first().id
        val harness = buildHarness(
            initialSettings = TtsSettings(isAiVoice = false),
            isVieNeuSupported = true,
            initialSherpaDownloaded = setOf(sherpaId),
        )
        advanceUntilIdle()

        harness.viewModel.onAiVoiceToggled(true)
        advanceUntilIdle()
        assertEquals(AiTtsBackend.VIENEU_NANO, harness.viewModel.uiState.value.selectedAiBackend)
        assertEquals(TtsVoiceCatalog.DEFAULT_VIENEU_VOICE_ID, harness.viewModel.uiState.value.selectedVoiceId)
        assertEquals(TtsVoiceCatalog.DEFAULT_VIENEU_VOICE_ID, harness.settingsFlow.value.voiceId)

        harness.viewModel.onAiBackendSelected(AiTtsBackend.SHERPA_VITS)
        advanceUntilIdle()
        assertEquals(AiTtsBackend.SHERPA_VITS, harness.viewModel.uiState.value.selectedAiBackend)
        assertEquals(sherpaId, harness.viewModel.uiState.value.selectedVoiceId)
        assertEquals(sherpaId, harness.settingsFlow.value.voiceId)
        assertTrue(harness.viewModel.uiState.value.filteredAiVoices.all { it.backend == AiTtsBackend.SHERPA_VITS })

        harness.viewModel.onAiBackendSelected(AiTtsBackend.VIENEU_NANO)
        advanceUntilIdle()
        assertEquals(AiTtsBackend.VIENEU_NANO, harness.viewModel.uiState.value.selectedAiBackend)
        assertEquals(TtsVoiceCatalog.DEFAULT_VIENEU_VOICE_ID, harness.viewModel.uiState.value.selectedVoiceId)
        assertTrue(harness.viewModel.uiState.value.filteredAiVoices.all { it.backend == AiTtsBackend.VIENEU_NANO })
    }

    @Test
    fun `download cancel and clear package actions delegate and sync state`() = runTest {
        val harness = buildHarness(isVieNeuSupported = true, isVieNeuReady = false)
        advanceUntilIdle()

        harness.viewModel.downloadVieNeuPackage()
        assertEquals(1, harness.coordinator.startCount)

        harness.coordinator.emitState(
            VieNeuDownloadState.Downloading(
                DownloadProgress(
                    currentFile = "vector_estimator.onnx",
                    fileIndex = 6,
                    totalFiles = 7,
                    downloadedBytes = 180_000_000L,
                    totalBytes = 360_000_000L,
                ),
            ),
        )
        advanceUntilIdle()
        var state = harness.viewModel.uiState.value
        assertTrue(state.isVieNeuDownloading)
        assertEquals("vector_estimator.onnx", state.vieNeuCurrentFile)
        assertEquals(180, state.vieNeuDownloadedMb)
        assertEquals(0.5f, state.vieNeuDownloadProgress, 0.001f)

        harness.runtimeManager.ready = true
        harness.coordinator.emitState(FakeDownloadCoordinator.readyState(harness.downloader))
        harness.viewModel.onAiVoiceToggled(true)
        advanceUntilIdle()
        state = harness.viewModel.uiState.value
        assertFalse(state.isVieNeuDownloading)
        assertTrue(state.isVieNeuModelReady)
        assertTrue(state.isModelDownloaded)
        assertTrue(state.filteredAiVoices.all { it.isDownloaded })

        harness.viewModel.cancelVieNeuDownload()
        assertEquals(1, harness.coordinator.cancelCount)

        harness.runtimeManager.ready = false
        harness.viewModel.clearVieNeuModel()
        harness.coordinator.emitState(FakeDownloadCoordinator.idleState(harness.downloader))
        advanceUntilIdle()
        assertEquals(1, harness.coordinator.clearCount)
        assertEquals(1, harness.runtimeManager.unloadCount)
        assertFalse(harness.viewModel.uiState.value.isVieNeuModelReady)
    }

    @Test
    fun `presets and tuning sliders auto-switch to CUSTOM and reset restores FAST defaults`() = runTest {
        val harness = buildHarness(isVieNeuSupported = true, isVieNeuReady = true)
        advanceUntilIdle()

        harness.viewModel.onVieNeuPresetSelected(VieNeuQualityPreset.BALANCED)
        advanceUntilIdle()
        var state = harness.viewModel.uiState.value
        assertEquals(VieNeuQualityPreset.BALANCED, state.vieNeuPreset)
        assertEquals(16, state.vieNeuSteps)
        assertEquals(3.0f, state.vieNeuCfg, 0.001f)
        assertEquals(0.0f, state.vieNeuSway, 0.001f)

        harness.viewModel.onVieNeuStepsChanged(11)
        assertEquals(VieNeuQualityPreset.CUSTOM, harness.viewModel.uiState.value.vieNeuPreset)
        assertEquals(11, harness.viewModel.uiState.value.vieNeuSteps)

        harness.viewModel.onVieNeuCfgChanged(2.44f)
        assertEquals(2.4f, harness.viewModel.uiState.value.vieNeuCfg, 0.001f)
        assertEquals(VieNeuQualityPreset.CUSTOM, harness.viewModel.uiState.value.vieNeuPreset)

        harness.viewModel.onVieNeuSwayChanged(-0.34f)
        assertEquals(-0.3f, harness.viewModel.uiState.value.vieNeuSway, 0.001f)

        harness.viewModel.onVieNeuThreadsSelected(2)
        assertEquals(2, harness.viewModel.uiState.value.vieNeuThreads)

        harness.viewModel.resetVieNeuDefaults()
        advanceUntilIdle()
        state = harness.viewModel.uiState.value
        assertEquals(VieNeuQualityPreset.FAST, state.vieNeuPreset)
        assertEquals(TtsSettings.DEFAULT_VIENEU_STEPS, state.vieNeuSteps)
        assertEquals(TtsSettings.DEFAULT_VIENEU_CFG, state.vieNeuCfg, 0.001f)
        assertEquals(TtsSettings.DEFAULT_VIENEU_SWAY, state.vieNeuSway, 0.001f)
        assertEquals(TtsSettings.DEFAULT_VIENEU_THREADS, state.vieNeuThreads)
        assertEquals(VieNeuQualityPreset.FAST, harness.settingsFlow.value.vieNeuPreset)
    }

    @Test
    fun `testVoice synthesizes VieNeu chunks with current config and supports stopActivePreview`() = runTest {
        val harness = buildHarness(
            initialSettings = TtsSettings(
                isConfigured = true,
                isAiVoice = true,
                language = "vi",
                voiceId = "vieneu:Minh Quân",
                speed = 1.15f,
                vieNeuPreset = VieNeuQualityPreset.CUSTOM,
                vieNeuSteps = 10,
                vieNeuCfg = 2.8f,
                vieNeuSway = -0.6f,
                vieNeuThreads = 2,
            ),
            isVieNeuSupported = true,
            isVieNeuReady = true,
        )
        harness.runtimeManager.chunksToPrepare = listOf(
            PreparedChunk(text = "Câu một.", phonemes = "k a u 1", gapBefore = ChunkGap.FIRST),
            PreparedChunk(text = "Câu hai.", phonemes = "k a u 2", gapBefore = ChunkGap.SENTENCE),
        )
        val gate = CompletableDeferred<Unit>()
        harness.pcmPlayer.playGate = gate
        advanceUntilIdle()

        harness.viewModel.testVoice("Câu một. Câu hai.")
        runCurrent()

        assertTrue(harness.viewModel.uiState.value.isPlaying)
        assertEquals(2, harness.runtimeManager.synthCalls)
        assertEquals(10, harness.runtimeManager.lastSteps)
        assertEquals(2.8f, harness.runtimeManager.lastCfg, 0.001f)
        assertEquals(-0.6f, harness.runtimeManager.lastSway, 0.001f)
        assertEquals(2, harness.runtimeManager.lastThreads)
        assertEquals(1.15f, harness.runtimeManager.lastSpeed, 0.001f)
        assertTrue((harness.pcmPlayer.playedSamples?.size ?: 0) > 960)

        harness.viewModel.stopActivePreview()
        gate.complete(Unit)
        advanceUntilIdle()

        assertFalse(harness.viewModel.uiState.value.isPlaying)
        assertTrue(harness.pcmPlayer.pauseAndFlushCount >= 1)
    }

    @Test
    fun `testVoice surfaces error message and resets isPlaying on VieNeu synthesis failure`() = runTest {
        val harness = buildHarness(
            initialSettings = TtsSettings(
                isConfigured = true,
                isAiVoice = true,
                language = "vi",
                voiceId = "vieneu:Minh Quân",
            ),
            isVieNeuSupported = true,
            isVieNeuReady = true,
        )
        harness.runtimeManager.synthFailure = IllegalStateException("onnx-boom")
        advanceUntilIdle()

        harness.viewModel.testVoice("Thử lỗi tổng hợp.")
        advanceUntilIdle()

        val state = harness.viewModel.uiState.value
        assertFalse(state.isPlaying)
        assertTrue(state.vieNeuDownloadError?.contains("onnx-boom") == true)
    }
}
