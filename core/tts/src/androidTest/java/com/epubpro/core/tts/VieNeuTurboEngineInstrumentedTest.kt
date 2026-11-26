package com.epubpro.core.tts

import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Kiểm thử E2E [VieNeuTurboEngine] trên thiết bị thật: độ dài audio so với SDK chính thức, RTF, độ trễ tới
 * audio đầu tiên, RAM, cùng file WAV để nghe (giọng chính thức và giọng người dùng `Vie_ThuyTrang`).
 *
 * Chuẩn bị (thiếu file thì test tự bỏ qua): chép toàn bộ file model Turbo + codec MOSS (phẳng) vào
 * `files/vieneu-turbo-flat/` và `turbo_golden.json` vào cùng thư mục bằng `adb shell run-as`.
 */
@RunWith(AndroidJUnit4::class)
class VieNeuTurboEngineInstrumentedTest {

    /** Tổng hợp các câu mẫu bằng chế độ greedy và chế độ lấy mẫu mặc định, ghi số đo ra logcat. */
    @Test
    fun synthesizeGoldenSentences() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.filesDir, "vieneu-turbo-flat")
        val goldenFile = File(dir, "turbo_golden.json")
        assumeTrue("Chưa chép model Turbo vào $dir", goldenFile.isFile)

        val golden = JSONObject(goldenFile.readText())
        val official = VieNeuTurboVoice.parsePresets(
            context.assets.open("voices_v3_turbo.json").bufferedReader().use { it.readText() }, isUserVoice = false,
        )
        val user = VieNeuTurboVoice.parsePresets(
            context.assets.open("voices_v3_turbo_user.json").bufferedReader().use { it.readText() }, isUserVoice = true,
        )
        Log.i(TAG, "voices official=${official.size} user=${user.map { it.name }}")
        val voices = listOf(official.first { it.name == golden.getString("voice") }, user.first())

        val loadStart = SystemClock.elapsedRealtime()
        VieNeuTurboEngine(dir).use { engine ->
            Log.i(TAG, "load=${SystemClock.elapsedRealtime() - loadStart}ms rss=${rssKb()}KB native=${Debug.getNativeHeapAllocatedSize() / 1_048_576}MB")
            val cases = golden.getJSONArray("cases")
            for (voice in voices) {
                for ((mode, params) in listOf("greedy" to VieNeuTurboParams(temperature = 0f), "sampled" to VieNeuTurboParams(seed = 1234L))) {
                    for (i in 0 until cases.length()) {
                        val case = cases.getJSONObject(i)
                        val phonemes = case.getString("phonemes")
                        val ids = case.getJSONArray("phone_ids")
                        val tokens = engine.tokenize(phonemes)
                        assertTrue("Token id lệch SDK ở case $i", tokens.toList() == List(ids.length()) { ids.getInt(it) })

                        val chunks = ArrayList<FloatArray>()
                        val start = SystemClock.elapsedRealtime()
                        var firstAudioMs = -1L
                        val samples = engine.synthesize(phonemes, voice, params) { pcm ->
                            if (firstAudioMs < 0) firstAudioMs = SystemClock.elapsedRealtime() - start
                            chunks += pcm
                        }
                        val elapsed = (SystemClock.elapsedRealtime() - start) / 1000f
                        val seconds = samples / VieNeuTurboEngine.SAMPLE_RATE.toFloat()
                        val goldenSeconds = case.getInt("audio_samples") / VieNeuTurboEngine.SAMPLE_RATE.toFloat()
                        assertTrue("Audio rỗng ở case $i", samples > 0)
                        Log.i(
                            TAG,
                            "voice=${voice.name} mode=$mode case=$i audio=%.2fs (sdk %.2fs) gen=%.2fs RTF=%.3f first=%dms rss=%dKB native=%dMB"
                                .format(seconds, goldenSeconds, elapsed, elapsed / seconds, firstAudioMs, rssKb(), Debug.getNativeHeapAllocatedSize() / 1_048_576),
                        )
                        val all = FloatArray(samples).also { out -> var o = 0; chunks.forEach { it.copyInto(out, o); o += it.size } }
                        writeWav(File(dir, "engine_${voice.name.filter { it.isLetterOrDigit() || it == '_' }}_${mode}_$i.wav"), all)
                    }
                }
            }
        }
    }

    private fun rssKb(): Long =
        File("/proc/self/status").readLines().firstOrNull { it.startsWith("VmRSS") }?.filter { it.isDigit() }?.toLongOrNull() ?: -1

    /**
     * Ghi PCM float ra WAV 16-bit mono 48 kHz.
     *
     * @param file File đích.
     * @param wav Mẫu PCM float trong khoảng [-1, 1].
     */
    private fun writeWav(file: File, wav: FloatArray) {
        val rate = VieNeuTurboEngine.SAMPLE_RATE
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

    private companion object {
        const val TAG = "VieNeuTurboE2E"
    }
}
