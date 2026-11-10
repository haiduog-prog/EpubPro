package com.epubpro.core.tts

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.Random
import java.util.zip.ZipFile
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Engine thử nghiệm (PoC) cho VieNeu-TTS v3 Nano: model flow-matching 48M tham số chạy hoàn toàn bằng
 * ONNX Runtime trên CPU, xuất audio 24 kHz.
 *
 * Port trực tiếp từ `OnnxV3NanoEngine.infer` của repo gốc (github.com/pnnbao97/VieNeu-TTS, `v3nano.py`):
 * text_encoder → duration_predictor → N bước Euler trên vector_estimator (có classifier-free guidance)
 * → codec_decoder. Đầu vào là chuỗi phoneme đã phiên âm sẵn bằng sea-g2p; engine chưa tự phiên âm.
 *
 * Mỗi instance giữ khoảng 280 MiB model trong bộ nhớ native nên cần [close] khi xong.
 *
 * @param modelDir Thư mục chứa `text_encoder.onnx`, `duration_predictor.onnx`, `vector_estimator.onnx`,
 * `codec_decoder.onnx`, `config.json` và `constants.npz` tải từ repo HF `pnnbao-ump/VieNeu-TTS-v3-Nano`.
 * @param threads Số luồng intra-op cho ONNX Runtime.
 * @throws IOException Nếu thiếu file model hoặc file cấu hình không đọc được.
 */
class VieNeuNanoEngine(modelDir: File, threads: Int) : Closeable {

    private val environment = OrtEnvironment.getEnvironment()
    private val options = OrtSession.SessionOptions().apply {
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        setIntraOpNumThreads(threads.coerceAtLeast(1))
        setInterOpNumThreads(1)
        addConfigEntry("session.intra_op.allow_spinning", "0")
    }
    private val vocab: Map<Char, Long>
    private val bosId: Long
    private val eosId: Long
    private val framesPerSecond: Float
    private val nullSpeaker: FloatArray
    private val nullStyle: FloatArray
    private val sessions = ArrayList<OrtSession>(4)
    private val textEncoder: OrtSession
    private val durationPredictor: OrtSession
    private val vectorEstimator: OrtSession
    private val codecDecoder: OrtSession
    private val nullContext: FloatArray
    private val nullContextLength = 2

