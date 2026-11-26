package com.epubpro.core.tts

import org.json.JSONObject

/**
 * Một giọng preset của VieNeu-TTS v3 Turbo: x-vector người nói và audio code tham chiếu.
 *
 * @property name Tên khoá của preset trong file JSON (ví dụ `"Hải Đăng"`, `"Vie_ThuyTrang"`).
 * @property description Mô tả ngắn (vùng miền, phong cách) do file JSON cung cấp.
 * @property gender Giới tính (`"male"`/`"female"`, có thể rỗng với giọng người dùng tự lưu).
 * @property region Vùng miền (`"Bắc"`, `"Trung"`, `"Nam"`, có thể rỗng).
 * @property style Phong cách đọc (`"tu_nhien"`, `"tin_tuc"`, `"doc_truyen"`...).
 * @property speakerEmbedding X-vector 192 chiều của người nói.
 * @property refCodes Audio code tham chiếu, mỗi phần tử là 16 code của một frame MOSS.
 * @property isUserVoice `true` nếu giọng đến từ file giọng người dùng tự lưu (app Gradio của SDK).
 */
data class VieNeuTurboVoice(
    val name: String,
    val description: String,
    val gender: String,
    val region: String,
    val style: String,
    val speakerEmbedding: FloatArray,
    val refCodes: List<IntArray>,
    val isUserVoice: Boolean,
) {
    override fun equals(other: Any?): Boolean = other is VieNeuTurboVoice && other.name == name && other.isUserVoice == isUserVoice

    override fun hashCode(): Int = name.hashCode() * 31 + isUserVoice.hashCode()

    companion object {
        /** Số chiều x-vector người nói. */
        const val SPEAKER_EMBEDDING_DIM = 192

        /** Số codebook mỗi frame MOSS. */
        const val CODEBOOKS = 16

        /** Code codebook-0 của frame đệm lỗi do encoder ONNX sinh ra ở cuối clip (issue #198 của SDK). */
        private const val ENCODER_PAD_CODE = 455

        /**
         * Đọc danh sách preset từ JSON định dạng `{"presets": {"Tên": {...}}}` của SDK `vieneu`
         * (`voices_v3_turbo.json` chính thức hoặc `user_voices_v3_turbo.json` do người dùng lưu).
         *
         * Preset thiếu dữ liệu hoặc sai kích thước bị bỏ qua. Frame đệm 455 ở cuối `codes` được cắt bỏ
         * giống cách SDK nạp giọng đã lưu.
         *
         * @param json Nội dung file JSON.
         * @param isUserVoice Đánh dấu các giọng đọc được là giọng người dùng.
         * @return Danh sách giọng hợp lệ theo thứ tự trong file.
         */
        fun parsePresets(json: String, isUserVoice: Boolean): List<VieNeuTurboVoice> {
            val presets = JSONObject(json).optJSONObject("presets") ?: return emptyList()
            val voices = ArrayList<VieNeuTurboVoice>(presets.length())
            for (name in presets.keys()) {
                val item = presets.optJSONObject(name) ?: continue
                val embJson = item.optJSONArray("speaker_emb") ?: continue
                val codesJson = item.optJSONArray("codes") ?: continue
                if (embJson.length() != SPEAKER_EMBEDDING_DIM || codesJson.length() == 0) continue
                val embedding = FloatArray(SPEAKER_EMBEDDING_DIM) { embJson.getDouble(it).toFloat() }
                if (embedding.all { it == 0f }) continue
                val codes = ArrayList<IntArray>(codesJson.length())
                var valid = true
                for (f in 0 until codesJson.length()) {
                    val frame = codesJson.optJSONArray(f)
                    if (frame == null || frame.length() != CODEBOOKS) {
                        valid = false
                        break
                    }
                    codes += IntArray(CODEBOOKS) { frame.getInt(it) }
                }
                if (!valid) continue
                while (codes.size > 1 && codes.last()[0] == ENCODER_PAD_CODE) codes.removeAt(codes.lastIndex)
                voices += VieNeuTurboVoice(
                    name = name,
                    description = item.optString("description"),
                    gender = item.optString("gender"),
                    region = item.optString("region"),
                    style = item.optString("style", item.optString("style_name")),
                    speakerEmbedding = embedding,
                    refCodes = codes,
                    isUserVoice = isUserVoice,
                )
            }
            return voices
        }
    }
}
