package com.epubpro.core.tts

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Kiểm thử tải gói VieNeu Turbo thật qua mạng bằng [VieNeuTurboModelDownloader] (cùng lớp app dùng):
 * huỷ giữa chừng phải giữ file `.tmp`, tải tiếp phải resume bằng HTTP Range, kết thúc đủ file đúng kích thước
 * và engine nạp được từ thư mục vừa tải. Cần mạng; tải khoảng 275 MB vào bộ nhớ app test rồi xoá.
 */
@RunWith(AndroidJUnit4::class)
class VieNeuTurboDownloadInstrumentedTest {

    /** Tải, huỷ ở khoảng 20 MB, tải tiếp tới hết rồi nạp engine. */
    @Test
    fun downloadCancelResumeAndLoad() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.filesDir, "vieneu-turbo-download-test").apply { deleteRecursively() }
        val downloader = VieNeuTurboModelDownloader(
            modelDirProvider = { dir },
            nanoDirProvider = { File(context.filesDir, "no-nano-dir") },
        )
        assertTrue(!downloader.isModelReady())

        // Lượt 1: huỷ khi đã tải khoảng 20 MB.
        val reached = CompletableDeferred<Unit>()
        val first = launch(Dispatchers.IO) {
            downloader.downloadMissingArtifacts { progress ->
                if (progress.downloadedBytes >= CANCEL_AFTER_BYTES) reached.complete(Unit)
            }
        }
        withTimeout(PHASE_TIMEOUT_MS) { reached.await() }
        first.cancel()
        first.join()
        val afterCancel = downloader.inspectInventory()
        val tmpFiles = dir.listFiles { f -> f.name.endsWith(".tmp") }.orEmpty()
        Log.i(TAG, "sau khi huỷ: present=${afterCancel.presentCount}/${afterCancel.totalCount} existing=${afterCancel.existingBytes / 1_000_000}MB tmp=${tmpFiles.map { "${it.name}:${it.length() / 1_000_000}MB" }}")
        assertTrue("Huỷ giữa chừng phải chưa đủ file", !afterCancel.isReady)
        assertTrue("Phải còn dữ liệu đã tải sau khi huỷ", afterCancel.existingBytes >= CANCEL_AFTER_BYTES / 2)

        // Lượt 2: tải tiếp tới hết, đo thời gian và theo dõi file đang tải.
        val start = SystemClock.elapsedRealtime()
        var lastFile = ""
        var firstReportedBytes = -1L
        withTimeout(FULL_TIMEOUT_MS) {
            downloader.downloadMissingArtifacts { progress ->
                if (firstReportedBytes < 0) firstReportedBytes = progress.downloadedBytes
                if (progress.currentFile != lastFile) {
                    lastFile = progress.currentFile
                    Log.i(TAG, "đang tải ${progress.fileIndex}/${progress.totalFiles} ${progress.currentFile} ${progress.downloadedBytes / 1_000_000}/${progress.totalBytes / 1_000_000}MB")
                }
            }
        }
        val seconds = (SystemClock.elapsedRealtime() - start) / 1000f
        val inventory = downloader.inspectInventory()
        Log.i(TAG, "xong: present=${inventory.presentCount}/${inventory.totalCount} ${inventory.existingBytes / 1_000_000}MB trong %.1fs, lượt 2 bắt đầu từ ${firstReportedBytes / 1_000_000}MB".format(seconds))
        assertTrue("Tải tiếp phải bắt đầu từ phần đã có (resume)", firstReportedBytes >= CANCEL_AFTER_BYTES / 2)
        assertTrue(inventory.isReady)
        for (spec in VieNeuTurboModelDownloader.TURBO_ARTIFACTS) {
            assertEquals("Sai kích thước ${spec.fileName}", spec.expectedBytes, File(dir, spec.fileName).length())
        }
        assertTrue("Không được còn file .tmp", dir.listFiles { f -> f.name.endsWith(".tmp") }.isNullOrEmpty())

        // Gói vừa tải phải nạp được engine và tổng hợp được một câu.
        val voice = VieNeuTurboVoice.parsePresets(
            context.assets.open("voices_v3_turbo.json").bufferedReader().use { it.readText() }, isUserVoice = false,
        ).first()
        VieNeuTurboEngine(dir).use { engine ->
            var samples = 0
            engine.synthesize("sˈin tʃˈaː2w.", voice, VieNeuTurboParams(seed = 1L)) { samples += it.size }
            Log.i(TAG, "engine từ gói vừa tải: ${samples} mẫu")
            assertTrue(samples > 0)
        }
        dir.deleteRecursively()
    }

    private companion object {
        const val TAG = "VieNeuTurboDownload"
        const val CANCEL_AFTER_BYTES = 20_000_000L
        const val PHASE_TIMEOUT_MS = 180_000L
        const val FULL_TIMEOUT_MS = 1_500_000L
    }
}
