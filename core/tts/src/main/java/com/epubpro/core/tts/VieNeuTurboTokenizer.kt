package com.epubpro.core.tts

import org.json.JSONObject
import java.text.Normalizer
import java.util.regex.Pattern

/**
 * Tokenizer byte-level BPE của VieNeu-TTS v3 Turbo, đọc từ `tokenizer.json` (định dạng Hugging Face `tokenizers`).
 *
 * Mô phỏng đúng pipeline của file cấu hình: chuẩn hoá NFC → tách token đặc biệt (`<|emotion_1|>`...) →
 * tách bằng regex kiểu GPT-2 → ánh xạ byte UTF-8 sang ký tự (ByteLevel) → ghép cặp theo thứ hạng merge.
 * Ký hiệu không có trong vocab được thay bằng `<|unk|>` (không gộp nhiều unk liền nhau).
 *
 * @property vocab Bảng token → id.
 * @property mergeRanks Thứ hạng của từng cặp merge (`"a b"`), hạng nhỏ được ghép trước.
 * @property specialTokens Token đặc biệt được nhận diện nguyên văn trước khi tách regex.
 * @property unkId Id của token `<|unk|>`.
 */
class VieNeuTurboTokenizer private constructor(
    private val vocab: Map<String, Int>,
    private val mergeRanks: Map<String, Int>,
    private val specialTokens: Map<String, Int>,
    private val unkId: Int,
) {
    private val specialPattern: Pattern? = specialTokens.keys
        .sortedByDescending { it.length }
        .takeIf { it.isNotEmpty() }
        ?.joinToString("|") { Pattern.quote(it) }
        ?.let { Pattern.compile(it) }

    private val cache = object : LinkedHashMap<String, IntArray>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, IntArray>?): Boolean = size > CACHE_SIZE
    }

    /**
     * Mã hoá chuỗi âm vị thành danh sách token id, không thêm token đặc biệt đầu/cuối
     * (tương đương `encode(text, add_special_tokens=False)`).
     *
     * @param text Chuỗi âm vị do `sea-g2p` sinh ra (có thể chứa `<|emotion_k|>`).
     * @return Mảng token id.
     */
    fun encode(text: String): IntArray {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFC)
        val out = ArrayList<Int>(normalized.length)
        var cursor = 0
        val matcher = specialPattern?.matcher(normalized)
        while (matcher != null && matcher.find()) {
            encodeOrdinary(normalized.substring(cursor, matcher.start()), out)
            out += specialTokens.getValue(matcher.group())
            cursor = matcher.end()
        }
        encodeOrdinary(normalized.substring(cursor), out)
        return out.toIntArray()
    }

    /** Tách đoạn văn bản thường bằng regex rồi chạy BPE cho từng mảnh. */
    private fun encodeOrdinary(segment: String, out: MutableList<Int>) {
        if (segment.isEmpty()) return
        val matcher = SPLIT_PATTERN.matcher(segment)
        while (matcher.find()) {
            val piece = matcher.group()
            if (piece.isEmpty()) continue
            val ids = synchronized(cache) { cache[piece] } ?: bpe(toByteLevel(piece)).also { ids ->
                synchronized(cache) { cache[piece] = ids }
            }
            for (id in ids) out += id
        }
    }

    /**
     * Chạy BPE trên một mảnh đã ánh xạ ByteLevel: ghép lặp cặp kề nhau có thứ hạng nhỏ nhất.
     *
     * @param symbols Mảnh văn bản ByteLevel.
     * @return Token id của mảnh.
     */
    private fun bpe(symbols: String): IntArray {
        val parts = ArrayList<String>(symbols.length)
        var i = 0
        while (i < symbols.length) {
            val cp = symbols.codePointAt(i)
            val len = Character.charCount(cp)
            parts += symbols.substring(i, i + len)
            i += len
        }
        while (parts.size > 1) {
            var bestRank = Int.MAX_VALUE
            var bestIndex = -1
            for (k in 0 until parts.size - 1) {
                val rank = mergeRanks["${parts[k]} ${parts[k + 1]}"] ?: continue
                if (rank < bestRank) {
                    bestRank = rank
                    bestIndex = k
                }
            }
            if (bestIndex < 0) break
            // Ghép mọi lần xuất hiện của cặp tốt nhất từ trái sang phải, giống thuật toán BPE gốc.
            val left = parts[bestIndex]
            val right = parts[bestIndex + 1]
            val merged = ArrayList<String>(parts.size)
            var k = 0
            while (k < parts.size) {
                if (k < parts.size - 1 && parts[k] == left && parts[k + 1] == right) {
                    merged += left + right
                    k += 2
                } else {
                    merged += parts[k]
                    k++
                }
            }
            parts.clear()
            parts.addAll(merged)
        }
        return IntArray(parts.size) { vocab[parts[it]] ?: unkId }
    }

    companion object {
        private const val CACHE_SIZE = 4_096

        private const val SPLIT_REGEX =
            "(?i:'s|'t|'re|'ve|'m|'ll|'d)|[^\\r\\n\\p{L}\\p{N}]?\\p{L}+|\\p{N}| ?[^\\s\\p{L}\\p{N}]+[\\r\\n]*|\\s*[\\r\\n]+|\\s+(?!\\S)|\\s+"

        /**
         * Regex tách trước của file cấu hình (kiểu GPT-2/Qwen) với `\s` theo Unicode như `fancy-regex`.
         * Regex ICU của Android vốn hiểu `\s` theo Unicode và không nhận cờ `UNICODE_CHARACTER_CLASS`,
         * nên chỉ bật cờ này trên JVM (unit test).
         */
        private val SPLIT_PATTERN: Pattern = try {
            Pattern.compile(SPLIT_REGEX, Pattern.UNICODE_CHARACTER_CLASS)
        } catch (_: IllegalArgumentException) {
            Pattern.compile(SPLIT_REGEX)
        }

        /** Bảng ánh xạ byte → ký tự của ByteLevel (`bytes_to_unicode` của GPT-2). */
        private val BYTE_TO_CHAR: CharArray = run {
            val table = CharArray(256)
            val printable = (('!'.code..'~'.code) + ('¡'.code..'¬'.code) + ('®'.code..'ÿ'.code)).toSet()
            var extra = 0
            for (b in 0 until 256) {
                table[b] = if (b in printable) b.toChar() else (256 + extra++).toChar()
            }
            table
        }

        /**
         * Đổi chuỗi sang dạng ByteLevel: mỗi byte UTF-8 thành một ký tự hiển thị được.
         *
         * @param text Mảnh văn bản gốc.
         * @return Chuỗi ByteLevel tương ứng.
         */
        internal fun toByteLevel(text: String): String {
            val bytes = text.toByteArray(Charsets.UTF_8)
            val sb = StringBuilder(bytes.size)
            for (b in bytes) sb.append(BYTE_TO_CHAR[b.toInt() and 0xFF])
            return sb.toString()
        }

        /**
         * Nạp tokenizer từ nội dung `tokenizer.json`.
         *
         * @param json Nội dung file `tokenizer.json`.
         * @return [VieNeuTurboTokenizer] sẵn sàng dùng.
         * @throws IllegalArgumentException Khi model không phải BPE hoặc thiếu token `<|unk|>`.
         */
        fun fromJson(json: String): VieNeuTurboTokenizer {
            val root = JSONObject(json)
            val model = root.getJSONObject("model")
            require(model.getString("type") == "BPE") { "tokenizer.json không phải BPE" }
            val vocabJson = model.getJSONObject("vocab")
            val vocab = HashMap<String, Int>(vocabJson.length() * 2)
            for (key in vocabJson.keys()) vocab[key] = vocabJson.getInt(key)
            val mergesJson = model.getJSONArray("merges")
            val ranks = HashMap<String, Int>(mergesJson.length() * 2)
            for (i in 0 until mergesJson.length()) {
                val entry = mergesJson.get(i)
                val pair = if (entry is String) entry else mergesJson.getJSONArray(i).let { "${it.getString(0)} ${it.getString(1)}" }
                ranks.putIfAbsent(pair, i)
            }
            val special = HashMap<String, Int>()
            val added = root.optJSONArray("added_tokens")
            if (added != null) {
                for (i in 0 until added.length()) {
                    val token = added.getJSONObject(i)
                    special[token.getString("content")] = token.getInt("id")
                }
            }
            val unkToken = model.optString("unk_token", "<|unk|>")
            val unkId = vocab[unkToken] ?: special[unkToken]
                ?: throw IllegalArgumentException("Thiếu token $unkToken")
            return VieNeuTurboTokenizer(vocab, ranks, special, unkId)
        }
    }
}