    init {
        val config = JSONObject(requireFile(modelDir, CONFIG_FILE).readText(Charsets.UTF_8))
        val vocabJson = config.getJSONObject("vocab")
        vocab = vocabJson.keys().asSequence()
            .filter { it.length == 1 }
            .associate { it[0] to vocabJson.getLong(it) }
        bosId = config.getLong("bos_id")
        eosId = config.getLong("eos_id")
        framesPerSecond = config.optDouble("flow_fps", DEFAULT_FLOW_FPS).toFloat()

        val constants = readNpzFloats(requireFile(modelDir, CONSTANTS_FILE))
        nullSpeaker = constants["null_spk"] ?: throw IOException("constants.npz thiếu null_spk")
        nullStyle = constants["null_style"] ?: throw IOException("constants.npz thiếu null_style")

        try {
            textEncoder = openSession(modelDir, TEXT_ENCODER_FILE)
            durationPredictor = openSession(modelDir, DURATION_PREDICTOR_FILE)
            vectorEstimator = openSession(modelDir, VECTOR_ESTIMATOR_FILE)
            codecDecoder = openSession(modelDir, CODEC_DECODER_FILE)
            // Ngữ cảnh "không điều kiện" của CFG không phụ thuộc giọng nên chỉ tính một lần.
            nullContext = encodeText(longArrayOf(bosId, eosId), nullStyle)
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    /**
     * Tổng hợp một chunk phoneme (tối đa ~15 giây audio) thành waveform 24 kHz.
     *
     * @param phonemes Chuỗi phoneme do sea-g2p sinh ra; ký tự ngoài vocab bị bỏ qua giống bản Python.
     * @param voice Giọng đọc (x-vector + style token) lấy từ preset.
     * @param steps Số bước Euler; 16 là mặc định, 8 nhanh gấp đôi (nên dùng kèm `sway = -1`).
     * @param cfg Hệ số classifier-free guidance; 0 giảm một nửa tính toán nhưng giảm độ rõ.
     * @param sway Độ cong lịch thời gian Euler, âm thì dồn bước về đầu quỹ đạo.
     * @param speed Hệ số tốc độ đọc, lớn hơn 1 là nhanh hơn.
     * @param seed Seed cho nhiễu khởi tạo để tái lập kết quả.
     * @return Mẫu PCM float trong khoảng [-1, 1], đã cắt im lặng và fade hai đầu.
     */
    fun synthesize(
        phonemes: String,
        voice: VieNeuNanoVoice,
        steps: Int = DEFAULT_STEPS,
        cfg: Float = DEFAULT_CFG,
        sway: Float = 0f,
        speed: Float = 1f,
        seed: Long = 0L
    ): FloatArray {
        val ids = encodePhonemes(phonemes)
        val context = encodeText(ids, voice.style)
        val seconds = min(exp(predictLogSeconds(context, ids.size, voice)) / max(speed, 1e-3f), MAX_CHUNK_SECONDS)
        val frames = max(MIN_FRAMES, (seconds * framesPerSecond).roundToInt())

        val random = Random(seed)
        val latent = FloatArray(LATENT_CHANNELS * frames) { random.nextGaussian().toFloat() }
        val schedule = eulerSchedule(max(1, steps), sway)
        for (step in 0 until schedule.size - 1) {
            val time = schedule[step]
            val conditional = estimateVelocity(latent, frames, time, context, ids.size, voice.speakerEmbedding, voice.style)
            val dt = schedule[step + 1] - time
            if (cfg > 0f) {
                val unconditional = estimateVelocity(
                    latent, frames, time, nullContext, nullContextLength, nullSpeaker, nullStyle
                )
                for (i in latent.indices) {
                    val guided = unconditional[i] + cfg * (conditional[i] - unconditional[i])
                    latent[i] += dt * guided
                }
            } else {
                for (i in latent.indices) latent[i] += dt * conditional[i]
            }
        }
        return trimAndFade(decode(latent, frames))
    }

    /**
     * Giải phóng toàn bộ phiên ONNX và tùy chọn phiên; gọi nhiều lần vẫn an toàn.
     */
    override fun close() {
        sessions.forEach { it.close() }
        sessions.clear()
        options.close()
    }

    /**
     * Đổi chuỗi phoneme sang id theo vocab, bọc bởi BOS/EOS.
     *
     * @param phonemes Chuỗi phoneme đầu vào.
     * @return Mảng id dạng `[bos, ..., eos]`.
     */
    private fun encodePhonemes(phonemes: String): LongArray {
        val ids = ArrayList<Long>(phonemes.length + 2)
        ids += bosId
        phonemes.forEach { char -> vocab[char]?.let(ids::add) }
        ids += eosId
        return ids.toLongArray()
    }

    /**
     * Chạy text_encoder để lấy ngữ cảnh văn bản `[1, L, 256]`.
     *
     * @param ids Id phoneme đã có BOS/EOS.
     * @param style Style token `[50, 256]` của giọng (dạng phẳng).
     * @return Ngữ cảnh văn bản dạng phẳng.
     */
    private fun encodeText(ids: LongArray, style: FloatArray): FloatArray {
        OnnxTensor.createTensor(environment, LongBuffer.wrap(ids), longArrayOf(1, ids.size.toLong())).use { idsTensor ->
            styleTensor(style).use { styleTensor ->
                textEncoder.run(mapOf("ids" to idsTensor, "style" to styleTensor)).use { result ->
                    return (result[0] as OnnxTensor).floatBuffer.toArray()
                }
            }
        }
    }

    /**
     * Dự đoán log độ dài (giây) của câu từ ngữ cảnh và giọng đọc.
     *
     * @param context Ngữ cảnh văn bản từ [encodeText].
     * @param length Số token phoneme (kể cả BOS/EOS).
     * @param voice Giọng đọc đang dùng.
     * @return Log của số giây audio dự kiến.
     */
    private fun predictLogSeconds(context: FloatArray, length: Int, voice: VieNeuNanoVoice): Float {
        contextTensor(context, length).use { ctx ->
            maskTensor(length).use { mask ->
                speakerTensor(voice.speakerEmbedding).use { spk ->
                    durationPredictor.run(mapOf("ctx" to ctx, "ctx_mask" to mask, "spk" to spk)).use { result ->
                        return (result[0] as OnnxTensor).floatBuffer.get(0)
                    }
                }
            }
        }
    }

    /**
     * Chạy một lượt vector_estimator để lấy vận tốc flow tại thời điểm [time].
     *
     * @param latent Latent hiện tại `[1, 144, frames]` dạng phẳng.
     * @param frames Số frame latent.
     * @param time Thời điểm trên quỹ đạo flow, trong khoảng [0, 1].
     * @param context Ngữ cảnh văn bản dạng phẳng.
     * @param length Số token tương ứng với [context].
     * @param speaker X-vector 192 chiều.
     * @param style Style token `[50, 256]` dạng phẳng.
     * @return Vận tốc cùng shape với [latent].
     */
    private fun estimateVelocity(
        latent: FloatArray,
        frames: Int,
        time: Float,
        context: FloatArray,
        length: Int,
        speaker: FloatArray,
        style: FloatArray
    ): FloatArray {
        val shape = longArrayOf(1, LATENT_CHANNELS.toLong(), frames.toLong())
        val inputs = linkedMapOf(
            "x" to OnnxTensor.createTensor(environment, FloatBuffer.wrap(latent), shape),
            "t" to OnnxTensor.createTensor(environment, FloatBuffer.wrap(floatArrayOf(time)), longArrayOf(1)),
            "ctx" to contextTensor(context, length),
            "ctx_mask" to maskTensor(length),
            "spk" to speakerTensor(speaker),
            "style" to styleTensor(style)
        )
        try {
            vectorEstimator.run(inputs).use { result ->
                return (result[0] as OnnxTensor).floatBuffer.toArray()
            }
        } finally {
            inputs.values.forEach { it.close() }
        }
    }

    /**
     * Giải mã latent thành waveform bằng codec_decoder và kẹp biên độ về [-1, 1].
     *
     * @param latent Latent cuối `[1, 144, frames]` dạng phẳng.
     * @param frames Số frame latent.
     * @return Mẫu PCM float 24 kHz.
     */
    private fun decode(latent: FloatArray, frames: Int): FloatArray {
        val shape = longArrayOf(1, LATENT_CHANNELS.toLong(), frames.toLong())
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(latent), shape).use { x ->
            codecDecoder.run(mapOf("x" to x)).use { result ->
                val wav = (result[0] as OnnxTensor).floatBuffer.toArray()
                for (i in wav.indices) wav[i] = wav[i].coerceIn(-1f, 1f)
                return wav
            }
        }
    }

    /**
     * Tạo tensor ngữ cảnh `[1, L, 256]`.
     *
     * @param context Ngữ cảnh dạng phẳng.
     * @param length Số token L.
     * @return Tensor ONNX mới, người gọi chịu trách nhiệm đóng.
     */
    private fun contextTensor(context: FloatArray, length: Int): OnnxTensor =
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(context), longArrayOf(1, length.toLong(), CONTEXT_DIM))

