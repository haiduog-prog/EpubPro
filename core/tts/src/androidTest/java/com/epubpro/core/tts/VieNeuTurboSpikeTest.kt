package com.epubpro.core.tts

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.LongBuffer
import java.util.zip.ZipFile

/**
 * Giai đoạn 0 (spike) của VieNeu-TTS v3 Turbo: đo RTF, độ trễ tới audio đầu tiên và RAM trên máy thật,
 * đồng thời so khớp audio code chế độ greedy với dữ liệu mẫu do SDK Python chính thức tạo ra.
 *
 * Luồng suy luận mô phỏng `vieneu/_v3_turbo_engine/onnx_runtime_lite.py` trong SDK `vieneu` 3.8.5
 * (Apache-2.0, https://github.com/pnnbao97/VieNeu-TTS). Đây là code thử nghiệm, không dùng trong app.
 *
 * Chuẩn bị (thiếu file thì test tự bỏ qua): đẩy thư mục `turbo/`, `codec/` và `golden/turbo_golden.json`
 * vào `/data/local/tmp/vieneu-turbo` rồi `adb shell run-as com.epubpro.core.tts.test cp -r ...` sang
 * `files/vieneu-turbo/`. Kết quả ghi ra logcat tag `VieNeuTurboSpike` và file WAV 48 kHz cùng thư mục.
 */
@RunWith(AndroidJUnit4::class)
class VieNeuTurboSpikeTest {

    /** Chạy từng câu mẫu với số luồng khác nhau, ghi thời gian từng phần, RTF, RAM và độ khớp code. */
    @Test
    fun measureTurboOnDevice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.filesDir, "vieneu-turbo")
        val goldenFile = File(root, "golden/turbo_golden.json")
        assumeTrue("Chưa đẩy model VieNeu Turbo vào $root", goldenFile.isFile)

        val golden = JSONObject(goldenFile.readText())
        val voices = JSONObject(File(root, "turbo/voices_v3_turbo.json").readText()).getJSONObject("presets")
        val voice = voices.getJSONObject(golden.getString("voice"))
        val rssStart = rssKb()
        val loadStart = SystemClock.elapsedRealtime()
        val weights = TurboWeights.load(File(root, "turbo/vieneu_v3_heads.npz"))
        Log.i(TAG, "heads loaded in ${SystemClock.elapsedRealtime() - loadStart}ms rss=${rssKb()}KB")

        for (threads in THREAD_COUNTS) {
            val t0 = SystemClock.elapsedRealtime()
            TurboSpikeEngine(root, threads, weights).use { engine ->
                Log.i(TAG, "threads=$threads sessions loaded in ${SystemClock.elapsedRealtime() - t0}ms rss=${rssKb()}KB")
                val anchor = weights.speakerAnchor(voice.getJSONArray("speaker_emb").toFloatArray())
                val refCodes = voice.getJSONArray("codes").toIntMatrix()
                val cases = golden.getJSONArray("cases")
                for (i in 0 until cases.length()) {
                    val case = cases.getJSONObject(i)
                    val result = engine.synthesize(
                        phoneIds = case.getJSONArray("phone_ids").toIntArray(),
                        refCodes = refCodes,
                        anchor = anchor,
                        styleId = golden.getInt("style_id"),
                        maxFrames = case.getInt("max_frames"),
                        repetitionPenalty = golden.getDouble("repetition_penalty").toFloat(),
                        repetitionWindow = golden.getInt("repetition_window"),
                    )
                    val expected = case.getJSONArray("codes").toIntMatrix()
                    val matched = result.codes.zip(expected).takeWhile { (a, b) -> a.contentEquals(b) }.size
                    if (threads == THREAD_COUNTS.first()) {
                        for (f in 0 until minOf(3, result.codes.size, expected.size)) {
                            val same = result.codes[f].indices.count { result.codes[f][it] == expected[f][it] }
                            Log.i(TAG, "case=$i frame=$f sameChannels=$same/16 got=${result.codes[f].toList()} want=${expected[f].toList()}")
                        }
                    }
                    val audioS = result.audio.size / SAMPLE_RATE.toFloat()
                    Log.i(
                        TAG,
                        "threads=$threads case=$i frames=${result.codes.size}/${expected.size} matchedFrames=$matched " +
                            "audio=%.2fs total=%.2fs RTF=%.3f firstAudio=%dms | prefill=%dms backbone=%dms acoustic=%dms heads=%dms codec=%dms | rss=%dKB nativeHeap=%dMB"
                                .format(
                                    audioS, result.totalMs / 1000f, result.totalMs / 1000f / audioS, result.firstAudioMs,
                                    result.prefillMs, result.backboneMs, result.acousticMs, result.headsMs, result.codecMs,
                                    rssKb(), Debug.getNativeHeapAllocatedSize() / 1_048_576,
                                ),
                    )
                    writeWav(File(root, "spike_t${threads}_case$i.wav"), result.audio)
                }
            }
        }
        Log.i(TAG, "done rssStart=${rssStart}KB rssEnd=${rssKb()}KB")
    }

    private companion object {
        const val TAG = "VieNeuTurboSpike"
        const val SAMPLE_RATE = 48_000
        val THREAD_COUNTS = listOf(4, 2, 6)
    }
}

