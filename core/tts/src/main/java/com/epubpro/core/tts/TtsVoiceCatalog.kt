package com.epubpro.core.tts

import com.epubpro.domain.model.AiTtsBackend
import com.epubpro.domain.model.TtsSettings

/**
 * Thông tin một model giọng đọc AI offline thuộc bộ Sherpa-ONNX (tải lẻ từng giọng).
 *
 * @property id Mã định danh duy nhất của giọng đọc Sherpa.
 * @property displayName Tên hiển thị cho người dùng.
 * @property language Mã ngôn ngữ (hiện hỗ trợ `"vi"`).
 * @property downloadSize Dung lượng tải ước tính của từng giọng.
 * @property onnxFileName Tên file `.onnx` tương ứng trên repository.
 */
data class AiTtsVoiceModel(
    val id: String,
    val displayName: String,
    val language: String,
    val downloadSize: String,
    val onnxFileName: String,
)

/**
 * Thông tin một giọng mẫu preset trong gói dùng chung VieNeu-TTS v3-Nano (11 giọng).
 *
 * @property id Mã giọng đầy đủ kèm prefix `"vieneu:"` để không trùng ID với giọng Sherpa.
 * @property presetName Tên khóa gốc của preset trong `voices_v3_nano.json`.
 * @property displayName Tên hiển thị trên giao diện người dùng.
 * @property language Mã ngôn ngữ hỗ trợ (`"vi"`).
 * @property gender Giới tính giọng (`"male"` hoặc `"female"`).
 * @property description Mô tả ngắn về vùng miền và phong cách đọc.
 */
data class VieNeuPresetVoiceInfo(
    val id: String,
    val presetName: String,
    val displayName: String,
    val language: String,
    val gender: String,
    val description: String,
)

/**
 * Danh mục tập trung các giọng đọc AI offline mà EpubPro hỗ trợ (Sherpa VITS và VieNeu-TTS v3-Nano).
 */
object TtsVoiceCatalog {
    private const val MODEL_BASE_URL =
        "https://huggingface.co/doof-ferb/nghitts-copy/resolve/main/sherpa-onnx"

    const val TOKENS_URL = "$MODEL_BASE_URL/tokens.txt"
    const val VIENEU_SHARED_PACKAGE_SIZE = "345 MB"
    const val DEFAULT_VIENEU_VOICE_ID = "${TtsSettings.VIENEU_VOICE_ID_PREFIX}Minh Quân"

    val aiVoices: List<AiTtsVoiceModel> = listOf(
        AiTtsVoiceModel("ngoc_ngan", "Ngọc Ngạn", "vi", "60.6 MB", "ngocngan3701.onnx"),
        AiTtsVoiceModel("quang_minh", "Quang Minh", "vi", "60.6 MB", "minhquang.onnx"),
        AiTtsVoiceModel("ngoc_huyen", "Ngọc Huyền", "vi", "60.6 MB", "ngochuyen.onnx"),
        AiTtsVoiceModel("ngoc_huyen_new", "Ngọc Huyền (Bản mới)", "vi", "60.6 MB", "ngochuyennew.onnx"),
        AiTtsVoiceModel("phuong_mai", "Phương Mai", "vi", "60.6 MB", "maiphuong.onnx"),
        AiTtsVoiceModel("lac_phi", "Lạc Phi", "vi", "60.6 MB", "lacphi.onnx"),
        AiTtsVoiceModel("duy", "Duy", "vi", "60.6 MB", "duyoryx3175.onnx"),
        AiTtsVoiceModel("vais1000", "Minh Khang", "vi", "60.6 MB", "minhkhang.onnx"),
        AiTtsVoiceModel("ban_mai", "Ban Mai", "vi", "60.6 MB", "banmai.onnx"),
        AiTtsVoiceModel("chieu_thanh", "Chiếu Thành", "vi", "60.6 MB", "chieuthanh.onnx"),
        AiTtsVoiceModel("manh_dung", "Mạnh Dũng", "vi", "60.6 MB", "manhdung.onnx"),
        AiTtsVoiceModel("minh_thu", "Minh Thu", "vi", "60.6 MB", "minhthu.onnx"),
        AiTtsVoiceModel("my_tam_2", "Mỹ Tâm (Bản 2)", "vi", "60.6 MB", "mytam2.onnx"),
        AiTtsVoiceModel("my_tam_2794", "Mỹ Tâm (Bản 2794)", "vi", "60.6 MB", "mytam2794.onnx"),
        AiTtsVoiceModel("phuong_trang", "Phương Trang", "vi", "60.6 MB", "phuongtrang.onnx"),
        AiTtsVoiceModel("tai_an_2", "Tài An (Bản 2)", "vi", "60.6 MB", "taian2.onnx"),
        AiTtsVoiceModel("tai_an_4", "Tài An (Bản 4)", "vi", "60.6 MB", "taian4.onnx"),
        AiTtsVoiceModel("thanh_phuong", "Thanh Phương", "vi", "60.6 MB", "thanhphuong2.onnx"),
        AiTtsVoiceModel("thien_tam", "Thiện Tâm", "vi", "60.6 MB", "thientam.onnx"),
        AiTtsVoiceModel("tran_thanh", "Trấn Thành", "vi", "60.6 MB", "tranthanh3870.onnx"),
        AiTtsVoiceModel("viet_thao", "Việt Thảo", "vi", "60.6 MB", "vietthao3886.onnx"),
        AiTtsVoiceModel("calm_woman", "Calm Woman", "vi", "60.6 MB", "calmwoman3688.onnx"),
        AiTtsVoiceModel("deep_man", "Deep Man", "vi", "60.6 MB", "deepman3909.onnx"),
        AiTtsVoiceModel("adam", "Adam", "vi", "60.6 MB", "adam1.onnx"),
        AiTtsVoiceModel("yan_new", "Yan", "vi", "60.6 MB", "yannew.onnx"),
    )