    /**
     * Tạo mask `[1, L]` toàn `true` vì mỗi lượt chỉ có một chuỗi, không có padding.
     *
     * @param length Số token L.
     * @return Tensor bool mới, người gọi chịu trách nhiệm đóng.
     */
    private fun maskTensor(length: Int): OnnxTensor =
        OnnxTensor.createTensor(environment, arrayOf(BooleanArray(length) { true }))

    /**
     * Tạo tensor x-vector `[1, 192]`.
     *
     * @param speaker X-vector của giọng.
     * @return Tensor ONNX mới, người gọi chịu trách nhiệm đóng.
     */
    private fun speakerTensor(speaker: FloatArray): OnnxTensor =
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(speaker), longArrayOf(1, speaker.size.toLong()))

    /**
     * Tạo tensor style token `[1, 50, 256]`.
     *
     * @param style Style token dạng phẳng.
     * @return Tensor ONNX mới, người gọi chịu trách nhiệm đóng.
     */
    private fun styleTensor(style: FloatArray): OnnxTensor =
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(style), longArrayOf(1, STYLE_TOKENS, CONTEXT_DIM))

    /**
     * Mở một phiên ONNX và ghi nhận để [close] giải phóng sau.
     *
     * @param modelDir Thư mục model.
     * @param name Tên file `.onnx`.
     * @return Phiên đã mở.
     * @throws IOException Nếu file không tồn tại.
     */
    private fun openSession(modelDir: File, name: String): OrtSession =
        environment.createSession(requireFile(modelDir, name).absolutePath, options).also(sessions::add)

    companion object {
        const val SAMPLE_RATE = 24_000
        const val DEFAULT_STEPS = 16
        const val DEFAULT_CFG = 3f

        private const val TEXT_ENCODER_FILE = "text_encoder.onnx"
        private const val DURATION_PREDICTOR_FILE = "duration_predictor.onnx"
        private const val VECTOR_ESTIMATOR_FILE = "vector_estimator.onnx"
        private const val CODEC_DECODER_FILE = "codec_decoder.onnx"
        private const val CONFIG_FILE = "config.json"
        private const val CONSTANTS_FILE = "constants.npz"

        private const val LATENT_CHANNELS = 144
        private const val CONTEXT_DIM = 256L
        private const val STYLE_TOKENS = 50L
        private const val DEFAULT_FLOW_FPS = 24_000.0 / 256 / 6
        private const val MAX_CHUNK_SECONDS = 15f
        private const val MIN_FRAMES = 2

        private const val EDGE_THRESH_DB = -45.0
        private const val EDGE_KEEP_SECONDS = 0.04f
        private const val EDGE_FADE_SECONDS = 0.015f
        private const val EDGE_WINDOW_SECONDS = 0.01f

        /**
         * Lịch thời gian Euler `u + sway * (cos(pi/2 * u) - 1 + u)` với `u` chia đều [0, 1].
         *
         * @param steps Số bước Euler.
         * @param sway Độ cong lịch thời gian.
         * @return Mảng `steps + 1` mốc thời gian.
         */
        internal fun eulerSchedule(steps: Int, sway: Float): FloatArray = FloatArray(steps + 1) { i ->
            val u = i.toDouble() / steps
            (u + sway * (cos(PI / 2 * u) - 1 + u)).toFloat()
        }

        /**
         * Cắt im lặng hai đầu (giữ lại 40 ms) rồi fade cosine 15 ms ở hai mép, giống `trim_and_fade`
         * trong `vieneu_utils/core_utils.py`, để các chunk nối nhau không bị click.
         *
         * @param wav Waveform 24 kHz.
         * @return Bản sao đã cắt và fade; mảng rỗng trả nguyên.
         */
        internal fun trimAndFade(wav: FloatArray): FloatArray {
            if (wav.isEmpty()) return wav
            val window = max(1, (EDGE_WINDOW_SECONDS * SAMPLE_RATE).toInt())
            val windows = wav.size / window
            val threshold = 10.0.pow(EDGE_THRESH_DB / 20).toFloat()
            var first = -1
            var last = -1
            for (w in 0 until windows) {
                var sum = 0f
                for (i in w * window until (w + 1) * window) sum += abs(wav[i])
                if (sum / window > threshold) {
                    if (first < 0) first = w
                    last = w
                }
            }
            val lead = if (first < 0) wav.size else first * window
            val tail = if (first < 0) 0 else wav.size - (last + 1) * window
            val keep = (EDGE_KEEP_SECONDS * SAMPLE_RATE).toInt()
            val start = max(0, lead - keep)
            val end = wav.size - max(0, tail - keep)
            val out = if (start < end) wav.copyOfRange(start, end) else FloatArray(0)
            val fade = min((EDGE_FADE_SECONDS * SAMPLE_RATE).toInt(), out.size / 2)
            for (i in 0 until fade) {
                val ramp = (0.5 - 0.5 * cos(PI * i / max(1, fade - 1))).toFloat()
                out[i] *= ramp
                out[out.size - 1 - i] *= ramp
            }
            return out
        }

        /**
         * Đọc các mảng float32 little-endian (`<f4`) trong file `.npz` của numpy.
         *
         * @param file File `.npz`.
         * @return Map tên mảng (bỏ đuôi `.npy`) sang dữ liệu dạng phẳng; mảng khác kiểu bị bỏ qua.
         * @throws IOException Nếu file hỏng.
         */
        internal fun readNpzFloats(file: File): Map<String, FloatArray> = ZipFile(file).use { zip ->
            zip.entries().asSequence().filter { it.name.endsWith(".npy") }.mapNotNull { entry ->
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                val major = bytes[6].toInt()
                val headerLength = if (major == 1) buffer.getShort(8).toInt() and 0xFFFF else buffer.getInt(8)
                val headerStart = if (major == 1) 10 else 12
                val header = String(bytes, headerStart, headerLength, Charsets.ISO_8859_1)
                if (!header.contains("'<f4'")) return@mapNotNull null
                buffer.position(headerStart + headerLength)
                val floats = FloatArray(buffer.remaining() / 4)
                buffer.asFloatBuffer().get(floats)
                entry.name.removeSuffix(".npy") to floats
            }.toMap()
        }

        /**
         * Lấy file trong thư mục model, báo lỗi rõ ràng nếu thiếu.
         *
         * @param dir Thư mục model.
         * @param name Tên file.
         * @return File tồn tại.
         * @throws IOException Nếu file không tồn tại.
         */
        private fun requireFile(dir: File, name: String): File =
            File(dir, name).takeIf { it.isFile } ?: throw IOException("Thiếu file model VieNeu Nano: $name")

        /**
         * Sao chép nội dung còn lại của [FloatBuffer] ra mảng mới.
         *
         * @return Mảng float mới.
         */
        private fun FloatBuffer.toArray(): FloatArray = FloatArray(remaining()).also { get(it) }
    }
}

