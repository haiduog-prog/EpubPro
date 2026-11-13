package com.vieneu.tts.sample.tts

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtLoggingLevel
import ai.onnxruntime.OrtSession
import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.Collections
import java.util.Random
import java.util.concurrent.CancellationException
import java.util.zip.ZipInputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Thông tin một giọng mẫu preset được nạp từ `voices_v3_nano.json`.
 *
 * @property name Tên hiển thị của giọng đọc.
 * @property description Mô tả chất giọng và vùng miền.
 * @property gender Giới tính (`male` / `female`).
 * @property speakerEmbedding Vector đặc trưng người nói kích thước 192.
 * @property style Ma trận style kích thước `[50, 256]`.
 */
data class VieNeuNanoVoice(
    val name: String,
    val description: String,
    val gender: String,
    val speakerEmbedding: FloatArray,
    val style: Array<FloatArray>,
) {
    /**
     * Trả về vector style phẳng `[50 * 256]` để tạo nhanh `OnnxTensor`.
     */
    internal val flatStyle: FloatArray by lazy {
        val rows = style.size
        val cols = if (rows > 0) style[0].size else 0
        FloatArray(rows * cols).also { out ->
            for (r in 0 until rows) {
                style[r].copyInto(out, destinationOffset = r * cols)
            }
        }
    }
}

/**
 * Danh sách giọng preset và giọng mặc định từ `voices_v3_nano.json`.
 *
 * @property defaultVoiceName Tên giọng mặc định khuyến nghị.
 * @property voices Danh sách tất cả giọng preset khả dụng.
 */
data class VoiceCatalog(
    val defaultVoiceName: String,
    val voices: List<VieNeuNanoVoice>,
)

/**
 * Engine tổng hợp giọng nói VieNeu-TTS v3-Nano chạy hoàn toàn trên CPU bằng ONNX Runtime.
 *
 * @param modelDir Thư mục chứa 4 file `.onnx`, `config.json` và `constants.npz`.
 * @param threads Số luồng intra-op ONNX Runtime CPU.
 */
class VieNeuNanoEngine(modelDir: File, threads: Int = DEFAULT_THREADS) : Closeable {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val lock = Any()

    @Volatile
    private var closed: Boolean = false

    private val vocab: Map<String, Int>
    private val emotionMap: Map<String, String>
    private val bosId: Long
    private val eosId: Long
    private val padId: Long
    private val fps: Double

    private val nullSpkData: FloatArray
    private val nullSpkShape: LongArray
    private val nullStyleData: FloatArray
    private val nullStyleShape: LongArray
    private val nullCtxData: FloatArray
    private val nullCtxShape: LongArray
    private val nullMaskData: BooleanArray
    private val nullMaskShape: LongArray

    private val textEncoderSession: OrtSession
    private val durationPredictorSession: OrtSession
    private val vectorEstimatorSession: OrtSession
    private val codecDecoderSession: OrtSession
    private val oovWarned = Collections.synchronizedSet(HashSet<Char>())

