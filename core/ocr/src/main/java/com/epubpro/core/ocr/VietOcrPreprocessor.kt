package com.epubpro.core.ocr

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Chuẩn bị ảnh một dòng chữ cho encoder VietOCR: co về cao [HEIGHT] px, rộng làm tròn lên bội số 10
 * trong [MIN_WIDTH]..[MAX_WIDTH], giá trị RGB 0..1 theo bố cục CHW.
 *
 * Co ảnh bằng trung bình diện tích (box filter) thay cho LANCZOS của bản Python: chữ sách thường được
 * thu nhỏ 2–3 lần và cách này không bị răng cưa như lấy mẫu song tuyến tính của [android.graphics.Bitmap].
 */
internal object VietOcrPreprocessor {

    const val HEIGHT = 32
    private const val MIN_WIDTH = 32
    private const val MAX_WIDTH = 512
    private const val WIDTH_STEP = 10

    /**
     * Tính độ rộng đầu vào giữ tỉ lệ khung hình như lúc huấn luyện.
     *
     * @param width Độ rộng ảnh dòng (px), > 0.
     * @param height Chiều cao ảnh dòng (px), > 0.
     * @return Độ rộng sau khi co về cao [HEIGHT].
     */
    fun targetWidth(width: Int, height: Int): Int {
        val scaled = (HEIGHT.toLong() * width / height).toInt()
        val rounded = (scaled + WIDTH_STEP - 1) / WIDTH_STEP * WIDTH_STEP
        return rounded.coerceIn(MIN_WIDTH, MAX_WIDTH)
    }

    /**
     * Co ảnh ARGB về `[3, HEIGHT, outWidth]` và chuẩn hóa về 0..1.
     *
     * @param argb Điểm ảnh ARGB theo hàng (như [android.graphics.Bitmap.getPixels]).
     * @param width Độ rộng ảnh nguồn.
     * @param height Chiều cao ảnh nguồn.
     * @param outWidth Độ rộng đích, thường là [targetWidth].
     * @return Mảng float CHW kích thước `3 * HEIGHT * outWidth`.
     * @throws IllegalArgumentException Nếu kích thước không khớp số điểm ảnh.
     */
    fun toInputTensor(argb: IntArray, width: Int, height: Int, outWidth: Int): FloatArray {
        require(width > 0 && height > 0 && argb.size == width * height) { "Kích thước ảnh dòng không hợp lệ" }
        val planes = Array(CHANNELS) { FloatArray(width * height) }
        for (i in argb.indices) {
            val pixel = argb[i]
            planes[0][i] = ((pixel shr 16) and 0xFF).toFloat()
            planes[1][i] = ((pixel shr 8) and 0xFF).toFloat()
            planes[2][i] = (pixel and 0xFF).toFloat()
        }
        val columns = boxWeights(width, outWidth)
        val rows = boxWeights(height, HEIGHT)
        val output = FloatArray(CHANNELS * HEIGHT * outWidth)
        val horizontal = FloatArray(outWidth * height)
        for (channel in 0 until CHANNELS) {
            val source = planes[channel]
            for (y in 0 until height) {
                for (x in 0 until outWidth) {
                    horizontal[y * outWidth + x] = columns[x].apply(source, y * width, 1)
                }
            }
            val offset = channel * HEIGHT * outWidth
            for (y in 0 until HEIGHT) {
                for (x in 0 until outWidth) {
                    output[offset + y * outWidth + x] = rows[y].apply(horizontal, x, outWidth) / MAX_VALUE
                }
            }
        }
        return output
    }

    /**
     * Trọng số trung bình diện tích cho từng điểm đích trên một trục.
     *
     * Điểm đích `o` phủ đoạn nguồn `[o * scale, (o + 1) * scale)`; mỗi điểm nguồn góp theo phần giao.
     *
     * @param inSize Số điểm nguồn.
     * @param outSize Số điểm đích.
     * @return Trọng số đã chuẩn hóa (tổng bằng 1) cho mỗi điểm đích.
     */
    private fun boxWeights(inSize: Int, outSize: Int): Array<AxisWeights> {
        val scale = inSize.toDouble() / outSize
        return Array(outSize) { o ->
            val start = o * scale
            val end = min((o + 1) * scale, inSize.toDouble())
            val first = floor(start).toInt().coerceIn(0, inSize - 1)
            val last = max(first, ceil(end).toInt() - 1).coerceAtMost(inSize - 1)
            val weights = FloatArray(last - first + 1) { k ->
                val i = first + k
                (min(end, i + 1.0) - max(start, i.toDouble())).coerceAtLeast(0.0).toFloat()
            }
            val total = weights.sum()
            if (total > 0f) for (k in weights.indices) weights[k] /= total else weights[0] = 1f
            AxisWeights(first, weights)
        }
    }

    /**
     * Các điểm nguồn liên tiếp từ [first] cùng trọng số tương ứng.
     *
     * @property first Chỉ số điểm nguồn đầu tiên.
     * @property weights Trọng số đã chuẩn hóa.
     */
    private class AxisWeights(val first: Int, val weights: FloatArray) {

        /**
         * Tính tổng có trọng số dọc một trục của mảng phẳng.
         *
         * @param values Mảng giá trị phẳng.
         * @param base Chỉ số của điểm nguồn 0 trên trục.
         * @param stride Khoảng cách giữa hai điểm liên tiếp trên trục.
         * @return Giá trị nội suy.
         */
        fun apply(values: FloatArray, base: Int, stride: Int): Float {
            var sum = 0f
            for (k in weights.indices) sum += values[base + (first + k) * stride] * weights[k]
            return sum
        }
    }

    private const val CHANNELS = 3
    private const val MAX_VALUE = 255f
}