/**
 * Giọng đọc VieNeu Nano: x-vector 192 chiều và 50 × 256 style token (lưu dạng phẳng).
 *
 * @property name Tên giọng hiển thị trong file preset.
 * @property speakerEmbedding X-vector của người nói.
 * @property style Style token theo thứ tự hàng `[50, 256]`.
 */
class VieNeuNanoVoice(
    val name: String,
    val speakerEmbedding: FloatArray,
    val style: FloatArray
) {
    companion object {
        /**
         * Đọc toàn bộ preset trong `voices_v3_nano.json` của SDK VieNeu (`src/vieneu/assets`).
         *
         * @param file File JSON preset.
         * @return Danh sách giọng theo thứ tự trong file.
         */
        fun loadPresets(file: File): List<VieNeuNanoVoice> {
            val presets = JSONObject(file.readText(Charsets.UTF_8)).getJSONObject("presets")
            return presets.keys().asSequence().map { name ->
                val preset = presets.getJSONObject(name)
                val speaker = preset.getJSONArray("speaker_emb")
                val rows = preset.getJSONArray("style")
                val style = ArrayList<Float>(rows.length() * 256)
                for (r in 0 until rows.length()) {
                    val row = rows.getJSONArray(r)
                    for (c in 0 until row.length()) style += row.getDouble(c).toFloat()
                }
                VieNeuNanoVoice(
                    name = name,
                    speakerEmbedding = FloatArray(speaker.length()) { speaker.getDouble(it).toFloat() },
                    style = style.toFloatArray()
                )
            }.toList()
        }
    }
}
