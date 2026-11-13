package com.epubpro.domain.model

import java.util.Locale
import kotlin.math.abs

/**
 * Công nghệ tổng hợp giọng đọc AI offline mà ứng dụng hỗ trợ.
 */
enum class AiTtsBackend {
    /** Bộ đọc Piper / VITS qua Sherpa-ONNX (tải lẻ từng giọng). */
    SHERPA_VITS,

    /** Bộ đọc VieNeu-TTS v3-Nano flow-matching + `sea-g2p` (gói dùng chung 11 giọng). */
    VIENEU_NANO
}

/**
 * Các mức cấu hình tổng hợp chuẩn cho VieNeu-TTS v3-Nano.
 *
 * @property steps Số bước giải phương trình vi phân Euler.
 * @property cfg Hệ số Classifier-Free Guidance (`0f` bỏ qua pass unconditional).
 * @property sway Hệ số dịch lịch bước thời gian (`-1f` khuyên dùng cho 8 bước).
 */
enum class VieNeuQualityPreset(
    val steps: Int,
    val cfg: Float,
    val sway: Float
) {
    /** Mặc định tối ưu cho thiết bị flagship `arm64-v8a` (8 bước, giữ CFG 3.0). */
    FAST(steps = 8, cfg = 3.0f, sway = -1.0f),

    /** Ưu tiên chất lượng tối đa (16 bước, CFG 3.0, sway 0). */
    BALANCED(steps = 16, cfg = 3.0f, sway = 0.0f),

    /** Ưu tiên tốc độ phản hồi tối đa (8 bước, tắt CFG = 0). */
    TURBO(steps = 8, cfg = 0.0f, sway = -1.0f),

    /** Người dùng tự kéo các tham số chi tiết trong Cài đặt âm thanh. */
    CUSTOM(steps = 8, cfg = 3.0f, sway = -1.0f);

    companion object {
        private const val FLOAT_TOLERANCE = 1e-3f

        /**
         * Tìm preset khớp với bộ ba `(steps, cfg, sway)` hiện tại, hoặc trả về [CUSTOM] nếu không khớp.
         *
         * @param steps Số bước Euler hiện tại.
         * @param cfg Hệ số CFG hiện tại.
         * @param sway Hệ số Sway hiện tại.
         * @return Preset tương ứng hoặc [CUSTOM].
         */
        fun matchingPreset(steps: Int, cfg: Float, sway: Float): VieNeuQualityPreset =
            entries.firstOrNull { preset ->
                preset != CUSTOM &&
                    preset.steps == steps &&
                    abs(preset.cfg - cfg) <= FLOAT_TOLERANCE &&
                    abs(preset.sway - sway) <= FLOAT_TOLERANCE
            } ?: CUSTOM
    }
}

data class TtsChunk(
    val id: Int,
    val paragraphIndex: Int,
    val text: String
)

data class TtsVoice(
    val id: String,
    val name: String,
    val language: String,
    val isNetworkRequired: Boolean = false,
    val isDownloaded: Boolean = true,
    val aiBackend: AiTtsBackend? = null,
    val description: String? = null
)

