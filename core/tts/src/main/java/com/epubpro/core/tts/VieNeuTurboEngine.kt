package com.epubpro.core.tts

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.LongBuffer
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Tham số lấy mẫu của VieNeu-TTS v3 Turbo (mặc định theo SDK: temperature 0.8, top-k 25, top-p 0.95,
 * repetition penalty 1.2 trên cửa sổ 64 frame).
 *
 * @property temperature Nhiệt độ lấy mẫu; `<= 0` là greedy.
 * @property topK Số ứng viên lớn nhất giữ lại.
 * @property topP Ngưỡng nucleus.
 * @property repetitionPenalty Hệ số phạt lặp.
 * @property repetitionWindow Số frame gần nhất bị phạt lặp.
 * @property maxNewFrames Trần số frame tuyệt đối của một chunk (12,5 frame mỗi giây).
 * @property seed Hạt giống ngẫu nhiên (tuỳ chọn) để tái lập kết quả.
 */
data class VieNeuTurboParams(
    val temperature: Float = 0.8f,
    val topK: Int = 25,
    val topP: Float = 0.95f,
    val repetitionPenalty: Float = 1.2f,
    val repetitionWindow: Int = 64,
    val maxNewFrames: Int = 300,
    val seed: Long? = null,
)

/**
 * Engine VieNeu-TTS v3 Turbo chạy trực tiếp bằng ONNX Runtime (CPU), tổng hợp dạng streaming 48 kHz.
 *
 * Luồng: dựng prompt (style + phoneme + audio code tham chiếu, cộng anchor người nói) → prefill backbone
 * 12 lớp → lặp từng frame: acoustic decoder sinh 16 code (head chạy bằng ONNX `MatMul` trên trọng số mmap)
 * → decode-step backbone có KV cache → cứ vài frame giải mã một lần bằng codec MOSS streaming và đẩy PCM ra
 * [synthesize] qua callback.
 *
 * Port từ `vieneu/_v3_turbo_engine/onnx_runtime_lite.py` (SDK `vieneu` 3.8.5, Apache-2.0,
 * https://github.com/pnnbao97/VieNeu-TTS). Codec: MOSS-Audio-Tokenizer-Nano (OpenMOSS-Team, Apache-2.0).
 * Không an toàn đa luồng; gọi tuần tự (runtime manager giữ mutex).
 *
 * @param modelDir Thư mục chứa graph Turbo `onnx_int8`, `vieneu_v3_heads.npz`, `config.json` và codec MOSS.
 * @param threads Số luồng intra-op ONNX Runtime.
 * @throws IllegalStateException Khi thiếu file hoặc model không hợp lệ.
 */
class VieNeuTurboEngine(modelDir: File, threads: Int = DEFAULT_THREADS) : Closeable {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val sessionOptions = buildOptions(threads, disablePrepacking = false)
    private val prefillOptions = buildOptions(threads, disablePrepacking = true)
    private val sessions = ArrayList<OrtSession>(5)
    private val ownedTensors = ArrayList<OnnxTensor>(20)

    private val prefill: OrtSession
    private val decode: OrtSession
    private val acoustic: OrtSession
    private val codec: OrtSession
    private val head: OrtSession

    private val textEmb: FloatBuffer
    private val audioEmb: FloatBuffer
    private val audioHeads: Array<OnnxTensor>
    private val textHead: OnnxTensor
    private val xvecW: FloatArray
    private val xvecB: FloatArray
    private val xvecLnW: FloatArray
    private val xvecLnB: FloatArray
    private val xvecLnEps: Float

    private val tokenizer: VieNeuTurboTokenizer
    private val styleTokenId: Int
    private val codecSpec: CodecSpec

