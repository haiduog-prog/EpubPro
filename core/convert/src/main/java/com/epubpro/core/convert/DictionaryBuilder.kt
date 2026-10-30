package com.epubpro.core.convert

import java.io.Reader

/**
 * Dựng [PhraseDictionary] từ dữ liệu lớn mà không tạo hàng triệu đối tượng String.
 *
 * Khóa và nghĩa được nối vào mảng nguyên thủy tăng dần; sau đó sắp xếp ổn định mảng chỉ số bằng merge sort
 * trên [IntArray] và khử trùng (mục xuất hiện sau thắng, giống cách QuickTranslator ghi đè).
 */
class DictionaryBuilder {
    private var keyChars = CharArray(INITIAL_CAPACITY)
    private var keyCharCount = 0
    private var keyStarts = IntArray(INITIAL_ENTRIES)
    private var keyLengths = IntArray(INITIAL_ENTRIES)
    private var valueBytes = ByteArray(INITIAL_CAPACITY)
    private var valueByteCount = 0
    private var valueStarts = IntArray(INITIAL_ENTRIES)
    private var valueLengths = IntArray(INITIAL_ENTRIES)
    private var count = 0

    /** Số mục đã thêm (chưa khử trùng). */
    val addedCount: Int get() = count

    /**
     * Thêm một mục.
     *
     * @param key Khóa tiếng Trung, đã cắt khoảng trắng; khóa rỗng bị bỏ qua.
     * @param value Nghĩa tiếng Việt.
     */
    fun add(key: String, value: String) {
        if (key.isEmpty()) return
        ensureEntryCapacity()
        ensureKeyCapacity(key.length)
        key.toCharArray(keyChars, keyCharCount)
        keyStarts[count] = keyCharCount
        keyLengths[count] = key.length
        keyCharCount += key.length

        val bytes = value.toByteArray(Charsets.UTF_8)
        ensureValueCapacity(bytes.size)
        bytes.copyInto(valueBytes, valueByteCount)
        valueStarts[count] = valueByteCount
        valueLengths[count] = bytes.size
        valueByteCount += bytes.size
        count++
    }

    /**
     * Đọc file định dạng QuickTranslator (`中文=nghĩa1/nghĩa2`), chỉ giữ nghĩa đầu tiên.
     *
     * Bỏ qua dòng trống, dòng chú thích (`#`, `//`), BOM đầu file và dòng dài hơn [MAX_LINE_CHARS]
     * (đọc theo khối nên file nhị phân không có ký tự xuống dòng cũng không tạo ra một chuỗi khổng lồ).
     *
     * @param reader Nguồn văn bản đã giải mã.
     * @param onProgress Callback báo số dòng đã đọc mỗi 50.000 dòng.
     */
    fun addQuickTranslatorLines(reader: Reader, onProgress: (Int) -> Unit = {}) {
        var lineNumber = 0
        val line = StringBuilder()
        var overflow = false
        val buffer = CharArray(READ_BUFFER_CHARS)

        fun endLine() {
            lineNumber++
            if (lineNumber % PROGRESS_INTERVAL == 0) onProgress(lineNumber)
            if (!overflow) parseQuickTranslatorLine(line.toString())?.let { (key, value) -> add(key, value) }
            line.setLength(0)
            overflow = false
        }

        while (true) {
            val read = reader.read(buffer)
            if (read < 0) break
            for (i in 0 until read) {
                when (val char = buffer[i]) {
                    '\n' -> endLine()
                    '\r' -> Unit
                    else -> if (!overflow) {
                        if (line.length >= MAX_LINE_CHARS) {
                            overflow = true
                            line.setLength(0)
                        } else {
                            line.append(char)
                        }
                    }
                }
            }
        }
        if (line.isNotEmpty() || overflow) endLine()
    }

    /**
     * Sắp xếp, khử trùng và tạo từ điển.
     *
     * @return Từ điển sẵn sàng tra cứu.
     */
    fun build(): PhraseDictionary {
        val order = IntArray(count) { it }
        mergeSort(order)

        // Giữ mục cuối cùng của mỗi nhóm khóa bằng nhau (merge sort ổn định nên đó là dòng thêm sau cùng).
        val kept = IntArray(count)
        var keptCount = 0
        var i = 0
        while (i < count) {
            var j = i
            while (j + 1 < count && compareEntries(order[j + 1], order[i]) == 0) j++
            kept[keptCount++] = order[j]
            i = j + 1
        }

        val outKeyOffsets = IntArray(keptCount + 1)
        val outValueOffsets = IntArray(keptCount + 1)
        var outKeyLength = 0
        var outValueLength = 0
        var maxKeyLength = 0
        for (k in 0 until keptCount) {
            val entry = kept[k]
            outKeyLength += keyLengths[entry]
            outValueLength += valueLengths[entry]
            maxKeyLength = maxOf(maxKeyLength, keyLengths[entry])
        }
        val outKeys = CharArray(outKeyLength)
        val outValues = ByteArray(outValueLength)
        var keyPosition = 0
        var valuePosition = 0
        for (k in 0 until keptCount) {
            val entry = kept[k]
            outKeyOffsets[k] = keyPosition
            keyChars.copyInto(outKeys, keyPosition, keyStarts[entry], keyStarts[entry] + keyLengths[entry])
            keyPosition += keyLengths[entry]
            outValueOffsets[k] = valuePosition
            valueBytes.copyInto(outValues, valuePosition, valueStarts[entry], valueStarts[entry] + valueLengths[entry])
            valuePosition += valueLengths[entry]
        }
        outKeyOffsets[keptCount] = keyPosition
        outValueOffsets[keptCount] = valuePosition
        return PhraseDictionary.fromSorted(outKeyOffsets, outKeys, outValueOffsets, outValues, maxKeyLength)
    }

