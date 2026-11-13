package com.vieneu.tts.sample.tts

import com.vieneu.tts.sample.g2p.TextG2p
import kotlin.math.abs

/**
 * Loại ranh giới ngắt nghỉ trước một chunk văn bản theo quy tắc VieNeu-TTS v3.
 */
enum class ChunkGap {
    FIRST,
    PARA,
    SENTENCE,
    MINOR,
}

/**
 * Một chunk đã chuẩn hoá kèm chuỗi âm vị IPA và loại khoảng nghỉ liền trước.
 *
 * @property text Văn bản chunk đã chuẩn hoá (giữ nguyên `<|emotion_k|>` nếu có).
 * @property phonemes Chuỗi âm vị IPA sinh bởi `sea-g2p`.
 * @property gapBefore Loại ranh giới giữa chunk trước và chunk này.
 */
data class PreparedChunk(
    val text: String,
    val phonemes: String,
    val gapBefore: ChunkGap,
)

/**
 * Bộ tách đoạn, chia câu, đóng gói chunk và chuyển đổi âm vị khớp `VieNeu-TTS/src/vieneu_utils`.
 *
 * @param g2p Engine chuẩn hoá và chuyển đổi chữ viết sang âm vị (`sea-g2p`).
 */
class VieNeuTextChunker(private val g2p: TextG2p) {

    /**
     * Tách văn bản đầu vào thành các chunk <= [maxChars] và chuyển từng chunk sang âm vị IPA.
     *
     * @param text Văn bản tiếng Việt hoặc song ngữ (hỗ trợ `[cười]`, `[thở dài]`, `[hắng giọng]`).
     * @param maxChars Độ dài tối đa tương đối của một chunk theo văn bản đã chuẩn hoá.
     * @param minChunkChars Độ dài tối thiểu trước khi gộp chunk quá ngắn vào chunk kề.
     * @return Danh sách [PreparedChunk] sẵn sàng đưa vào [VieNeuNanoEngine].
     */
    fun prepareChunks(
        text: String,
        maxChars: Int = DEFAULT_MAX_CHARS,
        minChunkChars: Int = DEFAULT_MIN_CHUNK_CHARS,
    ): List<PreparedChunk> {
        val (chunks, gaps) = normalizeToChunksWithGaps(text, maxChars, minChunkChars)
        if (chunks.isEmpty()) return emptyList()
        return chunks.mapIndexed { index, chunkText ->
            val gapBefore = if (index == 0) ChunkGap.FIRST else gaps[index - 1]
            PreparedChunk(
                text = chunkText,
                phonemes = phonemizeWithEmotions(chunkText),
                gapBefore = gapBefore,
            )
        }
    }

    /**
     * Tách và chuẩn hoá văn bản thành danh sách `(chunks, gaps)` theo đúng luồng `v3Nano`.
     *
     * @param text Văn bản đầu vào thô.
     * @param maxChars Giới hạn độ dài mỗi chunk sau chuẩn hoá.
     * @param minChunkChars Ngưỡng tối thiểu để tránh chunk quá ngắn 1-2 từ.
     * @return Cặp `(danh sách chunk đã puncNorm, danh sách ranh giới giữa các chunk)`.
     */
    internal fun normalizeToChunksWithGaps(
        text: String,
        maxChars: Int = DEFAULT_MAX_CHARS,
        minChunkChars: Int = DEFAULT_MIN_CHUNK_CHARS,
    ): Pair<List<String>, List<ChunkGap>> {
        if (text.isBlank()) return emptyList<String>() to emptyList()
        val keepCues = '[' in text || "<|emotion_" in text
        val chunks = mutableListOf<String>()
        val gaps = mutableListOf<ChunkGap>()

        for (sentences in normalizedSentencesByPara(text, keepCues)) {
            val paraChunks = packSentencesIntoChunks(sentences, maxChars)
            if (paraChunks.isEmpty()) continue
            if (chunks.isNotEmpty()) {
                gaps += ChunkGap.PARA
            }
            paraChunks.forEachIndexed { j, chunk ->
                if (j > 0) {
                    gaps += ChunkGap.SENTENCE
                }
                chunks += chunk
            }
        }

        if (chunks.isEmpty()) return emptyList<String>() to emptyList()

        val finalized = chunks.map { g2p.puncNorm(it) }.toMutableList()
        val finalGaps = gaps.mapIndexed { index, gap ->
            if (gap == ChunkGap.PARA) ChunkGap.PARA else classifyGap(finalized[index])
        }.toMutableList()

        mergeShortChunks(finalized, finalGaps, minChunkChars)
        return finalized to finalGaps
    }

