package com.epubpro.core.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.FloatBuffer
import java.nio.LongBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Nạp model VietOCR (ONNX, đóng gói trong `assets/vietocr`) để nhận dạng lại từng dòng chữ tiếng Việt
 * mà ML Kit đã tìm được: ML Kit định vị dòng tốt nhưng hay sai dấu, VietOCR đọc dấu chính xác hơn nhiều.
 *
 * Model được chép một lần ra bộ nhớ trong để ONNX Runtime đọc trực tiếp từ file thay vì giữ cả mảng byte
 * trên heap Java.
 */
@Singleton
class VietOcrEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /**
     * Mở phiên nhận dạng cho một lượt quét; phiên giữ ~40 MiB model nên cần [Closeable.close] khi xong.
     *
     * @return Phiên nhận dạng, hoặc `null` nếu không nạp được model (khi đó chỉ dùng kết quả ML Kit).
     */
    fun openSessionOrNull(): VietOcrSession? = try {
        val modelDir = prepareModelFiles()
        val vocab = VietOcrVocab(File(modelDir, VOCAB_FILE).readText(Charsets.UTF_8))
        val environment = OrtEnvironment.getEnvironment()
        val options = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, MAX_THREADS))
        }
        val encoder = environment.createSession(File(modelDir, ENCODER_FILE).absolutePath, options)
        val decoder = try {
            environment.createSession(File(modelDir, DECODER_FILE).absolutePath, options)
        } catch (error: OrtException) {
            encoder.close()
            throw error
        }
        VietOcrSession(environment, options, encoder, decoder, vocab)
    } catch (error: IOException) {
        Log.w(TAG, "Không chuẩn bị được model VietOCR", error)
        null
    } catch (error: OrtException) {
        Log.w(TAG, "Không nạp được model VietOCR", error)
        null
    }

    /**
     * Chép model từ assets ra `noBackupFilesDir/vietocr/<phiên bản>` nếu chưa có, xóa phiên bản cũ.
     *
     * @return Thư mục chứa file model.
     * @throws IOException Nếu đọc assets hoặc ghi file thất bại.
     */
    private fun prepareModelFiles(): File = synchronized(this) {
        val root = File(context.noBackupFilesDir, ROOT_DIR)
        val target = File(root, MODEL_VERSION)
        root.listFiles()?.filter { it.name != MODEL_VERSION }?.forEach { it.deleteRecursively() }
        if (!target.isDirectory && !target.mkdirs()) throw IOException("Không tạo được thư mục model")
        for (name in MODEL_FILES) {
            val file = File(target, name)
            if (file.isFile && file.length() > 0) continue
            val partial = File(target, "$name.part")
            context.assets.open("$ROOT_DIR/$name").use { input ->
                partial.outputStream().use { output -> input.copyTo(output) }
            }
            if (!partial.renameTo(file)) {
                partial.delete()
                throw IOException("Không lưu được file model")
            }
        }
        target
    }

    private companion object {
        const val TAG = "VietOcr"
        const val ROOT_DIR = "vietocr"
        /** Đổi khi thay file model trong assets để bản chép cũ bị thay thế. */
        const val MODEL_VERSION = "2"
        const val ENCODER_FILE = "vietocr_encoder.onnx"
        const val DECODER_FILE = "vietocr_decoder.onnx"
        const val VOCAB_FILE = "vietocr_vocab.txt"
        val MODEL_FILES = listOf(ENCODER_FILE, DECODER_FILE, VOCAB_FILE)
        const val MAX_THREADS = 4
    }
}

/**
 * Phiên VietOCR đã nạp model, dùng tuần tự trên một luồng cho các trang của một lượt quét.
 */
