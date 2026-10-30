package com.epubpro.core.comic

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.Build
import java.io.File
import java.io.FileOutputStream

/**
 * Trang truyện đã giải mã, chia thành các dải ngang để không vượt giới hạn texture của GPU.
 *
 * @property tiles Các bitmap xếp từ trên xuống dưới; cùng chiều rộng.
 * @property width Chiều rộng bitmap sau khi downsample.
 * @property height Tổng chiều cao các dải.
 */
class ComicPageImage(
    val tiles: List<Bitmap>,
    val width: Int,
    val height: Int
) {
    /** Tổng số byte bitmap đang chiếm, dùng làm trọng số cho LruCache. */
    val byteCount: Int get() = tiles.sumOf { it.allocationByteCount }
}

/**
 * Giải mã ảnh trang truyện có kiểm soát bộ nhớ.
 *
 * Ảnh được downsample theo chiều rộng đích, giới hạn tổng số pixel, và ảnh rất cao (webtoon) được cắt
 * thành nhiều dải bằng [BitmapRegionDecoder] để tránh lỗi `Canvas: trying to draw too large bitmap`
 * hoặc texture vượt kích thước tối đa.
 */
object ComicPageDecoder {
    /** Chiều cao tối đa của một dải bitmap. */
    const val MAX_TILE_HEIGHT = 2048

    private const val MAX_DECODED_PIXELS = 16_000_000L
    private const val RGB_565_THRESHOLD_PIXELS = 4_000_000L
    private const val COVER_TARGET_WIDTH = 480
    private const val COVER_JPEG_QUALITY = 85

    /**
     * Giải mã một trang thành các dải bitmap.
     *
     * @param bytes Dữ liệu ảnh nén của trang.
     * @param targetWidth Chiều rộng hiển thị mong muốn (px); ảnh không bị phóng to quá kích thước gốc.
     * @return Ảnh trang đã giải mã.
     * @throws ComicArchiveException Nếu dữ liệu không phải ảnh giải mã được.
     */
    fun decode(bytes: ByteArray, targetWidth: Int): ComicPageImage {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val sourceWidth = bounds.outWidth
        val sourceHeight = bounds.outHeight
        if (sourceWidth <= 0 || sourceHeight <= 0) throw ComicArchiveException("Không giải mã được ảnh trang")

        val sampleSize = computeSampleSize(sourceWidth, sourceHeight, targetWidth.coerceAtLeast(1))
        val decodedPixels = (sourceWidth.toLong() / sampleSize) * (sourceHeight.toLong() / sampleSize)
        val config = if (decodedPixels > RGB_565_THRESHOLD_PIXELS) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
        val decodedHeight = sourceHeight / sampleSize

        val tiles = if (decodedHeight > MAX_TILE_HEIGHT) {
            decodeTiles(bytes, sourceWidth, sourceHeight, sampleSize, config) ?: listOf(decodeWhole(bytes, sampleSize, config))
        } else {
            listOf(decodeWhole(bytes, sampleSize, config))
        }
        return ComicPageImage(tiles, tiles.first().width, tiles.sumOf { it.height })
    }

    /**
     * Tạo ảnh bìa JPEG nhỏ từ dữ liệu trang đầu và ghi atomically.
     *
     * @param bytes Dữ liệu ảnh trang bìa.
     * @param target File JPEG đích.
     * @return Đường dẫn tuyệt đối của ảnh bìa.
     * @throws ComicArchiveException Nếu ảnh không giải mã hoặc không ghi được.
     */
    fun writeCover(bytes: ByteArray, target: File): String {
        val page = decode(bytes, COVER_TARGET_WIDTH)
        val bitmap = page.tiles.first()
        val temporary = File(target.parentFile, "${target.name}.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, COVER_JPEG_QUALITY, output)) {
                    throw ComicArchiveException("Không ghi được ảnh bìa")
                }
                output.fd.sync()
            }
            if (target.exists() && !target.delete()) throw ComicArchiveException("Không thay được ảnh bìa cũ")
            if (!temporary.renameTo(target)) throw ComicArchiveException("Không lưu được ảnh bìa")
            return target.absolutePath
        } finally {
            temporary.delete()
            page.tiles.forEach(Bitmap::recycle)
        }
    }

    /**
     * Tính `inSampleSize` lũy thừa 2 lớn nhất mà vẫn giữ chiều rộng không nhỏ hơn đích và tổng pixel trong giới hạn.
     *
     * @param width Chiều rộng gốc.
     * @param height Chiều cao gốc.
     * @param targetWidth Chiều rộng đích.
     * @return Hệ số downsample (>= 1).
     */
    internal fun computeSampleSize(width: Int, height: Int, targetWidth: Int): Int {
        var sample = 1
        while (width / (sample * 2) >= targetWidth) sample *= 2
        while ((width.toLong() / sample) * (height.toLong() / sample) > MAX_DECODED_PIXELS) sample *= 2
        return sample
    }

    /**
     * Giải mã toàn bộ ảnh một lần.
     *
     * @param bytes Dữ liệu ảnh.
     * @param sampleSize Hệ số downsample.
     * @param config Cấu hình pixel.
     * @return Bitmap đã giải mã.
     * @throws ComicArchiveException Nếu decoder trả về null.
     */
    private fun decodeWhole(bytes: ByteArray, sampleSize: Int, config: Bitmap.Config): Bitmap {
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = config
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: throw ComicArchiveException("Không giải mã được ảnh trang")
    }

    /**
     * Cắt ảnh cao thành các dải bằng [BitmapRegionDecoder].
     *
     * @param bytes Dữ liệu ảnh.
     * @param sourceWidth Chiều rộng gốc.
     * @param sourceHeight Chiều cao gốc.
     * @param sampleSize Hệ số downsample.
     * @param config Cấu hình pixel.
     * @return Danh sách dải, hoặc null nếu định dạng không hỗ trợ giải mã theo vùng (ví dụ GIF).
     */
    private fun decodeTiles(
        bytes: ByteArray,
        sourceWidth: Int,
        sourceHeight: Int,
        sampleSize: Int,
        config: Bitmap.Config
    ): List<Bitmap>? {
        val decoder = runCatching { newRegionDecoder(bytes) }.getOrNull() ?: return null
        val tiles = mutableListOf<Bitmap>()
        try {
            val sourceTileHeight = MAX_TILE_HEIGHT * sampleSize
            var top = 0
            while (top < sourceHeight) {
                val bottom = minOf(top + sourceTileHeight, sourceHeight)
                val options = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = config
                }
                val tile = decoder.decodeRegion(Rect(0, top, sourceWidth, bottom), options)
                    ?: throw ComicArchiveException("Không giải mã được vùng ảnh")
                tiles += tile
                top = bottom
            }
            return tiles
        } catch (error: Throwable) {
            tiles.forEach(Bitmap::recycle)
            throw error
        } finally {
            decoder.recycle()
        }
    }

    /**
     * Tạo [BitmapRegionDecoder] theo API phù hợp với phiên bản Android.
     *
     * @param bytes Dữ liệu ảnh.
     * @return Decoder theo vùng.
     */
    private fun newRegionDecoder(bytes: ByteArray): BitmapRegionDecoder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            BitmapRegionDecoder.newInstance(bytes, 0, bytes.size)
        } else {
            @Suppress("DEPRECATION")
            BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false)
        }
}