/** Kết quả một lần tổng hợp: code từng frame, audio và thời gian từng phần (mili-giây). */
private class TurboResult(
    val codes: List<IntArray>,
    val audio: FloatArray,
    val totalMs: Long,
    val firstAudioMs: Long,
    val prefillMs: Long,
    val backboneMs: Long,
    val acousticMs: Long,
    val headsMs: Long,
    val codecMs: Long,
)

/**
 * Embedding/head dùng chung (`vieneu_v3_heads.npz`): `text_emb` (419×768), `audio_emb` (16×1024×768) và
 * phép chiếu x-vector → anchor người nói.
 */
private class TurboWeights(
    val textEmb: FloatArray,
    val audioEmb: FloatArray,
    private val xvecW: FloatArray,
    private val xvecB: FloatArray,
    private val lnW: FloatArray,
    private val lnB: FloatArray,
    private val lnEps: Float,
) {
    /**
     * Chiếu x-vector 192 chiều thành anchor 768 chiều (Linear + LayerNorm), giống `_speaker_anchor`.
     *
     * @param emb Speaker embedding 192 chiều của preset.
     * @return Anchor cộng vào mọi embedding đầu vào.
     */
    fun speakerAnchor(emb: FloatArray): FloatArray {
        val v = FloatArray(HIDDEN) { i ->
            var s = xvecB[i]
            val base = i * SPK_DIM
            for (k in 0 until SPK_DIM) s += xvecW[base + k] * emb[k]
            s
        }
        val mean = v.average().toFloat()
        val variance = v.fold(0f) { acc, x -> acc + (x - mean) * (x - mean) } / HIDDEN
        val inv = 1f / kotlin.math.sqrt(variance + lnEps)
        return FloatArray(HIDDEN) { i -> (v[i] - mean) * inv * lnW[i] + lnB[i] }
    }

    companion object {
        const val HIDDEN = 768
        const val N_VQ = 16
        const val AUDIO_VOCAB = 1024
        const val TEXT_VOCAB = 419
        const val SPK_DIM = 192

        /**
         * Đọc các mảng float32 từ file `.npz` (zip chứa `.npy` không nén).
         *
         * @param file Đường dẫn `vieneu_v3_heads.npz`.
         * @return Trọng số đã nạp.
         */
        fun load(file: File): TurboWeights = ZipFile(file).use { zip ->
            fun arr(name: String): FloatArray = zip.getInputStream(zip.getEntry("$name.npy")).use(::readNpyF32)
            TurboWeights(
                textEmb = arr("text_emb"), audioEmb = arr("audio_emb"), xvecW = arr("xvec_w"),
                xvecB = arr("xvec_b"), lnW = arr("xvec_ln_w"), lnB = arr("xvec_ln_b"),
                lnEps = arr("xvec_ln_eps")[0],
            )
        }

        /**
         * Đọc một mảng `.npy` dtype `<f4` thành [FloatArray] theo từng khối để tránh giữ hai bản trong RAM.
         *
         * @param input Luồng dữ liệu `.npy`.
         * @return Dữ liệu phẳng theo thứ tự C.
         * @throws IllegalArgumentException Khi dtype không phải `<f4` hoặc header không hợp lệ.
         */
        private fun readNpyF32(input: InputStream): FloatArray {
            val data = DataInputStream(input.buffered(1 shl 16))
            val magic = ByteArray(6).also(data::readFully)
            require(magic[0] == 0x93.toByte() && String(magic, 1, 5) == "NUMPY") { "Không phải file npy" }
            val major = data.readUnsignedByte()
            data.readUnsignedByte()
            val headerLen = if (major == 1) {
                data.readUnsignedByte() or (data.readUnsignedByte() shl 8)
            } else {
                ByteBuffer.wrap(ByteArray(4).also(data::readFully)).order(ByteOrder.LITTLE_ENDIAN).int
            }
            val header = String(ByteArray(headerLen).also(data::readFully))
            require("'<f4'" in header && "'fortran_order': False" in header) { "Header npy không hỗ trợ: $header" }
            val shape = Regex("'shape': \\(([^)]*)\\)").find(header)!!.groupValues[1]
                .split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { it.toInt() }
            val count = shape.fold(1) { a, b -> a * b }
            val out = FloatArray(count)
            val chunk = ByteArray(1 shl 20)
            var filled = 0
            while (filled < count) {
                val n = minOf(chunk.size / 4, count - filled)
                data.readFully(chunk, 0, n * 4)
                ByteBuffer.wrap(chunk, 0, n * 4).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out, filled, n)
                filled += n
            }
            return out
        }
    }
}

