package com.epubpro.core.reader.tts

import com.epubpro.core.tts.SherpaTtsEngine
import com.epubpro.core.tts.TtsVoiceCatalog
import com.epubpro.core.tts.VoiceModelDownloader
import com.epubpro.domain.model.AiTtsBackend
import com.epubpro.domain.model.TtsChunk
import com.epubpro.domain.model.TtsVoice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Lớp bọc bộ đọc TTS Piper / Sherpa-ONNX AI Offline.
 *
 * Quản lý vòng đời khởi tạo engine, đồng bộ hóa quá trình tổng hợp âm thanh PCM,
 * prefetch đoạn văn tiếp theo và xử lý an toàn các lỗi khởi tạo hoặc hủy tác vụ.
 *
 * @property sherpaTtsEngine Engine Sherpa-ONNX thực hiện tổng hợp PCM native.
 * @property downloader Trình tải và quản lý tệp giọng đọc AI offline.
 */
class PiperTtsEngineWrapper @Inject constructor(
    private val sherpaTtsEngine: SherpaTtsEngine,
    private val downloader: VoiceModelDownloader
) : TtsEngine {

    private val engineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isEngineReady = false
    private var currentVoiceId: String? = null
    private var currentLanguage = "vi"
    private var currentSpeed = 1.0f

    private var speakJob: Job? = null
    private var initializeJob: Job? = null
    private var isInitializing = false
    private var onReadyCallback: (() -> Unit)? = null
    private var onErrorCallback: ((String) -> Unit)? = null
    private var pendingSpeech: PendingSpeech? = null
    private val prefetchLock = Any()

    private var prefetchedAudio: PrefetchedAudio? = null

    private data class PrefetchedAudio(
        val key: String,
        val audio: Deferred<ByteArray>
    )

    private data class PendingSpeech(
        val chunk: TtsChunk,
        val onChunkStart: (Int) -> Unit,
        val onChunkDone: (Int) -> Unit,
        val onError: (String) -> Unit
    )

    /**
     * Khởi tạo engine Piper TTS với tệp model AI offline đã tải về.
     *
     * @param onReady Callback được gọi khi engine đã sẵn sàng phát âm thanh.
     * @param onError Callback được gọi khi có lỗi trong quá trình khởi tạo (ví dụ thiếu model).
     */
    override fun initialize(onReady: () -> Unit, onError: (String) -> Unit) {
        val passedReady = onReady
        val passedError = onError
        onReadyCallback = passedReady
        onErrorCallback = passedError
        if (isEngineReady) {
            passedReady()
            playPendingSpeech()
            return
        }
        if (isInitializing) return

        val voiceId = currentVoiceId
        if (voiceId == null) {
            val pending = pendingSpeech
            pendingSpeech = null
            val errorMsg = "Chưa chọn giọng AI Offline"
            passedError(errorMsg)
            pending?.onError?.invoke(errorMsg)
            return
        }
        if (TtsVoiceCatalog.find(voiceId)?.language != currentLanguage) {
            val pending = pendingSpeech
            pendingSpeech = null
            val errorMsg = "Giọng AI không hỗ trợ ngôn ngữ: $currentLanguage"
            passedError(errorMsg)
            pending?.onError?.invoke(errorMsg)
            return
        }

        isInitializing = true
        initializeJob = engineScope.launch {
            try {
                if (!downloader.isModelDownloaded(voiceId)) {
                    val pending = pendingSpeech
                    pendingSpeech = null
                    val errorMsg = "Voice model not downloaded yet: $voiceId"
                    onErrorCallback?.invoke(errorMsg)
                    pending?.onError?.invoke(errorMsg)
                    return@launch
                }
                if (!downloader.isEspeakDataReady()) {
                    downloader.downloadEspeakNgDataIfNeeded()
                }
                sherpaTtsEngine.initialize(
                    onnxPath = downloader.getModelPath(voiceId),
                    tokensPath = downloader.getTokensPath(voiceId),
                    dataDirPath = downloader.getEspeakDataDir()
                )
                isEngineReady = true
                onReadyCallback?.invoke()
                playPendingSpeech()
            } catch (error: Exception) {
                if (error !is CancellationException) {
                    val pending = pendingSpeech
                    pendingSpeech = null
                    val errorMsg = "Failed to initialize Piper TTS: ${error.message}"
                    onErrorCallback?.invoke(errorMsg)
                    pending?.onError?.invoke(errorMsg)
                }
            } finally {
                isInitializing = false
                initializeJob = null
            }
        }
    }

    /**
     * Nạp trước (prefetch) dữ liệu PCM âm thanh cho đoạn văn bản tiếp theo để phát liền mạch.
     *
     * @param chunk Đoạn văn bản TTS cần nạp trước.
     */
    override fun prefetch(chunk: TtsChunk) {
        if (!isEngineReady || currentVoiceId == null) return
        val speed = currentSpeed
        val key = prefetchKey(chunk, speed)
        synchronized(prefetchLock) {
            if (prefetchedAudio?.key == key) return
            prefetchedAudio?.audio?.cancel()
            prefetchedAudio = PrefetchedAudio(
                key = key,
                audio = engineScope.async { sherpaTtsEngine.synthesize(chunk.text, speed) }
            )
        }
    }

    /**
     * Bắt đầu đọc đoạn văn bản TTS. Nếu engine chưa sẵn sàng, yêu cầu phát sẽ được xếp hàng chờ
     * và tự động phát ngay khi khởi tạo hoàn tất.
     *
     * @param chunk Đoạn văn bản TTS cần phát.
     * @param onChunkStart Callback gọi khi bắt đầu phát âm thanh đoạn văn.
     * @param onChunkDone Callback gọi khi đoạn văn đã đọc xong hoàn tất.
     * @param onError Callback gọi khi gặp lỗi trong quá trình tổng hợp hoặc phát.
     */
    override fun speak(
        chunk: TtsChunk,
        onChunkStart: (Int) -> Unit,
        onChunkDone: (Int) -> Unit,
        onError: (String) -> Unit
    ) {
        if (currentVoiceId == null) {
            onError("Chưa chọn giọng AI Offline")
            return
        }
        if (!isEngineReady) {
            pendingSpeech = PendingSpeech(chunk, onChunkStart, onChunkDone, onError)
            val currentOnReady = onReadyCallback
            val currentOnError = onErrorCallback
            initialize(
                onReady = {
                    currentOnReady?.invoke()
                },
                onError = { errorMsg ->
                    currentOnError?.invoke(errorMsg)
                    val pending = pendingSpeech
                    pendingSpeech = null
                    pending?.onError?.invoke(errorMsg)
                }
            )
            return
        }

        speakJob?.cancel()
        speakJob = engineScope.launch {
            var completedNormally = false
            try {
                val speed = currentSpeed
                val cached = takePrefetched(prefetchKey(chunk, speed))
                val pcm = try {
                    cached?.audio?.await() ?: sherpaTtsEngine.synthesize(chunk.text, speed)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    cached?.audio?.cancel()
                    sherpaTtsEngine.synthesize(chunk.text, speed)
                }
                sherpaTtsEngine.playPcm(
                    pcm = pcm,
                    onAudioStarted = { onChunkStart(chunk.id) }
                )
                completedNormally = true
            } catch (error: Exception) {
                if (error !is CancellationException) {
                    onError(error.message ?: "Không thể phát giọng AI Offline")
                }
            } finally {
                if (completedNormally) onChunkDone(chunk.id)
            }
        }
    }

    private fun prefetchKey(chunk: TtsChunk, speed: Float): String =
        "${chunk.id}:${chunk.text.hashCode()}:$speed"

    private fun takePrefetched(key: String): PrefetchedAudio? = synchronized(prefetchLock) {
        val cached = prefetchedAudio
        prefetchedAudio = null
        if (cached?.key == key) cached else {
            cached?.audio?.cancel()
            null
        }
    }

    private fun clearPrefetch() {
        synchronized(prefetchLock) {
            prefetchedAudio?.audio?.cancel()
            prefetchedAudio = null
        }
    }

    private fun playPendingSpeech() {
        val speech = pendingSpeech ?: return
        pendingSpeech = null
        speak(speech.chunk, speech.onChunkStart, speech.onChunkDone, speech.onError)
    }

    /** Tạm dừng đọc sách và dừng phát âm thanh ngay lập tức. */
    override fun pause() = stopPlayback()

    /** Tiếp tục phát âm thanh (không áp dụng cho AI Offline vì phát từ vị trí dừng). */
    override fun resume() = Unit

    /** Dừng hẳn việc phát âm thanh và dọn dẹp hàng chờ phát. */
    override fun stop() = stopPlayback()

    private fun stopPlayback() {
        pendingSpeech = null
        clearPrefetch()
        speakJob?.cancel()
        speakJob = null
        initializeJob?.cancel()
        initializeJob = null
        isInitializing = false
        sherpaTtsEngine.stop()
    }

    /**
     * Cài đặt ngôn ngữ đọc cho engine TTS.
     *
     * @param language Mã ngôn ngữ (mặc định "vi").
     */
    override fun setLanguage(language: String) {
        currentLanguage = if (language.equals("vi", ignoreCase = true)) "vi" else language.lowercase()
        if (currentLanguage != "vi") setVoice(null)
    }

    /**
     * Cài đặt tốc độ đọc.
     *
     * @param speed Tốc độ phát âm thanh (ví dụ: 1.0f là tốc độ chuẩn).
     */
    override fun setSpeed(speed: Float) {
        if (currentSpeed != speed) clearPrefetch()
        currentSpeed = speed
    }

    /**
     * Cài đặt cao độ giọng đọc (không hỗ trợ trên Piper AI, giữ cố định 1.0f).
     *
     * @param pitch Cao độ âm thanh.
     */
    override fun setPitch(pitch: Float) = Unit

    /**
     * Cài đặt ID giọng đọc AI Offline từ catalog.
     *
     * @param voiceId ID giọng đọc AI offline (ví dụ: "ngoc_ngan"). Pass `null` để xóa lựa chọn.
     */
    override fun setVoice(voiceId: String?) {
        val supportedVoiceId = TtsVoiceCatalog.find(voiceId)
            ?.takeIf { it.language == currentLanguage }
            ?.id
        if (currentVoiceId == supportedVoiceId) return

        currentVoiceId = supportedVoiceId
        clearPrefetch()
        pendingSpeech = null
        speakJob?.cancel()
        speakJob = null
        initializeJob?.cancel()
        initializeJob = null
        isInitializing = false
        if (isEngineReady) sherpaTtsEngine.release()
        isEngineReady = false
    }

    /**
     * Trả về danh sách các giọng đọc AI Offline hỗ trợ cho ngôn ngữ chỉ định.
     *
     * @param language Mã ngôn ngữ cần lấy danh sách giọng đọc.
     * @return Danh sách [TtsVoice] kèm trạng thái đã nạp/tải về máy hay chưa.
     */
    override fun getAvailableVoices(language: String): List<TtsVoice> =
        TtsVoiceCatalog.forLanguage(language).map { model ->
            TtsVoice(
                id = model.id,
                name = model.displayName,
                language = model.language,
                isNetworkRequired = false,
                isDownloaded = downloader.isModelDownloaded(model.id),
                aiBackend = AiTtsBackend.SHERPA_VITS
            )
        }

    /** Giải phóng hoàn toàn bộ nhớ, hủy các coroutine job và dừng native C++ engine. */
    override fun shutdown() {
        clearPrefetch()
        pendingSpeech = null
        isInitializing = false
        speakJob?.cancel()
        initializeJob?.cancel()
        sherpaTtsEngine.release()
        isEngineReady = false
        engineScope.cancel()
    }
}
