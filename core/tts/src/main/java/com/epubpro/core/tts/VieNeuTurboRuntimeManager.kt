package com.epubpro.core.tts

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runtime dùng chung ở cấp `@Singleton` cho VieNeu-TTS v3 Turbo: `SeaG2pEngine` + [VieNeuTextChunker] +
 * [VieNeuTurboEngine] và danh mục giọng (preset chính thức + giọng người dùng).
 *
 * Chỉ giữ tối đa một engine Turbo (~190 MB native) và luôn giải phóng runtime Nano trước khi nạp Turbo để
 * hai model không cùng nằm trong RAM. Mọi thao tác đi qua [Mutex] để tránh use-after-free.
 *
 * @param officialVoicesJson Hàm đọc `voices_v3_turbo.json` (25 preset chính thức).
 * @param userVoicesJson Hàm đọc `voices_v3_turbo_user.json` (giọng người dùng), trả `null` nếu không có.
 * @param downloader Downloader gói Turbo.
 * @param deviceSupportChecker Bộ kiểm tra ABI `arm64-v8a`.
 * @param nanoRuntime Runtime VieNeu Nano cần giải phóng trước khi nạp Turbo.
 */
@Singleton
open class VieNeuTurboRuntimeManager constructor(
    private val officialVoicesJson: () -> String,
    private val userVoicesJson: () -> String?,
    private val downloader: VieNeuTurboModelDownloader,
    private val deviceSupportChecker: VieNeuDeviceSupportChecker,
    private val nanoRuntime: VieNeuRuntimeManager,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        downloader: VieNeuTurboModelDownloader,
        deviceSupportChecker: VieNeuDeviceSupportChecker,
        nanoRuntime: VieNeuRuntimeManager,
    ) : this(
        officialVoicesJson = { context.assets.open(OFFICIAL_VOICES_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() } },
        userVoicesJson = {
            try {
                context.assets.open(USER_VOICES_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
            } catch (_: java.io.IOException) {
                null
            }
        },
        downloader = downloader,
        deviceSupportChecker = deviceSupportChecker,
        nanoRuntime = nanoRuntime,
    )

    private val lifecycleMutex = Mutex()
    private val runtimeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val catalogLock = Any()

    @Volatile
    private var pendingUnloadJob: Job? = null

    @Volatile
    private var cachedVoices: List<VieNeuTurboVoice>? = null

    private var g2pEngine: SeaG2pEngine? = null
    private var chunker: VieNeuTextChunker? = null
    @Volatile
    private var engine: VieNeuTurboEngine? = null
    private var loadedThreads: Int = -1

    /**
     * Thiết bị có chạy được VieNeu Turbo (`arm64-v8a`) hay không.
     *
     * @return `true` nếu hỗ trợ.
     */
    open fun isSupportedDevice(): Boolean = deviceSupportChecker.isSupported()

    /**
     * Gói model Turbo đã tải đủ chưa.
     *
     * @return `true` nếu đủ mọi file bắt buộc.
     */
    open fun isModelReady(): Boolean = downloader.isModelReady()

    /**
     * Đọc và cache danh mục giọng: giọng người dùng trước, sau đó preset chính thức.
     *
     * @return Danh sách giọng Turbo hợp lệ.
     */
    open suspend fun loadVoices(): List<VieNeuTurboVoice> {
        cachedVoices?.let { return it }
        return withContext(Dispatchers.IO) {
            synchronized(catalogLock) {
                cachedVoices ?: run {
                    val user = userVoicesJson()?.let { VieNeuTurboVoice.parsePresets(it, isUserVoice = true) }.orEmpty()
                    val official = VieNeuTurboVoice.parsePresets(officialVoicesJson(), isUserVoice = false)
                    (user + official).also { cachedVoices = it }
                }
            }
        }
    }

    /**
     * Tìm giọng theo ID (`"vieneu-turbo:Tên"`) hoặc tên preset; không có thì dùng giọng mặc định.
     *
     * @param voiceIdOrName ID có prefix hoặc tên preset.
     * @return Giọng khớp, hoặc giọng mặc định, hoặc giọng đầu danh sách.
     * @throws IllegalStateException Khi không có giọng hợp lệ nào.
     */
    open suspend fun resolveVoice(voiceIdOrName: String?): VieNeuTurboVoice {
        val voices = loadVoices()
        check(voices.isNotEmpty()) { "Không có giọng VieNeu Turbo hợp lệ" }
        val name = voiceIdOrName?.removePrefix(VIENEU_TURBO_VOICE_ID_PREFIX)?.trim()
        return voices.firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: voices.firstOrNull { it.name == DEFAULT_VOICE_NAME }
            ?: voices.first()
    }

    /**
     * Nạp sẵn G2P và engine Turbo (giải phóng Nano trước).
     *
     * @param threads Số luồng ONNX Runtime.
     * @throws IllegalStateException Khi thiết bị không hỗ trợ hoặc model chưa tải.
     */
    open suspend fun ensureLoaded(threads: Int = VieNeuTurboEngine.DEFAULT_THREADS) {
        releaseNanoIfTurboNotLoaded()
        withContext(Dispatchers.IO) { lifecycleMutex.withLock { ensureLoadedLocked(threads) } }
    }

    /**
     * Nhả runtime Nano khi Turbo sắp được nạp, để hai model không cùng nằm trong RAM. Gọi trước khi lấy
     * [lifecycleMutex] ở mọi đường có thể nạp engine (khởi tạo, tách chunk, tổng hợp, nghe thử).
     */
    private suspend fun releaseNanoIfTurboNotLoaded() {
        if (engine == null) nanoRuntime.unload()
    }

    /**
     * Tách và chuyển [text] sang chunk âm vị (dùng chung luồng văn bản v3 với Nano).
     *
     * @param text Văn bản cần đọc.
     * @param threads Số luồng dùng khi phải khởi tạo runtime.
     * @param maxChars Độ dài tối đa mỗi chunk.
     * @return Danh sách [PreparedChunk].
     */
    open suspend fun prepareChunks(
        text: String,
        threads: Int = VieNeuTurboEngine.DEFAULT_THREADS,
        maxChars: Int = VieNeuTextChunker.DEFAULT_MAX_CHARS,
    ): List<PreparedChunk> {
        if (text.isBlank()) return emptyList()
        releaseNanoIfTurboNotLoaded()
        return withContext(Dispatchers.IO) {
            lifecycleMutex.withLock { ensureLoadedLocked(threads).second.prepareChunks(text = text, maxChars = maxChars) }
        }
    }

    /**
     * Tổng hợp một chunk âm vị dạng streaming dưới khoá dùng chung.
     *
     * @param phonemes Chuỗi âm vị.
     * @param voice Giọng cần đọc.
     * @param params Tham số lấy mẫu.
     * @param threads Số luồng ONNX Runtime.
     * @param isCancelled Hàm kiểm tra huỷ, gọi sau mỗi frame.
     * @param onAudio Nhận từng mảnh PCM 48 kHz.
     * @return Tổng số mẫu đã phát.
     */
    open suspend fun synthesizeChunk(
        phonemes: String,
        voice: VieNeuTurboVoice,
        params: VieNeuTurboParams,
        threads: Int,
        isCancelled: () -> Boolean,
        onAudio: (FloatArray) -> Unit,
    ): Int {
        releaseNanoIfTurboNotLoaded()
        return withContext(Dispatchers.IO) {
            lifecycleMutex.withLock {
                ensureLoadedLocked(threads).first.synthesize(phonemes, voice, params, isCancelled, onAudio)
            }
        }
    }

    /**
     * Lập lịch giải phóng engine ở nền.
     *
     * @return [Job] giải phóng.
     */
    open fun scheduleUnload(): Job = runtimeScope.launch { unload() }.also { pendingUnloadJob = it }

    /** Đợi tác vụ giải phóng gần nhất hoàn tất. */
    open suspend fun awaitPendingUnload() {
        pendingUnloadJob?.join()
    }

    /** Giải phóng engine Turbo và G2P để trả RAM native. */
    open suspend fun unload() {
        withContext(Dispatchers.IO) { lifecycleMutex.withLock { closeLocked() } }
    }

    /**
     * Nạp hoặc tái cấu hình runtime khi đang giữ [lifecycleMutex].
     *
     * @param threads Số luồng mong muốn.
     * @return Cặp (engine, chunker) đang hoạt động.
     */
    private fun ensureLoadedLocked(threads: Int): Pair<VieNeuTurboEngine, VieNeuTextChunker> {
        check(deviceSupportChecker.isSupported()) { "Thiết bị không hỗ trợ ABI arm64-v8a cho VieNeu Turbo" }
        check(downloader.isModelReady()) { "Model VieNeu Turbo chưa được tải đủ" }
        val dir = downloader.modelDir
        val clamped = threads.coerceAtLeast(1)
        if (g2pEngine == null || chunker == null) {
            val g2p = SeaG2pEngine(File(dir, VieNeuModelDownloader.SEA_G2P_DICT_FILE))
            g2pEngine = g2p
            chunker = VieNeuTextChunker(g2p)
        }
        if (engine == null || loadedThreads != clamped) {
            runCatching { engine?.close() }
            engine = null
            engine = VieNeuTurboEngine(dir, clamped)
            loadedThreads = clamped
        }
        return engine!! to chunker!!
    }

    private fun closeLocked() {
        runCatching { engine?.close() }
        engine = null
        loadedThreads = -1
        chunker = null
        runCatching { g2pEngine?.close() }
        g2pEngine = null
    }

    companion object {
        const val OFFICIAL_VOICES_ASSET = "voices_v3_turbo.json"
        const val USER_VOICES_ASSET = "voices_v3_turbo_user.json"
        private const val VIENEU_TURBO_VOICE_ID_PREFIX = "vieneu-turbo:"
        private const val DEFAULT_VOICE_NAME = "Hải Đăng"
    }
}