/**
 * Engine Turbo tối thiểu cho spike: prefill + decode-step (backbone 12 lớp), acoustic decoder 16 codebook,
 * head bằng Kotlin, lấy mẫu greedy có repetition penalty cửa sổ trượt, và codec MOSS streaming.
 */
private class TurboSpikeEngine(root: File, threads: Int, private val w: TurboWeights) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val options = OrtSession.SessionOptions().apply {
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        setInterOpNumThreads(1)
        setIntraOpNumThreads(threads)
        addConfigEntry("session.intra_op.allow_spinning", "0")
    }
    private val prefill = env.createSession(File(root, "turbo/vieneu_prefill.onnx").absolutePath, options)
    private val decode = env.createSession(File(root, "turbo/vieneu_decode_step.onnx").absolutePath, options)
    private val acoustic = env.createSession(File(root, "turbo/vieneu_acoustic_cached.onnx").absolutePath, options)
    private val codecStep = env.createSession(File(root, "codec/moss_audio_tokenizer_decode_step.onnx").absolutePath, options)
    private val streamSpec = JSONObject(File(root, "codec/codec_browser_onnx_meta.json").readText())
        .getJSONObject("streaming_decode")

    private var tPrefill = 0L
    private var tBackbone = 0L
    private var tAcoustic = 0L
    private var tHeads = 0L
    private var tCodec = 0L

    /**
     * Tổng hợp một câu từ phoneme id, trả về code, audio và thời gian từng phần.
     *
     * @param phoneIds Token id phoneme (đã tokenize bằng `tokenizer.json`).
     * @param refCodes Audio code tham chiếu của preset (số frame × 16).
     * @param anchor Anchor người nói 768 chiều.
     * @param styleId Token phong cách (mặc định tự nhiên).
     * @param maxFrames Trần số frame theo độ dài phoneme.
     * @param repetitionPenalty Hệ số phạt lặp.
     * @param repetitionWindow Kích thước cửa sổ trượt (frame) của lịch sử phạt lặp.
     * @return [TurboResult] của câu.
     */
    fun synthesize(
        phoneIds: IntArray,
        refCodes: List<IntArray>,
        anchor: FloatArray,
        styleId: Int,
        maxFrames: Int,
        repetitionPenalty: Float,
        repetitionWindow: Int,
    ): TurboResult {
        tPrefill = 0; tBackbone = 0; tAcoustic = 0; tHeads = 0; tCodec = 0
        val start = SystemClock.elapsedRealtime()
        var firstAudioMs = -1L
        val textIds = intArrayOf(styleId, TPS) + phoneIds + intArrayOf(TPE)
        val rows = textIds.map { id -> IntArray(N_VQ + 1) { if (it == 0) id else AUDIO_PAD } } +
            refCodes.map { rc -> IntArray(N_VQ + 1) { if (it == 0) REF_SLOT else rc[it - 1] } }
        val prompt = FloatArray(rows.size * H).also { buf -> rows.forEachIndexed { t, r -> embedRow(r, anchor, buf, t * H) } }

        var t = SystemClock.elapsedRealtime()
        var kv: OrtSession.Result
        var h: FloatArray
        OnnxTensor.createTensor(env, FloatBuffer.wrap(prompt), longArrayOf(1, rows.size.toLong(), H.toLong())).use { x ->
            kv = prefill.run(mapOf("inputs_embeds" to x))
        }
        h = lastHidden(kv, rows.size)
        tPrefill += SystemClock.elapsedRealtime() - t

        val history = Array(N_VQ) { SlidingHistory(repetitionWindow) }
        val codes = ArrayList<IntArray>()
        val audio = ArrayList<FloatArray>()
        val codecState = newCodecState()
        val pending = ArrayList<IntArray>()
        for (step in 0 until maxFrames) {
            val (frame, eos) = acousticFrame(h, repetitionPenalty, history)
            codes += frame
            pending += frame
            if (!eos) {
                t = SystemClock.elapsedRealtime()
                val slot = IntArray(N_VQ + 1) { if (it == 0) SGS else frame[it - 1] }
                val emb = FloatArray(H).also { embedRow(slot, anchor, it, 0) }
                val feeds = HashMap<String, OnnxTensor>()
                val x = OnnxTensor.createTensor(env, FloatBuffer.wrap(emb), longArrayOf(1, 1, H.toLong()))
                val pos = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf((rows.size + step).toLong())), longArrayOf(1, 1))
                feeds["inputs_embeds"] = x
                feeds["position_ids"] = pos
                for (i in 0 until LAYERS) {
                    feeds["past_k_$i"] = kv.get("present_k_$i").get() as OnnxTensor
                    feeds["past_v_$i"] = kv.get("present_v_$i").get() as OnnxTensor
                }
                val next = decode.run(feeds)
                x.close(); pos.close(); kv.close()
                kv = next
                h = lastHidden(kv, 1)
                tBackbone += SystemClock.elapsedRealtime() - t
            }
            val target = if (audio.isEmpty()) LEAD_IN_FRAMES else STREAM_CHUNK_FRAMES
            if (pending.size >= target || eos) {
                audio += decodeCodecChunk(pending, codecState)
                pending.clear()
                if (firstAudioMs < 0) firstAudioMs = SystemClock.elapsedRealtime() - start
            }
            if (eos) break
        }
        if (pending.isNotEmpty()) audio += decodeCodecChunk(pending, codecState)
        kv.close()
        codecState.values.forEach { it.close() }
        val all = FloatArray(audio.sumOf { it.size }).also { out ->
            var o = 0
            audio.forEach { a -> a.copyInto(out, o); o += a.size }
        }
        return TurboResult(
            codes, all, SystemClock.elapsedRealtime() - start, firstAudioMs,
            tPrefill, tBackbone, tAcoustic, tHeads, tCodec,
        )
    }

    /** Lấy hidden của vị trí cuối từ output `hidden` [1, T, 768]. */
    private fun lastHidden(result: OrtSession.Result, length: Int): FloatArray {
        val buf = (result.get("hidden").get() as OnnxTensor).floatBuffer
        return FloatArray(H).also { buf.position((length - 1) * H); buf.get(it) }
    }

    /**
     * Cộng embedding text + 16 codebook (bỏ qua pad) + anchor cho một hàng prompt, giống `_embed_rows`.
     */
    private fun embedRow(row: IntArray, anchor: FloatArray, out: FloatArray, offset: Int) {
        val textBase = row[0] * H
        for (k in 0 until H) out[offset + k] = w.textEmb[textBase + k] + anchor[k]
        for (ch in 0 until N_VQ) {
            val id = row[ch + 1]
            if (id == AUDIO_PAD) continue
            val base = (ch * AUDIO_VOCAB + id) * H
            for (k in 0 until H) out[offset + k] += w.audioEmb[base + k]
        }
    }

    /**
     * Sinh 16 code của một frame bằng acoustic decoder có KV cache, giống `_acoustic_frame`.
     *
     * @return Cặp (16 code, cờ kết thúc câu).
     */
    private fun acousticFrame(h: FloatArray, penalty: Float, history: Array<SlidingHistory>): Pair<IntArray, Boolean> {
        var t = SystemClock.elapsedRealtime()
        val first = FloatArray(2 * H).also { h.copyInto(it, 0); w.textEmb.copyInto(it, H, SGS * H, SGS * H + H) }
        val empty = FloatBuffer.allocate(0)
        val emptyShape = longArrayOf(1, LOCAL_HEADS.toLong(), 0, LOCAL_HEAD_DIM.toLong())
        var res: OrtSession.Result
        OnnxTensor.createTensor(env, FloatBuffer.wrap(first), longArrayOf(1, 2, H.toLong())).use { x ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(0, 1)), longArrayOf(1, 2)).use { p ->
                OnnxTensor.createTensor(env, empty, emptyShape).use { pk ->
                    OnnxTensor.createTensor(env, FloatBuffer.allocate(0), emptyShape).use { pv ->
                        res = acoustic.run(mapOf("token_emb" to x, "position_ids" to p, "past_k_0" to pk, "past_v_0" to pv))
                    }
                }
            }
        }
        val hidden0 = (res.get("hidden").get() as OnnxTensor).floatBuffer
        val slot0 = FloatArray(H).also { hidden0.position(0); hidden0.get(it) }
        val slot1 = FloatArray(H).also { hidden0.position(H); hidden0.get(it) }
        tAcoustic += SystemClock.elapsedRealtime() - t

        val codes = IntArray(N_VQ)
        codes[0] = sampleChannel(0, slot1, penalty, history)
        for (ch in 1 until N_VQ) {
            t = SystemClock.elapsedRealtime()
            val prevBase = ((ch - 1) * AUDIO_VOCAB + codes[ch - 1]) * H
            val emb = w.audioEmb.copyOfRange(prevBase, prevBase + H)
            val next: OrtSession.Result
            OnnxTensor.createTensor(env, FloatBuffer.wrap(emb), longArrayOf(1, 1, H.toLong())).use { x ->
                OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf((ch + 1).toLong())), longArrayOf(1, 1)).use { p ->
                    next = acoustic.run(
                        mapOf(
                            "token_emb" to x, "position_ids" to p,
                            "past_k_0" to res.get("present_k_0").get() as OnnxTensor,
                            "past_v_0" to res.get("present_v_0").get() as OnnxTensor,
                        ),
                    )
                }
            }
            res.close()
            res = next
            val hv = FloatArray(H).also { val b = (res.get("hidden").get() as OnnxTensor).floatBuffer; b.get(it) }
            tAcoustic += SystemClock.elapsedRealtime() - t
            codes[ch] = sampleChannel(ch, hv, penalty, history)
        }
        res.close()

        t = SystemClock.elapsedRealtime()
        var best = 0
        var bestV = Float.NEGATIVE_INFINITY
        for (j in 0 until TurboWeights.TEXT_VOCAB) {
            val v = dot(slot0, w.textEmb, j * H)
            if (v > bestV) { bestV = v; best = j }
        }
        tHeads += SystemClock.elapsedRealtime() - t
        return codes to (best == EOS_SPEECH)
    }

    /** Tính logits của codebook [ch] (head gắn với `audio_emb[ch]`), áp repetition penalty rồi lấy argmax. */
    private fun sampleChannel(ch: Int, vec: FloatArray, penalty: Float, history: Array<SlidingHistory>): Int {
        val t = SystemClock.elapsedRealtime()
        val logits = FloatArray(AUDIO_VOCAB) { j -> dot(vec, w.audioEmb, (ch * AUDIO_VOCAB + j) * H) }
        for (code in history[ch].codes()) {
            val v = logits[code]
            logits[code] = if (v < 0) v * penalty else v / penalty
        }
        var best = 0
        for (j in 1 until AUDIO_VOCAB) if (logits[j] > logits[best]) best = j
        history[ch].add(best)
        tHeads += SystemClock.elapsedRealtime() - t
        return best
    }

    private fun dot(a: FloatArray, b: FloatArray, off: Int): Float {
        var s0 = 0f; var s1 = 0f; var s2 = 0f; var s3 = 0f
        var k = 0
        while (k < H) {
            s0 += a[k] * b[off + k]; s1 += a[k + 1] * b[off + k + 1]
            s2 += a[k + 2] * b[off + k + 2]; s3 += a[k + 3] * b[off + k + 3]
            k += 4
        }
        return (s0 + s1) + (s2 + s3)
    }

    /** Khởi tạo state codec streaming theo `codec_browser_onnx_meta.json` (positions = -1 là ô trống). */
    private fun newCodecState(): MutableMap<String, OnnxTensor> {
        val state = HashMap<String, OnnxTensor>()
        val offsets = streamSpec.getJSONArray("transformer_offsets")
        for (i in 0 until offsets.length()) {
            val o = offsets.getJSONObject(i)
            state[o.getString("input_name")] = intTensor(o.getJSONArray("shape").toLongArray(), 0)
        }
        val caches = streamSpec.getJSONArray("attention_caches")
        for (i in 0 until caches.length()) {
            val a = caches.getJSONObject(i)
            state[a.getString("offset_input_name")] = intTensor(a.getJSONArray("offset_shape").toLongArray(), 0)
            val cacheShape = a.getJSONArray("cache_shape").toLongArray()
            val n = cacheShape.fold(1L) { x, y -> x * y }.toInt()
            state[a.getString("cached_keys_input_name")] = OnnxTensor.createTensor(env, FloatBuffer.wrap(FloatArray(n)), cacheShape)
            state[a.getString("cached_values_input_name")] = OnnxTensor.createTensor(env, FloatBuffer.wrap(FloatArray(n)), cacheShape)
            state[a.getString("cached_positions_input_name")] = intTensor(a.getJSONArray("positions_shape").toLongArray(), -1)
        }
        return state
    }

    private fun intTensor(shape: LongArray, value: Int): OnnxTensor {
        val n = shape.fold(1L) { x, y -> x * y }.toInt()
        return OnnxTensor.createTensor(env, IntBuffer.wrap(IntArray(n) { value }), shape)
    }

    /**
     * Giải mã một nhóm frame bằng codec streaming, cập nhật state tại chỗ, trả audio mono (trung bình kênh).
     */
    private fun decodeCodecChunk(frames: List<IntArray>, state: MutableMap<String, OnnxTensor>): FloatArray {
        val t = SystemClock.elapsedRealtime()
        val flat = IntArray(frames.size * N_VQ).also { buf -> frames.forEachIndexed { i, f -> f.copyInto(buf, i * N_VQ) } }
        val codes = OnnxTensor.createTensor(env, IntBuffer.wrap(flat), longArrayOf(1, frames.size.toLong(), N_VQ.toLong()))
        val lens = OnnxTensor.createTensor(env, IntBuffer.wrap(intArrayOf(frames.size)), longArrayOf(1))
        val feeds = HashMap<String, OnnxTensor>(state).apply { put("audio_codes", codes); put("audio_code_lengths", lens) }
        val out = codecStep.run(feeds)
        codes.close(); lens.close()
        val mapping = HashMap<String, String>()
        val offsets = streamSpec.getJSONArray("transformer_offsets")
        for (i in 0 until offsets.length()) offsets.getJSONObject(i).let { mapping[it.getString("input_name")] = it.getString("output_name") }
        val caches = streamSpec.getJSONArray("attention_caches")
        for (i in 0 until caches.length()) caches.getJSONObject(i).let { a ->
            for (key in listOf("offset", "cached_keys", "cached_values", "cached_positions")) {
                mapping[a.getString("${key}_input_name")] = a.getString("${key}_output_name")
            }
        }
        for ((input, output) in mapping) {
            val src = out.get(output).get() as OnnxTensor
            val copy = copyTensor(src)
            state.put(input, copy)?.close()
        }
        val audioT = out.get("audio").get() as OnnxTensor
        val shape = audioT.info.shape
        val channels = shape[1].toInt()
        val total = shape[2].toInt()
        val valid = (out.get("audio_lengths").get() as OnnxTensor).intBuffer.get(0).coerceAtMost(total)
        val buf = audioT.floatBuffer
        val mono = FloatArray(valid) { i ->
            var s = 0f
            for (c in 0 until channels) s += buf.get(c * total + i)
            s / channels
        }
        out.close()
        tCodec += SystemClock.elapsedRealtime() - t
        return mono
    }

    private fun copyTensor(src: OnnxTensor): OnnxTensor {
        val shape = src.info.shape
        return when (src.info.type) {
            ai.onnxruntime.OnnxJavaType.INT32 -> {
                val b = src.intBuffer; val a = IntArray(b.remaining()).also { b.get(it) }
                OnnxTensor.createTensor(env, IntBuffer.wrap(a), shape)
            }
            else -> {
                val b = src.floatBuffer; val a = FloatArray(b.remaining()).also { b.get(it) }
                OnnxTensor.createTensor(env, FloatBuffer.wrap(a), shape)
            }
        }
    }

    override fun close() {
        prefill.close(); decode.close(); acoustic.close(); codecStep.close(); options.close()
    }

    private companion object {
        const val H = TurboWeights.HIDDEN
        const val N_VQ = TurboWeights.N_VQ
        const val AUDIO_VOCAB = TurboWeights.AUDIO_VOCAB
        const val LAYERS = 12
        const val LOCAL_HEADS = 8
        const val LOCAL_HEAD_DIM = 96
        const val AUDIO_PAD = 1024
        const val TPS = 3
        const val TPE = 4
        const val SGS = 5
        const val EOS_SPEECH = 6
        const val REF_SLOT = 7
        const val LEAD_IN_FRAMES = 4
        const val STREAM_CHUNK_FRAMES = 12
    }
}

