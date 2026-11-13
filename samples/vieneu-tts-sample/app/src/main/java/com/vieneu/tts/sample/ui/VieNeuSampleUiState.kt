package com.vieneu.tts.sample.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import com.vieneu.tts.sample.R
import com.vieneu.tts.sample.data.DownloadProgress
import com.vieneu.tts.sample.data.ResourceInventory
import com.vieneu.tts.sample.tts.ChunkGap

/**
 * Chế độ cân bằng giữa tốc độ và chất lượng tổng hợp flow-matching.
 *
 * @property labelRes Mã tài nguyên chuỗi hiển thị cho người dùng.
 * @property steps Số bước tích phân Euler.
 * @property cfg Hệ số classifier-free guidance.
 * @property sway Hệ số dịch lịch bước thời gian.
 */
enum class QualityPreset(
    @StringRes val labelRes: Int,
    val steps: Int,
    val cfg: Float,
    val sway: Float,
) {
    BALANCED(R.string.quality_balanced, steps = 16, cfg = 3.0f, sway = 0f),
    FAST(R.string.quality_fast, steps = 8, cfg = 3.0f, sway = -1.0f),
    TURBO(R.string.quality_turbo, steps = 8, cfg = 0.0f, sway = -1.0f),
}

/**
 * Giai đoạn hiện tại của phiên đọc thử nghiệm.
 */
enum class PlaybackPhase {
    IDLE,
    PREPARING,
    SYNTHESIZING,
    DONE,
    STOPPED,
}

/**
 * Lỗi kiểm tra đầu vào phía giao diện, ánh xạ trực tiếp sang string resource.
 *
 * @property messageRes Mã tài nguyên chuỗi thông báo lỗi.
 */
enum class ValidationError(@StringRes val messageRes: Int) {
    EMPTY_TEXT(R.string.error_no_text),
    NO_VOICE(R.string.error_no_voice),
}

/**
 * Dữ liệu hiển thị nhẹ cho một giọng mẫu preset trong UI Compose.
 *
 * @property name Tên giọng mẫu.
 * @property description Mô tả chất giọng.
 * @property isFemale Cho biết đây là giọng nữ hay không.
 * @property isDefault Cho biết có phải giọng mặc định từ `voices_v3_nano.json` hay không.
 */
@Immutable
data class VoiceOptionUi(
    val name: String,
    val description: String,
    val isFemale: Boolean,
    val isDefault: Boolean,
)

/**
 * Thông tin chi tiết một chunk đã chuẩn hoá và đo thời gian tổng hợp.
 *
 * @property index Chỉ số chunk (bắt đầu từ 1).
 * @property text Văn bản đã chuẩn hoá của chunk.
 * @property phonemes Chuỗi IPA từ `sea-g2p`.
 * @property gapBefore Loại khoảng nghỉ đứng trước chunk.
 * @property synthMs Thời gian tổng hợp chunk trên CPU (ms), `null` nếu chưa xong.
 * @property audioSec Thời lượng âm thanh tạo được (giây), `null` nếu chưa xong.
 */
@Immutable
data class ChunkTelemetryUi(
    val index: Int,
    val text: String,
    val phonemes: String,
    val gapBefore: ChunkGap,
    val synthMs: Long? = null,
    val audioSec: Float? = null,
) {
    /**
     * Hệ số thời gian thực (RTF) của riêng chunk này (`< 1.0` nghĩa là nhanh hơn thời gian thực).
     */
    val rtf: Float?
        get() {
            val ms = synthMs ?: return null
            val sec = audioSec ?: return null
            if (sec <= 0.001f) return null
            return (ms.toFloat() / 1000f) / sec
        }
}

/**
 * Trạng thái giao diện tổng thể của màn hình thử nghiệm VieNeu-TTS.
 */
@Immutable
data class VieNeuSampleUiState(
    val inventory: ResourceInventory? = null,
    val downloadProgress: DownloadProgress? = null,
    val isDownloading: Boolean = false,
    val isLoadingEngine: Boolean = false,
    val isEngineLoaded: Boolean = false,
    val engineLoadMs: Long? = null,
    val seaG2pAbiVersion: Int? = null,
    val loadedThreads: Int = 4,
    val voices: List<VoiceOptionUi> = emptyList(),
    val selectedVoiceName: String = "",
    val inputText: String = "",
    val speed: Float = 1.0f,
    val qualityPreset: QualityPreset = QualityPreset.FAST,
    val selectedThreads: Int = 4,
    val playbackPhase: PlaybackPhase = PlaybackPhase.IDLE,
    val currentChunkIndex: Int = 0,
    val totalChunks: Int = 0,
    val ttfaMs: Long? = null,
    val totalSynthProcessMs: Long? = null,
    val totalAudioSeconds: Float = 0f,
    val chunkLogs: List<ChunkTelemetryUi> = emptyList(),
    val validationError: ValidationError? = null,
    val runtimeError: String? = null,
) {
    /**
     * Cho biết phiên tổng hợp/phát âm thanh có đang chạy hay không.
     */
    val isSynthesizing: Boolean
        get() = playbackPhase == PlaybackPhase.PREPARING || playbackPhase == PlaybackPhase.SYNTHESIZING

    /**
     * RTF tổng cộng cho toàn bộ văn bản đang chạy/đã chạy xong.
     */
    val totalRtf: Float?
        get() {
            val processMs = totalSynthProcessMs ?: return null
            if (totalAudioSeconds <= 0.001f) return null
            return (processMs.toFloat() / 1000f) / totalAudioSeconds
        }
}
