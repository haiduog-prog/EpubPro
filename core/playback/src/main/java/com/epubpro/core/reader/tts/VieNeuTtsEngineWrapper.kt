package com.epubpro.core.reader.tts

import com.epubpro.core.tts.PreparedChunk
import com.epubpro.core.tts.TtsVoiceCatalog
import com.epubpro.core.tts.VieNeuNanoEngine
import com.epubpro.core.tts.VieNeuPcmPlayer
import com.epubpro.core.tts.VieNeuRuntimeManager
import com.epubpro.domain.model.AiTtsBackend
import com.epubpro.domain.model.TtsChunk
import com.epubpro.domain.model.TtsSettings
import com.epubpro.domain.model.TtsVoice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Adapter hiện thực [TtsEngine] cho backend VieNeu-TTS v3-Nano trong `TtsService`.
 *
 * Kết nối [VieNeuRuntimeManager] (ONNX Runtime + `sea-g2p`) với [VieNeuPcmPlayer] (AudioTrack 24 kHz):
 * - Cắt đoạn/câu theo `VieNeuTextChunker` và nối pause chuẩn (`MINOR`/`SENTENCE`/`PARA`).
 * - Hỗ trợ lookahead chunk kế tiếp ngay trong lúc đang ghi `AudioTrack` và prefetch câu tiếp theo.
 * - Đồng bộ `onChunkDone` theo `playbackHeadPosition` để highlight WebView khớp đúng âm thanh thật.
 *
 * @property runtimeManager Trình quản lý runtime dùng chung cho ONNX và G2P.
 * @property pcmPlayer Bộ phát audio PCM float32 24 kHz cho luồng đọc sách.
 * @property ioDispatcher Dispatcher chạy tác vụ tổng hợp và phát audio.
 */
