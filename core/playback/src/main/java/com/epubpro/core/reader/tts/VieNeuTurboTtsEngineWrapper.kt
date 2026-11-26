package com.epubpro.core.reader.tts

import com.epubpro.core.designsystem.R
import com.epubpro.core.tts.ChunkGap
import com.epubpro.core.tts.PreparedChunk
import com.epubpro.core.tts.TtsVoiceCatalog
import com.epubpro.core.tts.VieNeuNanoEngine
import com.epubpro.core.tts.VieNeuPcmPlayer
import com.epubpro.core.tts.VieNeuTurboEngine
import com.epubpro.core.tts.VieNeuTurboParams
import com.epubpro.core.tts.VieNeuTurboRuntimeManager
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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Adapter [TtsEngine] cho backend VieNeu-TTS v3 Turbo trong `TtsService`.
 *
 * - Tách câu theo cùng luồng văn bản v3 với Nano (`VieNeuTextChunker`).
 * - Phát streaming: mỗi mảnh PCM 48 kHz được ghi ra [VieNeuPcmPlayer] ngay khi codec giải mã xong, nên câu
 *   đầu bắt đầu đọc sau khoảng 0,3 giây.
 * - Khoảng nghỉ giữa các chunk theo bảng v3 (0,7/0,5/0,3 giây) trừ phần im lặng model tự sinh.
 * - Tốc độ đọc áp bằng time-stretch của `AudioTrack` vì Turbo không có tham số tốc độ.
 * - Prefetch chunk đầu của câu kế tiếp sau khi câu hiện tại tổng hợp xong.
 *
 * @property runtimeManager Runtime dùng chung của Turbo.
 * @property pcmPlayer Bộ phát PCM float32 48 kHz.
 * @property ioDispatcher Dispatcher cho tổng hợp và phát.
 * @property messages Nguồn chuỗi thông báo lỗi.
 */
