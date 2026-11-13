package com.epubpro.core.tts

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

@OptIn(ExperimentalCoroutinesApi::class)
class VieNeuDownloadCoordinatorTest {

    private class FakeSupportChecker(
        var supported: Boolean = true,
    ) : VieNeuDeviceSupportChecker() {
        override fun isSupported(): Boolean = supported
    }

    private class FakeModelDownloader(
        private val tempDir: File,
    ) : VieNeuModelDownloader({ tempDir }) {
        var ready: Boolean = false
        var downloadGate: CompletableDeferred<Unit>? = null
        var cleared: Boolean = false

        override fun inspectInventory(): ResourceInventory {
            val total = REQUIRED_ARTIFACTS.size
            val totalBytes = TOTAL_EXPECTED_BYTES
            return if (ready) {
                ResourceInventory(
                    modelDir = tempDir,
                    presentCount = total,
                    totalCount = total,
                    existingBytes = totalBytes,
                    missingBytes = 0L,
                )
            } else {
                ResourceInventory(
                    modelDir = tempDir,
                    presentCount = 0,
                    totalCount = total,
                    existingBytes = 0L,
                    missingBytes = totalBytes,
                )
            }
        }

        override fun clearModelFiles(): Boolean {
            cleared = true
            ready = false
            return true
        }

        override suspend fun downloadMissingArtifacts(onProgress: (DownloadProgress) -> Unit) {
            onProgress(
                DownloadProgress(
                    currentFile = "vector_estimator.onnx",
                    fileIndex = 1,
                    totalFiles = 7,
                    downloadedBytes = 100_000_000L,
                    totalBytes = TOTAL_EXPECTED_BYTES,
                ),
            )
            downloadGate?.await()
            ready = true
        }
    }

    @Test
    fun `unsupported device fails immediately without launching download`() = runTest {
        val tempDir = Files.createTempDirectory("vieneu-coord-unsup").toFile().apply { deleteOnExit() }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val coordinator = VieNeuDownloadCoordinator(
            downloader = FakeModelDownloader(tempDir),
            deviceSupportChecker = FakeSupportChecker(supported = false),
            ioDispatcher = dispatcher,
        )

        val started = coordinator.startDownload()

        assertFalse(started)
        assertTrue(coordinator.state.value is VieNeuDownloadState.Error)
    }

    @Test
    fun `startDownload transitions from Downloading to Ready`() = runTest {
        val tempDir = Files.createTempDirectory("vieneu-coord-ready").toFile().apply { deleteOnExit() }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val downloader = FakeModelDownloader(tempDir).apply {
            downloadGate = CompletableDeferred()
        }
        val coordinator = VieNeuDownloadCoordinator(
            downloader = downloader,
            deviceSupportChecker = FakeSupportChecker(supported = true),
            ioDispatcher = dispatcher,
        )

        assertTrue(coordinator.startDownload())
        assertTrue(coordinator.state.value is VieNeuDownloadState.Downloading)

        downloader.downloadGate?.complete(Unit)
        advanceUntilIdle()

        assertTrue(coordinator.state.value is VieNeuDownloadState.Ready)
        assertEquals(1f, coordinator.state.value.progressFraction, 1e-5f)
    }

    @Test
    fun `cancelDownload reverts state to Idle while keeping model clean`() = runTest {
        val tempDir = Files.createTempDirectory("vieneu-coord-cancel").toFile().apply { deleteOnExit() }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val downloader = FakeModelDownloader(tempDir).apply {
            downloadGate = CompletableDeferred()
        }
        val coordinator = VieNeuDownloadCoordinator(
            downloader = downloader,
            deviceSupportChecker = FakeSupportChecker(supported = true),
            ioDispatcher = dispatcher,
        )

        coordinator.startDownload()
        coordinator.cancelDownload()
        advanceUntilIdle()

        assertTrue(coordinator.state.value is VieNeuDownloadState.Idle)
        assertFalse(downloader.cleared)
    }

    @Test
    fun `clearModel deletes files and resets state to Idle`() = runTest {
        val tempDir = Files.createTempDirectory("vieneu-coord-clear").toFile().apply { deleteOnExit() }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val downloader = FakeModelDownloader(tempDir).apply { ready = true }
        val coordinator = VieNeuDownloadCoordinator(
            downloader = downloader,
            deviceSupportChecker = FakeSupportChecker(supported = true),
            ioDispatcher = dispatcher,
        )

        assertTrue(coordinator.state.value is VieNeuDownloadState.Ready)
        coordinator.clearModel()

        assertTrue(downloader.cleared)
        assertTrue(coordinator.state.value is VieNeuDownloadState.Idle)
    }
}
