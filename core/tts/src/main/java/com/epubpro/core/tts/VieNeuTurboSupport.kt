package com.epubpro.core.tts

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.channels.FileChannel
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.random.Random

// Các thành phần không phụ thuộc ONNX Runtime của VieNeu-TTS v3 Turbo, port từ SDK `vieneu` 3.8.5
// (Apache-2.0, https://github.com/pnnbao97/VieNeu-TTS): `core_utils.max_expected_frames`,
// `rep_history.RepetitionHistory` và `onnx_runtime_lite._sample`.

/**
 * Quy tắc văn bản phía Turbo: trần số frame theo độ dài phoneme để chặn model "nói thêm".
 */
object VieNeuTurboText {
    private const val MAX_FRAMES_PER_PHONE = 2.0
    private const val FRAME_CAP_SLACK = 24
    private const val SINGLE_WORD_MAX_FRAMES = 13
    private const val SYLLABLE_CAP_PER_EXTRA = 5
    private const val SYLLABLE_CAP_MAX_SYL = 4
    private const val SINGLE_WORD_MAX_PHONES = 24
    private val FRAME_MARKUP = Regex("""<\|emotion_\d+\|>|</?en>""")
    private const val IPA_VOWELS = "aeiouyæɐɑɒɔəɘɛɜɤɯɵøœʉʊʌɪɨɚɝᵻᵿ"

    /**
     * Số frame tối đa hợp lý cho một chunk có chuỗi [phonemes] (`max_expected_frames`).
     *
     * @param phonemes Chuỗi âm vị của chunk.
     * @return Trần số frame (12,5 frame mỗi giây audio).
     */
    fun maxExpectedFrames(phonemes: String): Int {
        val stripped = FRAME_MARKUP.replace(phonemes, "")
        val effLen = stripped.length
        var cap = FRAME_CAP_SLACK + ceil(MAX_FRAMES_PER_PHONE * effLen).toInt()
        if (isCueOnly(phonemes)) return minOf(cap, SINGLE_WORD_MAX_FRAMES)
        if ("<|emotion_" !in phonemes) {
            val syl = maxOf(1, syllableCount(phonemes))
            if (syl <= SYLLABLE_CAP_MAX_SYL && effLen <= SINGLE_WORD_MAX_PHONES * syl) {
                cap = minOf(cap, SINGLE_WORD_MAX_FRAMES + SYLLABLE_CAP_PER_EXTRA * (syl - 1))
            }
        }
        return cap
    }

    /**
     * Chunk chỉ gồm tag cảm xúc, không có tiếng nào.
     *
     * @param phonemes Chuỗi âm vị.
     * @return `true` nếu chỉ có `<|emotion_k|>` và dấu câu.
     */
    fun isCueOnly(phonemes: String): Boolean =
        "<|emotion_" in phonemes && FRAME_MARKUP.replace(phonemes, "").none { it.isLetter() }

    /**
     * Ước lượng số tiếng (âm tiết) từ chuỗi phoneme `sea-g2p` (`syllable_count`).
     *
     * @param phonemes Chuỗi âm vị.
     * @return Số âm tiết.
     */
    fun syllableCount(phonemes: String): Int {
        val stripped = FRAME_MARKUP.replace(phonemes, "")
        var total = 0
        for (tok in stripped.split(Regex("\\s+"))) {
            if (tok.isEmpty()) continue
            var groups = 0
            var inVowel = false
            var consonantSeen = true
            for (ch in tok) {
                when {
                    ch in IPA_VOWELS -> {
                        if (!inVowel && consonantSeen) groups++
                        inVowel = true
                        consonantSeen = false
                    }
                    ch in "ːˈˌ" || ch.isDigit() -> {
                        if (ch in "ˈˌ" && groups > 0) {
                            inVowel = false
                            consonantSeen = true
                        } else {
                            inVowel = false
                        }
                    }
                    else -> {
                        inVowel = false
                        consonantSeen = true
                    }
                }
            }
            if (tok.any { it.isLetter() }) total += maxOf(1, groups)
        }
        return total
    }
}

