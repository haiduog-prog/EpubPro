package com.epubpro.core.convert

/**
 * Kết quả convert một đoạn văn bản.
 *
 * @property text Văn bản tiếng Việt.
 * @property endsSentence true nếu đoạn kết thúc bằng dấu kết câu (đoạn kế tiếp cần viết hoa).
 * @property endsWithWord true nếu đoạn kết thúc bằng từ/dấu đóng, đoạn kế tiếp bắt đầu bằng từ cần dấu cách.
 */
data class ConvertedText(val text: String, val endsSentence: Boolean, val endsWithWord: Boolean)

/**
 * Bộ convert Trung → Việt kiểu QuickTranslator: ghép cụm dài nhất (longest match) từ trái sang phải.
 *
 * Các tầng từ điển được xếp theo độ ưu tiên giảm dần (Name của truyện → Name chung → VietPhrase → Hán-Việt).
 * Tại mỗi vị trí, cụm dài nhất trên mọi tầng thắng; nếu cùng độ dài thì tầng ưu tiên cao hơn thắng.
 * Chữ Hán không có trong từ điển được giữ nguyên.
 *
 * @param layers Các tầng từ điển theo thứ tự ưu tiên.
 */
class HanVietConverter(private val layers: List<PhraseDictionary>) {

    /**
     * Convert một đoạn văn bản (thường là một text node HTML).
     *
     * @param text Văn bản gốc.
     * @param sentenceStart true nếu đoạn bắt đầu một câu mới (viết hoa từ đầu tiên).
     * @param afterWord true nếu text node liền trước (ví dụ ngoài thẻ `<b>`) kết thúc bằng một từ.
     * @return Văn bản đã convert và trạng thái kết câu.
     */
    fun convert(text: String, sentenceStart: Boolean = true, afterWord: Boolean = false): ConvertedText {
        val output = StringBuilder(text.length * 3)
        var previous = if (afterWord) TokenKind.WORD else TokenKind.START
        var capitalizeNext = sentenceStart
        var index = 0
        while (index < text.length) {
            val char = text[index]
            if (isHanAt(text, index)) {
                val match = bestMatch(text, index)
                val unitLength = if (Character.isHighSurrogate(char) && index + 1 < text.length) 2 else 1
                var word = match?.value ?: text.substring(index, index + unitLength)
                if (previous == TokenKind.WORD || previous == TokenKind.CLOSE || previous == TokenKind.RAW) {
                    output.append(' ')
                }
                if (capitalizeNext) {
                    word = capitalizeFirst(word)
                    capitalizeNext = false
                }
                output.append(word)
                previous = TokenKind.WORD
                index += match?.length ?: unitLength
                continue
            }

            val mapped = mapPunctuation(char)
            when {
                char.isWhitespace() || char == '　' -> {
                    if (output.isNotEmpty() && output.last() != ' ') output.append(' ')
                    previous = TokenKind.SPACE
                }
                mapped in CLOSING -> {
                    trimTrailingSpace(output)
                    output.append(mapped)
                    previous = TokenKind.CLOSE
                    if (mapped in SENTENCE_END) capitalizeNext = true
                }
                mapped in OPENING -> {
                    if (previous == TokenKind.WORD || previous == TokenKind.CLOSE || previous == TokenKind.RAW) {
                        output.append(' ')
                    }
                    output.append(mapped)
                    previous = TokenKind.OPEN
                    // Lời thoại mở ngoặc kép thường bắt đầu câu mới.
                    if (mapped == "“" || mapped == "‘") capitalizeNext = true
                }
                else -> {
                    if (previous == TokenKind.WORD || previous == TokenKind.CLOSE) output.append(' ')
                    output.append(if (capitalizeNext && char.isLetter()) mapped.uppercase() else mapped)
                    if (char.isLetterOrDigit()) capitalizeNext = false
                    previous = TokenKind.RAW
                }
            }
            index++
        }
        return ConvertedText(
            text = output.toString(),
            endsSentence = capitalizeNext,
            endsWithWord = previous == TokenKind.WORD || previous == TokenKind.CLOSE || previous == TokenKind.RAW
        )
    }

    /**
     * Tìm cụm khớp dài nhất trên mọi tầng; cùng độ dài thì tầng đứng trước thắng.
     *
     * @param text Văn bản nguồn.
     * @param start Vị trí bắt đầu.
     * @return Kết quả khớp hoặc null.
     */
    private fun bestMatch(text: String, start: Int): PhraseMatch? {
        var best: PhraseMatch? = null
        for (layer in layers) {
            val match = layer.longestMatch(text, start) ?: continue
            if (best == null || match.length > best.length) best = match
        }
        return best
    }

    /**
     * Kiểm tra vị trí có phải chữ Hán (kể cả ký tự mở rộng dạng surrogate pair).
     *
     * @param text Văn bản.
     * @param index Vị trí.
     * @return true nếu là chữ Hán.
     */
    private fun isHanAt(text: String, index: Int): Boolean =
        Character.UnicodeScript.of(text.codePointAt(index)) == Character.UnicodeScript.HAN

    /**
     * Viết hoa chữ cái đầu tiên của từ.
     *
     * @param word Từ cần viết hoa.
     * @return Từ đã viết hoa chữ đầu.
     */
    private fun capitalizeFirst(word: String): String {
        val first = word.indexOfFirst { it.isLetter() }
        if (first < 0) return word
        return word.substring(0, first) + word[first].uppercaseChar() + word.substring(first + 1)
    }

    /**
     * Bỏ một dấu cách cuối để dấu câu đóng dính vào từ trước.
     *
     * @param output Bộ đệm đầu ra.
     */
    private fun trimTrailingSpace(output: StringBuilder) {
        // Chỉ cắt khoảng trắng do chính đoạn này sinh ra; không đụng nội dung text node khác.
        if (output.isNotEmpty() && output.last() == ' ') output.setLength(output.length - 1)
    }

    /** Loại token cuối cùng đã ghi, quyết định có chèn dấu cách hay không. */
    private enum class TokenKind { START, WORD, SPACE, OPEN, CLOSE, RAW }

    companion object {
        private val CLOSING = setOf(",", ".", "!", "?", ";", ":", ")", "]", "”", "’", "…", "»", "%")
        private val OPENING = setOf("“", "‘", "(", "[", "«")
        private val SENTENCE_END = setOf(".", "!", "?", "…")

        /**
         * Chuyển dấu câu và ký tự toàn góc của tiếng Trung sang dạng dùng trong tiếng Việt.
         *
         * @param char Ký tự gốc.
         * @return Chuỗi thay thế (có thể là chính ký tự đó).
         */
        internal fun mapPunctuation(char: Char): String = when (char) {
            '，', '、' -> ","
            '。', '．' -> "."
            '！' -> "!"
            '？' -> "?"
            '；' -> ";"
            '：' -> ":"
            '（' -> "("
            '）' -> ")"
            '【', '〔', '［' -> "["
            '】', '〕', '］' -> "]"
            '「', '『', '《', '〈' -> "“"
            '」', '』', '》', '〉' -> "”"
            '～' -> "~"
            '％' -> "%"
            in '０'..'９', in 'Ａ'..'Ｚ', in 'ａ'..'ｚ' -> (char - 0xFEE0).toString()
            else -> char.toString()
        }
    }
}
