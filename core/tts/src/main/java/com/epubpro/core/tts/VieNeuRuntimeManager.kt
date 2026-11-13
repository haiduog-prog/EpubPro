package com.epubpro.core.tts

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bộ quản lý runtime dùng chung ở cấp `@Singleton` cho `SeaG2pEngine` và `VieNeuNanoEngine`.
 *
 * Đảm bảo chỉ mở tối đa một phiên bản model (~280 MB native RAM) trong toàn ứng dụng,
 * chia sẻ an toàn giữa `TtsService` và tính năng nghe thử ở màn hình Cài đặt,
 * đồng thời đồng bộ đóng/mở bằng [Mutex] để không xảy ra use-after-free khi nạp lại số luồng.
 *
 * @property assetsJsonProvider Hàm đọc JSON bảng preset `voices_v3_nano.json`.
 * @property downloader Bộ quản lý thư mục và kiểm kê file model trên thiết bị.
 * @property deviceSupportChecker Bộ kiểm tra thiết bị có hỗ trợ ABI `arm64-v8a` hay không.
 */
@Singleton
open class VieNeuRuntimeManager constructor(
    private val assetsJsonProvider: () -> String,
    private val downloader: VieNeuModelDownloader,
    private val deviceSupportChecker: VieNeuDeviceSupportChecker,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        downloader: VieNeuModelDownloader,
        deviceSupportChecker: VieNeuDeviceSupportChecker,
    ) : this(
        assetsJsonProvider = {
            context.assets.open(VOICES_ASSET_FILE).bufferedReader(Charsets.UTF_8).use { it.readText() }
        },
        downloader = downloader,
        deviceSupportChecker = deviceSupportChecker,
    )

    private val lifecycleMutex = Mutex()
    private val catalogLock = Any()

    @Volatile
    private var cachedCatalog: VoiceCatalog? = null

    private var g2pEngine: SeaG2pEngine? = null
    private var chunker: VieNeuTextChunker? = null
    private var nanoEngine: VieNeuNanoEngine? = null
    private var loadedThreads: Int = -1

    /**
     * Kiểm tra thiết bị hiện tại có hỗ trợ chạy `sea-g2p` + VieNeu v3-Nano (`arm64-v8a`) hay không.
     *
     * @return `true` nếu thiết bị hỗ trợ `arm64-v8a`.
     */
    open fun isSupportedDevice(): Boolean = deviceSupportChecker.isSupported()

    /**
     * Kiểm tra gói model VieNeu v3-Nano và từ điển `sea_g2p.bin` đã tải đủ trên máy chưa.
     *
     * @return `true` nếu đã tải đầy đủ cả 7 file bắt buộc.
     */
    open fun isModelReady(): Boolean = downloader.isModelReady()

    /**
     * Đọc và lưu cache danh sách 11 giọng preset từ asset `voices_v3_nano.json`.
     *
     * @return [VoiceCatalog] chứa giọng mặc định và danh sách preset đầy đủ embedding + style.
     */
    open suspend fun loadVoiceCatalog(): VoiceCatalog {
        cachedCatalog?.let { return it }
        return withContext(Dispatchers.IO) {
            synchronized(catalogLock) {
                cachedCatalog ?: VieNeuNanoEngine.loadPresets(assetsJsonProvider()).also {
                    cachedCatalog = it
                }
            }
        }
    }

    /**
     * Tìm giọng [VieNeuNanoVoice] theo ID (`"vieneu:Minh Quân"`) hoặc tên hiển thị (`"Minh Quân"`).
     *
     * @param voiceIdOrName ID có prefix `"vieneu:"` hoặc tên preset gốc.
     * @return [VieNeuNanoVoice] khớp với yêu cầu, hoặc giọng mặc định nếu không tìm thấy.
     */
    open suspend fun resolveVoice(voiceIdOrName: String?): VieNeuNanoVoice {
        val catalog = loadVoiceCatalog()
        val normalizedName = voiceIdOrName?.removePrefix(VIENEU_VOICE_ID_PREFIX)?.trim()
        return catalog.voices.firstOrNull { it.name.equals(normalizedName, ignoreCase = true) }
            ?: catalog.voices.firstOrNull { it.name == catalog.defaultVoiceName }
            ?: catalog.voices.first()
    }

    /**
     * Nạp sẵn `SeaG2pEngine` và `VieNeuNanoEngine` với cấu hình [threads] nếu chưa nạp hoặc khác số luồng.
     *
     * @param threads Số luồng intra-op ONNX Runtime (ví dụ `4` trên S25 Ultra).
     * @throws IllegalStateException Nếu thiết bị không phải `arm64-v8a` hoặc model chưa được tải.
     */
    open suspend fun ensureLoaded(threads: Int = VieNeuNanoEngine.DEFAULT_THREADS) {
        withContext(Dispatchers.IO) {
            lifecycleMutex.withLock {
                ensureLoadedLocked(threads)
            }
        }
    }

    /**
     * Tách đoạn/câu và chuyển đổi [text] sang danh sách [PreparedChunk] chứa âm vị IPA từ `sea-g2p`.
     *
     * @param text Văn bản đầu vào.
     * @param threads Số luồng dùng khi cần khởi tạo runtime.
     * @param maxChars Độ dài tối đa của mỗi chunk.
     * @return Danh sách [PreparedChunk] sẵn sàng tổng hợp audio.
     */
    open suspend fun prepareChunks(
        text: String,
        threads: Int = VieNeuNanoEngine.DEFAULT_THREADS,
        maxChars: Int = VieNeuTextChunker.DEFAULT_MAX_CHARS,
    ): List<PreparedChunk> {
        if (text.isBlank()) return emptyList()
        return withContext(Dispatchers.IO) {
            lifecycleMutex.withLock {
                val activeChunker = ensureLoadedLocked(threads).second
                activeChunker.prepareChunks(text = text, maxChars = maxChars)
            }
        }
    }

    /**
     * Tổng hợp một chunk âm vị [phonemes] thành mảng mẫu PCM float32 24 kHz dưới lock dùng chung.
     *
     * @param phonemes Chuỗi âm vị IPA của chunk.
     * @param voice Giọng preset cần tổng hợp.
     * @param steps Số bước Euler (`4..24`).
     * @param cfg Hệ số classifier-free guidance (`0.0f..5.0f`).
     * @param sway Độ cong lịch bước Euler (`-1.0f..0.0f`).
     * @param speed Tốc độ đọc.
     * @param threads Số luồng ONNX Runtime.
     * @param seed Hạt giống sinh nhiễu (tuỳ chọn).
     * @param isCancelled Hàm kiểm tra yêu cầu hủy hợp tác giữa các bước Euler.
     * @return Mảng mẫu audio PCM float32 ở 24 kHz.
     */
    open suspend fun synthesizeChunk(
        phonemes: String,
        voice: VieNeuNanoVoice,
        steps: Int,
        cfg: Float,
        sway: Float,
        speed: Float,
        threads: Int,
        seed: Long? = null,
        isCancelled: () -> Boolean = { false },
    ): FloatArray = withContext(Dispatchers.IO) {
        lifecycleMutex.withLock {
            val engine = ensureLoadedLocked(threads).first
            engine.synthesize(
                phonemes = phonemes,
                voice = voice,
                steps = steps,
                cfg = cfg,
                sway = sway,
                speed = speed,
                seed = seed,
                isCancelled = isCancelled,
            )
        }
    }

    /**
     * Giải phóng cả `VieNeuNanoEngine` và `SeaG2pEngine` để trả lại ~280 MB RAM native.
     */
    open suspend fun unload() {
        withContext(Dispatchers.IO) {
            lifecycleMutex.withLock {
                closeLocked()
            }
        }
    }

    /**
     * Nạp hoặc tái cấu hình engine khi đang giữ [lifecycleMutex].
     *
     * @param threads Số luồng ONNX Runtime mong muốn.
     * @return Cặp `(VieNeuNanoEngine, VieNeuTextChunker)` đang hoạt động.
     * @throws IllegalStateException Nếu thiếu điều kiện thiết bị hoặc model trên máy.
     */
    private fun ensureLoadedLocked(threads: Int): Pair<VieNeuNanoEngine, VieNeuTextChunker> {
        check(deviceSupportChecker.isSupported()) {
            "Thiết bị hiện tại không hỗ trợ ABI arm64-v8a cho VieNeu-TTS"
        }
        check(downloader.isModelReady()) {
            "Model VieNeu-TTS v3-Nano chưa được tải đủ"
        }

        val clampedThreads = threads.coerceAtLeast(1)
        val modelDir = downloader.modelDir

        if (g2pEngine == null || chunker == null) {
            val dictFile = File(modelDir, VieNeuModelDownloader.SEA_G2P_DICT_FILE)
            val createdG2p = SeaG2pEngine(dictFile)
            g2pEngine = createdG2p
            chunker = VieNeuTextChunker(createdG2p)
        }

        if (nanoEngine == null || loadedThreads != clampedThreads) {
            runCatching { nanoEngine?.close() }
            nanoEngine = null
            nanoEngine = VieNeuNanoEngine(modelDir = modelDir, threads = clampedThreads)
            loadedThreads = clampedThreads
        }

        return nanoEngine!! to chunker!!
    }

    /**
     * Đóng các handle ONNX và JNI hiện có dưới [lifecycleMutex].
     */
    private fun closeLocked() {
        runCatching { nanoEngine?.close() }
        nanoEngine = null
        loadedThreads = -1
        chunker = null
        runCatching { g2pEngine?.close() }
        g2pEngine = null
    }

    companion object {
        const val VOICES_ASSET_FILE = "voices_v3_nano.json"
        private const val VIENEU_VOICE_ID_PREFIX = "vieneu:"
    }
}