/**
 * Lịch sử code một codebook trong cửa sổ trượt [window] frame, dùng cho repetition penalty
 * (`rep_history._ChannelWindow`). `window <= 0` giữ lịch sử vĩnh viễn.
 *
 * @param window Số frame gần nhất bị phạt lặp.
 */
class VieNeuTurboRepetitionWindow(private val window: Int) {
    private val counts = HashMap<Int, Int>()
    private val order = ArrayDeque<Int>()

    /**
     * Ghi nhận code vừa sinh; trục xuất code cũ nhất khi vượt cửa sổ.
     *
     * @param code Code vừa chọn.
     */
    fun add(code: Int) {
        counts[code] = (counts[code] ?: 0) + 1
        if (window > 0) {
            order.addLast(code)
            if (order.size > window) {
                val old = order.removeFirst()
                val c = counts.getValue(old)
                if (c <= 1) counts.remove(old) else counts[old] = c - 1
            }
        }
    }

    /** Tập code đang bị phạt lặp. */
    val codes: Set<Int> get() = counts.keys
}

/**
 * Bộ lấy mẫu logits của Turbo: repetition penalty → nhiệt độ → top-k → softmax → top-p (`_sample`).
 *
 * @param random Nguồn ngẫu nhiên (truyền seed cố định để tái lập kết quả).
 */
class VieNeuTurboSampler(private val random: Random = Random.Default) {

    /**
     * Chọn một id từ [logits]. `temperature <= 0` lấy argmax (greedy).
     *
     * @param logits Logits (bị sửa tại chỗ khi áp penalty).
     * @param temperature Nhiệt độ lấy mẫu.
     * @param topK Số ứng viên lớn nhất giữ lại (`<= 0` hoặc `>= vocab` là không giới hạn).
     * @param topP Ngưỡng nucleus (`>= 1` là tắt).
     * @param repetitionPenalty Hệ số phạt lặp (`1` là tắt).
     * @param history Code gần đây bị phạt, hoặc `null`.
     * @return Id được chọn.
     */
    fun sample(
        logits: FloatArray,
        temperature: Float,
        topK: Int,
        topP: Float,
        repetitionPenalty: Float,
        history: Set<Int>?,
    ): Int {
        if (repetitionPenalty != 1f && !history.isNullOrEmpty()) {
            for (code in history) {
                val v = logits[code]
                logits[code] = if (v < 0) v * repetitionPenalty else v / repetitionPenalty
            }
        }
        if (temperature <= 0f) {
            var best = 0
            for (j in 1 until logits.size) if (logits[j] > logits[best]) best = j
            return best
        }
        val vocab = logits.size
        val k = if (topK in 1 until vocab) topK else vocab
        val candidates = topIndices(logits, k)
        val probs = DoubleArray(k)
        val maxLogit = logits[candidates[0]] / temperature
        var sum = 0.0
        for (i in 0 until k) {
            probs[i] = exp((logits[candidates[i]] / temperature - maxLogit).toDouble())
            sum += probs[i]
        }
        for (i in 0 until k) probs[i] /= sum
        if (topP < 1f) {
            var cumulative = 0.0
            var kept = 0.0
            for (i in 0 until k) {
                val keep = cumulative < topP
                cumulative += probs[i]
                if (!keep) probs[i] = 0.0
                kept += probs[i]
            }
            for (i in 0 until k) probs[i] /= kept
        }
        var r = random.nextDouble()
        for (i in 0 until k) {
            r -= probs[i]
            if (r <= 0) return candidates[i]
        }
        return candidates[k - 1]
    }

    /** Lấy [k] chỉ số có logits lớn nhất, sắp giảm dần. */
    private fun topIndices(logits: FloatArray, k: Int): IntArray {
        if (k >= logits.size) return logits.indices.sortedByDescending { logits[it] }.toIntArray()
        val idx = IntArray(k) { -1 }
        val vals = FloatArray(k) { Float.NEGATIVE_INFINITY }
        for (j in logits.indices) {
            val v = logits[j]
            if (v <= vals[k - 1]) continue
            var p = k - 1
            while (p > 0 && vals[p - 1] < v) {
                vals[p] = vals[p - 1]
                idx[p] = idx[p - 1]
                p--
            }
            vals[p] = v
            idx[p] = j
        }
        return idx
    }
}