/** Lịch sử code của một codebook trong cửa sổ trượt [window] frame (giống `_ChannelWindow`). */
private class SlidingHistory(private val window: Int) {
    private val counts = HashMap<Int, Int>()
    private val order = ArrayDeque<Int>()

    /** Thêm code vừa sinh, trục xuất code cũ nhất khi vượt cửa sổ. */
    fun add(code: Int) {
        counts[code] = (counts[code] ?: 0) + 1
        if (window > 0) {
            order.addLast(code)
            if (order.size > window) {
                val old = order.removeFirst()
                val c = counts.getValue(old)
                if (c <= 1) counts.remove(old) else counts[old] = c - 1
            }
        }
    }

    /** Các code đang nằm trong cửa sổ (bị phạt lặp). */
    fun codes(): Set<Int> = counts.keys
}

private fun rssKb(): Long =
    File("/proc/self/status").readLines().firstOrNull { it.startsWith("VmRSS") }
        ?.filter { it.isDigit() }?.toLongOrNull() ?: -1

private fun JSONArray.toFloatArray() = FloatArray(length()) { getDouble(it).toFloat() }
private fun JSONArray.toIntArray() = IntArray(length()) { getInt(it) }
private fun JSONArray.toLongArray() = LongArray(length()) { getLong(it) }
private fun JSONArray.toIntMatrix(): List<IntArray> = List(length()) { getJSONArray(it).toIntArray() }

/** Ghi PCM float ra WAV 16-bit mono 48 kHz. */
private fun writeWav(file: File, wav: FloatArray) {
    val rate = 48_000
    val pcm = ByteBuffer.allocate(wav.size * 2).order(ByteOrder.LITTLE_ENDIAN)
    wav.forEach { pcm.putShort((it.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()) }
    val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt(36 + pcm.capacity()); put("WAVE".toByteArray())
        put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
        putInt(rate); putInt(rate * 2); putShort(2); putShort(16)
        put("data".toByteArray()); putInt(pcm.capacity())
    }
    DataOutputStream(file.outputStream()).use { it.write(header.array()); it.write(pcm.array()) }
}
