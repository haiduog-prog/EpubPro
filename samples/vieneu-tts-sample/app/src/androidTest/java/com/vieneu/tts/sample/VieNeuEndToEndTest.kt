package com.vieneu.tts.sample

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vieneu.tts.sample.audio.PcmAudioPlayer
import com.vieneu.tts.sample.data.VieNeuModelDownloader
import com.vieneu.tts.sample.g2p.SeaG2pEngine
import com.vieneu.tts.sample.tts.ChunkGap
import com.vieneu.tts.sample.tts.VieNeuNanoEngine
import com.vieneu.tts.sample.tts.VieNeuTextChunker
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.sqrt

/**
 * Kiểm thử tích hợp trực tiếp trên thiết bị Android thật (`sea-g2p` JNI + `VieNeuNanoEngine`).
 */
@RunWith(AndroidJUnit4::class)
class VieNeuEndToEndTest {

    /**
     * Kiểm thử toàn bộ pipeline offline từ văn bản tiếng Việt sang PCM và xử lý ký tự OOV trên máy thật.
     */
    @Test
    fun synthesizeVietnameseTextOfflineWithPresetVoice() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val modelDir = File(targetContext.filesDir, VieNeuModelDownloader.MODEL_SUBDIR)
        val dictFile = File(modelDir, VieNeuModelDownloader.SEA_G2P_DICT_FILE)
        assertTrue("Thiếu modelDir: ${modelDir.absolutePath}", modelDir.isDirectory)
        assertTrue("Thiếu sea_g2p.bin: ${dictFile.absolutePath}", dictFile.isFile)

        val voicesJson = targetContext.assets.open("voices_v3_nano.json")
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
        val catalog = VieNeuNanoEngine.loadPresets(voicesJson)
        assertEquals(11, catalog.voices.size)
        val defaultVoice = catalog.voices.first { it.name == catalog.defaultVoiceName }

        val tLoad0 = SystemClock.elapsedRealtime()
        SeaG2pEngine(dictFile).use { g2p ->
            assertEquals(1, g2p.abiVersion)
            val text = "Hôm nay là ngày 13/11/2026 [cười], dự án đạt 98,5%.\n\nCảm ơn bạn đã thử nghiệm."
            val chunks = VieNeuTextChunker(g2p).prepareChunks(text)
            assertEquals(2, chunks.size)
            assertEquals(ChunkGap.FIRST, chunks[0].gapBefore)
            assertEquals(ChunkGap.PARA, chunks[1].gapBefore)
            assertTrue(chunks[0].phonemes.contains("<|emotion_1|>"))
            Log.i(TAG, "Chunk0 IPA: ${chunks[0].phonemes}")
            Log.i(TAG, "Chunk1 IPA: ${chunks[1].phonemes}")

            VieNeuNanoEngine(modelDir, threads = 4).use { engine ->
                val loadMs = SystemClock.elapsedRealtime() - tLoad0
                Log.i(TAG, "Loaded sea-g2p + VieNeuNanoEngine in $loadMs ms")

                // Ký tự ngoài vocab ('€') phải bị bỏ qua an toàn thay vì gây crash hoặc đổi token hợp lệ.
                val withOov = engine.encodePhones("${chunks[0].phonemes}€")
                val withoutOov = engine.encodePhones(chunks[0].phonemes)
                assertEquals(withoutOov.toList(), withOov.toList())

                val tFast0 = SystemClock.elapsedRealtime()
                val fastWav = engine.synthesize(
                    phonemes = chunks[0].phonemes,
                    voice = defaultVoice,
                    steps = 8,
                    cfg = 3.0f,
                    sway = -1.0f,
                    speed = 1.0f,
                    seed = 42L,
                )
                val fastMs = SystemClock.elapsedRealtime() - tFast0
                assertTrue("Audio chunk 0 rỗng", fastWav.isNotEmpty())
                val fastSec = fastWav.size.toFloat() / VieNeuNanoEngine.SAMPLE_RATE.toFloat()
                val fastRtf = (fastMs / 1000f) / fastSec
                val rms = rms(fastWav)
                assertTrue("RMS quá nhỏ ($rms), nghi audio im lặng", rms > 0.005f)
                Log.i(TAG, "Fast(8 steps) chunk0: gen=${fastMs}ms, audio=${fastSec}s, RTF=${fastRtf}, rms=${rms}")

                PcmAudioPlayer(VieNeuNanoEngine.SAMPLE_RATE).use { player ->
                    player.start()
                    player.stopImmediately()
                }
            }
        }
    }

    /**
     * Kiểm thử [PcmAudioPlayer] với [AudioManager] phát và drain trọn vẹn luồng PCM >10.5 giây không bị cắt sớm.
     */
    @Test
    fun pcmAudioPlayerDrainsLongStreamCleanlyWithAudioFocus() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val audioManager = targetContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        var focusLost = false

        // Buffer 0f (im lặng tuyệt đối) dài 10.6 giây để xác minh playback head không bị cắt ở ngưỡng 10s cũ.
        val silentStream = FloatArray((VieNeuNanoEngine.SAMPLE_RATE * 10.6f).toInt())
        val t0 = SystemClock.elapsedRealtime()
        PcmAudioPlayer(
            sampleRate = VieNeuNanoEngine.SAMPLE_RATE,
            audioManager = audioManager,
            onAudioFocusLost = { focusLost = true },
        ).use { player ->
            player.start()
            player.writeSamples(silentStream)
            runBlocking {
                player.finishAndRelease()
            }
        }
        val elapsedMs = SystemClock.elapsedRealtime() - t0
        assertFalse("Không kỳ vọng mất audio focus trong luồng kiểm thử", focusLost)
        assertTrue("Phát và drain luồng 10.6s bị kết thúc quá sớm: ${elapsedMs}ms", elapsedMs >= 10_000L)
        Log.i(TAG, "Drained 10.6s silent stream cleanly in ${elapsedMs}ms")
    }

    /**
     * Kiểm thử phân tích header `Content-Range` cho luồng tải resume HTTP 206 của [VieNeuModelDownloader].
     */
    @Test
    fun downloaderParsesContentRangeStartCorrectly() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val downloader = VieNeuModelDownloader(targetContext)
        assertEquals(1024L, downloader.parseContentRangeStart("bytes 1024-2047/4096"))
        assertEquals(0L, downloader.parseContentRangeStart("Bytes 0-99/*"))
        assertNull(downloader.parseContentRangeStart("bytes */4096"))
        assertNull(downloader.parseContentRangeStart(null))
    }

    /**
     * Tính biên độ hiệu dụng (RMS) của mảng mẫu waveform để xác minh audio không im lặng.
     *
     * @param samples Mảng mẫu PCM float32.
     * @return Giá trị RMS.
     */
    private fun rms(samples: FloatArray): Float {
        if (samples.isEmpty()) return 0f
        var sumSq = 0.0
        for (s in samples) {
            sumSq += s.toDouble() * s.toDouble()
        }
        return sqrt(sumSq / samples.size).toFloat()
    }

    companion object {
        private const val TAG = "VieNeuE2E"
    }
}
