package com.epubpro.core.reader.tts

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source-level guard for Android TextToSpeech initialization, which cannot be exercised by a
 * local JVM test without replacing the platform TextToSpeech implementation.
 */
class TtsServiceNativeEngineContractTest {
    private val source: String by lazy { findSource().readText() }

    @Test
    fun `service initializes native engine during onCreate`() {
        val onCreate = source
            .substringAfter("override fun onCreate()")
            .substringBefore("override fun onStartCommand")

        assertAppearsInOrder(
            onCreate,
            "nativeTtsEngine = AndroidNativeTtsEngine(applicationContext)",
            "applySettingsToEngines(activeSettings)",
            "nativeTtsEngine.initialize("
        )
    }

    @Test
    fun `manual resume clears pausedBySystem without blocking playback`() {
        val resumeBody = source
            .substringAfter("fun resume()")
            .substringBefore("private fun resumeChapterTransition")

        org.junit.Assert.assertFalse(
            "resume() must not short-circuit on pausedBySystem",
            resumeBody.contains("if (pausedBySystem) return")
        )
        assertTrue(resumeBody.contains("pausedBySystem = false"))
    }

    @Test
    fun `reading widget foreground promotion provides fallback service type`() {
        val moveBody = source
            .substringAfter("private fun handleReadingWidgetMove")
            .substringBefore("private suspend fun processReadingWidgetMoves")

        assertTrue(moveBody.contains("requireFallbackServiceType = true"))
        assertTrue(moveBody.contains("stopSelfAfterForegroundStart(startId)"))
    }

    @Test
    fun `chapter navigation never awaits a cancelled chapter preparation`() {
        listOf("advanceToNextChapter", "advanceToPreviousChapter").forEach { name ->
            val body = source
                .substringAfter("private suspend fun $name(")
                .substringBefore("private ")
            org.junit.Assert.assertFalse(
                "$name must not await chapterPreparation directly",
                body.contains("chapterPreparation?.await()")
            )
            assertTrue("$name must use awaitChapterPreparation()", body.contains("awaitChapterPreparation()"))
        }
        val helper = source
            .substringAfter("private suspend fun awaitChapterPreparation()")
            .substringBefore("private fun cancelChapterPreparation()")
        assertTrue(helper.contains("!pending.isCancelled"))
        assertTrue(helper.contains("chapterPlaybackCoordinator.prepare(bookId, preferAiContent)"))
    }

    @Test
    fun `session teardown drops the chapter preparation reference`() {
        listOf(
            "private fun stopSession()" to "private fun moveToChunk",
            "private fun finishPlayback()" to "private fun showPlaybackError",
            "private fun cancelSnapshotRestoreToIdle()" to "private fun pauseFromSystem"
        ).forEach { (start, end) ->
            val body = source.substringAfter(start).substringBefore(end)
            assertTrue("$start must call cancelChapterPreparation()", body.contains("cancelChapterPreparation()"))
        }
    }

    @Test
    fun `widget commands satisfy the foreground start contract before dispatch`() {
        listOf("handleWidgetPlayPause", "handleWidgetPrevious", "handleWidgetNext").forEach { name ->
            val body = source
                .substringAfter("private fun $name(")
                .substringBefore("private fun ")
            assertAppearsInOrder(body, "stopSelfAfterForegroundStart(startId)", "ensureForegroundForWidgetStart()")
        }
        val detachPolicy = source
            .substringAfter("private fun shouldDetachPausedNotification()")
            .substringBefore("private fun ")
        assertTrue(
            "System pauses must stay foreground so auto-resume can start playback from background",
            detachPolicy.contains("!pausedBySystem")
        )
    }

    private fun assertAppearsInOrder(text: String, vararg markers: String) {
        var previousIndex = -1
        markers.forEach { marker ->
            val index = text.indexOf(marker)
            assertTrue("Missing native TTS lifecycle marker: $marker", index >= 0)
            assertTrue("Native TTS lifecycle marker is out of order: $marker", index > previousIndex)
            previousIndex = index
        }
    }

    private fun findSource(): File {
        val relativePath = "src/main/java/com/epubpro/core/reader/tts/TtsService.kt"
        val workingDirectory = requireNotNull(System.getProperty("user.dir"))
        return generateSequence(File(workingDirectory)) { it.parentFile }
            .flatMap { root ->
                sequenceOf(
                    File(root, relativePath),
                    File(root, "core/reader/$relativePath")
                )
            }
            .firstOrNull(File::isFile)
            ?: error("Unable to locate TtsService.kt from $workingDirectory")
    }
}