    /**
     * 11 giọng preset chính thức của gói VieNeu-TTS v3-Nano (`voices_v3_nano.json`).
     */
    val vieNeuVoices: List<VieNeuPresetVoiceInfo> = listOf(
        vieNeuEntry("Minh Quân", "male", "Nam · Bắc · Phong cách tự nhiên"),
        vieNeuEntry("Mạnh Dũng", "male", "Nam · Bắc · Phong cách tự nhiên"),
        vieNeuEntry("Anh Khôi", "male", "Nam · Bắc · Phong cách kể chuyện"),
        vieNeuEntry("Đức Trí", "male", "Nam · Bắc · Phong cách đọc truyện"),
        vieNeuEntry("Hữu Quân", "male", "Nam · Bắc · Phong cách tin tức"),
        vieNeuEntry("Mỹ Duyên", "female", "Nữ · Bắc · Phong cách đọc truyện"),
        vieNeuEntry("Ái Hân", "female", "Nữ · Nam · Phong cách tin tức"),
        vieNeuEntry("Xuân Tiên", "female", "Nữ · Bắc · Phong cách tin tức"),
        vieNeuEntry("Mai Anh", "female", "Nữ · Bắc · Phong cách tin tức"),
        vieNeuEntry("Trúc Ly", "female", "Nữ · Bắc · Phong cách tự nhiên"),
        vieNeuEntry("Adam", "male", "Nam · Nam · Giọng đọc tự nhiên"),
    )

    /**
     * Tìm model giọng Sherpa VITS theo [id].
     *
     * @param id Mã giọng đọc cần tra cứu.
     * @return [AiTtsVoiceModel] tương ứng hoặc `null` nếu không tìm thấy.
     */
    fun find(id: String?): AiTtsVoiceModel? = id?.let { voiceId ->
        aiVoices.firstOrNull { it.id == voiceId }
    }

    /**
     * Lấy danh sách giọng Sherpa VITS hỗ trợ ngôn ngữ [language].
     *
     * @param language Mã ngôn ngữ ISO (ví dụ `"vi"`).
     * @return Danh sách [AiTtsVoiceModel] phù hợp.
     */
    fun forLanguage(language: String): List<AiTtsVoiceModel> =
        aiVoices.filter { it.language.equals(language, ignoreCase = true) }

    /**
     * Tạo URL tải file `.onnx` cho một model Sherpa VITS.
     *
     * @param model Thông tin model cần tải.
     * @return Đường dẫn URL đầy đủ của file model.
     */
    fun modelUrl(model: AiTtsVoiceModel): String = "$MODEL_BASE_URL/${model.onnxFileName}"

    /**
     * Tìm thông tin giọng preset VieNeu-TTS v3-Nano theo ID (`"vieneu:..."`) hoặc tên preset gốc.
     *
     * @param id Mã giọng có hoặc không có prefix `"vieneu:"`.
     * @return [VieNeuPresetVoiceInfo] khớp chính xác, hoặc `null` nếu không tồn tại trong catalog.
     */
    fun findVieNeu(id: String?): VieNeuPresetVoiceInfo? {
        val raw = id?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val targetName = raw.removePrefix(TtsSettings.VIENEU_VOICE_ID_PREFIX)
        return vieNeuVoices.firstOrNull { it.id == raw || it.presetName == targetName }
    }

    /**
     * Lấy danh sách 11 giọng preset VieNeu-TTS hỗ trợ ngôn ngữ [language].
     *
     * @param language Mã ngôn ngữ ISO (ví dụ `"vi"`).
     * @return Danh sách [VieNeuPresetVoiceInfo] khả dụng.
     */
    fun vieNeuForLanguage(language: String): List<VieNeuPresetVoiceInfo> =
        vieNeuVoices.filter { it.language.equals(language, ignoreCase = true) }

    /**
     * Kiểm tra mã [id] có thuộc backend VieNeu-TTS hay không.
     *
     * @param id Mã giọng cần kiểm tra.
     * @return `true` nếu [id] bắt đầu bằng `"vieneu:"`.
     */
    fun isVieNeuVoice(id: String?): Boolean = TtsSettings.isVieNeuVoiceId(id)

    /**
     * Xác định backend AI TTS ([AiTtsBackend]) tương ứng với [id].
     *
     * @param id Mã giọng đọc cần kiểm tra.
     * @return [AiTtsBackend.VIENEU_NANO], [AiTtsBackend.SHERPA_VITS], hoặc `null` nếu không phải giọng AI catalog.
     */
    fun findBackend(id: String?): AiTtsBackend? = when {
        TtsSettings.isVieNeuVoiceId(id) -> AiTtsBackend.VIENEU_NANO
        find(id) != null -> AiTtsBackend.SHERPA_VITS
        else -> null
    }

    /**
     * Tạo nhanh một mục [VieNeuPresetVoiceInfo] với ID chuẩn hoá kèm prefix `"vieneu:"`.
     *
     * @param presetName Tên gốc của preset trong `voices_v3_nano.json`.
     * @param gender Giới tính (`"male"` / `"female"`).
     * @param description Mô tả giọng đọc hiển thị trên UI.
     * @return Đối tượng [VieNeuPresetVoiceInfo] hoàn chỉnh.
     */
    private fun vieNeuEntry(
        presetName: String,
        gender: String,
        description: String,
    ): VieNeuPresetVoiceInfo = VieNeuPresetVoiceInfo(
        id = "${TtsSettings.VIENEU_VOICE_ID_PREFIX}$presetName",
        presetName = presetName,
        displayName = presetName,
        language = "vi",
        gender = gender,
        description = description,
    )
}
