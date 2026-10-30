package com.epubpro.core.ocr

import android.graphics.Bitmap
import com.epubpro.domain.model.ComicSourceLanguage
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Model OCR của ngôn ngữ chưa sẵn sàng (Google Play services đang tải lần đầu).
 *
 * @param cause Lỗi gốc từ ML Kit.
 */
class OcrModelUnavailableException(cause: Throwable) : IllegalStateException("OCR model unavailable", cause)

/**
 * Nhận diện chữ trên trang truyện bằng ML Kit Text Recognition v2, chạy hoàn toàn trên máy.
 *
 * Trang được truyền dưới dạng các dải bitmap xếp dọc (như [com.epubpro.core.comic.ComicPageImage]),
 * tọa độ kết quả được quy về hệ 0..1 của cả trang.
 */
@Singleton
class ComicTextRecognizer @Inject constructor() {

    /**
     * Nhận diện các dòng chữ trên trang.
     *
     * @param tiles Các dải bitmap của trang, cùng chiều rộng, xếp từ trên xuống.
     * @param language Ngôn ngữ chữ trên trang.
     * @return Các dòng chữ với tọa độ chuẩn hóa.
     * @throws OcrModelUnavailableException Nếu Play services đang tải model của ngôn ngữ này.
     * @throws Exception Lỗi khác từ ML Kit.
     */
    suspend fun recognize(tiles: List<Bitmap>, language: ComicSourceLanguage): List<OcrTextBlock> {
        if (tiles.isEmpty()) return emptyList()
        val pageWidth = tiles.first().width.toFloat()
        val pageHeight = tiles.sumOf { it.height }.toFloat()
        val recognizer = createRecognizer(language)
        try {
            val blocks = mutableListOf<OcrTextBlock>()
            var offsetY = 0
            for (tile in tiles) {
                val result = try {
                    recognizer.process(InputImage.fromBitmap(tile, 0)).await()
                } catch (error: MlKitException) {
                    if (error.errorCode == MlKitException.UNAVAILABLE) throw OcrModelUnavailableException(error)
                    throw error
                }
                result.textBlocks.flatMap { it.lines }.forEach { line ->
                    val box = line.boundingBox ?: return@forEach
                    blocks += OcrTextBlock(
                        text = line.text,
                        left = box.left / pageWidth,
                        top = (box.top + offsetY) / pageHeight,
                        right = box.right / pageWidth,
                        bottom = (box.bottom + offsetY) / pageHeight
                    )
                }
                offsetY += tile.height
            }
            return blocks
        } finally {
            recognizer.close()
        }
    }

    /**
     * Tạo recognizer theo hệ chữ.
     *
     * @param language Ngôn ngữ nguồn.
     * @return Recognizer ML Kit; caller phải đóng sau khi dùng.
     */
    private fun createRecognizer(language: ComicSourceLanguage): TextRecognizer = when (language) {
        ComicSourceLanguage.CHINESE -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        ComicSourceLanguage.JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        ComicSourceLanguage.KOREAN -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        ComicSourceLanguage.ENGLISH -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }
}

/**
 * Chờ một [Task] của Google Play services trong coroutine.
 *
 * @receiver Task cần chờ.
 * @return Kết quả của task.
 * @throws Exception Lỗi của task.
 */
internal suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { result -> continuation.resume(result) }
    addOnFailureListener { error -> continuation.resumeWithException(error) }
    addOnCanceledListener { continuation.cancel() }
}