@Singleton
class VieNeuTurboTtsEngineWrapper internal constructor(
    private val runtimeManager: VieNeuTurboRuntimeManager,
    private val pcmPlayer: VieNeuPcmPlayer,
    private val ioDispatcher: CoroutineDispatcher,
    private val messages: TtsMessageResolver,
) : TtsEngine {
    @Inject
    constructor(
        runtimeManager: VieNeuTurboRuntimeManager,
        messages: TtsMessageResolver,
    ) : this(
        runtimeManager = runtimeManager,
        pcmPlayer = VieNeuPcmPlayer(sampleRate = VieNeuTurboEngine.SAMPLE_RATE),
        ioDispatcher = Dispatchers.IO,
        messages = messages,
    )

    private var engineScope = CoroutineScope(ioDispatcher + SupervisorJob())
    private var initializeJob: Job? = null
    private var speakJob: Job? = null
    private var activeCancelFlag: AtomicBoolean? = null
    private var currentSynthesisGate: CompletableDeferred<Unit>? = null

    private var currentLanguage: String = "vi"
    private var currentVoiceId: String? = TtsVoiceCatalog.DEFAULT_VIENEU_TURBO_VOICE_ID
    private var currentSpeed: Float = 1.0f
    private var currentThreads: Int = VieNeuTurboEngine.DEFAULT_THREADS

    @Volatile
    private var isEngineReady: Boolean = false

    @Volatile
    private var isInitializing: Boolean = false

    private var pendingSpeech: PendingSpeech? = null
    private val prefetchLock = Any()
    private var prefetched: PrefetchEntry? = null

    private data class PendingSpeech(
        val chunk: TtsChunk,
        val onChunkStart: (Int) -> Unit,
        val onChunkDone: (Int) -> Unit,
        val onError: (String) -> Unit,
    )

    private class PrefetchData(val chunks: List<PreparedChunk>, val firstAudio: FloatArray?)

    private class PrefetchEntry(
        val key: String,
        val cancelFlag: AtomicBoolean,
        val job: Job,
        val result: Deferred<PrefetchData>,
    )

    /**
     * Khởi tạo runtime Turbo ở nền (giải phóng Nano trước khi nạp).
     *
     * @param onReady Callback khi engine sẵn sàng.
     * @param onError Callback khi thiết bị không hỗ trợ, chưa tải model hoặc nạp lỗi.
     */
    override fun initialize(onReady: () -> Unit, onError: (String) -> Unit) {
        if (isEngineReady) {
            onReady()
            playPendingSpeech()
            return
        }
        if (isInitializing) return
        val failure = when {
            !runtimeManager.isSupportedDevice() -> R.string.tts_error_vieneu_unsupported_device
            !runtimeManager.isModelReady() -> R.string.tts_error_vieneu_turbo_not_downloaded
            else -> null
        }
        if (failure != null) {
            val pending = pendingSpeech
            pendingSpeech = null
            val message = messages.getString(failure)
            onError(message)
            pending?.onError?.invoke(message)
            return
        }
        isInitializing = true
        val threads = currentThreads
        initializeJob = ensureScope().launch {
            try {
                runtimeManager.awaitPendingUnload()
                runtimeManager.loadVoices()
                runtimeManager.ensureLoaded(threads)
                isEngineReady = true
                isInitializing = false
                onReady()
                playPendingSpeech()
            } catch (_: CancellationException) {
                isInitializing = false
            } catch (_: Throwable) {
                isInitializing = false
                isEngineReady = false
                val pending = pendingSpeech
                pendingSpeech = null
                val message = messages.getString(R.string.tts_error_vieneu_turbo_init_failed)
                onError(message)
                pending?.onError?.invoke(message)
            }
        }
    }

    /**
     * Đọc một [TtsChunk] bằng VieNeu Turbo, phát streaming.
     *
     * @param chunk Đoạn văn bản cần đọc.
     * @param onChunkStart Gọi khi mảnh audio đầu tiên được ghi ra loa.
     * @param onChunkDone Gọi khi phần lời đã phát xong thực tế.
     * @param onError Gọi khi tổng hợp lỗi.
     */
    override fun speak(
        chunk: TtsChunk,
        onChunkStart: (Int) -> Unit,
        onChunkDone: (Int) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (!currentLanguage.equals("vi", ignoreCase = true)) {
            onError(messages.getString(R.string.tts_error_vieneu_language_unsupported))
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

        val voiceId = currentVoiceId
        val threads = currentThreads
        val cached = consumePrefetchedIfMatch(buildKey(chunk.text, voiceId, threads))

        speakJob = ensureScope().launch {
            var completedNormally = false
            try {
                val isCancelled = { cancelFlag.get() || !isActive }
                val voice = runtimeManager.resolveVoice(voiceId)
                val cachedData = cached?.let {
                    try {
                        it.result.await()
                    } catch (cancelled: CancellationException) {
                        if (isCancelled()) throw cancelled
                        null
                    } catch (_: Exception) {
                        null
                    }
                }
                val prepared = cachedData?.chunks
                    ?: runtimeManager.prepareChunks(text = chunk.text, threads = threads).filter { it.phonemes.isNotBlank() }
                if (prepared.isEmpty()) {
                    synthGate.complete(Unit)
                    if (!isCancelled()) {
                        onChunkStart(chunk.id)
                        onChunkDone(chunk.id)
                    }
                    return@launch
                }

                pcmPlayer.setPlaybackSpeed(currentSpeed)
                val epoch = pcmPlayer.ensureStarted()
                var started = false
                var lastSpeechEndFrame = 0L
                var previousTail: FloatArray? = null

                /** Ghi một mảnh PCM: chèn khoảng nghỉ trước mảnh đầu của chunk (trừ chunk đầu). */
                fun writePiece(pcm: FloatArray, gap: ChunkGap, firstPieceOfChunk: Boolean) {
                    if (pcm.isEmpty()) return
                    if (firstPieceOfChunk && previousTail != null && gap != ChunkGap.FIRST) {
                        val pause = VieNeuNanoEngine.computePausePadSamples(previousTail!!, pcm, gap, VieNeuTurboEngine.SAMPLE_RATE)
                        if (pause > 0 && pcmPlayer.writeSilence(pause, epoch, isCancelled) < 0L) throw CancellationException()
                    }
                    if (!started) {
                        started = true
                        onChunkStart(chunk.id)
                    }
                    val end = pcmPlayer.writeSamples(pcm, epoch, isCancelled)
                    if (end < 0L) throw CancellationException()
                    lastSpeechEndFrame = end
                    previousTail = pcm
                }

                for ((index, part) in prepared.withIndex()) {
                    if (isCancelled()) throw CancellationException()
                    if (index == 0 && cachedData?.firstAudio != null) {
                        writePiece(cachedData.firstAudio, part.gapBefore, firstPieceOfChunk = true)
                        continue
                    }
                    var first = true
                    runtimeManager.synthesizeChunk(
                        phonemes = part.phonemes,
                        voice = voice,
                        params = VieNeuTurboParams(),
                        threads = threads,
                        isCancelled = isCancelled,
                    ) { pcm ->
                        writePiece(pcm, part.gapBefore, firstPieceOfChunk = first)
                        if (pcm.isNotEmpty()) first = false
                    }
                }
                synthGate.complete(Unit)
                if (pcmPlayer.writeSilence(TRAILING_SILENCE_SAMPLES, epoch, isCancelled) < 0L) throw CancellationException()
                if (!started) onChunkStart(chunk.id)
                completedNormally = pcmPlayer.awaitFramePlayed(
                    targetFrame = lastSpeechEndFrame,
                    expectedEpoch = epoch,
                    isCancelled = isCancelled,
                )
            } catch (_: CancellationException) {
                completedNormally = false
            } catch (_: Throwable) {
                synthGate.complete(Unit)
                if (!cancelFlag.get() && isActive) onError(messages.getString(R.string.tts_error_vieneu_synthesis_failed))
            } finally {
                synthGate.complete(Unit)
            }
            if (completedNormally && !cancelFlag.get() && isActive) onChunkDone(chunk.id)
        }
    }

    /**
     * Tổng hợp trước chunk đầu của câu kế tiếp, sau khi câu hiện tại tổng hợp xong.
     *
     * @param chunk Câu kế tiếp.
     */
    override fun prefetch(chunk: TtsChunk) {
        if (!isEngineReady || chunk.text.isBlank()) return
        if (!currentLanguage.equals("vi", ignoreCase = true)) return
        val voiceId = currentVoiceId
        val threads = currentThreads
        val key = buildKey(chunk.text, voiceId, threads)
        synchronized(prefetchLock) {
            if (prefetched?.key == key) return
            cancelPrefetchLocked()
            val cancelFlag = AtomicBoolean(false)
            val gate = currentSynthesisGate
            val deferred = CompletableDeferred<PrefetchData>()
            val job = ensureScope().launch {
                try {
                    gate?.await()
                    val isCancelled = { cancelFlag.get() || !isActive }
                    if (isCancelled()) {
                        deferred.cancel()
                        return@launch
                    }
                    val voice = runtimeManager.resolveVoice(voiceId)
                    val prepared = runtimeManager.prepareChunks(text = chunk.text, threads = threads)
                        .filter { it.phonemes.isNotBlank() }
                    if (prepared.isEmpty()) {
                        deferred.complete(PrefetchData(emptyList(), null))
                        return@launch
                    }
                    val pieces = ArrayList<FloatArray>()
                    runtimeManager.synthesizeChunk(prepared.first().phonemes, voice, VieNeuTurboParams(), threads, isCancelled) { pieces += it }
                    if (isCancelled()) {
                        deferred.cancel()
                        return@launch
                    }
                    val audio = FloatArray(pieces.sumOf { it.size }).also { out ->
                        var o = 0
                        pieces.forEach { p -> p.copyInto(out, o); o += p.size }
                    }
                    deferred.complete(PrefetchData(prepared, audio))
                } catch (cancel: CancellationException) {
                    deferred.cancel(cancel)
                } catch (_: Exception) {
                    deferred.cancel()
                }
            }
            prefetched = PrefetchEntry(key, cancelFlag, job, deferred)
        }
    }

    /** Tạm dừng ngay và xả đệm `AudioTrack`; `TtsService` sẽ gọi lại [speak] khi tiếp tục. */
    override fun pause() = stopPlayback()

    /** Không cần xử lý: `TtsService` đọc lại câu đang dừng. */
    override fun resume() = Unit

    /** Dừng tổng hợp và phát hiện tại. */
    override fun stop() = stopPlayback()

    /**
     * Cập nhật ngôn ngữ; Turbo chỉ đọc tiếng Việt (kèm đoạn tiếng Anh trong câu).
     *
     * @param language Mã ngôn ngữ.
     */
    override fun setLanguage(language: String) {
        val normalized = language.lowercase()
        if (currentLanguage != normalized) {
            currentLanguage = normalized
            stopPlayback()
        }
    }

    /**
     * Cập nhật tốc độ đọc, áp ngay lên `AudioTrack` đang phát (không cần tổng hợp lại).
     *
     * @param speed Tốc độ đọc (`0.5f..2.0f`).
     */
    override fun setSpeed(speed: Float) {
        currentSpeed = speed.coerceIn(0.5f, 2.0f)
        pcmPlayer.setPlaybackSpeed(currentSpeed)
    }

    /**
     * Bỏ qua cao độ: model neural giữ cao độ tự nhiên.
     *
     * @param pitch Hệ số cao độ (không áp dụng).
     */
    override fun setPitch(pitch: Float) = Unit

    /**
     * Chọn giọng Turbo trong catalog.
     *
     * @param voiceId Mã giọng có tiền tố `"vieneu-turbo:"`, hoặc `null` để dùng giọng mặc định.
     */
    override fun setVoice(voiceId: String?) {
        val resolved = voiceId?.trim()?.takeIf { TtsSettings.isVieNeuTurboVoiceId(it) }
        if (currentVoiceId != resolved) {
            currentVoiceId = resolved
            clearPrefetch()
        }
    }

    /**
     * Cập nhật số luồng ONNX Runtime cho các lần tổng hợp kế tiếp.
     *
     * @param threads Số luồng (`2` hoặc `4`).
     */
    fun setThreads(threads: Int) {
        val normalized = if (threads <= 2) 2 else 4
        if (currentThreads != normalized) {
            currentThreads = normalized
            clearPrefetch()
        }
    }

    /**
     * Danh sách giọng Turbo nếu thiết bị hỗ trợ.
     *
     * @param language Mã ngôn ngữ (chỉ `"vi"`).
     * @return Danh sách [TtsVoice] với `aiBackend = AiTtsBackend.VIENEU_TURBO`.
     */
    override fun getAvailableVoices(language: String): List<TtsVoice> {
        if (!runtimeManager.isSupportedDevice() || !language.equals("vi", ignoreCase = true)) return emptyList()
        val ready = runtimeManager.isModelReady()
        return TtsVoiceCatalog.vieNeuTurboVoices.map { preset ->
            TtsVoice(
                id = preset.id,
                name = preset.displayName,
                language = preset.language,
                isNetworkRequired = !ready,
                isDownloaded = ready,
                aiBackend = AiTtsBackend.VIENEU_TURBO,
                description = preset.description,
            )
        }
    }

    /** Dừng phát, đóng `AudioTrack` và lập lịch giải phóng engine. */
    override fun shutdown() {
        stopPlayback()
        isEngineReady = false
        pcmPlayer.close()
        engineScope.cancel()
        runtimeManager.scheduleUnload()
    }

    /**
     * Giải phóng engine Turbo khi `TtsService` chuyển sang engine khác, để Turbo không giữ RAM khi không dùng.
     */
    fun releaseForEngineSwitch() {
        stopPlayback()
        isEngineReady = false
        runtimeManager.scheduleUnload()
    }

    private fun playPendingSpeech() {
        val pending = pendingSpeech ?: return
        pendingSpeech = null
        speak(pending.chunk, pending.onChunkStart, pending.onChunkDone, pending.onError)
    }

    private fun stopPlayback() {
        pendingSpeech = null
        clearPrefetch()
        cancelActiveSpeakJob()
        initializeJob?.cancel()
        initializeJob = null
        isInitializing = false
        pcmPlayer.pauseAndFlush()
    }

    private fun cancelActiveSpeakJob() {
        activeCancelFlag?.set(true)
        activeCancelFlag = null
        currentSynthesisGate?.complete(Unit)
        currentSynthesisGate = null
        speakJob?.cancel()
        speakJob = null
    }

    private fun consumePrefetchedIfMatch(key: String): PrefetchEntry? = synchronized(prefetchLock) {
        val current = prefetched ?: return@synchronized null
        prefetched = null
        if (current.key == key) {
            current
        } else {
            current.cancelFlag.set(true)
            current.job.cancel()
            current.result.cancel()
            null
        }
    }

    private fun clearPrefetch() = synchronized(prefetchLock) { cancelPrefetchLocked() }

    private fun cancelPrefetchLocked() {
        prefetched?.let {
            it.cancelFlag.set(true)
            it.job.cancel()
            it.result.cancel()
        }
        prefetched = null
    }

    private fun ensureScope(): CoroutineScope {
        if (!engineScope.isActive) engineScope = CoroutineScope(ioDispatcher + SupervisorJob())
        return engineScope
    }

    /** Khoá prefetch theo văn bản + giọng + số luồng (tốc độ áp lúc phát nên không vào khoá). */
    private fun buildKey(text: String, voiceId: String?, threads: Int): String = "$voiceId|$threads|$text"

    companion object {
        // Đệm 160 ms im lặng cuối câu để AudioTrack không underrun khi TtsService xếp câu kế tiếp.
        private const val TRAILING_SILENCE_SAMPLES = (VieNeuTurboEngine.SAMPLE_RATE * 160) / 1_000
    }
}