    /**
     * Chuyển một chunk văn bản sang IPA đồng thời giữ nguyên token cảm xúc `<|emotion_k|>`.
     *
     * @param text Chunk văn bản đã chuẩn hoá.
     * @return Chuỗi âm vị IPA đã áp dụng `puncNorm`.
     */
    internal fun phonemizeWithEmotions(text: String): String {
        if ('[' !in text && "<|emotion_" !in text) {
            return g2p.phonemize(text, puncNorm = true)
        }
        val sb = StringBuilder()
        val parts = splitKeepingDelimiterMatches(EMOTION_SPLIT_REGEX, text)
        for (i in parts.indices) {
            val part = parts[i]
            val token = if (i % 2 == 1) emotionTagToken(part) else null
            if (token != null) {
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(token)
                continue
            }
            val ph = if (part.isNotBlank()) g2p.phonemize(part, puncNorm = false) else ""
            if (ph.isEmpty()) continue
            when {
                sb.isEmpty() -> sb.append(ph)
                ph[0] in ATTACHING_PUNCT -> sb.append(ph)
                else -> sb.append(' ').append(ph)
            }
        }
        return g2p.puncNorm(sb.toString())
    }

    /**
     * Tách văn bản thành từng đoạn theo ký tự xuống dòng rồi chuẩn hoá từng câu trong đoạn.
     *
     * @param text Văn bản đầu vào thô.
     * @param keepCues Có giữ lại token cảm xúc `<|emotion_k|>` khi chuẩn hoá hay không.
     * @return Danh sách các đoạn văn, mỗi đoạn là danh sách câu đã chuẩn hoá.
     */
    private fun normalizedSentencesByPara(text: String, keepCues: Boolean): List<List<String>> {
        val paragraphs = RE_NEWLINE_SPLIT.split(text).filter { it.isNotBlank() }
        if (paragraphs.isEmpty()) return emptyList()
        val result = mutableListOf<List<String>>()
        for (para in paragraphs) {
            val sentences = splitIntoSentences(para)
            if (sentences.isEmpty()) continue
            val normalized = sentences.map { sentence ->
                if (keepCues) {
                    normalizeSentenceKeepCues(sentence)
                } else {
                    g2p.normalize(sentence, puncNorm = false)
                }
            }.filter { it.isNotBlank() }
            if (normalized.isNotEmpty()) {
                result += normalized
            }
        }
        return result
    }

    /**
     * Chuẩn hoá một câu nhưng vẫn giữ nguyên các thẻ cảm xúc `[cười]` / `<|emotion_k|>`.
     *
     * @param sentence Một câu thô đầu vào.
     * @return Câu đã chuẩn hoá kèm token `<|emotion_k|>`.
     */
    private fun normalizeSentenceKeepCues(sentence: String): String {
        if ('[' !in sentence && "<|emotion_" !in sentence) {
            return g2p.normalize(sentence, puncNorm = false)
        }
        val out = mutableListOf<String>()
        val parts = splitKeepingDelimiterMatches(EMOTION_SPLIT_REGEX, sentence)
        for (i in parts.indices) {
            val part = parts[i]
            if (i % 2 == 1) {
                out += emotionTagToken(part) ?: part
            } else if (part.isNotBlank()) {
                val norm = g2p.normalize(part, puncNorm = false)
                if (norm.isNotEmpty()) out += norm
            }
        }
        return out.filter { it.isNotEmpty() }.joinToString(" ")
    }

