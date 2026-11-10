package com.epubpro.core.tts

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Benchmark PoC đo tốc độ (RTF) của [VieNeuNanoEngine] trên thiết bị thật.
 *
 * Model không đóng gói trong repo; trước khi chạy cần chép các file của `pnnbao-ump/VieNeu-TTS-v3-Nano`
 * cùng `voices_v3_nano.json` vào `files/vieneu-nano/` của app test (đẩy vào `/data/local/tmp` rồi
 * `adb shell run-as com.epubpro.core.tts.test cp ...`). Thiếu model thì test tự bỏ qua.
 * Kết quả ghi ra logcat (tag `VieNeuNanoBench`) và file WAV cùng thư mục.
 */
@RunWith(AndroidJUnit4::class)
class VieNeuNanoBenchmarkTest {

    /**
     * Tổng hợp các câu mẫu với 2/4 luồng và 8/16 bước Euler, ghi RTF = thời gian tạo / độ dài audio.
     */
    @Test
    fun measureRealTimeFactor() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val modelDir = File(context.filesDir, MODEL_DIR)
        val voicesFile = File(modelDir, VOICES_FILE)
        assumeTrue("Chưa đẩy model VieNeu Nano vào $modelDir", voicesFile.isFile)

        val voice = VieNeuNanoVoice.loadPresets(voicesFile).first { it.name == VOICE_NAME }
        for (threads in THREAD_COUNTS) {
            val loadStart = SystemClock.elapsedRealtime()
            VieNeuNanoEngine(modelDir, threads).use { engine ->
                Log.i(TAG, "threads=$threads load=${SystemClock.elapsedRealtime() - loadStart}ms")
                engine.synthesize(PHONEMES.first(), voice, steps = 2)
                for ((steps, sway) in STEP_CONFIGS) {
                    PHONEMES.forEachIndexed { index, phonemes ->
                        val start = SystemClock.elapsedRealtime()
                        val wav = engine.synthesize(phonemes, voice, steps = steps, sway = sway)
                        val elapsed = (SystemClock.elapsedRealtime() - start) / 1000f
                        val seconds = wav.size.toFloat() / VieNeuNanoEngine.SAMPLE_RATE
                        assertTrue(wav.isNotEmpty())
                        Log.i(
                            TAG,
                            "threads=$threads steps=$steps text=$index audio=%.2fs gen=%.2fs RTF=%.3f"
                                .format(seconds, elapsed, elapsed / seconds)
                        )
                        writeWav(File(modelDir, "out_t${threads}_s${steps}_$index.wav"), wav)
                    }
                }
            }
        }
    }

    /**
     * Ghi waveform float ra WAV PCM 16-bit mono 24 kHz để nghe thử trên máy tính.
     *
     * @param file File đích.
     * @param wav Mẫu PCM float trong khoảng [-1, 1].
     */
    private fun writeWav(file: File, wav: FloatArray) {
        val pcm = ByteBuffer.allocate(wav.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        wav.forEach { pcm.putShort((it * Short.MAX_VALUE).toInt().toShort()) }
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + pcm.capacity()); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
            putInt(VieNeuNanoEngine.SAMPLE_RATE); putInt(VieNeuNanoEngine.SAMPLE_RATE * 2)
            putShort(2); putShort(16)
            put("data".toByteArray()); putInt(pcm.capacity())
        }
        DataOutputStream(file.outputStream()).use {
            it.write(header.array())
            it.write(pcm.array())
        }
    }

    private companion object {
        const val TAG = "VieNeuNanoBench"
        const val MODEL_DIR = "vieneu-nano"
        const val VOICES_FILE = "voices_v3_nano.json"
        const val VOICE_NAME = "Minh Quân"
        val THREAD_COUNTS = listOf(2, 4)
        val STEP_CONFIGS = listOf(8 to -1f, 16 to 0f)

        // Sinh bằng sea-g2p (SEAPipeline(lang="vi").run(text, punc_norm=True)) cho 3 câu:
        // "Xin chào, đây là thử nghiệm giọng đọc tiếng Việt chạy hoàn toàn trên thiết bị."
        // "Chương một. Trời đã về khuya, con đường nhỏ dẫn vào làng chìm trong màn sương mỏng."
        // "Anh ấy mở cuốn sách cũ, lật từng trang giấy đã ngả màu vàng theo năm tháng."
        val PHONEMES = listOf(
            "sˈin tʃˈaː2w, ɗˈəɪ lˌaː2 tˈy4 ŋˈiɛ6m zˈɔ6ŋ ɗˈɔ6k t̪ˈiɛɜŋ vˈiɛ6t̪ tʃˈa6j hwˈaː2n t̪wˈaː2n tʃˈen tˈiɛɜt̪ bˌi6.",
            "tʃˈyəŋ mˈo6t̪, tʃˈəː2j ɗˌaː5 vˈe2 xwˈiə, kˌɔn ɗˈyə2ŋ ɲˈɔ4 zˈə5n vˈaː2w lˈaː2ŋ tʃˈi2m tʃˈɔŋ mˈaː2n sˈyəŋ mˈɔ4ŋ.",
            "ˈe-ɲ ˈəɪɜ mˈəː4 kˈuəɜn sˈe-ɜc kˈu5, lˈə6t̪ t̪ˈy2ŋ tʃˈaːŋ zˈəɪɜ ɗˌaː5 ŋˈaː4 mˈa2w vˈaː2ŋ tˈɛw nˈam tˈaːɜŋ."
        )
    }
}
