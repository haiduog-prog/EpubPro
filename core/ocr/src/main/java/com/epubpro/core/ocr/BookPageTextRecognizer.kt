package com.epubpro.core.ocr

import ai.onnxruntime.OrtException
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect
import android.media.ExifInterface
import android.util.Log
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Nhận diện chữ trên ảnh chụp trang sách giấy bằng ML Kit Text Recognition v2 (hệ chữ Latin,
 * bao gồm tiếng Việt), chạy hoàn toàn trên máy.
 *
 * Ảnh được thu nhỏ khi giải mã để giới hạn bộ nhớ, hướng xoay EXIF được truyền cho ML Kit
 * thay vì xoay bitmap. Khi có [VietOcrSession], ML Kit chỉ dùng để định vị dòng; chữ của từng dòng được
 * VietOCR đọc lại để giữ đúng dấu tiếng Việt.
 */
@Singleton
class BookPageTextRecognizer @Inject constructor() {

    /**
     * Nhận diện các dòng chữ trên một ảnh trang.
     *
     * @param imageFile File ảnh JPEG/PNG của trang.
     * @param vietOcr Phiên VietOCR để đọc lại chữ từng dòng; `null` thì giữ chữ ML Kit.
     * @return Các dòng chữ theo tọa độ chuẩn hóa 0..1 của trang (đã tính hướng xoay EXIF);
     * rỗng nếu trang không có chữ.
     * @throws IOException Nếu không giải mã được ảnh.
     * @throws OcrModelUnavailableException Nếu Play services đang tải model OCR lần đầu.
     * @throws Exception Lỗi khác từ ML Kit.
     */
    suspend fun recognize(imageFile: File, vietOcr: VietOcrSession? = null): List<OcrTextBlock> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(imageFile.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Không đọc được ảnh trang")
        var sampleSize = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sampleSize > MAX_DECODE_SIDE) sampleSize *= 2
        val decoded = BitmapFactory.decodeFile(
            imageFile.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sampleSize }
        ) ?: throw IOException("Không giải mã được ảnh trang")
        val exifRotation = readRotationDegrees(imageFile)
        // VietOCR cắt dòng theo tọa độ ML Kit (hệ ảnh đã xoay thẳng) nên cần bitmap đã xoay thật.
        val bitmap = if (vietOcr != null && exifRotation != 0) rotate(decoded, exifRotation) else decoded
        val rotation = if (bitmap !== decoded) 0 else exifRotation
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val result = try {
                recognizer.process(InputImage.fromBitmap(bitmap, rotation)).await()
            } catch (error: MlKitException) {
                if (error.errorCode == MlKitException.UNAVAILABLE) throw OcrModelUnavailableException(error)
                throw error
            }
            // Tọa độ ML Kit trả về theo ảnh đã xoay thẳng.
            val rotated = rotation == 90 || rotation == 270
            val pageWidth = (if (rotated) bitmap.height else bitmap.width).toFloat()
            val pageHeight = (if (rotated) bitmap.width else bitmap.height).toFloat()
            return result.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                val box = line.boundingBox ?: return@mapNotNull null
                coroutineContext.ensureActive()
                val text = if (vietOcr == null) line.text else chooseLineText(line.text, readLine(vietOcr, bitmap, box))
                OcrTextBlock(
                    text = text,
                    left = box.left / pageWidth,
                    top = box.top / pageHeight,
                    right = box.right / pageWidth,
                    bottom = box.bottom / pageHeight
                )
            }
        } finally {
            recognizer.close()
            bitmap.recycle()
        }
    }

    /**
     * Đọc lại một dòng bằng VietOCR; lỗi runtime chỉ làm dòng đó quay về chữ ML Kit.
     *
     * @param vietOcr Phiên VietOCR.
     * @param page Ảnh trang đã xoay thẳng.
     * @param box Khung dòng theo pixel.
     * @return Chữ VietOCR, hoặc `null` nếu chạy model lỗi.
     */
    private fun readLine(vietOcr: VietOcrSession, page: Bitmap, box: Rect): String? = try {
        vietOcr.recognizeLine(page, box)
    } catch (error: OrtException) {
        Log.w(TAG, "VietOCR lỗi khi đọc một dòng", error)
        null
    }

    /**
     * Xoay bitmap theo EXIF và giải phóng bản gốc.
     *
     * @param source Bitmap vừa giải mã.
     * @param degrees Góc xoay theo chiều kim đồng hồ.
     * @return Bitmap đã xoay thẳng.
     */
    private fun rotate(source: Bitmap, degrees: Int): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        if (rotated !== source) source.recycle()
        return rotated
    }

    /**
     * Đọc góc xoay từ EXIF của ảnh chụp bằng camera.
     *
     * @param imageFile File ảnh.
     * @return Góc xoay theo chiều kim đồng hồ (0, 90, 180, 270); 0 khi không có EXIF.
     */
    private fun readRotationDegrees(imageFile: File): Int = try {
        when (ExifInterface(imageFile.absolutePath).getAttributeInt(
            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
        )) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    } catch (_: IOException) {
        0
    }

    private companion object {
        const val TAG = "BookPageOcr"

        /** Cạnh dài tối đa sau khi giải mã; đủ nét cho chữ sách in mà không tốn quá nhiều bộ nhớ. */
        const val MAX_DECODE_SIDE = 3000
    }
}