    init {
        for (fileName in REQUIRED_FILES) {
            val f = File(modelDir, fileName)
            require(f.isFile) { "Thiếu file model bắt buộc: ${f.absolutePath}" }
        }

        val configJson = JSONObject(File(modelDir, "config.json").readText(Charsets.UTF_8))
        val vocabObj = configJson.getJSONObject("vocab")
        val vocabMap = LinkedHashMap<String, Int>(vocabObj.length())
        val vocabKeys = vocabObj.keys()
        while (vocabKeys.hasNext()) {
            val k = vocabKeys.next()
            vocabMap[k] = vocabObj.getInt(k)
        }
        vocab = vocabMap

        val emotionObj = configJson.optJSONObject("emotion_tags")
        val emotions = LinkedHashMap<String, String>()
        if (emotionObj != null) {
            val emoKeys = emotionObj.keys()
            while (emoKeys.hasNext()) {
                val tag = emoKeys.next()
                emotions[tag] = emotionObj.getString(tag)
            }
        }
        emotionMap = emotions

        bosId = configJson.getLong("bos_id")
        eosId = configJson.getLong("eos_id")
        padId = configJson.getLong("pad_id")
        fps = configJson.optDouble("flow_fps", DEFAULT_FLOW_FPS)

        val npzEntries = readNpzFloatArrays(File(modelDir, "constants.npz"))
        val rawNullSpk = npzEntries["null_spk.npy"]
            ?: error("constants.npz thiếu null_spk.npy")
        val rawNullStyle = npzEntries["null_style.npy"]
            ?: error("constants.npz thiếu null_style.npy")

        nullSpkData = rawNullSpk.data
        nullSpkShape = longArrayOf(1L, rawNullSpk.shape[0].toLong())
        nullStyleData = rawNullStyle.data
        nullStyleShape = longArrayOf(
            1L,
            rawNullStyle.shape[0].toLong(),
            rawNullStyle.shape[1].toLong(),
        )

        val sessionOptions = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setInterOpNumThreads(1)
            setIntraOpNumThreads(threads.coerceAtLeast(1))
            addConfigEntry("session.intra_op.allow_spinning", "0")
            setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_ERROR)
        }

        var sText: OrtSession? = null
        var sDur: OrtSession? = null
        var sVe: OrtSession? = null
        var sDec: OrtSession? = null
        try {
            sText = env.createSession(File(modelDir, "text_encoder.onnx").absolutePath, sessionOptions)
            sDur = env.createSession(File(modelDir, "duration_predictor.onnx").absolutePath, sessionOptions)
            sVe = env.createSession(File(modelDir, "vector_estimator.onnx").absolutePath, sessionOptions)
            sDec = env.createSession(File(modelDir, "codec_decoder.onnx").absolutePath, sessionOptions)

            val nullIds = longArrayOf(bosId, eosId)
            val nullIdsShape = longArrayOf(1L, 2L)
            nullMaskData = BooleanArray(nullIds.size) { idx -> nullIds[idx] != padId }
            nullMaskShape = nullIdsShape

            OnnxTensor.createTensor(env, LongBuffer.wrap(nullIds), nullIdsShape).use { idsTensor ->
                OnnxTensor.createTensor(env, FloatBuffer.wrap(nullStyleData), nullStyleShape).use { styleTensor ->
                    sText.run(
                        mapOf("ids" to idsTensor, "style" to styleTensor),
                    ).use { result ->
                        val ctxTensor = result[0] as OnnxTensor
                        nullCtxShape = ctxTensor.info.shape
                        nullCtxData = readFloatTensor(ctxTensor)
                    }
                }
            }
            textEncoderSession = sText
            durationPredictorSession = sDur
            vectorEstimatorSession = sVe
            codecDecoderSession = sDec
        } catch (t: Throwable) {
            runCatching { sDec?.close() }
            runCatching { sVe?.close() }
            runCatching { sDur?.close() }
            runCatching { sText?.close() }
            throw t
        } finally {
            sessionOptions.close()
        }
    }

    /**
     * Mã hoá chuỗi âm vị IPA (có thể chứa `<|emotion_k|>`) thành mảng token ID cho `text_encoder`.
     *
     * @param phonemes Chuỗi âm vị từ `sea-g2p`.
     * @return Mảng ID dạng `[bos, ..., eos]`.
     */
    fun encodePhones(phonemes: String): LongArray {
        var expanded = phonemes
        for ((tag, ch) in emotionMap) {
            if (expanded.contains(tag)) {
                expanded = expanded.replace(tag, ch)
            }
        }
        val ids = LongArray(expanded.length + 2)
        var count = 0
        ids[count++] = bosId
        for (i in expanded.indices) {
            val ch = expanded[i]
            val mapped = vocab[ch.toString()]
            if (mapped == null) {
                if (oovWarned.add(ch)) {
                    logWarn("v3-Nano bỏ qua ký tự ngoài vocab: '$ch' (U+${ch.code.toString(16).uppercase()})")
                }
                continue
            }
            ids[count++] = mapped.toLong()
        }
        ids[count++] = eosId
        return if (count == ids.size) ids else ids.copyOf(count)
    }

    /**
     * Ghi log cảnh báo qua `android.util.Log` một cách an toàn cả khi chạy JVM unit test.
     *
     * @param message Nội dung cảnh báo cần ghi.
     */
    private fun logWarn(message: String) {
        runCatching { Log.w(TAG, message) }
    }

    /**
     * Tổng hợp một chunk âm vị IPA thành waveform PCM float32 24 kHz.
     *
     * @param phonemes Chuỗi âm vị IPA của chunk.
     * @param voice Giọng đọc preset đã chọn.
     * @param steps Số bước Euler (16 chuẩn, 8 nhanh).
     * @param cfg Hệ số classifier-free guidance (3.0 chuẩn, 0 để bỏ unconditional pass).
     * @param sway Hệ số dịch lịch bước thời gian (`-1f` khuyên dùng khi `steps = 8`).
     * @param speed Tốc độ đọc (1.0f mặc định).
     * @param seed Hạt giống sinh nhiễu (tuỳ chọn).
     * @param isCancelled Hàm kiểm tra huỷ hợp tác giữa các bước Euler.
     * @return Mảng mẫu audio PCM float32 ở 24 kHz đã trim/fade hai đầu.
     * @throws CancellationException Nếu bị huỷ qua [isCancelled].
     */
    fun synthesize(
        phonemes: String,
        voice: VieNeuNanoVoice,
        steps: Int = DEFAULT_STEPS,
        cfg: Float = DEFAULT_CFG,
        sway: Float = 0f,
        speed: Float = 1f,
        seed: Long? = null,
        isCancelled: () -> Boolean = { false },
    ): FloatArray {
        check(!closed) { "VieNeuNanoEngine đã được đóng" }
        synchronized(lock) {
            check(!closed) { "VieNeuNanoEngine đã được đóng" }
            if (isCancelled()) throw CancellationException("Tổng hợp bị huỷ trước khi bắt đầu")

            val ids = encodePhones(phonemes)
            val idsShape = longArrayOf(1L, ids.size.toLong())
            val maskData = BooleanArray(ids.size) { idx -> ids[idx] != padId }
            val styleShape = longArrayOf(1L, voice.style.size.toLong(), voice.style[0].size.toLong())
            val styleFlat = voice.flatStyle
            val spkShape = longArrayOf(1L, voice.speakerEmbedding.size.toLong())

            lateinit var ctxData: FloatArray
            lateinit var ctxShape: LongArray

            OnnxTensor.createTensor(env, LongBuffer.wrap(ids), idsShape).use { idsTensor ->
                OnnxTensor.createTensor(env, FloatBuffer.wrap(styleFlat), styleShape).use { styleTensor ->
                    textEncoderSession.run(
                        mapOf("ids" to idsTensor, "style" to styleTensor),
                    ).use { res ->
                        val t = res[0] as OnnxTensor
                        ctxShape = t.info.shape
                        ctxData = readFloatTensor(t)
                    }
                }
            }

            if (isCancelled()) throw CancellationException("Tổng hợp bị huỷ sau text_encoder")

            val logDuration = OnnxTensor.createTensor(env, FloatBuffer.wrap(ctxData), ctxShape).use { ctxTensor ->
                OnnxTensor.createTensor(env, maskTo2dBooleanArray(maskData)).use { maskTensor ->
                    OnnxTensor.createTensor(env, FloatBuffer.wrap(voice.speakerEmbedding), spkShape).use { spkTensor ->
                        durationPredictorSession.run(
                            mapOf("ctx" to ctxTensor, "ctx_mask" to maskTensor, "spk" to spkTensor),
                        ).use { res ->
                            val durTensor = res[0] as OnnxTensor
                            durTensor.floatBuffer.get(0)
                        }
                    }
                }
            }

            val secs = min(exp(logDuration.toDouble()) / max(speed.toDouble(), 1e-3), MAX_CHUNK_SECONDS)
            val tFrames = max(MIN_FRAMES, (secs * fps).roundToInt())
            val latentSize = LATENT_CHANNELS * tFrames
            val x = FloatArray(latentSize)
            val rng = if (seed != null) Random(seed) else Random()
            for (k in x.indices) {
                x[k] = rng.nextGaussian().toFloat()
            }

            val nSteps = max(1, steps)
            val tg = DoubleArray(nSteps + 1) { stepIdx ->
                val u = stepIdx.toDouble() / nSteps.toDouble()
                u + sway.toDouble() * (cos(PI / 2.0 * u) - 1.0 + u)
            }
            val xShape = longArrayOf(1L, LATENT_CHANNELS.toLong(), tFrames.toLong())
            val tShape = longArrayOf(1L)
            val tBuf = FloatArray(1)
            val v = FloatArray(latentSize)
            val vu = if (cfg > 0f) FloatArray(latentSize) else null

            OnnxTensor.createTensor(env, FloatBuffer.wrap(ctxData), ctxShape).use { ctxTensor ->
                OnnxTensor.createTensor(env, maskTo2dBooleanArray(maskData)).use { maskTensor ->
                    OnnxTensor.createTensor(env, FloatBuffer.wrap(voice.speakerEmbedding), spkShape).use { spkTensor ->
                        OnnxTensor.createTensor(env, FloatBuffer.wrap(styleFlat), styleShape).use { styleTensor ->
                            OnnxTensor.createTensor(env, FloatBuffer.wrap(nullCtxData), nullCtxShape).use { nCtxTensor ->
                                OnnxTensor.createTensor(env, maskTo2dBooleanArray(nullMaskData)).use { nMaskTensor ->
                                    OnnxTensor.createTensor(env, FloatBuffer.wrap(nullSpkData), nullSpkShape).use { nSpkTensor ->
                                        OnnxTensor.createTensor(env, FloatBuffer.wrap(nullStyleData), nullStyleShape).use { nStyleTensor ->
                                            for (stepIdx in 0 until nSteps) {
                                                if (isCancelled()) {
                                                    throw CancellationException("Tổng hợp bị huỷ tại bước Euler $stepIdx/$nSteps")
                                                }
                                                tBuf[0] = tg[stepIdx].toFloat()
                                                OnnxTensor.createTensor(env, FloatBuffer.wrap(x), xShape).use { xTensor ->
                                                    OnnxTensor.createTensor(env, FloatBuffer.wrap(tBuf), tShape).use { tTensor ->
                                                        vectorEstimatorSession.run(
                                                            mapOf(
                                                                "x" to xTensor,
                                                                "t" to tTensor,
                                                                "ctx" to ctxTensor,
                                                                "ctx_mask" to maskTensor,
                                                                "spk" to spkTensor,
                                                                "style" to styleTensor,
                                                            ),
                                                        ).use { condRes ->
                                                            readFloatTensorInto(condRes[0] as OnnxTensor, v)
                                                        }

                                                        if (cfg > 0f && vu != null) {
                                                            vectorEstimatorSession.run(
                                                                mapOf(
                                                                    "x" to xTensor,
                                                                    "t" to tTensor,
                                                                    "ctx" to nCtxTensor,
                                                                    "ctx_mask" to nMaskTensor,
                                                                    "spk" to nSpkTensor,
                                                                    "style" to nStyleTensor,
                                                                ),
                                                            ).use { uncondRes ->
                                                                readFloatTensorInto(uncondRes[0] as OnnxTensor, vu)
                                                            }
                                                            for (k in v.indices) {
                                                                v[k] = vu[k] + cfg * (v[k] - vu[k])
                                                            }
                                                        }
                                                    }
                                                }
                                                val dt = (tg[stepIdx + 1] - tg[stepIdx]).toFloat()
                                                for (k in x.indices) {
                                                    x[k] += dt * v[k]
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (isCancelled()) throw CancellationException("Tổng hợp bị huỷ trước codec_decoder")

            val rawWav = OnnxTensor.createTensor(env, FloatBuffer.wrap(x), xShape).use { xTensor ->
                codecDecoderSession.run(mapOf("x" to xTensor)).use { res ->
                    readFloatTensor(res[0] as OnnxTensor)
                }
            }
            for (k in rawWav.indices) {
                rawWav[k] = rawWav[k].coerceIn(-1f, 1f)
            }
            return trimAndFade(rawWav, SAMPLE_RATE)
        }
    }

    /**
     * Giải phóng các session ONNX Runtime khi không sử dụng.
     */
    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            runCatching { textEncoderSession.close() }
            runCatching { durationPredictorSession.close() }
            runCatching { vectorEstimatorSession.close() }
            runCatching { codecDecoderSession.close() }
        }
    }

    /**
     * Gói mảng boolean 1D thành mảng 2D `[1, L]` để truyền cho tensor `ctx_mask`.
     *
     * @param mask Mặt nạ 1D theo độ dài chuỗi token.
     * @return Mảng boolean 2D `[1, L]`.
     */
    private fun maskTo2dBooleanArray(mask: BooleanArray): Array<BooleanArray> = arrayOf(mask)

    /**
     * Sao chép toàn bộ giá trị float32 từ một [OnnxTensor] sang [FloatArray] mới.
     *
     * @param tensor Tensor đầu ra của ONNX Runtime.
     * @return Mảng [FloatArray] chứa dữ liệu của tensor.
     */
    private fun readFloatTensor(tensor: OnnxTensor): FloatArray {
        val fb = tensor.floatBuffer
        val out = FloatArray(fb.remaining())
        fb.get(out)
        return out
    }

    /**
     * Đọc dữ liệu float32 từ [OnnxTensor] trực tiếp vào mảng đích [dest] để tránh cấp phát lặp.
     *
     * @param tensor Tensor nguồn.
     * @param dest Mảng đích đã được cấp phát sẵn.
     */
    private fun readFloatTensorInto(tensor: OnnxTensor, dest: FloatArray) {
        val fb = tensor.floatBuffer
        val count = min(fb.remaining(), dest.size)
        fb.get(dest, 0, count)
    }

    private class NpyFloatArray(val shape: IntArray, val data: FloatArray)

    /**
     * Giải nén toàn bộ mảng `.npy` kiểu float32 trong file `constants.npz`.
     *
     * @param npzFile Đường dẫn file `constants.npz`.
     * @return Bản đồ ánh xạ từ tên entry sang [NpyFloatArray].
     */
    private fun readNpzFloatArrays(npzFile: File): Map<String, NpyFloatArray> {
        val entries = LinkedHashMap<String, NpyFloatArray>()
        ZipInputStream(npzFile.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val bytes = readAllBytes(zip)
                entries[entry.name] = parseNpyFloat32(bytes)
                zip.closeEntry()
            }
        }
        return entries
    }

    /**
     * Đọc trọn vẹn luồng byte của entry hiện tại trong [ZipInputStream].
     *
     * @param zip Luồng nén đang mở.
     * @return Mảng byte của entry hiện tại.
     */
    private fun readAllBytes(zip: ZipInputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = zip.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /**
     * Phân tích phần header và payload float32 little-endian của định dạng NumPy `.npy`.
     *
     * @param bytes Dữ liệu thô của file `.npy`.
     * @return Cấu trúc [NpyFloatArray] gồm kích thước và mảng float32.
     */
    private fun parseNpyFloat32(bytes: ByteArray): NpyFloatArray {
        require(bytes.size >= 10) { "File NPY quá ngắn" }
        val major = bytes[6].toInt() and 0xFF
        val headerLen: Int
        val dataOffset: Int
        if (major == 1) {
            headerLen = ByteBuffer.wrap(bytes, 8, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
            dataOffset = 10 + headerLen
        } else {
            headerLen = ByteBuffer.wrap(bytes, 8, 4).order(ByteOrder.LITTLE_ENDIAN).int
            dataOffset = 12 + headerLen
        }
        val header = String(bytes, dataOffset - headerLen, headerLen, Charsets.ISO_8859_1)
        val shapeMatch = Regex("""'shape':\s*\(([^)]*)\)""").find(header)
            ?: error("Không đọc được shape từ header NPY: $header")
        val shape = shapeMatch.groupValues[1]
            .split(',')
            .mapNotNull { it.trim().takeIf { s -> s.isNotEmpty() }?.toInt() }
            .toIntArray()
        val floatCount = (bytes.size - dataOffset) / Float.SIZE_BYTES
        val floats = FloatArray(floatCount)
        ByteBuffer.wrap(bytes, dataOffset, floatCount * Float.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asFloatBuffer()
            .get(floats)
        return NpyFloatArray(shape, floats)
    }

    companion object {
        private const val TAG = "VieNeuNanoEngine"
        const val SAMPLE_RATE = 24_000
        const val DEFAULT_STEPS = 16
        const val DEFAULT_CFG = 3.0f
        const val DEFAULT_THREADS = 4
        private const val DEFAULT_FLOW_FPS = 24_000.0 / 256.0 / 6.0
        private const val LATENT_CHANNELS = 144
        private const val MAX_CHUNK_SECONDS = 15.0
        private const val MIN_FRAMES = 2
        private const val EDGE_THRESH_DB = -45.0f
        private const val EDGE_KEEP_S = 0.04f
        private const val EDGE_FADE_S = 0.015f

        val REQUIRED_FILES = listOf(
            "text_encoder.onnx",
            "duration_predictor.onnx",
            "vector_estimator.onnx",
            "codec_decoder.onnx",
            "config.json",
            "constants.npz",
        )

        /**
         * Đọc toàn bộ danh sách giọng mẫu preset từ nội dung JSON `voices_v3_nano.json`.
         *
         * @param jsonText Nội dung chuỗi JSON của `voices_v3_nano.json`.
         * @return [VoiceCatalog] chứa giọng mặc định và danh sách các giọng preset.
         */
        fun loadPresets(jsonText: String): VoiceCatalog {
            val root = JSONObject(jsonText)
            val defaultVoice = root.optString("default_voice", "Minh Quân")
            val presetsObj = root.getJSONObject("presets")
            val voices = mutableListOf<VieNeuNanoVoice>()
            val names = presetsObj.keys()
            while (names.hasNext()) {
                val name = names.next()
                val obj = presetsObj.getJSONObject(name)
                val spkArr = obj.getJSONArray("speaker_emb")
                val spk = FloatArray(spkArr.length()) { idx -> spkArr.getDouble(idx).toFloat() }
                val styleArr = obj.getJSONArray("style")
                val style = Array(styleArr.length()) { r ->
                    val row = styleArr.getJSONArray(r)
                    FloatArray(row.length()) { c -> row.getDouble(c).toFloat() }
                }
                voices += VieNeuNanoVoice(
                    name = name,
                    description = obj.optString("description", ""),
                    gender = obj.optString("gender", ""),
                    speakerEmbedding = spk,
                    style = style,
                )
            }
            return VoiceCatalog(defaultVoiceName = defaultVoice, voices = voices)
        }

        /**
         * Đo số mẫu im lặng ở đầu và cuối một đoạn waveform theo ngưỡng -45 dBFS.
         *
         * @param wav Mảng mẫu PCM float32 mono.
         * @param sampleRate Tần số lấy mẫu (Hz).
         * @return Cặp `(leadSamples, tailSamples)`.
         */
        fun edgeSilence(wav: FloatArray, sampleRate: Int = SAMPLE_RATE): Pair<Int, Int> {
            val nSamp = wav.size
            val win = max(1, (0.01f * sampleRate).toInt())
            val nWin = nSamp / win
            if (nWin == 0) return nSamp to 0
            val thresh = 10.0.pow(EDGE_THRESH_DB.toDouble() / 20.0).toFloat()
            var firstAbove = -1
            var lastAbove = -1
            for (w in 0 until nWin) {
                val base = w * win
                var sumAbs = 0f
                for (k in 0 until win) {
                    sumAbs += abs(wav[base + k])
                }
                if (sumAbs / win > thresh) {
                    if (firstAbove == -1) firstAbove = w
                    lastAbove = w
                }
            }
            if (firstAbove == -1) return nSamp to 0
            val lead = firstAbove * win
            val tail = nSamp - (lastAbove + 1) * win
            return lead to tail
        }

        /**
         * Cắt im lặng thừa ở hai đầu chunk và tạo fade cosine 15 ms để nối mượt không click.
         *
         * @param wav Mảng mẫu audio đầu vào.
         * @param sampleRate Tần số lấy mẫu (Hz).
         * @return Mảng mẫu audio sau khi cắt và fade mượt hai đầu.
         */
        fun trimAndFade(wav: FloatArray, sampleRate: Int = SAMPLE_RATE): FloatArray {
            if (wav.isEmpty()) return wav
            val (lead, tail) = edgeSilence(wav, sampleRate)
            val keep = (EDGE_KEEP_S * sampleRate).toInt()
            val start = max(0, lead - keep)
            val end = wav.size - max(0, tail - keep)
            if (end <= start) return FloatArray(0)
            val out = wav.copyOfRange(start, end)
            val fadeLen = min((EDGE_FADE_S * sampleRate).toInt(), out.size / 2)
            if (fadeLen > 0) {
                for (i in 0 until fadeLen) {
                    val u = if (fadeLen > 1) i.toDouble() / (fadeLen - 1).toDouble() else 1.0
                    val gain = (0.5 - 0.5 * cos(PI * u)).toFloat()
                    out[i] *= gain
                    out[out.size - 1 - i] *= gain
                }
            }
            return out
        }

        /**
         * Tính số mẫu số 0 cần chèn giữa [prevWav] và [nextWav] để tổng khoảng nghỉ đạt chuẩn [gap].
         *
         * @param prevWav Waveform chunk liền trước.
         * @param nextWav Waveform chunk kế tiếp.
         * @param gap Loại ranh giới ngắt nghỉ.
         * @param sampleRate Tần số lấy mẫu (Hz).
         * @return Số mẫu im lặng cần chèn thêm (`>= 0`).
         */
        fun computePausePadSamples(
            prevWav: FloatArray,
            nextWav: FloatArray,
            gap: ChunkGap,
            sampleRate: Int = SAMPLE_RATE,
        ): Int {
            val targetPauseSec = when (gap) {
                ChunkGap.FIRST -> return 0
                ChunkGap.PARA -> 0.70f
                ChunkGap.SENTENCE -> 0.50f
                ChunkGap.MINOR -> 0.30f
            }
            val (leadPrev, tailRaw) = edgeSilence(prevWav, sampleRate)
            val tail = if (leadPrev == prevWav.size) prevWav.size else tailRaw
            val (leadNext, _) = edgeSilence(nextWav, sampleRate)
            return max(0, (targetPauseSec * sampleRate).toInt() - tail - leadNext)
        }
    }
}