data class TtsSettings(
    val isConfigured: Boolean = false,
    val isAiVoice: Boolean = false,
    val language: String = "vi",
    val voiceId: String? = null,
    val speed: Float = 1.0f,
    val pitch: Float = 1.0f,
    val vieNeuPreset: VieNeuQualityPreset = VieNeuQualityPreset.FAST,
    val vieNeuSteps: Int = DEFAULT_VIENEU_STEPS,
    val vieNeuCfg: Float = DEFAULT_VIENEU_CFG,
    val vieNeuSway: Float = DEFAULT_VIENEU_SWAY,
    val vieNeuThreads: Int = DEFAULT_VIENEU_THREADS
) {
    /**
     * Cho biết [voiceId] hiện tại có phải một giọng thuộc VieNeu-TTS v3-Nano hay không.
     */
    val isVieNeuVoiceSelected: Boolean
        get() = isAiVoice && isVieNeuVoiceId(voiceId)

    /**
     * Trả về bản sao cài đặt đã áp dụng một [VieNeuQualityPreset] cụ thể.
     *
     * @param preset Mức cấu hình cần áp dụng.
     * @return [TtsSettings] mới với tham số `steps/cfg/sway` cập nhật theo [preset] (hoặc giữ nguyên nếu là [VieNeuQualityPreset.CUSTOM]).
     */
    fun withVieNeuPreset(preset: VieNeuQualityPreset): TtsSettings {
        val (steps, cfg, sway) = resolveVieNeuParamsForPreset(
            preset = preset,
            currentSteps = vieNeuSteps,
            currentCfg = vieNeuCfg,
            currentSway = vieNeuSway
        )
        return copy(
            vieNeuPreset = preset,
            vieNeuSteps = steps,
            vieNeuCfg = cfg,
            vieNeuSway = sway
        ).normalizedForPlayback()
    }

    companion object {
        const val VIENEU_VOICE_ID_PREFIX = "vieneu:"
        const val MIN_VIENEU_STEPS = 4
        const val MAX_VIENEU_STEPS = 24
        const val DEFAULT_VIENEU_STEPS = 8
        const val MIN_VIENEU_CFG = 0.0f
        const val MAX_VIENEU_CFG = 5.0f
        const val DEFAULT_VIENEU_CFG = 3.0f
        const val MIN_VIENEU_SWAY = -1.0f
        const val MAX_VIENEU_SWAY = 0.0f
        const val DEFAULT_VIENEU_SWAY = -1.0f
        const val DEFAULT_VIENEU_THREADS = 4

        /**
         * Kiểm tra một `voiceId` có mang tiền tố giọng VieNeu-TTS hay không.
         *
         * @param voiceId Mã giọng cần kiểm tra.
         * @return `true` nếu mã giọng bắt đầu bằng [VIENEU_VOICE_ID_PREFIX].
         */
        fun isVieNeuVoiceId(voiceId: String?): Boolean =
            voiceId?.trim()?.startsWith(VIENEU_VOICE_ID_PREFIX) == true

        /**
         * Quy đổi một [VieNeuQualityPreset] sang bộ tham số `(steps, cfg, sway)`.
         *
         * @param preset Preset cần áp dụng.
         * @param currentSteps Số bước hiện tại nếu preset là [VieNeuQualityPreset.CUSTOM].
         * @param currentCfg Hệ số CFG hiện tại nếu preset là [VieNeuQualityPreset.CUSTOM].
         * @param currentSway Hệ số Sway hiện tại nếu preset là [VieNeuQualityPreset.CUSTOM].
         * @return Bộ ba `(steps, cfg, sway)`.
         */
        fun resolveVieNeuParamsForPreset(
            preset: VieNeuQualityPreset,
            currentSteps: Int = DEFAULT_VIENEU_STEPS,
            currentCfg: Float = DEFAULT_VIENEU_CFG,
            currentSway: Float = DEFAULT_VIENEU_SWAY
        ): Triple<Int, Float, Float> = if (preset == VieNeuQualityPreset.CUSTOM) {
            Triple(currentSteps, currentCfg, currentSway)
        } else {
            Triple(preset.steps, preset.cfg, preset.sway)
        }
    }
}

fun TtsSettings.normalizedForPlayback(): TtsSettings {
    val normalizedLanguage = if (isAiVoice) {
        "vi"
    } else {
        language.lowercase(Locale.ROOT).takeIf { it == "vi" || it == "en" } ?: "vi"
    }
    return copy(
        language = normalizedLanguage,
        voiceId = voiceId?.trim()?.takeIf { it.isNotEmpty() },
        speed = speed.coerceIn(0.5f, 2.0f),
        pitch = if (isAiVoice) 1.0f else pitch.coerceIn(0.5f, 1.5f),
        vieNeuSteps = vieNeuSteps.coerceIn(TtsSettings.MIN_VIENEU_STEPS, TtsSettings.MAX_VIENEU_STEPS),
        vieNeuCfg = vieNeuCfg.coerceIn(TtsSettings.MIN_VIENEU_CFG, TtsSettings.MAX_VIENEU_CFG),
        vieNeuSway = vieNeuSway.coerceIn(TtsSettings.MIN_VIENEU_SWAY, TtsSettings.MAX_VIENEU_SWAY),
        vieNeuThreads = if (vieNeuThreads <= 2) 2 else 4
    )
}

sealed class TtsPlayerState {
    object Idle : TtsPlayerState()
    object Loading : TtsPlayerState()
    data class Preparing(
        val bookId: String = "",
        val chapterIndex: Int = 0,
        val currentChunkIndex: Int,
        val totalChunks: Int,
        val currentChunk: TtsChunk
    ) : TtsPlayerState()
    data class Playing(
        val bookId: String = "",
        val chapterIndex: Int = 0,
        val currentChunkIndex: Int,
        val totalChunks: Int,
        val currentChunk: TtsChunk,
        val progressMs: Long = 0L,
        val totalMs: Long = 0L
    ) : TtsPlayerState()
    data class Paused(
        val bookId: String = "",
        val chapterIndex: Int = 0,
        val currentChunkIndex: Int,
        val totalChunks: Int,
        val currentChunk: TtsChunk
    ) : TtsPlayerState()
    data class Completed(
        val bookId: String = "",
        val chapterIndex: Int = 0
    ) : TtsPlayerState()
    data class Error(val message: String) : TtsPlayerState()
}

enum class SleepTimerOption(val label: String, val minutes: Int) {
    OFF("Tắt", 0),
    MIN_15("15 phút", 15),
    MIN_30("30 phút", 30),
    MIN_45("45 phút", 45),
    MIN_60("60 phút", 60),
    END_OF_CHAPTER("Hết chương", -1)
}