@Singleton
class VieNeuTtsEngineWrapper internal constructor(
    private val runtimeManager: VieNeuRuntimeManager,
    private val pcmPlayer: VieNeuPcmPlayer,
    private val ioDispatcher: CoroutineDispatcher,
) : TtsEngine {
    @Inject
    constructor(
        runtimeManager: VieNeuRuntimeManager,
    ) : this(
        runtimeManager = runtimeManager,
        pcmPlayer = VieNeuPcmPlayer(),
        ioDispatcher = Dispatchers.IO,
    )

    private var engineScope = CoroutineScope(ioDispatcher + SupervisorJob())
    private var initializeJob: Job? = null
    private var speakJob: Job? = null
    private var activeCancelFlag: AtomicBoolean? = null
    private var currentSynthesisGate: CompletableDeferred<Unit>? = null

    private var currentLanguage: String = "vi"
    private var currentVoiceId: String? = TtsVoiceCatalog.DEFAULT_VIENEU_VOICE_ID
    private var currentSpeed: Float = 1.0f
    private var currentSteps: Int = VieNeuNanoEngine.DEFAULT_STEPS
    private var currentCfg: Float = VieNeuNanoEngine.DEFAULT_CFG
    private var currentSway: Float = VieNeuNanoEngine.DEFAULT_SWAY
    private var currentThreads: Int = VieNeuNanoEngine.DEFAULT_THREADS

    @Volatile
    private var isEngineReady: Boolean = false

    @Volatile
    private var isInitializing: Boolean = false

    private var pendingSpeech: PendingSpeech? = null
    private val prefetchLock = Any()
    private var prefetchedUtterance: PrefetchedUtteranceEntry? = null

    private data class PendingSpeech(
        val chunk: TtsChunk,
        val onChunkStart: (Int) -> Unit,
        val onChunkDone: (Int) -> Unit,
        val onError: (String) -> Unit,
    )

    private data class PrefetchedData(
        val chunks: List<PreparedChunk>,
        val firstAudio: FloatArray?,
    )

    private data class PrefetchedUtteranceEntry(
        val key: String,
        val cancelFlag: AtomicBoolean,
        val job: Job,
        val result: Deferred<PrefetchedData>,
    )

    /**
     * Khởi tạo runtime VieNeu-TTS v3-Nano (`sea_g2p` + 4 model ONNX) ở nền.
     *
     * @param onReady Callback báo engine đã sẵn sàng tổng hợp giọng nói.
     * @param onError Callback báo lỗi nếu thiếu ABI `arm64-v8a` hoặc chưa tải đủ model.
     */
    override fun initialize(onReady: () -> Unit, onError: (String) -> Unit) {
        if (isEngineReady) {
            onReady()
            playPendingSpeech()
            return
        }
        if (isInitializing) return

        if (!runtimeManager.isSupportedDevice()) {
            val pending = pendingSpeech
            pendingSpeech = null
            val errorMsg = "Thiết bị hiện tại không hỗ trợ VieNeu-TTS (yêu cầu arm64-v8a)"
            onError(errorMsg)
            pending?.onError?.invoke(errorMsg)
            return
        }

        if (!runtimeManager.isModelReady()) {
            val pending = pendingSpeech
            pendingSpeech = null
            val errorMsg = "Gói giọng đọc VieNeu-TTS v3-Nano chưa được tải xuống"
            onError(errorMsg)
            pending?.onError?.invoke(errorMsg)
            return
        }

        isInitializing = true
        val threadsSnapshot = currentThreads
        initializeJob = ensureScope().launch {
            try {
                runtimeManager.loadVoiceCatalog()
                runtimeManager.ensureLoaded(threadsSnapshot)
                isEngineReady = true
                isInitializing = false
                onReady()
                playPendingSpeech()
            } catch (_: CancellationException) {
                isInitializing = false
            } catch (error: Throwable) {
                isInitializing = false
                isEngineReady = false
                val pending = pendingSpeech
                pendingSpeech = null
                val errorMsg = error.message ?: "Không thể khởi tạo VieNeu-TTS v3-Nano"
                onError(errorMsg)
                pending?.onError?.invoke(errorMsg)
            }
        }
    }

    /**
     * Đọc một [TtsChunk] (thường tương ứng một câu/đoạn trên WebView) bằng VieNeu-TTS v3-Nano.
     *
     * @param chunk Đoạn văn bản cần đọc.
     * @param onChunkStart Callback gọi khi âm thanh câu bắt đầu đẩy ra [AudioTrack].
     * @param onChunkDone Callback gọi khi phần lời của câu đã phát xong thực tế qua loa.
     * @param onError Callback gọi nếu có lỗi khởi tạo hoặc tổng hợp.
     */
    override fun speak(
        chunk: TtsChunk,
        onChunkStart: (Int) -> Unit,
        onChunkDone: (Int) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (!currentLanguage.equals("vi", ignoreCase = true)) {
            onError("VieNeu-TTS hiện chỉ hỗ trợ tiếng Việt")
            return
        }

        if (!isEngineReady) {
            pendingSpeech = PendingSpeech(chunk, onChunkStart, onChunkDone, onError)
            initialize(onReady = {}, onError = {})
            return
        }

        cancelActiveSpeakJob()

        val cancelFlag = AtomicBoolean(false)
        activeCancelFlag = cancelFlag
        val synthGate = CompletableDeferred<Unit>()
        currentSynthesisGate = synthGate

        val voiceIdSnapshot = currentVoiceId
        val speedSnapshot = currentSpeed
        val stepsSnapshot = currentSteps
        val cfgSnapshot = currentCfg
        val swaySnapshot = currentSway
        val threadsSnapshot = currentThreads
        val key = buildCacheKey(
            text = chunk.text,
            voiceId = voiceIdSnapshot,
            steps = stepsSnapshot,
            cfg = cfgSnapshot,
            sway = swaySnapshot,
            speed = speedSnapshot,
            threads = threadsSnapshot,
        )
        val cachedEntry = consumePrefetchedIfMatch(key)

        speakJob = ensureScope().launch {
            var completedNormally = false
            try {
                val isCancelled = { cancelFlag.get() || !isActive }
                val voice = runtimeManager.resolveVoice(voiceIdSnapshot)
                val cachedData = cachedEntry?.result?.await()

                val preparedChunks = if (cachedData != null) {
                    cachedData.chunks
                } else {
                    runtimeManager.prepareChunks(text = chunk.text, threads = threadsSnapshot)
                        .filter { it.phonemes.isNotBlank() }
                }

                if (preparedChunks.isEmpty()) {
                    synthGate.complete(Unit)
                    if (!isCancelled()) {
                        onChunkStart(chunk.id)
                        onChunkDone(chunk.id)
                    }
                    return@launch
                }

                val activeEpoch = pcmPlayer.ensureStarted()
                var lastSpeechEndFrame = 0L
                var prevWav: FloatArray? = null
                var nextSynthDeferred: Deferred<FloatArray>? = null

                for (index in preparedChunks.indices) {
                    if (isCancelled()) throw CancellationException()
                    val currentChunk = preparedChunks[index]

                    val wav = when {
                        index == 0 && cachedData?.firstAudio != null -> cachedData.firstAudio
                        nextSynthDeferred != null -> nextSynthDeferred.await()
                        else -> runtimeManager.synthesizeChunk(
                            phonemes = currentChunk.phonemes,
                            voice = voice,
                            steps = stepsSnapshot,
                            cfg = cfgSnapshot,
                            sway = swaySnapshot,
                            speed = speedSnapshot,
                            threads = threadsSnapshot,
                            isCancelled = isCancelled,
                        )
                    }
                    nextSynthDeferred = null

                    if (isCancelled()) throw CancellationException()

                    if (index + 1 < preparedChunks.size) {
                        val upcoming = preparedChunks[index + 1]
                        nextSynthDeferred = async {
                            runtimeManager.synthesizeChunk(
                                phonemes = upcoming.phonemes,
                                voice = voice,
                                steps = stepsSnapshot,
                                cfg = cfgSnapshot,
                                sway = swaySnapshot,
                                speed = speedSnapshot,
                                threads = threadsSnapshot,
                                isCancelled = isCancelled,
                            )
                        }
                    } else {
                        // Nhường khóa tổng hợp cho câu prefetch kế tiếp ngay khi chunk cuối bắt đầu phát.
                        synthGate.complete(Unit)
                    }

                    if (prevWav != null) {
                        val pauseSamples = VieNeuNanoEngine.computePausePadSamples(
                            prevWav = prevWav,
                            nextWav = wav,
                            gap = currentChunk.gapBefore,
                        )
                        if (pauseSamples > 0) {
                            val pauseFrame = pcmPlayer.writeSilence(pauseSamples, activeEpoch, isCancelled)
                            if (pauseFrame < 0L) throw CancellationException()
                        }
                    }

                    if (index == 0) {
                        onChunkStart(chunk.id)
                    }

                    if (wav.isNotEmpty()) {
                        val frameAfterSpeech = pcmPlayer.writeSamples(wav, activeEpoch, isCancelled)
                        if (frameAfterSpeech < 0L) throw CancellationException()
                        lastSpeechEndFrame = frameAfterSpeech
                    }

                    if (index == preparedChunks.lastIndex) {
                        val tailFrame = pcmPlayer.writeSilence(TRAILING_SILENCE_SAMPLES, activeEpoch, isCancelled)
                        if (tailFrame < 0L) throw CancellationException()
                    }
                    prevWav = wav
                }

                synthGate.complete(Unit)
                completedNormally = pcmPlayer.awaitFramePlayed(
                    targetFrame = lastSpeechEndFrame,
                    expectedEpoch = activeEpoch,
                    isCancelled = isCancelled,
                )
            } catch (_: CancellationException) {
                completedNormally = false
            } catch (error: Throwable) {
                synthGate.complete(Unit)
                if (!cancelFlag.get() && isActive) {
                    onError(error.message ?: "Lỗi tổng hợp giọng VieNeu-TTS")
                }
            } finally {
                synthGate.complete(Unit)
            }

            if (completedNormally && !cancelFlag.get() && isActive) {
                onChunkDone(chunk.id)
            }
        }
    }

    /**
     * Bắt đầu chuẩn hoá G2P và tổng hợp trước chunk đầu tiên của câu kế tiếp để phát nối tiếp mượt mà.
     *
     * @param chunk Câu kế tiếp cần tổng hợp trước.
     */
    override fun prefetch(chunk: TtsChunk) {
        if (!isEngineReady || chunk.text.isBlank()) return
        if (!currentLanguage.equals("vi", ignoreCase = true)) return

        val voiceIdSnapshot = currentVoiceId
        val speedSnapshot = currentSpeed
        val stepsSnapshot = currentSteps
        val cfgSnapshot = currentCfg
        val swaySnapshot = currentSway
        val threadsSnapshot = currentThreads
        val key = buildCacheKey(
            text = chunk.text,
            voiceId = voiceIdSnapshot,
            steps = stepsSnapshot,
            cfg = cfgSnapshot,
            sway = swaySnapshot,
            speed = speedSnapshot,
            threads = threadsSnapshot,
        )

        synchronized(prefetchLock) {
            if (prefetchedUtterance?.key == key) return
            cancelPrefetchEntryLocked()

            val cancelFlag = AtomicBoolean(false)
            val gateToAwait = currentSynthesisGate
            val deferred = CompletableDeferred<PrefetchedData>()
            val job = ensureScope().launch {
                try {
                    gateToAwait?.await()
                    val isCancelled = { cancelFlag.get() || !isActive }
                    if (isCancelled()) {
                        deferred.cancel()
                        return@launch
                    }
                    val voice = runtimeManager.resolveVoice(voiceIdSnapshot)
                    val prepared = runtimeManager.prepareChunks(text = chunk.text, threads = threadsSnapshot)
                        .filter { it.phonemes.isNotBlank() }
                    if (prepared.isEmpty()) {
                        deferred.complete(PrefetchedData(emptyList(), null))
                        return@launch
                    }
                    val firstAudio = runtimeManager.synthesizeChunk(
                        phonemes = prepared.first().phonemes,
                        voice = voice,
                        steps = stepsSnapshot,
                        cfg = cfgSnapshot,
                        sway = swaySnapshot,
                        speed = speedSnapshot,
                        threads = threadsSnapshot,
                        isCancelled = isCancelled,
                    )
                    deferred.complete(PrefetchedData(prepared, firstAudio))
                } catch (cancel: CancellationException) {
                    deferred.cancel(cancel)
                } catch (_: Throwable) {
                    deferred.cancel()
                }
            }
            prefetchedUtterance = PrefetchedUtteranceEntry(
                key = key,
                cancelFlag = cancelFlag,
                job = job,
                result = deferred,
            )
        }
    }

    /**
     * Tạm dừng phát ngay lập tức và xoá phần đệm đang chờ trong [AudioTrack].
     */
    override fun pause() = stopPlayback()

    /**
     * Tiếp tục phát không cần xử lý riêng vì `TtsService` gọi lại `speak()` từ câu đang dừng.
     */
    override fun resume() = Unit

    /**
     * Dừng hẳn tác vụ tổng hợp và phát âm thanh hiện tại.
     */
    override fun stop() = stopPlayback()

    /**
     * Cập nhật ngôn ngữ đọc; nếu thay đổi ngôn ngữ sẽ huỷ bộ đệm prefetch và phát hiện tại.
     *
     * @param language Mã ngôn ngữ (hiện hỗ trợ `"vi"`).
     */
    override fun setLanguage(language: String) {
        val normalized = language.lowercase()
        if (currentLanguage != normalized) {
            currentLanguage = normalized
            stopPlayback()
        }
    }

    /**
     * Cập nhật tốc độ đọc cho các lần tổng hợp kế tiếp.
     *
     * @param speed Tốc độ đọc (trong khoảng `0.5f..2.0f`).
     */
    override fun setSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.5f, 2.0f)
        if (currentSpeed != clamped) {
            currentSpeed = clamped
            clearPrefetch()
        }
    }

    /**
     * Bỏ qua thay đổi pitch vì model neural tự nhiên giữ cao độ tự nhiên ở `1.0f`.
     *
     * @param pitch Hệ số cao độ (không áp dụng cho VieNeu v3-Nano).
     */
    override fun setPitch(pitch: Float) = Unit

    /**
     * Chọn giọng đọc preset trong catalog VieNeu-TTS v3-Nano.
     *
     * @param voiceId Mã giọng có tiền tố `"vieneu:"`, hoặc `null` để về giọng mặc định.
     */
    override fun setVoice(voiceId: String?) {
        val resolved = voiceId?.trim()?.takeIf { TtsSettings.isVieNeuVoiceId(it) }
        if (currentVoiceId != resolved) {
            currentVoiceId = resolved
            clearPrefetch()
        }
    }

    /**
     * Cập nhật bộ tham số tổng hợp riêng của VieNeu-TTS v3-Nano (`steps`, `cfg`, `sway`, `threads`).
     *
     * @param steps Số bước Euler (`4..24`).
     * @param cfg Hệ số CFG (`0.0f..5.0f`).
     * @param sway Hệ số dịch lịch bước Euler (`-1.0f..0.0f`).
     * @param threads Số luồng CPU cho ONNX Runtime (`2` hoặc `4`).
     */
    fun setSynthesisConfig(
        steps: Int,
        cfg: Float,
        sway: Float,
        threads: Int,
    ) {
        val normalizedSteps = steps.coerceIn(TtsSettings.MIN_VIENEU_STEPS, TtsSettings.MAX_VIENEU_STEPS)
        val normalizedCfg = cfg.coerceIn(TtsSettings.MIN_VIENEU_CFG, TtsSettings.MAX_VIENEU_CFG)
        val normalizedSway = sway.coerceIn(TtsSettings.MIN_VIENEU_SWAY, TtsSettings.MAX_VIENEU_SWAY)
        val normalizedThreads = if (threads <= 2) 2 else 4

        val changed = currentSteps != normalizedSteps ||
            currentCfg != normalizedCfg ||
            currentSway != normalizedSway ||
            currentThreads != normalizedThreads
        if (changed) {
            currentSteps = normalizedSteps
            currentCfg = normalizedCfg
            currentSway = normalizedSway
            currentThreads = normalizedThreads
            clearPrefetch()
        }
    }

    /**
     * Trả về danh sách 11 giọng preset VieNeu v3-Nano nếu thiết bị hỗ trợ `arm64-v8a` và đúng ngôn ngữ.
     *
     * @param language Mã ngôn ngữ cần lọc (chỉ hỗ trợ `"vi"`).
     * @return Danh sách [TtsVoice] với `aiBackend = AiTtsBackend.VIENEU_NANO`.
     */
    override fun getAvailableVoices(language: String): List<TtsVoice> {
        if (!runtimeManager.isSupportedDevice()) return emptyList()
        val isReady = runtimeManager.isModelReady()
        return TtsVoiceCatalog.vieNeuForLanguage(language).map { preset ->
            TtsVoice(
                id = preset.id,
                name = preset.displayName,
                language = preset.language,
                isNetworkRequired = !isReady,
                isDownloaded = isReady,
                aiBackend = AiTtsBackend.VIENEU_NANO,
                description = preset.description,
            )
        }
    }

    /**
     * Dừng phát, giải phóng `AudioTrack` và yêu cầu giải phóng session ONNX nếu service kết thúc.
     */
    override fun shutdown() {
        stopPlayback()
        isEngineReady = false
        pcmPlayer.close()
        engineScope.cancel()
        CoroutineScope(ioDispatcher).launch {
            runtimeManager.unload()
        }
    }

    /**
     * Tiếp tục phát đoạn [pendingSpeech] ngay khi quá trình khởi tạo engine hoàn tất.
     */
    private fun playPendingSpeech() {
        val pending = pendingSpeech ?: return
        pendingSpeech = null
        speak(pending.chunk, pending.onChunkStart, pending.onChunkDone, pending.onError)
    }

    /**
     * Dừng toàn bộ coroutine tổng hợp, prefetch và xả buffer `AudioTrack` ngay lập tức.
     */
    private fun stopPlayback() {
        pendingSpeech = null
        clearPrefetch()
        cancelActiveSpeakJob()
        initializeJob?.cancel()
        initializeJob = null
        isInitializing = false
        pcmPlayer.pauseAndFlush()
    }

    /**
     * Huỷ coroutine `speak` hiện tại và bật cờ hủy hợp tác cho vòng lặp Euler.
     */
    private fun cancelActiveSpeakJob() {
        activeCancelFlag?.set(true)
        activeCancelFlag = null
        currentSynthesisGate?.complete(Unit)
        currentSynthesisGate = null
        speakJob?.cancel()
        speakJob = null
    }

    /**
     * Lấy kết quả prefetch nếu khóa tham số khớp hoàn toàn, và bỏ qua nếu không khớp.
     *
     * @param key Chuỗi khóa đại diện đầy đủ văn bản và tham số tổng hợp.
     * @return [PrefetchedUtteranceEntry] nếu khớp, ngược lại `null`.
     */
    private fun consumePrefetchedIfMatch(key: String): PrefetchedUtteranceEntry? =
        synchronized(prefetchLock) {
            val current = prefetchedUtterance ?: return@synchronized null
            prefetchedUtterance = null
            if (current.key == key) {
                current
            } else {
                current.cancelFlag.set(true)
                current.job.cancel()
                current.result.cancel()
                null
            }
        }

    /**
     * Xoá mục prefetch hiện tại nếu tham số giọng/tốc độ thay đổi.
     */
    private fun clearPrefetch() {
        synchronized(prefetchLock) {
            cancelPrefetchEntryLocked()
        }
    }

    /**
     * Huỷ mục prefetch dưới [prefetchLock].
     */
    private fun cancelPrefetchEntryLocked() {
        prefetchedUtterance?.let { entry ->
            entry.cancelFlag.set(true)
            entry.job.cancel()
            entry.result.cancel()
        }
        prefetchedUtterance = null
    }

    /**
     * Đảm bảo [engineScope] vẫn còn hoạt động (khởi tạo lại nếu trước đó đã từng `shutdown()`).
     *
     * @return [CoroutineScope] đang hoạt động trên [ioDispatcher].
     */
    private fun ensureScope(): CoroutineScope {
        if (!engineScope.isActive) {
            engineScope = CoroutineScope(ioDispatcher + SupervisorJob())
        }
        return engineScope
    }

    /**
     * Tạo khóa cache duy nhất gắn đủ văn bản + giọng + tham số để tránh phát nhầm audio cũ khi đổi cấu hình.
     */
    private fun buildCacheKey(
        text: String,
        voiceId: String?,
        steps: Int,
        cfg: Float,
        sway: Float,
        speed: Float,
        threads: Int,
    ): String = "$voiceId|$steps|$cfg|$sway|$speed|$threads|$text"

    companion object {
        // Đệm 160ms im lặng cuối câu để hardware AudioTrack không underrun khi TtsService xếp câu kế tiếp.
        private const val TRAILING_SILENCE_SAMPLES = (VieNeuNanoEngine.SAMPLE_RATE * 160) / 1_000
    }
}
