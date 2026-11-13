package com.epubpro.core.tts

import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * Kiểm thử tích hợp trên thiết bị thật cho [VieNeuRuntimeManager], `SeaG2pEngine` JNI và `VieNeuNanoEngine`.
 */
@RunWith(AndroidJUnit4::class)
class VieNeuRuntimeInstrumentedTest {

    /**
     * Xác nhận thiết bị `arm64-v8a`, bảng 11 giọng trong asset APK và bộ phân giải ID `"vieneu:"` hoạt động đúng.
     */
    @Test
    fun deviceSupportAndCatalogLoadFromApkAssets() {
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val supportChecker = VieNeuDeviceSupportChecker()
            val expectedArm64 = Build.SUPPORTED_ABIS.any { it.equals("arm64-v8a", ignoreCase = true) }
            assertEquals(expectedArm64, supportChecker.isSupported())

            val downloader = VieNeuModelDownloader(context)
            val runtimeManager = VieNeuRuntimeManager(context, downloader, supportChecker)
            val catalog = runtimeManager.loadVoiceCatalog()

            assertEquals(11, catalog.voices.size)
            assertEquals("Minh Quân", catalog.defaultVoiceName)

            val resolved = runtimeManager.resolveVoice("vieneu:Minh Quân")
            assertEquals("Minh Quân", resolved.name)
            assertEquals(192, resolved.speakerEmbedding.size)
        }
    }

    /**
     * Kiểm thử luồng end-to-end thật trên thiết bị: JNI `sea-g2p` + ONNX `VieNeuNanoEngine` với cấu hình flagship.
     */
    @Test
    fun endToEndSeaG2pAndOnnxSynthesis() {
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val supportChecker = VieNeuDeviceSupportChecker()
            assumeTrue("Thiết bị không hỗ trợ arm64-v8a", supportChecker.isSupported())

            val downloader = VieNeuModelDownloader(context)
            assumeTrue(
                "Thiếu model VieNeu trong ${downloader.modelDir.absolutePath}",
                downloader.isModelReady(),
            )

            val runtimeManager = VieNeuRuntimeManager(context, downloader, supportChecker)
            val totalStartMs = SystemClock.elapsedRealtime()
            try {
                val voice = runtimeManager.resolveVoice("vieneu:Minh Quân")
                val chunks = runtimeManager.prepareChunks(
                    text = "Xin chào, đây là thử nghiệm giọng đọc VieNeu v3-Nano tích hợp trong EpubPro.",
                    threads = 4,
                )
                assertTrue("Phải tách được ít nhất 1 chunk âm vị", chunks.isNotEmpty())
                assertTrue("Âm vị từ sea-g2p không được rỗng", chunks.all { it.phonemes.isNotBlank() })

                val wav = runtimeManager.synthesizeChunk(
                    phonemes = chunks.first().phonemes,
                    voice = voice,
                    steps = 8,
                    cfg = 3.0f,
                    sway = -1.0f,
                    speed = 1.0f,
                    threads = 4,
                )

                val elapsedMs = SystemClock.elapsedRealtime() - totalStartMs
                val audioSeconds = wav.size.toFloat() / VieNeuNanoEngine.SAMPLE_RATE
                val peak = wav.maxOfOrNull { abs(it) } ?: 0f

                assertTrue("Audio đầu ra phải dài hơn 0.5 giây", audioSeconds > 0.5f)
                assertTrue("Mọi mẫu audio phải hữu hạn", wav.all { it.isFinite() })
                assertTrue("Biên độ đỉnh phải > 0.01f (không phải im lặng)", peak > 0.01f)

                Log.i(
                    TAG,
                    "E2E OK: chunks=${chunks.size}, audio=${"%.2f".format(audioSeconds)}s, total=${elapsedMs}ms, peak=${"%.3f".format(peak)}",
                )
            } finally {
                runtimeManager.unload()
            }
        }
    }

    private companion object {
        const val TAG = "VieNeuInstrumented"
    }
}
