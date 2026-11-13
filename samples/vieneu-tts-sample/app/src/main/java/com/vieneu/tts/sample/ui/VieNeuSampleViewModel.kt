package com.vieneu.tts.sample.ui

import android.app.Application
import android.media.AudioManager
import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vieneu.tts.sample.R
import com.vieneu.tts.sample.audio.PcmAudioPlayer
import com.vieneu.tts.sample.data.VieNeuModelDownloader
import com.vieneu.tts.sample.g2p.SeaG2pEngine
import com.vieneu.tts.sample.tts.VieNeuNanoEngine
import com.vieneu.tts.sample.tts.VieNeuNanoVoice
import com.vieneu.tts.sample.tts.VieNeuTextChunker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * ViewModel điều phối tải tài nguyên, nạp engine `sea-g2p` + ONNX v3-Nano và phát audio streaming.
 *
 * @param application Context ứng dụng Android.
 */
class VieNeuSampleViewModel(application: Application) : AndroidViewModel(application) {

    private class AudioSegment(val samples: FloatArray, val silencePadSamples: Int)

    private val downloader = VieNeuModelDownloader(application)
    private val audioManager = application.getSystemService(AudioManager::class.java)
    private val voiceByName = LinkedHashMap<String, VieNeuNanoVoice>()
    private val engineLifecycleMutex = Mutex()

    @Volatile
    private var nanoEngine: VieNeuNanoEngine? = null

    @Volatile
    private var seaG2pEngine: SeaG2pEngine? = null

    @Volatile
    private var activePlayer: PcmAudioPlayer? = null

    private var downloadJob: Job? = null
    private var engineJob: Job? = null
    private var speakJob: Job? = null

    private val _uiState = MutableStateFlow(
        VieNeuSampleUiState(
            inputText = application.getString(R.string.sample_text_news),
        ),
    )

    /**
     * Luồng trạng thái bất biến cho giao diện Compose.
     */
    val uiState: StateFlow<VieNeuSampleUiState> = _uiState.asStateFlow()

    init {
        initializeResources()
    }

    /**
     * Nạp danh sách giọng mẫu từ `assets` và kiểm tra file model có sẵn khi khởi tạo ViewModel.
     */
    private fun initializeResources() {
        viewModelScope.launch {
            val catalog = withContext(Dispatchers.IO) {
                val jsonText = getApplication<Application>().assets.open(VOICES_ASSET_FILE)
                    .bufferedReader(Charsets.UTF_8)
                    .use { it.readText() }
                VieNeuNanoEngine.loadPresets(jsonText)
            }
            val inventory = withContext(Dispatchers.IO) {
                downloader.inspectInventory()
            }

            voiceByName.clear()
            catalog.voices.forEach { voice -> voiceByName[voice.name] = voice }

            val voiceUiList = catalog.voices.map { voice ->
                VoiceOptionUi(
                    name = voice.name,
                    description = voice.description,
                    isFemale = voice.gender.equals("female", ignoreCase = true),
                    isDefault = voice.name == catalog.defaultVoiceName,
                )
            }
            val initialVoice = voiceByName[catalog.defaultVoiceName]?.name
                ?: catalog.voices.firstOrNull()?.name.orEmpty()

            _uiState.update {
                it.copy(
                    inventory = inventory,
                    voices = voiceUiList,
                    selectedVoiceName = initialVoice,
                )
            }

            if (inventory.isReady) {
                loadEngineInternal()
            }
        }
    }