/**
 * Mảng float32 trong file `.npz` không nén, ánh xạ trực tiếp vào bộ nhớ (mmap) thay vì nạp lên heap Java.
 *
 * @property shape Kích thước mảng.
 * @property buffer Vùng nhớ chỉ đọc theo thứ tự little-endian (direct buffer).
 */
class VieNeuTurboNpyArray(val shape: IntArray, val buffer: FloatBuffer) {
    companion object {
        /**
         * Ánh xạ mọi mảng `<f4` của một file `.npz` lưu không nén (`np.savez`).
         *
         * @param file File `.npz`.
         * @return Bảng tên mảng (không đuôi `.npy`) → mảng đã ánh xạ.
         * @throws IllegalArgumentException Khi file nén, không phải zip hoặc dtype không hỗ trợ.
         */
        fun mapNpz(file: File): Map<String, VieNeuTurboNpyArray> = RandomAccessFile(file, "r").use { raf ->
            val channel = raf.channel
            val size = channel.size()
            val tailLen = minOf(size, 65_557L).toInt()
            val tail = ByteBuffer.allocate(tailLen).order(ByteOrder.LITTLE_ENDIAN)
            channel.read(tail, size - tailLen)
            var eocd = -1
            for (i in tailLen - 22 downTo 0) {
                if (tail.getInt(i) == 0x06054b50) {
                    eocd = i
                    break
                }
            }
            require(eocd >= 0) { "Không tìm thấy End Of Central Directory trong ${file.name}" }
            val entries = tail.getShort(eocd + 10).toInt() and 0xFFFF
            val cdSize = tail.getInt(eocd + 12).toLong() and 0xFFFFFFFFL
            val cdOffset = tail.getInt(eocd + 16).toLong() and 0xFFFFFFFFL
            val cd = ByteBuffer.allocate(cdSize.toInt()).order(ByteOrder.LITTLE_ENDIAN)
            channel.read(cd, cdOffset)
            val result = HashMap<String, VieNeuTurboNpyArray>()
            var p = 0
            repeat(entries) {
                require(cd.getInt(p) == 0x02014b50) { "Central directory hỏng trong ${file.name}" }
                val method = cd.getShort(p + 10).toInt()
                val nameLen = cd.getShort(p + 28).toInt() and 0xFFFF
                val extraLen = cd.getShort(p + 30).toInt() and 0xFFFF
                val commentLen = cd.getShort(p + 32).toInt() and 0xFFFF
                val localOffset = cd.getInt(p + 42).toLong() and 0xFFFFFFFFL
                val name = String(ByteArray(nameLen).also { cd.position(p + 46); cd.get(it) }, Charsets.UTF_8)
                cd.position(0)
                require(method == 0) { "$name trong ${file.name} bị nén, không ánh xạ được" }
                val local = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN)
                channel.read(local, localOffset)
                val dataStart = localOffset + 30 + (local.getShort(26).toInt() and 0xFFFF) + (local.getShort(28).toInt() and 0xFFFF)
                result[name.removeSuffix(".npy")] = mapNpy(channel, dataStart, name)
                p += 46 + nameLen + extraLen + commentLen
            }
            result
        }