    companion object {
        const val DEFAULT_MAX_CHARS = 140
        const val DEFAULT_MIN_CHUNK_CHARS = 20
        private const val CHUNK_TAIL_SLACK = 15

        private val RE_NEWLINE_SPLIT = Regex("""[\r\n]+""")
        private val RE_MINOR_PUNCT = Regex("""(?<=[,;\:\-–—])\s+""")
        private val RE_TOKEN_KEEP_EN = Regex("""<en>.*?</en>|\S+""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val EMOTION_SPLIT_REGEX = Regex("""(\[[^\]]+\]|<\|emotion_\d+\|>)""")
        private val EMOTION_TOKEN_REGEX = Regex("""<\|emotion_\d+\|>""")

        private val EMOTION_TAG_TO_K = mapOf(
            "chuckle" to 1,
            "cười" to 1,
            "cuoi" to 1,
            "sigh" to 2,
            "thở dài" to 2,
            "tho dai" to 2,
            "clear throat" to 3,
            "hắng giọng" to 3,
            "hang giong" to 3,
        )

        private val ATTACHING_PUNCT = setOf('.', ',', '!', '?', ';', ':', '…', ')', ']', '}', '"', '\'', '’', '”')
        private val OPENERS = setOf('(', '[', '{', '“', '‘', '«', '‹', '「', '『')
        private val CLOSERS = setOf(')', ']', '}', '”', '’', '»', '›', '」', '』')
        private val SENT_END_CHARS = setOf('.', '!', '?', '…')
        private val TRAILING_CLOSE = CLOSERS + setOf('"', '\'', '’', '”')

        private val CONN_WORDS = setOf(
            "và", "nhưng", "hoặc", "song", "rồi", "nên", "vì", "nếu", "khi", "để", "do", "bởi",
        )
        private val CONN_PAIRS = setOf(
            "sau" to "khi", "trước" to "khi", "trong" to "khi", "mỗi" to "khi",
            "đến" to "khi", "tới" to "khi", "cho" to "nên", "cho" to "đến",
            "bởi" to "vì", "nếu" to "như", "tuy" to "nhiên", "thế" to "nhưng",
            "vì" to "vậy", "vì" to "thế", "do" to "đó", "sau" to "đó",
        )
        private val NUMBER_WORDS = setOf(
            "không", "một", "mốt", "hai", "ba", "bốn", "tư", "năm", "lăm", "sáu",
            "bảy", "tám", "chín", "mười", "mươi", "trăm", "nghìn", "ngàn",
            "triệu", "tỷ", "tỉ", "linh", "lẻ", "phẩy", "chấm",
        )

        /**
         * Quy đổi thẻ cảm xúc dạng `[cười]` hoặc `<|emotion_k|>` về token chuẩn `<|emotion_k|>`.
         *
         * @param tag Chuỗi thẻ bắt được theo cặp ngoặc.
         * @return Token `<|emotion_k|>` nếu hợp lệ, hoặc `null` nếu là ngoặc văn bản thường.
         */
        internal fun emotionTagToken(tag: String): String? {
            val trimmed = tag.trim()
            if (trimmed.startsWith("<|")) return trimmed
            if (trimmed.length < 2) return null
            val inner = trimmed.substring(1, trimmed.length - 1).trim().lowercase()
            val k = EMOTION_TAG_TO_K[inner] ?: return null
            return "<|emotion_$k|>"
        }

        /**
         * Tách chuỗi thành các đoạn xen kẽ: phần tử chỉ số chẵn là text, chỉ số lẻ là match.
         *
         * @param regex Regex tìm delimiter cần giữ lại.
         * @param input Chuỗi đầu vào.
         * @return Danh sách mảnh xen kẽ tương thích hành vi `re.split` có nhóm bắt trong Python.
         */
        internal fun splitKeepingDelimiterMatches(regex: Regex, input: String): List<String> {
            val out = mutableListOf<String>()
            var lastEnd = 0
            for (match in regex.findAll(input)) {
                out += input.substring(lastEnd, match.range.first)
                out += match.value
                lastEnd = match.range.last + 1
            }
            out += input.substring(lastEnd)
            return out
        }

        /**
         * Tách câu có nhận biết dấu ngoặc và trích dẫn để không cắt giữa lời thoại.
         *
         * @param text Văn bản thô một đoạn văn.
         * @return Danh sách câu đã cắt gọn.
         */
        internal fun splitIntoSentences(text: String): List<String> {
            if (text.isEmpty()) return emptyList()
            val (sentences, balanced) = scanSentences(text, quoteAware = true)
            if (balanced) return sentences
            return scanSentences(text, quoteAware = false).first
        }

        /**
         * Quét một lượt để tách câu ở dấu kết câu nằm ngoài ngoặc/trích dẫn.
         *
         * @param text Chuỗi đoạn văn cần tách.
         * @param quoteAware Có theo dõi trạng thái đóng/mở ngoặc hay không.
         * @return Cặp `(danh sách câu, trạng thái ngoặc cân bằng)`.
         */
        private fun scanSentences(text: String, quoteAware: Boolean): Pair<List<String>, Boolean> {
            val sentences = mutableListOf<String>()
            val n = text.length
            var start = 0
            var i = 0
            var depth = 0
            var inQuote = false

            while (i < n) {
                val ch = text[i]
                when {
                    quoteAware && ch == '"' -> inQuote = !inQuote
                    quoteAware && ch in OPENERS -> depth++
                    quoteAware && ch in CLOSERS -> if (depth > 0) depth--
                    ch in SENT_END_CHARS && depth == 0 && !inQuote -> {
                        var j = i + 1
                        while (j < n && text[j] in SENT_END_CHARS) j++
                        while (j < n && text[j] in TRAILING_CLOSE) j++
                        if (j >= n || text[j].isWhitespace()) {
                            sentences += text.substring(start, j)
                            start = j
                            i = j
                            continue
                        }
                        i = j
                        continue
                    }
                }
                i++
            }
            if (start < n) {
                sentences += text.substring(start)
            }
            return sentences.map { it.trim() }.filter { it.isNotEmpty() } to (depth == 0 && !inQuote)
        }

        /**
         * Đóng gói danh sách câu đã chuẩn hoá thành các chunk xấp xỉ `<= maxChars`.
         *
         * @param sentences Danh sách câu cùng một đoạn văn.
         * @param maxChars Trần độ dài tương đối mỗi chunk.
         * @return Danh sách chunk đã đóng gói.
         */
        internal fun packSentencesIntoChunks(sentences: List<String>, maxChars: Int): List<String> {
            val finalChunks = mutableListOf<String>()
            var buffer = ""

            for (rawSentence in sentences) {
                val sentence = rawSentence.trim()
                if (sentence.isEmpty()) continue

                if (sentence.length > maxChars) {
                    if (buffer.isNotEmpty()) {
                        finalChunks += buffer
                        buffer = ""
                    }
                    for (rawPart in RE_MINOR_PUNCT.split(sentence)) {
                        val part = rawPart.trim()
                        if (part.isEmpty()) continue
                        if (fits(buffer.length, part.length, maxChars)) {
                            buffer = if (buffer.isNotEmpty()) "$buffer $part" else part
                        } else {
                            if (buffer.isNotEmpty()) finalChunks += buffer
                            buffer = part
                            if (buffer.length > maxChars) {
                                val pieces = splitLongPart(buffer, maxChars)
                                if (pieces.size > 1) {
                                    finalChunks.addAll(pieces.dropLast(1))
                                }
                                buffer = pieces.lastOrNull().orEmpty()
                            }
                        }
                    }
                } else {
                    if (buffer.isNotEmpty() && !fits(buffer.length, sentence.length, maxChars)) {
                        finalChunks += buffer
                        buffer = sentence
                    } else {
                        buffer = if (buffer.isNotEmpty()) "$buffer $sentence" else sentence
                    }
                }
            }
            if (buffer.isNotEmpty()) {
                finalChunks += buffer
            }
            return finalChunks.map { it.trim() }.filter { it.isNotEmpty() }
        }

        /**
         * Khoảng nới trần cuối chunk để tránh tách rời mảnh vụn quá ngắn.
         *
         * @param maxChars Trần độ dài chunk cấu hình.
         * @return Số ký tự nới tối đa.
         */
        private fun tailSlack(maxChars: Int): Int = minOf(CHUNK_TAIL_SLACK, maxChars / 8)

        /**
         * Kiểm tra mảnh có độ dài [addLen] có ghép vừa vào chunk hiện tại dài [curLen] hay không.
         *
         * @param curLen Độ dài hiện tại của buffer.
         * @param addLen Độ dài phần sắp nối thêm.
         * @param maxChars Trần độ dài mục tiêu.
         * @return `true` nếu vừa hoặc nằm trong phần nới [tailSlack].
         */
        private fun fits(curLen: Int, addLen: Int, maxChars: Int): Boolean {
            val total = if (curLen > 0) curLen + 1 + addLen else addLen
            val slack = tailSlack(maxChars)
            return total <= maxChars || (addLen <= slack && total <= maxChars + slack)
        }

        /**
         * Chuẩn hoá token về chữ thường bỏ dấu ngoặc để đối chiếu từ nối/số.
         *
         * @param token Từ cần trích khoá.
         * @return Khoá chữ thường đã bỏ ngoặc bao quanh.
         */
        private fun connKey(token: String): String {
            val stripped = token.trim { it in "\"'“”‘’()[]«»…" }
            return stripped.lowercase()
        }

        /**
         * Kiểm tra token có thuộc cụm từ đọc số hay không để tránh cắt đôi con số.
         *
         * @param token Token cần kiểm tra.
         * @return `true` nếu là từ số học hoặc chữ số.
         */
        private fun isNumberWord(token: String): Boolean {
            val key = connKey(token)
            return key in NUMBER_WORDS || (key.isNotEmpty() && key.all { it.isDigit() })
        }

        /**
         * Tính độ dài chuỗi khi ghép `words[start until end]` bằng một dấu cách.
         *
         * @param words Danh sách từ.
         * @param start Chỉ số bắt đầu (bao gồm).
         * @param end Chỉ số kết thúc (loại trừ).
         * @return Tổng số ký tự của đoạn.
         */
        private fun spanLen(words: List<String>, start: Int, end: Int): Int {
            if (end <= start) return 0
            var chars = 0
            for (k in start until end) chars += words[k].length
            return chars + (end - start - 1)
        }

        /**
         * Tách chuỗi theo khoảng trắng nhưng giữ nguyên cụm `<en>...</en>` thành một token.
         *
         * @param text Chuỗi cần tách từ.
         * @return Danh sách token.
         */
        private fun tokenizeKeepEn(text: String): List<String> =
            RE_TOKEN_KEEP_EN.findAll(text).map { it.value }.toList()

        /**
         * Chia một đoạn văn quá dài không còn dấu ngắt thành các mảnh cân đối `<= maxChars`.
         *
         * @param part Mảnh văn bản dài cần cắt theo từ.
         * @param maxChars Giới hạn ký tự mục tiêu mỗi mảnh.
         * @return Danh sách các mảnh đã chia đều.
         */
        private fun splitLongPart(part: String, maxChars: Int): List<String> {
            val words = tokenizeKeepEn(part)
            if (words.isEmpty()) return emptyList()
            val minLeft = maxChars / 3
            val pieces = mutableListOf<String>()
            var start = 0
            while (start < words.size) {
                val rest = spanLen(words, start, words.size)
                if (rest <= maxChars) {
                    pieces += words.subList(start, words.size).joinToString(" ")
                    break
                }
                var full = start + 1
                while (full < words.size && spanLen(words, start, full + 1) <= maxChars) {
                    full++
                }
                if (fits(spanLen(words, start, full), spanLen(words, full, words.size), maxChars)) {
                    pieces += words.subList(start, words.size).joinToString(" ")
                    break
                }
                val nPieces = (rest + maxChars - 1) / maxChars
                val target = rest.toDouble() / nPieces.toDouble()
                val end = balancedCut(words, start, target, maxChars, minLeft)
                pieces += words.subList(start, end).joinToString(" ")
                start = end
            }
            return pieces
        }

        /**
         * Chọn vị trí cắt từ gần [target] nhất, ưu tiên trước từ nối và không xẻ đôi cặp từ nối/số.
         *
         * @param words Danh sách từ.
         * @param start Chỉ số bắt đầu.
         * @param target Độ dài mục tiêu của mảnh trái.
         * @param maxChars Trần ký tự tối đa.
         * @param minLeft Độ dài tối thiểu khi ưu tiên cắt tại từ nối.
         * @return Chỉ số kết thúc tốt nhất cho mảnh trái.
         */
        private fun balancedCut(
            words: List<String>,
            start: Int,
            target: Double,
            maxChars: Int,
            minLeft: Int,
        ): Int {
            var bestNatDist = Double.POSITIVE_INFINITY
            var bestNatIdx = -1
            var bestPlainDist = Double.POSITIVE_INFINITY
            var bestPlainIdx = -1
            var endCap = start + 1

            for (j in (start + 1) until words.size) {
                val left = spanLen(words, start, j)
                if (left > maxChars) break
                endCap = j
                val key = connKey(words[j])
                val prev = connKey(words[j - 1])
                val nxt = if (j + 1 < words.size) connKey(words[j + 1]) else ""
                val dist = abs(left - target)
                val insidePair = (prev to key) in CONN_PAIRS
                val natural = (key in CONN_WORDS || (key to nxt) in CONN_PAIRS) && prev !in CONN_WORDS
                if (natural && !insidePair && left >= minLeft && dist < bestNatDist) {
                    bestNatDist = dist
                    bestNatIdx = j
                }
                val plainOk = !insidePair && !(isNumberWord(words[j - 1]) && isNumberWord(words[j]))
                if (plainOk && dist < bestPlainDist) {
                    bestPlainDist = dist
                    bestPlainIdx = j
                }
            }
            if (bestNatIdx != -1) return bestNatIdx
            if (bestPlainIdx != -1) return bestPlainIdx

            var end = endCap
            while (end > start + 1 && (connKey(words[end - 1]) to connKey(words[end])) in CONN_PAIRS) {
                end--
            }
            return end
        }

        /**
         * Phân loại khoảng nghỉ sau [chunk] dựa vào dấu câu kết thúc (`.!?` hoặc ngắt phụ).
         *
         * @param chunk Nội dung chunk đã chốt dấu câu.
         * @return [ChunkGap.SENTENCE] hoặc [ChunkGap.MINOR].
         */
        private fun classifyGap(chunk: String): ChunkGap {
            val trimmed = chunk.trimEnd()
            return if (trimmed.isNotEmpty() && trimmed.last() in ".!?") {
                ChunkGap.SENTENCE
            } else {
                ChunkGap.MINOR
            }
        }

        /**
         * Tính độ dài ký tự đọc thực tế sau khi bỏ token `<|emotion_k|>`.
         *
         * @param chunk Nội dung chunk.
         * @return Số ký tự đọc được của chunk.
         */
        private fun effectiveLen(chunk: String): Int =
            EMOTION_TOKEN_REGEX.replace(chunk, "").trim().length

        /**
         * Gộp các chunk quá ngắn (< [minChars]) vào chunk kề cạnh, ưu tiên tránh gộp xuyên đoạn văn.
         *
         * @param chunks Danh sách chunk sẽ bị chỉnh sửa tại chỗ.
         * @param gaps Danh sách ranh giới tương ứng (`size == chunks.size - 1`).
         * @param minChars Độ dài hiệu dụng tối thiểu.
         */
        private fun mergeShortChunks(
            chunks: MutableList<String>,
            gaps: MutableList<ChunkGap>,
            minChars: Int,
        ) {
            while (chunks.size > 1) {
                var shortestIdx = -1
                var shortestEffLen = Int.MAX_VALUE
                for (i in chunks.indices) {
                    val effLen = effectiveLen(chunks[i])
                    if (effLen < minChars && effLen < shortestEffLen) {
                        shortestEffLen = effLen
                        shortestIdx = i
                    }
                }
                if (shortestIdx == -1) break

                val i = shortestIdx
                val mergeRight = when {
                    i == 0 -> true
                    i == chunks.lastIndex -> false
                    else -> {
                        val rightNotPara = gaps[i] != ChunkGap.PARA
                        val leftNotPara = gaps[i - 1] != ChunkGap.PARA
                        when {
                            rightNotPara != leftNotPara -> rightNotPara
                            else -> chunks[i + 1].length <= chunks[i - 1].length
                        }
                    }
                }

                if (mergeRight) {
                    chunks[i] = chunks[i] + " " + chunks.removeAt(i + 1)
                    gaps.removeAt(i)
                } else {
                    chunks[i - 1] = chunks[i - 1] + " " + chunks.removeAt(i)
                    gaps.removeAt(i - 1)
                }
            }
        }
    }
}