    /**
     * Bắt đầu tải `VieNeu-TTS-v3-Nano` và `sea_g2p.bin` vào bộ nhớ ứng dụng.
     */
    fun startDownload() {
        if (downloadJob?.isActive == true) return
        downloadJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isDownloading = true,
                    downloadProgress = null,
                    runtimeError = null,
                    validationError = null,
                )
            }
            try {
                downloader.downloadMissingArtifacts { progress ->
                    _uiState.update { it.copy(downloadProgress = progress) }
                }
                val updated = withContext(Dispatchers.IO) { downloader.inspectInventory() }
                _uiState.update {
                    it.copy(
                        inventory = updated,
                        isDownloading = false,
                        downloadProgress = null,
                    )
                }
                if (updated.isReady) {
                    loadEngineInternal()
                }
            } catch (_: CancellationException) {
                val updated = withContext(Dispatchers.IO + NonCancellable) { downloader.inspectInventory() }
                _uiState.update {
                    it.copy(
                        inventory = updated,
                        isDownloading = false,
                        downloadProgress = null,
                    )
                }
            } catch (t: Throwable) {
                val updated = withContext(Dispatchers.IO + NonCancellable) { downloader.inspectInventory() }
                _uiState.update {
                    it.copy(
                        inventory = updated,
                        isDownloading = false,
                        downloadProgress = null,
                        runtimeError = t.message ?: t.javaClass.simpleName,
                    )
                }
            }
        }
    }

    /**
     * Huỷ tác vụ tải xuống đang chạy (nếu có).
     */
    fun cancelDownload() {
        downloadJob?.cancel()
    }

    /**
     * Nạp thủ công `SeaG2pEngine` và `VieNeuNanoEngine` với số luồng đã chọn.
     */
    fun loadEngine() {
        if (engineJob?.isActive == true) return
        viewModelScope.launch {
            loadEngineInternal()
        }
    }

    /**
     * Dừng phiên đọc hiện tại (nếu có), giải phóng engine cũ trên IO thread rồi nạp lại `sea-g2p` + `VieNeuNanoEngine`.
     */
    private suspend fun loadEngineInternal() {
        val job = viewModelScope.launch {
            engineLifecycleMutex.withLock {
                val threads = _uiState.value.selectedThreads
                _uiState.update {
                    it.copy(
                        isLoadingEngine = true,
                        isEngineLoaded = false,
                        runtimeError = null,
                        validationError = null,
                    )
                }
                stopPlaybackAndWait()
                releaseEnginesOnIo()

                var newG2p: SeaG2pEngine? = null
                var newNano: VieNeuNanoEngine? = null
                try {
                    val startMs = SystemClock.elapsedRealtime()
                    val dir = downloader.modelDir
                    val dictFile = File(dir, VieNeuModelDownloader.SEA_G2P_DICT_FILE)
                    withContext(Dispatchers.IO) {
                        val g2pEngine = SeaG2pEngine(dictFile)
                        newG2p = g2pEngine
                        currentCoroutineContext().ensureActive()
                        val nano = VieNeuNanoEngine(dir, threads = threads)
                        newNano = nano
                    }
                    val loadedG2p = requireNotNull(newG2p)
                    val loadedNano = requireNotNull(newNano)
                    seaG2pEngine = loadedG2p
                    nanoEngine = loadedNano

                    val elapsed = SystemClock.elapsedRealtime() - startMs
                    _uiState.update {
                        it.copy(
                            isLoadingEngine = false,
                            isEngineLoaded = true,
                            engineLoadMs = elapsed,
                            seaG2pAbiVersion = loadedG2p.abiVersion,
                            loadedThreads = threads,
                        )
                    }
                } catch (cancel: CancellationException) {
                    withContext(Dispatchers.IO + NonCancellable) {
                        runCatching { newNano?.close() }
                        runCatching { newG2p?.close() }
                    }
                    _uiState.update {
                        it.copy(
                            isLoadingEngine = false,
                            isEngineLoaded = nanoEngine != null && seaG2pEngine != null,
                        )
                    }
                    throw cancel
                } catch (t: Throwable) {
                    withContext(Dispatchers.IO + NonCancellable) {
                        runCatching { newNano?.close() }
                        runCatching { newG2p?.close() }
                        releaseEnginesOnIo()
                    }
                    _uiState.update {
                        it.copy(
                            isLoadingEngine = false,
                            isEngineLoaded = false,
                            engineLoadMs = null,
                            seaG2pAbiVersion = null,
                            runtimeError = t.message ?: t.javaClass.simpleName,
                        )
                    }
                }
            }
        }
        engineJob = job
        job.join()
    }

    /**
     * Dừng phát audio và giải phóng toàn bộ session ONNX + từ điển `sea-g2p` khỏi RAM.
     */
    fun unloadEngine() {
        viewModelScope.launch {
            engineJob?.cancelAndJoin()
            engineLifecycleMutex.withLock {
                _uiState.update {
                    it.copy(
                        isEngineLoaded = false,
                        isLoadingEngine = false,
                        engineLoadMs = null,
                        seaG2pAbiVersion = null,
                    )
                }
                stopPlaybackAndWait()
                releaseEnginesOnIo()
                _uiState.update { it.copy(playbackPhase = PlaybackPhase.IDLE) }
            }
        }
    }

    /**
     * Chọn một giọng mẫu preset theo tên.
     *
     * @param voiceName Tên giọng mẫu trong `voices_v3_nano.json`.
     */
    fun selectVoice(voiceName: String) {
        _uiState.update {
            it.copy(
                selectedVoiceName = voiceName,
                validationError = null,
            )
        }
    }

    /**
     * Cập nhật văn bản cần đọc thử.
     *
     * @param text Chuỗi văn bản mới từ ô nhập liệu.
     */
    fun updateInputText(text: String) {
        _uiState.update {
            it.copy(
                inputText = text,
                validationError = null,
            )
        }
    }

    /**
     * Nạp nhanh một đoạn văn bản mẫu từ string resource.
     *
     * @param textRes Mã tài nguyên chuỗi của đoạn văn bản mẫu.
     */
    fun applySampleText(@StringRes textRes: Int) {
        val sample = getApplication<Application>().getString(textRes)
        updateInputText(sample)
    }

    /**
     * Thay đổi tốc độ đọc mong muốn.
     *
     * @param speed Hệ số tốc độ (`0.6f..1.8f`).
     */
    fun updateSpeed(speed: Float) {
        _uiState.update { it.copy(speed = speed.coerceIn(MIN_SPEED, MAX_SPEED)) }
    }

    /**
     * Chọn cấu hình bước Euler / CFG / Sway.
     *
     * @param preset Cấu hình chất lượng tổng hợp.
     */
    fun selectQualityPreset(preset: QualityPreset) {
        _uiState.update { it.copy(qualityPreset = preset) }
    }

    /**
     * Chọn số luồng intra-op ONNX CPU (áp dụng khi nạp Engine).
     *
     * @param threads Số luồng (`2` hoặc `4`).
     */
    fun selectThreads(threads: Int) {
        _uiState.update { it.copy(selectedThreads = threads) }
    }

    /**
     * Bắt đầu tách chunk bằng `sea-g2p`, tổng hợp tuần tự từng chunk và phát ngay qua `AudioTrack`.
     */
    fun speak() {
        val snapshot = _uiState.value
        if (!snapshot.isEngineLoaded || snapshot.isLoadingEngine) return

        val text = snapshot.inputText.trim()
        if (text.isEmpty()) {
            _uiState.update { it.copy(validationError = ValidationError.EMPTY_TEXT) }
            return
        }

        val voice = voiceByName[snapshot.selectedVoiceName]
        if (voice == null) {
            _uiState.update { it.copy(validationError = ValidationError.NO_VOICE) }
            return
        }

        val previousSpeak = speakJob
        speakJob = viewModelScope.launch {
            if (previousSpeak?.isActive == true) {
                activePlayer?.stopImmediately()
                previousSpeak.cancelAndJoin()
            }

            val (g2p, engine) = engineLifecycleMutex.withLock {
                if (!_uiState.value.isEngineLoaded) {
                    null to null
                } else {
                    seaG2pEngine to nanoEngine
                }
            }
            if (g2p == null || engine == null) return@launch

            _uiState.update {
                it.copy(
                    playbackPhase = PlaybackPhase.PREPARING,
                    currentChunkIndex = 0,
                    totalChunks = 0,
                    ttfaMs = null,
                    totalSynthProcessMs = null,
                    totalAudioSeconds = 0f,
                    chunkLogs = emptyList(),
                    validationError = null,
                    runtimeError = null,
                )
            }

            val player = PcmAudioPlayer(
                sampleRate = VieNeuNanoEngine.SAMPLE_RATE,
                audioManager = audioManager,
                onAudioFocusLost = { stopSpeaking() },
            )
            activePlayer = player
            val startWallMs = SystemClock.elapsedRealtime()

            try {
                val preparedChunks = withContext(Dispatchers.Default) {
                    VieNeuTextChunker(g2p).prepareChunks(text)
                }
                if (preparedChunks.isEmpty()) {
                    _uiState.update { it.copy(playbackPhase = PlaybackPhase.IDLE) }
                    return@launch
                }

                val initialLogs = preparedChunks.mapIndexed { idx, chunk ->
                    ChunkTelemetryUi(
                        index = idx + 1,
                        text = chunk.text,
                        phonemes = chunk.phonemes,
                        gapBefore = chunk.gapBefore,
                    )
                }

                _uiState.update {
                    it.copy(
                        playbackPhase = PlaybackPhase.SYNTHESIZING,
                        currentChunkIndex = 1,
                        totalChunks = preparedChunks.size,
                        chunkLogs = initialLogs,
                    )
                }

                withContext(Dispatchers.IO) {
                    player.start()
                }

                // Giới hạn backpressure tối đa 2 chunk PCM trong Channel để tiết kiệm RAM với văn bản dài.
                val audioChannel = Channel<AudioSegment>(capacity = AUDIO_CHANNEL_CAPACITY)
                val playConsumer = launch(Dispatchers.IO) {
                    for (segment in audioChannel) {
                        currentCoroutineContext().ensureActive()
                        if (segment.silencePadSamples > 0) {
                            player.writeSilence(segment.silencePadSamples)
                        }
                        player.writeSamples(segment.samples)
                    }
                    player.finishAndRelease()
                }

                val synthStartMs = SystemClock.elapsedRealtime()
                var totalAudioSec = 0f
                var prevWav: FloatArray? = null

                try {
                    for (idx in preparedChunks.indices) {
                        currentCoroutineContext().ensureActive()
                        val chunk = preparedChunks[idx]
                        _uiState.update { it.copy(currentChunkIndex = idx + 1) }

                        val chunkStartMs = SystemClock.elapsedRealtime()
                        val wav = withContext(Dispatchers.Default) {
                            val coroutineCtx = currentCoroutineContext()
                            engine.synthesize(
                                phonemes = chunk.phonemes,
                                voice = voice,
                                steps = snapshot.qualityPreset.steps,
                                cfg = snapshot.qualityPreset.cfg,
                                sway = snapshot.qualityPreset.sway,
                                speed = snapshot.speed,
                                isCancelled = { !coroutineCtx.isActive },
                            )
                        }
                        val chunkElapsedMs = SystemClock.elapsedRealtime() - chunkStartMs
                        val audioSec = wav.size.toFloat() / VieNeuNanoEngine.SAMPLE_RATE.toFloat()
                        totalAudioSec += audioSec

                        val silencePad = if (wav.isNotEmpty() && prevWav != null) {
                            VieNeuNanoEngine.computePausePadSamples(
                                prevWav = prevWav,
                                nextWav = wav,
                                gap = chunk.gapBefore,
                            )
                        } else {
                            0
                        }
                        if (wav.isNotEmpty()) {
                            prevWav = wav
                            audioChannel.send(AudioSegment(wav, silencePad))
                        }

                        val nowTtfa = if (_uiState.value.ttfaMs == null && wav.isNotEmpty()) {
                            SystemClock.elapsedRealtime() - startWallMs
                        } else {
                            _uiState.value.ttfaMs
                        }
                        val runningProcessMs = SystemClock.elapsedRealtime() - synthStartMs

                        _uiState.update { state ->
                            val updatedLogs = state.chunkLogs.toMutableList()
                            if (idx < updatedLogs.size) {
                                updatedLogs[idx] = updatedLogs[idx].copy(
                                    synthMs = chunkElapsedMs,
                                    audioSec = audioSec,
                                )
                            }
                            state.copy(
                                ttfaMs = nowTtfa,
                                totalSynthProcessMs = runningProcessMs,
                                totalAudioSeconds = totalAudioSec,
                                chunkLogs = updatedLogs,
                            )
                        }
                    }
                    audioChannel.close()
                    playConsumer.join()
                    _uiState.update { it.copy(playbackPhase = PlaybackPhase.DONE) }
                } catch (t: Throwable) {
                    audioChannel.close(t)
                    playConsumer.cancel()
                    throw t
                }
            } catch (_: CancellationException) {
                player.stopImmediately()
                _uiState.update { it.copy(playbackPhase = PlaybackPhase.STOPPED) }
            } catch (t: Throwable) {
                player.stopImmediately()
                _uiState.update {
                    it.copy(
                        playbackPhase = PlaybackPhase.IDLE,
                        runtimeError = t.message ?: t.javaClass.simpleName,
                    )
                }
            } finally {
                if (activePlayer === player) {
                    activePlayer = null
                }
                player.close()
            }
        }
    }

    /**
     * Dừng tổng hợp và ngừng phát âm thanh ngay lập tức.
     */
    fun stopSpeaking() {
        activePlayer?.stopImmediately()
        speakJob?.cancel()
    }

    /**
     * Dừng loa và đợi coroutine đọc kết thúc trước khi giải phóng engine.
     */
    private suspend fun stopPlaybackAndWait() {
        activePlayer?.stopImmediately()
        val job = speakJob
        if (job != null) {
            job.cancelAndJoin()
            if (speakJob === job) {
                speakJob = null
            }
        }
    }

    /**
     * Đóng cả ONNX engine và từ điển `sea-g2p` trên `Dispatchers.IO`, đưa tham chiếu về `null`.
     */
    private suspend fun releaseEnginesOnIo() {
        val nano = nanoEngine
        val g2p = seaG2pEngine
        nanoEngine = null
        seaG2pEngine = null
        if (nano != null || g2p != null) {
            withContext(Dispatchers.IO + NonCancellable) {
                runCatching { nano?.close() }
                runCatching { g2p?.close() }
            }
        }
    }

    override fun onCleared() {
        activePlayer?.stopImmediately()
        downloadJob?.cancel()
        engineJob?.cancel()
        speakJob?.cancel()

        val pendingJobs = listOfNotNull(speakJob, engineJob)
        val nanoToClose = nanoEngine
        val g2pToClose = seaG2pEngine
        nanoEngine = null
        seaG2pEngine = null

        if (nanoToClose != null || g2pToClose != null) {
            val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            cleanupScope.launch {
                try {
                    pendingJobs.forEach { runCatching { it.join() } }
                    runCatching { nanoToClose?.close() }
                    runCatching { g2pToClose?.close() }
                } finally {
                    cleanupScope.cancel()
                }
            }
        }
        super.onCleared()
    }

    companion object {
        private const val VOICES_ASSET_FILE = "voices_v3_nano.json"
        private const val AUDIO_CHANNEL_CAPACITY = 2
        private const val MIN_SPEED = 0.6f
        private const val MAX_SPEED = 1.8f
    }
}