    /**
     * So sánh khóa của hai mục theo code unit UTF-16.
     *
     * @param a Chỉ số mục thứ nhất.
     * @param b Chỉ số mục thứ hai.
     * @return Âm, 0 hoặc dương.
     */
    private fun compareEntries(a: Int, b: Int): Int {
        val lengthA = keyLengths[a]
        val lengthB = keyLengths[b]
        val startA = keyStarts[a]
        val startB = keyStarts[b]
        val common = minOf(lengthA, lengthB)
        for (i in 0 until common) {
            val diff = keyChars[startA + i] - keyChars[startB + i]
            if (diff != 0) return diff
        }
        return lengthA - lengthB
    }

    /**
     * Merge sort ổn định, lặp từ dưới lên, trên mảng chỉ số để không phải boxing.
     *
     * @param order Mảng chỉ số cần sắp xếp tại chỗ.
     */
    private fun mergeSort(order: IntArray) {
        var source = order
        var target = IntArray(order.size)
        var width = 1
        while (width < order.size) {
            var left = 0
            while (left < order.size) {
                val mid = minOf(left + width, order.size)
                val right = minOf(left + 2 * width, order.size)
                var i = left
                var j = mid
                var k = left
                while (i < mid && j < right) {
                    target[k++] = if (compareEntries(source[j], source[i]) < 0) source[j++] else source[i++]
                }
                while (i < mid) target[k++] = source[i++]
                while (j < right) target[k++] = source[j++]
                left += 2 * width
            }
            val swap = source
            source = target
            target = swap
            width *= 2
        }
        if (source !== order) source.copyInto(order)
    }

    /** Mở rộng các mảng theo mục khi đầy. */
    private fun ensureEntryCapacity() {
        if (count < keyStarts.size) return
        val newSize = keyStarts.size * 2
        keyStarts = keyStarts.copyOf(newSize)
        keyLengths = keyLengths.copyOf(newSize)
        valueStarts = valueStarts.copyOf(newSize)
        valueLengths = valueLengths.copyOf(newSize)
    }

    /**
     * Mở rộng mảng ký tự khóa.
     *
     * @param extra Số ký tự sắp thêm.
     */
    private fun ensureKeyCapacity(extra: Int) {
        if (keyCharCount + extra <= keyChars.size) return
        keyChars = keyChars.copyOf(maxOf(keyChars.size * 2, keyCharCount + extra))
    }

    /**
     * Mở rộng mảng byte nghĩa.
     *
     * @param extra Số byte sắp thêm.
     */
    private fun ensureValueCapacity(extra: Int) {
        if (valueByteCount + extra <= valueBytes.size) return
        valueBytes = valueBytes.copyOf(maxOf(valueBytes.size * 2, valueByteCount + extra))
    }

    companion object {
        private const val INITIAL_CAPACITY = 4096
        private const val INITIAL_ENTRIES = 1024
        private const val PROGRESS_INTERVAL = 50_000
        private const val READ_BUFFER_CHARS = 16 * 1024

        /** Độ dài tối đa của một dòng từ điển; dòng dài hơn bị bỏ qua. */
        const val MAX_LINE_CHARS = 4096

        /**
         * Phân tích một dòng định dạng QuickTranslator.
         *
         * @param raw Dòng gốc.
         * @return Cặp (khóa, nghĩa đầu tiên) hoặc null nếu dòng không hợp lệ.
         */
        fun parseQuickTranslatorLine(raw: String): Pair<String, String>? {
            val line = raw.removePrefix("﻿").trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) return null
            val separator = line.indexOf('=')
            if (separator <= 0) return null
            val key = line.substring(0, separator).trim()
            val value = line.substring(separator + 1).split('/', '|').firstOrNull { it.isNotBlank() }?.trim()
                ?: return null
            if (key.isEmpty()) return null
            return key to value
        }
    }
}