    init {
        try {
            val config = JSONObject(File(modelDir, CONFIG_FILE).readText())
            check(config.getInt("n_vq") == N_VQ && config.getInt("hidden_size") == H) { "config.json Turbo không khớp kiến trúc" }
            styleTokenId = config.optInt("default_style_token_id", DEFAULT_STYLE_ID)
            tokenizer = VieNeuTurboTokenizer.fromJson(File(modelDir, TOKENIZER_FILE).readText())
            codecSpec = CodecSpec.parse(JSONObject(File(modelDir, CODEC_META_FILE).readText()).getJSONObject("streaming_decode"))

            val arrays = VieNeuTurboNpyArray.mapNpz(File(modelDir, HEADS_FILE))
            textEmb = arrays.getValue("text_emb").buffer
            audioEmb = arrays.getValue("audio_emb").buffer
            xvecW = arrays.getValue("xvec_w").toArray()
            xvecB = arrays.getValue("xvec_b").toArray()
            xvecLnW = arrays.getValue("xvec_ln_w").toArray()
            xvecLnB = arrays.getValue("xvec_ln_b").toArray()
            xvecLnEps = arrays.getValue("xvec_ln_eps").buffer.get(0)

            prefill = open(File(modelDir, PREFILL_FILE), prefillOptions)
            decode = open(File(modelDir, DECODE_FILE), sessionOptions)
            acoustic = open(File(modelDir, ACOUSTIC_FILE), sessionOptions)
            codec = open(File(modelDir, CODEC_STEP_FILE), sessionOptions)
            head = env.createSession(VieNeuTurboMatMulModel.bytes(H), sessionOptions).also { sessions += it }

            audioHeads = Array(N_VQ) { ch -> weightTensor(audioEmb, ch * AUDIO_VOCAB * H, AUDIO_VOCAB) }
            textHead = weightTensor(textEmb, 0, TEXT_VOCAB)
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    /**
     * Mã hoá chuỗi âm vị thành token id bằng tokenizer của Turbo.
     *
     * @param phonemes Chuỗi âm vị `sea-g2p` (có thể chứa `<|emotion_k|>`).
     * @return Token id.
     */
    fun tokenize(phonemes: String): IntArray = tokenizer.encode(phonemes)

    /**
     * Tổng hợp một chunk âm vị, đẩy audio 48 kHz ra [onAudio] ngay khi giải mã xong từng nhóm frame.
     *
     * @param phonemes Chuỗi âm vị của chunk.
     * @param voice Giọng preset (x-vector + code tham chiếu).
     * @param params Tham số lấy mẫu.
     * @param isCancelled Hàm kiểm tra yêu cầu huỷ; được gọi sau mỗi frame.
     * @param onAudio Nhận từng mảnh PCM float32 mono 48 kHz theo thứ tự.
     * @return Tổng số mẫu đã phát ra; dừng sớm (kết quả dở dang) nếu bị huỷ.
     */
    fun synthesize(
        phonemes: String,
        voice: VieNeuTurboVoice,
        params: VieNeuTurboParams = VieNeuTurboParams(),
        isCancelled: () -> Boolean = { false },
        onAudio: (FloatArray) -> Unit,
    ): Int {
        val phoneIds = tokenizer.encode(phonemes)
        if (phoneIds.isEmpty()) return 0
        val maxFrames = minOf(params.maxNewFrames, VieNeuTurboText.maxExpectedFrames(phonemes))
        val random = params.seed?.let { Random(it) } ?: Random.Default
        val sampler = VieNeuTurboSampler(random)
        val anchor = speakerAnchor(voice.speakerEmbedding)

        val textIds = IntArray(phoneIds.size + 3).also {
            it[0] = styleTokenId
            it[1] = TEXT_PROMPT_START
            phoneIds.copyInto(it, 2)
            it[it.size - 1] = TEXT_PROMPT_END
        }
        val promptLen = textIds.size + voice.refCodes.size
        val prompt = FloatArray(promptLen * H)
        for (t in textIds.indices) embed(textIds[t], null, anchor, prompt, t * H)
        voice.refCodes.forEachIndexed { i, codes -> embed(AUDIO_REF_SLOT, codes, anchor, prompt, (textIds.size + i) * H) }

        val history = Array(N_VQ) { VieNeuTurboRepetitionWindow(params.repetitionWindow) }
        var emitted = 0
        var kv: OrtSession.Result = tensor(prompt, longArrayOf(1, promptLen.toLong(), H.toLong())).use { x ->
            prefill.run(mapOf("inputs_embeds" to x))
        }
        var codecState: CodecState? = null
        try {
            var h = lastHidden(kv, promptLen)
            codecState = CodecState.initial(env, codecSpec)
            val pending = ArrayList<IntArray>(STREAM_CHUNK_FRAMES)
            var decodedChunks = 0
            for (step in 0 until maxFrames) {
                if (isCancelled()) return emitted
                val (frame, eos) = acousticFrame(h, params, sampler, history)
                pending += frame
                if (!eos) {
                    val slot = FloatArray(H)
                    embed(SPEECH_GENERATION_START, frame, anchor, slot, 0)
                    val next = tensor(slot, longArrayOf(1, 1, H.toLong())).use { x ->
                        OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf((promptLen + step).toLong())), longArrayOf(1, 1)).use { pos ->
                            val feeds = HashMap<String, OnnxTensor>(2 + 2 * LAYERS)
                            feeds["inputs_embeds"] = x
                            feeds["position_ids"] = pos
                            for (i in 0 until LAYERS) {
                                feeds["past_k_$i"] = kv.get("present_k_$i").get() as OnnxTensor
                                feeds["past_v_$i"] = kv.get("present_v_$i").get() as OnnxTensor
                            }
                            decode.run(feeds)
                        }
                    }
                    kv.close()
                    kv = next
                    h = lastHidden(kv, 1)
                }
                val target = if (decodedChunks == 0) LEAD_IN_FRAMES else STREAM_CHUNK_FRAMES
                if (pending.size >= target || eos) {
                    val audio = codecState.decode(codec, pending)
                    pending.clear()
                    decodedChunks++
                    if (audio.isNotEmpty()) {
                        emitted += audio.size
                        onAudio(audio)
                    }
                }
                if (eos) break
            }
            if (pending.isNotEmpty() && !isCancelled()) {
                val audio = codecState.decode(codec, pending)
                if (audio.isNotEmpty()) {
                    emitted += audio.size
                    onAudio(audio)
                }
            }
            return emitted
        } finally {
            kv.close()
            codecState?.close()
        }
    }

