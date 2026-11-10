package com.epubpro.core.ocr

/**
 * Bảng ký tự của model VietOCR: id 0..3 là token đặc biệt, ký tự thứ i trong [chars] có id `i + 4`.
 *
 * @property chars Chuỗi ký tự đúng thứ tự lúc huấn luyện (file `vietocr_vocab.txt`).
 */
internal class VietOcrVocab(private val chars: String) {

    /** Số lớp đầu ra của decoder (token đặc biệt + ký tự). */
    val size: Int get() = chars.length + FIRST_CHAR_ID

    /**
     * Ghép chuỗi id do decoder sinh ra thành văn bản.
     *
     * Bỏ token đặc biệt (pad, sos, mask) và dừng ở token kết thúc đầu tiên.
     *
     * @param ids Chuỗi id, thường bắt đầu bằng [SOS].
     * @return Văn bản của dòng.
     */
    fun decode(ids: List<Int>): String = buildString {
        for (id in ids) {
            if (id == EOS) break
            val index = id - FIRST_CHAR_ID
            if (index in chars.indices) append(chars[index])
        }
    }

    companion object {
        const val SOS = 1
        const val EOS = 2
        private const val FIRST_CHAR_ID = 4
    }
}
