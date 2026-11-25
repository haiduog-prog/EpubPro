package com.epubpro.feature.reader.webview

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Intentional source-level guard for a platform rendering contract that cannot be
 * reproduced by a local JVM test. See docs/reader-chapter-transition-snapshot-design.md.
 */
class ReaderChapterTransitionContractTest {

    private val source: String by lazy {
        findReaderSource().readText()
    }

    @Test
    fun transitionFrameUsesCompositedPixelCopyInsteadOfSoftwareWebViewCapture() {
        assertTrue(source.contains("PixelCopy.request("))
        assertFalse(source.contains("import android.graphics.Canvas"))
        assertFalse(source.contains("draw(Canvas("))
        assertFalse(source.contains("capturePicture("))
    }

    @Test
    fun destinationCoverClearsOnlyAfterCommittedVisualFrame() {
        val layoutReadyFlow = source
            .substringAfter("onReaderLayoutReadyListener =")
            .substringBefore("LaunchedEffect(transitionCover?.token)")

        assertAppearsInOrder(
            layoutReadyFlow,
            "postVisualStateCallback(",
            "postOnAnimation {",
            "completeIfExpected(loadGeneration)",
            "transitionCover = null"
        )
    }

    @Test
    fun chapterReloadStartsOnlyAfterCoverGetsAComposeFrame() {
        val transitionEffect = source
            .substringAfter("LaunchedEffect(transitionCover?.token)")
            .substringBefore("LaunchedEffect(activeTtsParagraphIndex)")

        assertAppearsInOrder(
            transitionEffect,
            "withFrameNanos { }",
            "currentOnNextChapter()"
        )
        assertAppearsInOrder(
            transitionEffect,
            "withFrameNanos { }",
            "currentOnPreviousChapter()"
        )
    }

    /**
     * Kiểm tra khóa nhận diện HTML của WebView gắn kèm `chapterReloadToken` ở cả hai đầu (commit trong DOM
     * và nạp ngoài), để bấm Next rồi Previous dưới màn che hoặc chọn lại chương hiện tại từ Mục lục vẫn
     * nạp lại document và gỡ màn che đúng hạn, đồng thời gộp yêu cầu dưới màn che có gia hạn timeout.
     */
    @Test
    fun reloadKeyCarriesReloadTokenAndCoalescingExtendsTimeout() {
        assertTrue(source.contains("""_${'$'}currentChapterReloadToken""""))
        assertTrue(source.contains("""_${'$'}chapterReloadToken""""))
        assertTrue(source.contains("coalesceRevision += 1"))
        assertTrue(source.contains("coalesceRevision != observedRevision"))
    }

    private fun assertAppearsInOrder(text: String, vararg markers: String) {
        var previousIndex = -1
        markers.forEach { marker ->
            val index = text.indexOf(marker)
            assertTrue("Missing transition contract marker: $marker", index >= 0)
            assertTrue("Transition contract marker is out of order: $marker", index > previousIndex)
            previousIndex = index
        }
    }

    private fun findReaderSource(): File {
        val relativePath =
            "src/main/java/com/epubpro/feature/reader/webview/EpubProWebView.kt"
        val workingDirectory = requireNotNull(System.getProperty("user.dir"))
        val roots = generateSequence(File(workingDirectory)) { it.parentFile }
        return roots
            .flatMap { root ->
                sequenceOf(
                    File(root, relativePath),
                    File(root, "feature/reader/$relativePath")
                )
            }
            .firstOrNull(File::isFile)
            ?: error("Unable to locate EpubProWebView.kt from $workingDirectory")
    }
}
