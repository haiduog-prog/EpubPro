package com.epubpro.core.convert

import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Kết quả khớp cụm từ dài nhất tại một vị trí.
 *
 * @property length Số ký tự (UTF-16) của khóa đã khớp.
 * @property value Nghĩa tiếng Việt.
 */
data class PhraseMatch(val length: Int, val value: String)

/**
 * Từ điển cụm từ Trung → Việt tối ưu bộ nhớ cho tra cứu longest-match.
 *
 * Khóa được giữ trong RAM dưới dạng một [CharArray] liền khối đã sắp xếp (tra nhị phân không cấp phát),
 * còn nghĩa UTF-8 nằm trong [ByteBuffer] (thường là vùng mmap của file) và chỉ được giải mã khi khớp.
 * Với VietPhrase ~1,5 triệu dòng, heap dùng khoảng 20 MB thay vì hàng trăm MB nếu dùng HashMap<String, String>.
 */
class PhraseDictionary private constructor(
    private val keyOffsets: IntArray,
    private val keyChars: CharArray,
    private val valueOffsets: IntArray,
    private val values: ByteBuffer,
    /** Độ dài khóa dài nhất, giới hạn số lần thử khi khớp. */
    val maxKeyLength: Int
) {
    /** Số mục trong từ điển. */
    val size: Int get() = keyOffsets.size - 1

    /**
     * Tìm cụm từ dài nhất trong từ điển bắt đầu tại [start].
     *
     * @param text Văn bản nguồn.
     * @param start Vị trí bắt đầu.
     * @param limit Độ dài tối đa được phép khớp.
     * @return Kết quả khớp, hoặc null nếu không có khóa nào là tiền tố tại vị trí đó.
     */
    fun longestMatch(text: CharSequence, start: Int, limit: Int = Int.MAX_VALUE): PhraseMatch? {
        val maxLength = minOf(maxKeyLength, text.length - start, limit)
        for (length in maxLength downTo 1) {
            val index = find(text, start, length)
            if (index >= 0) return PhraseMatch(length, valueAt(index))
        }
        return null
    }

    /**
     * Tra nghĩa của một khóa chính xác.
     *
     * @param key Khóa tiếng Trung.
     * @return Nghĩa hoặc null nếu không có.
     */
    fun get(key: String): String? {
        val index = find(key, 0, key.length)
        return if (index >= 0) valueAt(index) else null
    }

    /**
     * Ghi từ điển ra file nhị phân để nạp lại bằng [load].
     *
     * @param target File đích; ghi qua file tạm rồi đổi tên.
     * @throws java.io.IOException Nếu không ghi được.
     */
    fun writeTo(target: File) {
        val temporary = File(target.parentFile, "${target.name}.tmp")
        try {
            DataOutputStream(BufferedOutputStream(FileOutputStream(temporary), 1 shl 16)).use { out ->
                out.writeInt(MAGIC)
                out.writeInt(FORMAT_VERSION)
                out.writeInt(size)
                out.writeInt(maxKeyLength)
                out.writeInt(keyChars.size)
                out.writeInt(values.limit())
                keyOffsets.forEach(out::writeInt)
                keyChars.forEach { out.writeChar(it.code) }
                valueOffsets.forEach(out::writeInt)
                val copy = values.duplicate().apply { position(0) }
                val buffer = ByteArray(1 shl 16)
                while (copy.hasRemaining()) {
                    val chunk = minOf(buffer.size, copy.remaining())
                    copy.get(buffer, 0, chunk)
                    out.write(buffer, 0, chunk)
                }
            }
            if (target.exists() && !target.delete()) throw java.io.IOException("Không thay được từ điển cũ")
            if (!temporary.renameTo(target)) throw java.io.IOException("Không lưu được từ điển")
        } finally {
            temporary.delete()
        }
    }

    /**
     * Tìm nhị phân khóa bằng đúng đoạn `text[start, start + length)`.
     *
     * @param text Văn bản nguồn.
     * @param start Vị trí đầu đoạn.
     * @param length Độ dài đoạn.
     * @return Chỉ số mục hoặc -1.
     */
    private fun find(text: CharSequence, start: Int, length: Int): Int {
        var low = 0
        var high = size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val order = compareKey(mid, text, start, length)
            when {
                order < 0 -> low = mid + 1
                order > 0 -> high = mid - 1
                else -> return mid
            }
        }
        return -1
    }

    /**
     * So sánh khóa thứ [index] với đoạn văn bản theo thứ tự code unit UTF-16 (giống [String.compareTo]).
     *
     * @param index Chỉ số khóa.
     * @param text Văn bản nguồn.
     * @param start Vị trí đầu đoạn.
     * @param length Độ dài đoạn.
     * @return Âm, 0 hoặc dương.
     */
    private fun compareKey(index: Int, text: CharSequence, start: Int, length: Int): Int {
        val keyStart = keyOffsets[index]
        val keyLength = keyOffsets[index + 1] - keyStart
        val common = minOf(keyLength, length)
        for (i in 0 until common) {
            val diff = keyChars[keyStart + i] - text[start + i]
            if (diff != 0) return diff
        }
        return keyLength - length
    }

    /**
     * Giải mã nghĩa UTF-8 của mục thứ [index].
     *
     * @param index Chỉ số mục.
     * @return Nghĩa.
     */
    private fun valueAt(index: Int): String {
        val from = valueOffsets[index]
        val to = valueOffsets[index + 1]
        val bytes = ByteArray(to - from)
        values.duplicate().apply { position(from) }.get(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    companion object {
        private const val MAGIC = 0x45504356 // "EPCV"
        private const val FORMAT_VERSION = 1
        private const val HEADER_INTS = 6

        /**
         * Tạo từ điển trong bộ nhớ từ danh sách mục (dùng cho Name của từng truyện và kiểm thử).
         *
         * @param entries Cặp (khóa, nghĩa); khóa trùng thì mục xuất hiện sau thắng.
         * @return Từ điển.
         */
        fun fromEntries(entries: Iterable<Pair<String, String>>): PhraseDictionary {
            val builder = DictionaryBuilder()
            entries.forEach { (key, value) -> builder.add(key, value) }
            return builder.build()
        }

        /**
         * Nạp từ điển đã biên dịch bằng mmap; chỉ khóa và bảng offset được chép vào heap.
         *
         * @param file File do [writeTo] tạo.
         * @return Từ điển.
         * @throws IllegalStateException Nếu file sai định dạng hoặc phiên bản.
         */
        fun load(file: File): PhraseDictionary = RandomAccessFile(file, "r").use { raf ->
            val mapped = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())
            check(mapped.int == MAGIC) { "Sai định dạng từ điển" }
            check(mapped.int == FORMAT_VERSION) { "Phiên bản từ điển không hỗ trợ" }
            val count = mapped.int
            val maxKeyLength = mapped.int
            val keyCharCount = mapped.int
            val valueByteCount = mapped.int
            check(count >= 0 && keyCharCount >= 0 && valueByteCount >= 0) { "Header từ điển hỏng" }
            val expected = HEADER_INTS * 4L + (count + 1) * 8L + keyCharCount * 2L + valueByteCount
            check(raf.length() == expected) { "Kích thước từ điển không khớp" }

            val keyOffsets = IntArray(count + 1).also { mapped.asIntBuffer().get(it) }
            mapped.position(mapped.position() + (count + 1) * 4)
            val keyChars = CharArray(keyCharCount).also { mapped.asCharBuffer().get(it) }
            mapped.position(mapped.position() + keyCharCount * 2)
            val valueOffsets = IntArray(count + 1).also { mapped.asIntBuffer().get(it) }
            mapped.position(mapped.position() + (count + 1) * 4)
            val values = mapped.slice()
            PhraseDictionary(keyOffsets, keyChars, valueOffsets, values, maxKeyLength)
        }

        /**
         * Đọc số mục từ header mà không nạp toàn bộ file.
         *
         * @param file File từ điển.
         * @return Số mục, hoặc null nếu file hỏng.
         */
        fun readEntryCount(file: File): Int? = runCatching {
            RandomAccessFile(file, "r").use { raf ->
                if (raf.readInt() != MAGIC || raf.readInt() != FORMAT_VERSION) null else raf.readInt()
            }
        }.getOrNull()

        /**
         * Tạo từ điển từ mảng đã sắp xếp và khử trùng (dùng nội bộ bởi [DictionaryBuilder]).
         *
         * @param keyOffsets Offset đầu mỗi khóa trong [keyChars], phần tử cuối là tổng độ dài.
         * @param keyChars Toàn bộ khóa nối liền theo thứ tự tăng dần.
         * @param valueOffsets Offset đầu mỗi nghĩa trong [values].
         * @param values Nghĩa UTF-8 nối liền.
         * @param maxKeyLength Độ dài khóa dài nhất.
         * @return Từ điển.
         */
        internal fun fromSorted(
            keyOffsets: IntArray,
            keyChars: CharArray,
            valueOffsets: IntArray,
            values: ByteArray,
            maxKeyLength: Int
        ): PhraseDictionary = PhraseDictionary(keyOffsets, keyChars, valueOffsets, ByteBuffer.wrap(values), maxKeyLength)
    }
}