        /** Đọc header `.npy` tại [start] rồi ánh xạ phần dữ liệu float32. */
        private fun mapNpy(channel: FileChannel, start: Long, name: String): VieNeuTurboNpyArray {
            val head = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            channel.read(head, start)
            require(head.get(0) == 0x93.toByte() && String(ByteArray(5) { head.get(1 + it) }) == "NUMPY") { "$name không phải npy" }
            val major = head.get(6).toInt()
            val headerLen: Int
            val headerStart: Long
            if (major == 1) {
                headerLen = head.getShort(8).toInt() and 0xFFFF
                headerStart = start + 10
            } else {
                headerLen = head.getInt(8)
                headerStart = start + 12
            }
            val headerBuf = ByteBuffer.allocate(headerLen)
            channel.read(headerBuf, headerStart)
            val header = String(headerBuf.array(), Charsets.ISO_8859_1)
            require("'<f4'" in header && "'fortran_order': False" in header) { "$name: dtype không hỗ trợ ($header)" }
            val shape = Regex("'shape': \\(([^)]*)\\)").find(header)!!.groupValues[1]
                .split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { it.toInt() }.toIntArray()
            val count = shape.fold(1L) { a, b -> a * b }
            val mapped = channel.map(FileChannel.MapMode.READ_ONLY, headerStart + headerLen, count * 4)
            return VieNeuTurboNpyArray(shape, mapped.order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer())
        }
    }
}

/**
 * Sinh bytes của một model ONNX tối giản `y = MatMul(w, x)` với `w: [V, H]`, `x: [H, 1]`, dùng làm "head"
 * chạy bằng ONNX Runtime (MLAS NEON) thay cho vòng lặp Kotlin. `w` được truyền vào dưới dạng input trỏ
 * thẳng vùng nhớ mmap nên không có bản sao trọng số.
 */
internal object VieNeuTurboMatMulModel {
    private const val FLOAT = 1

    /**
     * Tạo `ModelProto` đã mã hoá protobuf.
     *
     * @param hidden Số chiều ẩn `H` (cố định, ví dụ 768).
     * @return Bytes model ONNX (opset 13).
     */
    fun bytes(hidden: Int): ByteArray {
        val node = msg {
            str(1, "w"); str(1, "x"); str(2, "y"); str(3, "head"); str(4, "MatMul")
        }
        val graph = msg {
            bytes(1, node)
            str(2, "vieneu_turbo_head")
            bytes(11, valueInfo("w", listOf(Dim.Param("V"), Dim.Value(hidden.toLong()))))
            bytes(11, valueInfo("x", listOf(Dim.Value(hidden.toLong()), Dim.Value(1))))
            bytes(12, valueInfo("y", listOf(Dim.Param("V"), Dim.Value(1))))
        }
        val opset = msg { str(1, ""); varint(2, 13) }
        return msg {
            varint(1, 8)
            str(2, "epubpro")
            bytes(7, graph)
            bytes(8, opset)
        }
    }

    private sealed class Dim {
        data class Value(val v: Long) : Dim()
        data class Param(val name: String) : Dim()
    }

    private fun valueInfo(name: String, dims: List<Dim>): ByteArray {
        val shape = msg {
            for (d in dims) {
                bytes(1, msg { if (d is Dim.Value) varint(1, d.v) else str(2, (d as Dim.Param).name) })
            }
        }
        val tensorType = msg { varint(1, FLOAT.toLong()); bytes(2, shape) }
        val type = msg { bytes(1, tensorType) }
        return msg { str(1, name); bytes(2, type) }
    }

    private class Writer {
        val out = ByteArrayOutputStream()

        fun varint(field: Int, value: Long) {
            writeRawVarint((field shl 3).toLong())
            writeRawVarint(value)
        }

        fun bytes(field: Int, data: ByteArray) {
            writeRawVarint(((field shl 3) or 2).toLong())
            writeRawVarint(data.size.toLong())
            out.write(data)
        }

        fun str(field: Int, s: String) = bytes(field, s.toByteArray(Charsets.UTF_8))

        private fun writeRawVarint(v: Long) {
            var value = v
            while (true) {
                if (value and 0x7FL.inv() == 0L) {
                    out.write(value.toInt())
                    return
                }
                out.write(((value and 0x7F) or 0x80).toInt())
                value = value ushr 7
            }
        }
    }

    private inline fun msg(block: Writer.() -> Unit): ByteArray = Writer().apply(block).out.toByteArray()
}
