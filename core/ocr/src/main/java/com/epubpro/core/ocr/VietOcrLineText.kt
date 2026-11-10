package com.epubpro.core.ocr

import java.text.Normalizer

/**
 * Chọn chữ cho một dòng từ kết quả hai engine đọc cùng một khung: VietOCR đọc dấu tốt hơn nhiều, còn ML Kit
 * tách từ đáng tin nên dùng làm mốc để bắt lỗi của VietOCR.
 *
 * - Độ dài lệch hẳn (VietOCR đọc nhầm vùng không phải chữ hoặc bỏ sót nửa dòng): giữ chữ ML Kit.
 * - VietOCR tự thêm từ ở đầu/cuối dòng (hay gặp khi chữ sát mép khung): bỏ từ thừa đó.
 * - VietOCR bỏ sót gạch đầu lời thoại mà ML Kit thấy: thêm lại, vì bước dựng đoạn dựa vào nó.
 *
 * @param mlKitText Chữ ML Kit đọc được cho dòng.
 * @param vietOcrText Chữ VietOCR đọc được, `null` nếu lỗi.
 * @return Chữ dùng cho dòng.
 */
internal fun chooseLineText(mlKitText: String, vietOcrText: String?): String {
    val candidate = vietOcrText?.trim().orEmpty()
    if (candidate.isEmpty()) return mlKitText
    val expected = mlKitText.count { !it.isWhitespace() }
    val actual = candidate.count { !it.isWhitespace() }
    val plausible = actual >= expected * MIN_LENGTH_RATIO - LENGTH_SLACK &&
        actual <= expected * MAX_LENGTH_RATIO + LENGTH_SLACK
    if (!plausible) return mlKitText
    val trimmed = trimExtraEdgeWords(mlKitText, candidate)
    val dash = mlKitText.trimStart().firstOrNull()
    return if (dash != null && dash in DIALOGUE_DASHES && trimmed.first() !in DIALOGUE_DASHES) "$dash $trimmed" else trimmed
}

/**
 * Bỏ từ VietOCR thêm vào đầu/cuối dòng mà ML Kit không thấy.
 *
 * Từ đầu (cuối) bị coi là thừa khi không khớp từ đầu (cuối) của ML Kit nhưng từ kế bên lại khớp; so khớp
 * bỏ dấu và cho lệch một ký tự vì ML Kit hay sai dấu.
 *
 * @param mlKitText Chữ ML Kit của dòng.
 * @param vietOcrText Chữ VietOCR của dòng.
 * @return Chữ VietOCR đã bỏ từ thừa ở hai đầu.
 */
private fun trimExtraEdgeWords(mlKitText: String, vietOcrText: String): String {
    val reference = mlKitText.split(WHITESPACE).filter { it.isNotEmpty() }.map(::skeleton)
    val words = vietOcrText.split(WHITESPACE).filter { it.isNotEmpty() }.toMutableList()
    if (reference.isEmpty()) return vietOcrText
    while (words.size > reference.size && words.size >= 2 &&
        !similar(skeleton(words[0]), reference.first()) && similar(skeleton(words[1]), reference.first())
    ) {
        words.removeAt(0)
    }
    while (words.size > reference.size && words.size >= 2 &&
        !similar(skeleton(words.last()), reference.last()) && similar(skeleton(words[words.size - 2]), reference.last())
    ) {
        words.removeAt(words.size - 1)
    }
    return words.joinToString(" ")
}

/**
 * Dạng so khớp của một từ: chữ thường, bỏ dấu thanh/dấu phụ, đ thành d.
 *
 * @param word Từ gốc.
 * @return Từ đã bỏ dấu.
 */
private fun skeleton(word: String): String =
    Normalizer.normalize(word.lowercase(), Normalizer.Form.NFD)
        .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
        .replace('đ', 'd')

/**
 * Hai từ (đã bỏ dấu) giống nhau hoặc chỉ lệch một ký tự (thay, thêm hoặc bớt).
 *
 * @param a Từ thứ nhất.
 * @param b Từ thứ hai.
 * @return `true` nếu khoảng cách chỉnh sửa không quá 1.
 */
private fun similar(a: String, b: String): Boolean {
    if (a == b) return true
    if (kotlin.math.abs(a.length - b.length) > 1) return false
    var i = 0
    var j = 0
    var edits = 0
    while (i < a.length && j < b.length) {
        if (a[i] == b[j]) {
            i++
            j++
            continue
        }
        if (++edits > 1) return false
        when {
            a.length > b.length -> i++
            a.length < b.length -> j++
            else -> {
                i++
                j++
            }
        }
    }
    return edits + (a.length - i) + (b.length - j) <= 1
}

private val WHITESPACE = Regex("\\s+")
private const val DIALOGUE_DASHES = "-–—"
private const val MIN_LENGTH_RATIO = 0.6
private const val MAX_LENGTH_RATIO = 1.6
private const val LENGTH_SLACK = 2