class VietOcrSession internal constructor(
    private val environment: OrtEnvironment,
    private val options: OrtSession.SessionOptions,
    private val encoder: OrtSession,
    private val decoder: OrtSession,
    private val vocab: VietOcrVocab
) : Closeable {

    /**
     * Nhận dạng chữ trong một vùng dòng của ảnh trang (giải mã tham lam từng ký tự).
     *
     * Khung được nới lề trên chính ảnh trang (lề giấy) trước khi cắt.
     *
     * @param page Ảnh trang ARGB_8888 đã xoay thẳng.
     * @param box Khung dòng chữ theo pixel của [page] (thường do ML Kit trả về).
     * @return Văn bản của dòng; rỗng nếu khung nằm ngoài ảnh.
     * @throws OrtException Nếu ONNX Runtime chạy model lỗi.
     */
    internal fun recognizeLine(page: Bitmap, box: Rect): String {
        // ML Kit trả khung sát chữ, đôi khi lẹm vào chữ cái đầu/cuối; thiếu lề ngang khiến model tự thêm chữ.
        val padX = (box.height() * LINE_PADDING_X).roundToInt()
        val padY = (box.height() * LINE_PADDING_Y).roundToInt()
        val left = (box.left - padX).coerceAtLeast(0)
        val top = (box.top - padY).coerceAtLeast(0)
        val right = (box.right + padX).coerceAtMost(page.width)
        val bottom = (box.bottom + padY).coerceAtMost(page.height)
        val width = right - left
        val height = bottom - top
        if (width <= 0 || height <= 0) return ""
        val pixels = IntArray(width * height)
        page.getPixels(pixels, 0, width, left, top, width, height)
        val inputWidth = VietOcrPreprocessor.targetWidth(width, height)
        val input = VietOcrPreprocessor.toInputTensor(pixels, width, height, inputWidth)
        val shape = longArrayOf(1, 3, VietOcrPreprocessor.HEIGHT.toLong(), inputWidth.toLong())
        return OnnxTensor.createTensor(environment, FloatBuffer.wrap(input), shape).use { image ->
            encoder.run(mapOf(ENCODER_INPUT to image)).use { encoded ->
                val cross = CROSS_NAMES.mapIndexed { index, name -> name to (encoded.get(index) as OnnxTensor) }.toMap()
                decode(cross)
            }
        }
    }

    /**
     * Sinh lần lượt từng ký tự; mỗi bước chỉ tính vị trí mới và nhận lại trạng thái các lớp để dùng ở bước sau.
     *
     * @param cross K/V cross-attention của từng lớp decoder do encoder tính.
     * @return Văn bản đã giải mã.
     * @throws OrtException Nếu ONNX Runtime chạy model lỗi.
     */
    private fun decode(cross: Map<String, OnnxTensor>): String {
        val emptyStates = List(LAYERS) {
            OnnxTensor.createTensor(environment, FloatBuffer.allocate(0), longArrayOf(0, 1, D_MODEL))
        }
        var states: List<OnnxTensor> = emptyStates
        var previous: OrtSession.Result? = null
        val ids = mutableListOf(VietOcrVocab.SOS)
        try {
            for (step in 0 until MAX_STEPS) {
                val token = OnnxTensor.createTensor(environment, LongBuffer.wrap(longArrayOf(ids.last().toLong())), longArrayOf(1, 1))
                val position = OnnxTensor.createTensor(environment, LongBuffer.wrap(longArrayOf(step.toLong())), longArrayOf(1))
                val inputs = HashMap<String, OnnxTensor>(cross)
                inputs[DECODER_TOKEN] = token
                inputs[DECODER_POSITION] = position
                states.forEachIndexed { layer, state -> inputs["$DECODER_PAST$layer"] = state }
                val result = try {
                    decoder.run(inputs)
                } finally {
                    token.close()
                    position.close()
                }
                // Trạng thái bước trước nằm trong kết quả cũ; chỉ đóng sau khi bước mới đã chạy xong.
                previous?.close()
                previous = result
                val next = argmax((result.get(0) as OnnxTensor).floatBuffer)
                if (next == VietOcrVocab.EOS) break
                ids += next
                states = List(LAYERS) { layer -> result.get(layer + 1) as OnnxTensor }
            }
        } finally {
            previous?.close()
            emptyStates.forEach { it.close() }
        }
        return vocab.decode(ids)
    }

    /**
     * Tìm chỉ số có điểm cao nhất.
     *
     * @param logits Điểm của từng lớp ký tự.
     * @return Chỉ số lớp cao điểm nhất.
     */
    private fun argmax(logits: FloatBuffer): Int {
        var best = 0
        var bestValue = Float.NEGATIVE_INFINITY
        for (i in 0 until logits.remaining()) {
            val value = logits.get(i)
            if (value > bestValue) {
                bestValue = value
                best = i
            }
        }
        return best
    }

    override fun close() {
        encoder.close()
        decoder.close()
        options.close()
    }

    private companion object {
        /** Lề ngang quanh khung dòng, theo chiều cao dòng. */
        const val LINE_PADDING_X = 0.5f
        /** Lề dọc quanh khung dòng để không cắt mất dấu phía trên/dưới. */
        const val LINE_PADDING_Y = 0.15f
        const val MAX_STEPS = 128
        const val LAYERS = 6
        const val D_MODEL = 256L
        const val ENCODER_INPUT = "image"
        const val DECODER_TOKEN = "token"
        const val DECODER_POSITION = "position"
        const val DECODER_PAST = "past_"
        /** Thứ tự đầu ra của encoder, trùng tên đầu vào của decoder. */
        val CROSS_NAMES = (0 until LAYERS).flatMap { listOf("cross_k_$it", "cross_v_$it") }
    }
}