    /**
     * Sinh 16 code của một frame bằng acoustic decoder có KV cache (`_acoustic_frame`).
     *
     * @return Cặp (code 16 codebook, cờ kết thúc câu).
     */
    private fun acousticFrame(
        h: FloatArray,
        params: VieNeuTurboParams,
        sampler: VieNeuTurboSampler,
        history: Array<VieNeuTurboRepetitionWindow>,
    ): Pair<IntArray, Boolean> {
        val first = FloatArray(2 * H)
        h.copyInto(first, 0)
        readRow(textEmb, SPEECH_GENERATION_START * H, first, H)
        val emptyShape = longArrayOf(1, LOCAL_HEADS.toLong(), 0, LOCAL_HEAD_DIM.toLong())
        var res = tensor(first, longArrayOf(1, 2, H.toLong())).use { x ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(0, 1)), longArrayOf(1, 2)).use { p ->
                OnnxTensor.createTensor(env, FloatBuffer.allocate(0), emptyShape).use { pk ->
                    OnnxTensor.createTensor(env, FloatBuffer.allocate(0), emptyShape).use { pv ->
                        acoustic.run(mapOf("token_emb" to x, "position_ids" to p, "past_k_0" to pk, "past_v_0" to pv))
                    }
                }
            }
        }
        val codes = IntArray(N_VQ)
        val slot0: FloatArray
        try {
            val hidden = (res.get("hidden").get() as OnnxTensor).floatBuffer
            slot0 = FloatArray(H).also { hidden.position(0); hidden.get(it) }
            val slot1 = FloatArray(H).also { hidden.position(H); hidden.get(it) }
            codes[0] = sampleCode(0, slot1, params, sampler, history)
            for (ch in 1 until N_VQ) {
                val emb = FloatArray(H)
                readRow(audioEmb, ((ch - 1) * AUDIO_VOCAB + codes[ch - 1]) * H, emb, 0)
                val next = tensor(emb, longArrayOf(1, 1, H.toLong())).use { x ->
                    OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf((ch + 1).toLong())), longArrayOf(1, 1)).use { p ->
                        acoustic.run(
                            mapOf(
                                "token_emb" to x,
                                "position_ids" to p,
                                "past_k_0" to res.get("present_k_0").get() as OnnxTensor,
                                "past_v_0" to res.get("present_v_0").get() as OnnxTensor,
                            ),
                        )
                    }
                }
                res.close()
                res = next
                val hv = FloatArray(H).also { (res.get("hidden").get() as OnnxTensor).floatBuffer.get(it) }
                codes[ch] = sampleCode(ch, hv, params, sampler, history)
            }
        } finally {
            res.close()
        }
        val textLogits = runHead(textHead, slot0)
        var best = 0
        for (j in 1 until textLogits.size) if (textLogits[j] > textLogits[best]) best = j
        return codes to (best == SPEECH_GENERATION_END)
    }

    /** Tính logits codebook [ch] bằng head ONNX rồi lấy mẫu và cập nhật lịch sử phạt lặp. */
    private fun sampleCode(
        ch: Int,
        vec: FloatArray,
        params: VieNeuTurboParams,
        sampler: VieNeuTurboSampler,
        history: Array<VieNeuTurboRepetitionWindow>,
    ): Int {
        val logits = runHead(audioHeads[ch], vec)
        val code = sampler.sample(
            logits = logits,
            temperature = params.temperature,
            topK = params.topK,
            topP = params.topP,
            repetitionPenalty = params.repetitionPenalty,
            history = history[ch].codes,
        )
        history[ch].add(code)
        return code
    }

    /** Chạy `y = W · x` với `W` là tensor trọng số đã ánh xạ, trả logits. */
    private fun runHead(weights: OnnxTensor, vec: FloatArray): FloatArray =
        tensor(vec, longArrayOf(H.toLong(), 1)).use { x ->
            head.run(mapOf("w" to weights, "x" to x)).use { out ->
                val buf = (out.get("y").get() as OnnxTensor).floatBuffer
                FloatArray(buf.remaining()).also { buf.get(it) }
            }
        }

    /**
     * Cộng embedding text + các codebook hợp lệ + anchor cho một hàng prompt (`_embed_rows`).
     *
     * @param textId Token text ở cột 0.
     * @param codes 16 audio code của hàng, hoặc `null` nếu hàng chỉ có text.
     * @param anchor Anchor người nói.
     * @param out Mảng đích.
     * @param offset Vị trí bắt đầu trong [out].
     */
    private fun embed(textId: Int, codes: IntArray?, anchor: FloatArray, out: FloatArray, offset: Int) {
        val textBase = textId * H
        for (k in 0 until H) out[offset + k] = textEmb.get(textBase + k) + anchor[k]
        if (codes == null) return
        for (ch in 0 until N_VQ) {
            val id = codes[ch]
            if (id == AUDIO_PAD || id < 0 || id >= AUDIO_VOCAB) continue
            val base = (ch * AUDIO_VOCAB + id) * H
            for (k in 0 until H) out[offset + k] += audioEmb.get(base + k)
        }
    }

    /**
     * Chiếu x-vector 192 chiều thành anchor 768 chiều (Linear + LayerNorm, `_speaker_anchor`).
     *
     * @param embedding X-vector của giọng.
     * @return Anchor cộng vào mọi embedding.
     */
    private fun speakerAnchor(embedding: FloatArray): FloatArray {
        val dim = VieNeuTurboVoice.SPEAKER_EMBEDDING_DIM
        val v = FloatArray(H) { i ->
            var s = xvecB[i]
            val base = i * dim
            for (k in 0 until dim) s += xvecW[base + k] * embedding[k]
            s
        }
        var mean = 0f
        for (x in v) mean += x
        mean /= H
        var variance = 0f
        for (x in v) variance += (x - mean) * (x - mean)
        variance /= H
        val inv = 1f / sqrt(variance + xvecLnEps)
        return FloatArray(H) { i -> (v[i] - mean) * inv * xvecLnW[i] + xvecLnB[i] }
    }

    private fun lastHidden(result: OrtSession.Result, length: Int): FloatArray {
        val buf = (result.get("hidden").get() as OnnxTensor).floatBuffer
        return FloatArray(H).also { buf.position((length - 1) * H); buf.get(it) }
    }

    private fun readRow(src: FloatBuffer, start: Int, dst: FloatArray, dstOffset: Int) {
        for (k in 0 until H) dst[dstOffset + k] = src.get(start + k)
    }

    private fun tensor(data: FloatArray, shape: LongArray): OnnxTensor =
        OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape)

    /**
     * Tạo tensor trọng số `[rows, H]` trỏ thẳng vùng mmap; nếu ONNX Runtime không nhận buffer chỉ đọc
     * thì chép sang bộ nhớ native (không dùng heap Java).
     */
    private fun weightTensor(source: FloatBuffer, start: Int, rows: Int): OnnxTensor {
        val view = source.duplicate().also { it.position(start); it.limit(start + rows * H) }.slice()
        val shape = longArrayOf(rows.toLong(), H.toLong())
        val tensor = try {
            OnnxTensor.createTensor(env, view, shape)
        } catch (error: Exception) {
            Log.w(TAG, "Không dùng trực tiếp vùng mmap cho head, chép sang native: ${error.message}")
            val copy = ByteBuffer.allocateDirect(rows * H * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            copy.put(view.duplicate())
            copy.rewind()
            OnnxTensor.createTensor(env, copy, shape)
        }
        ownedTensors += tensor
        return tensor
    }

    private fun open(file: File, options: OrtSession.SessionOptions): OrtSession {
        check(file.isFile) { "Thiếu file model ${file.name}" }
        return env.createSession(file.absolutePath, options).also { sessions += it }
    }

    override fun close() {
        ownedTensors.forEach { runCatching { it.close() } }
        ownedTensors.clear()
        sessions.forEach { runCatching { it.close() } }
        sessions.clear()
        runCatching { sessionOptions.close() }
        runCatching { prefillOptions.close() }
    }

    /** Mô tả tên input/output state của codec streaming (`codec_browser_onnx_meta.json`). */
    private class CodecSpec(
        val inputs: List<StateInput>,
        val outputToInput: Map<String, String>,
    ) {
        class StateInput(val name: String, val shape: LongArray, val isFloat: Boolean, val fill: Int)

        companion object {
            fun parse(spec: JSONObject): CodecSpec {
                val inputs = ArrayList<StateInput>()
                val mapping = HashMap<String, String>()
                val offsets = spec.getJSONArray("transformer_offsets")
                for (i in 0 until offsets.length()) {
                    val o = offsets.getJSONObject(i)
                    inputs += StateInput(o.getString("input_name"), o.getJSONArray("shape").toLongArray(), false, 0)
                    mapping[o.getString("output_name")] = o.getString("input_name")
                }
                val caches = spec.getJSONArray("attention_caches")
                for (i in 0 until caches.length()) {
                    val a = caches.getJSONObject(i)
                    inputs += StateInput(a.getString("offset_input_name"), a.getJSONArray("offset_shape").toLongArray(), false, 0)
                    inputs += StateInput(a.getString("cached_keys_input_name"), a.getJSONArray("cache_shape").toLongArray(), true, 0)
                    inputs += StateInput(a.getString("cached_values_input_name"), a.getJSONArray("cache_shape").toLongArray(), true, 0)
                    inputs += StateInput(a.getString("cached_positions_input_name"), a.getJSONArray("positions_shape").toLongArray(), false, -1)
                    for (key in listOf("offset", "cached_keys", "cached_values", "cached_positions")) {
                        mapping[a.getString("${key}_output_name")] = a.getString("${key}_input_name")
                    }
                }
                return CodecSpec(inputs, mapping)
            }

            private fun org.json.JSONArray.toLongArray() = LongArray(length()) { getLong(it) }
        }
    }

    /**
     * State của codec MOSS streaming. State mới là tensor output của lần chạy trước, được giữ sống bằng
     * [OrtSession.Result] cho tới lần chạy kế tiếp nên không phải chép dữ liệu.
     */
    private class CodecState private constructor(
        private val env: OrtEnvironment,
        private val spec: CodecSpec,
        private var state: Map<String, OnnxTensor>,
        private var owner: AutoCloseable?,
    ) : AutoCloseable {

        /**
         * Giải mã một nhóm frame, cập nhật state, trả audio mono (trung bình các kênh).
         *
         * @param session Session `moss_audio_tokenizer_decode_step.onnx`.
         * @param frames Danh sách frame 16 code.
         * @return Mẫu PCM float32 48 kHz.
         */
        fun decode(session: OrtSession, frames: List<IntArray>): FloatArray {
            val flat = IntArray(frames.size * N_VQ).also { buf -> frames.forEachIndexed { i, f -> f.copyInto(buf, i * N_VQ) } }
            val result = OnnxTensor.createTensor(env, IntBuffer.wrap(flat), longArrayOf(1, frames.size.toLong(), N_VQ.toLong())).use { codes ->
                OnnxTensor.createTensor(env, IntBuffer.wrap(intArrayOf(frames.size)), longArrayOf(1)).use { lens ->
                    val feeds = HashMap<String, OnnxTensor>(state)
                    feeds["audio_codes"] = codes
                    feeds["audio_code_lengths"] = lens
                    session.run(feeds)
                }
            }
            val audioTensor = result.get("audio").get() as OnnxTensor
            val shape = audioTensor.info.shape
            val channels = shape[1].toInt()
            val total = shape[2].toInt()
            val valid = (result.get("audio_lengths").get() as OnnxTensor).intBuffer.get(0).coerceIn(0, total)
            val buf = audioTensor.floatBuffer
            val mono = FloatArray(valid) { i ->
                var s = 0f
                for (c in 0 until channels) s += buf.get(c * total + i)
                s / channels
            }
            val next = HashMap<String, OnnxTensor>(spec.outputToInput.size)
            for ((output, input) in spec.outputToInput) next[input] = result.get(output).get() as OnnxTensor
            owner?.close()
            owner = result
            state = next
            return mono
        }

        override fun close() {
            owner?.close()
            owner = null
        }

        companion object {
            fun initial(env: OrtEnvironment, spec: CodecSpec): CodecState {
                val tensors = HashMap<String, OnnxTensor>(spec.inputs.size)
                for (input in spec.inputs) {
                    val n = input.shape.fold(1L) { a, b -> a * b }.toInt()
                    tensors[input.name] = if (input.isFloat) {
                        OnnxTensor.createTensor(env, FloatBuffer.wrap(FloatArray(n)), input.shape)
                    } else {
                        OnnxTensor.createTensor(env, IntBuffer.wrap(IntArray(n) { input.fill }), input.shape)
                    }
                }
                val closer = AutoCloseable { tensors.values.forEach { it.close() } }
                return CodecState(env, spec, tensors, closer)
            }
        }
    }

    companion object {
        private const val TAG = "VieNeuTurboEngine"

        /** Tần số lấy mẫu đầu ra của codec MOSS. */
        const val SAMPLE_RATE = 48_000

        /** Số luồng ONNX Runtime mặc định (đo trên SM-S936B: 4 luồng cho RTF tốt nhất). */
        const val DEFAULT_THREADS = 4

        const val CONFIG_FILE = "config.json"
        const val TOKENIZER_FILE = "tokenizer.json"
        const val HEADS_FILE = "vieneu_v3_heads.npz"
        const val PREFILL_FILE = "vieneu_prefill.onnx"
        const val DECODE_FILE = "vieneu_decode_step.onnx"
        const val ACOUSTIC_FILE = "vieneu_acoustic_cached.onnx"
        const val BACKBONE_DATA_FILE = "vieneu_backbone_shared.data"
        const val CODEC_STEP_FILE = "moss_audio_tokenizer_decode_step.onnx"
        const val CODEC_DATA_FILE = "moss_audio_tokenizer_decode_shared.data"
        const val CODEC_META_FILE = "codec_browser_onnx_meta.json"

        private const val H = 768
        private const val N_VQ = 16
        private const val AUDIO_VOCAB = 1024
        private const val TEXT_VOCAB = 419
        private const val LAYERS = 12
        private const val LOCAL_HEADS = 8
        private const val LOCAL_HEAD_DIM = 96
        private const val AUDIO_PAD = 1024
        private const val TEXT_PROMPT_START = 3
        private const val TEXT_PROMPT_END = 4
        private const val SPEECH_GENERATION_START = 5
        private const val SPEECH_GENERATION_END = 6
        private const val AUDIO_REF_SLOT = 7
        private const val DEFAULT_STYLE_ID = 16
        private const val LEAD_IN_FRAMES = 4
        private const val STREAM_CHUNK_FRAMES = 12

        /**
         * Tạo cấu hình session theo kết quả đo bộ nhớ trên máy thật: tắt arena và memory pattern; riêng graph
         * prefill (chạy một lần mỗi chunk) tắt thêm prepack để không giữ bản backbone thứ hai.
         */
        private fun buildOptions(threads: Int, disablePrepacking: Boolean) = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setInterOpNumThreads(1)
            setIntraOpNumThreads(threads.coerceAtLeast(1))
            addConfigEntry("session.intra_op.allow_spinning", "0")
            setCPUArenaAllocator(false)
            setMemoryPatternOptimization(false)
            if (disablePrepacking) addConfigEntry("session.disable_prepacking", "1")
        }

        private fun VieNeuTurboNpyArray.toArray(): FloatArray {
            val b = buffer.duplicate()
            b.rewind()
            return FloatArray(b.remaining()).also { b.get(it) }
        }
    }
}
