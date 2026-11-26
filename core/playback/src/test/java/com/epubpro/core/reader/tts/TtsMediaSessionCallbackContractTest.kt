package com.epubpro.core.reader.tts

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Chặn lại lỗi callback MediaSession tự gọi chính nó (`override fun onPause() { onPause() }`), vốn gây
 * `StackOverflowError` khi bấm phím media; `MediaSession` không chạy được trong JVM test nên kiểm ở
 * mức mã nguồn.
 */
class TtsMediaSessionCallbackContractTest {
    private val source: String by lazy { findSource().readText() }

    /** Mỗi callback trùng tên với tham số constructor phải gọi qua `this@TtsMediaSessionManager`. */
    @Test
    fun `media session callbacks delegate to constructor lambdas instead of recursing`() {
        listOf("onPlay", "onPause", "onStop").forEach { name ->
            assertFalse(
                "$name() must not call itself",
                source.contains("override fun $name() { $name() }")
            )
            assertTrue(
                "$name() must delegate to the manager's lambda",
                source.contains("override fun $name() { this@TtsMediaSessionManager.$name() }")
            )
        }
    }

    /**
     * Tìm file nguồn của [TtsMediaSessionManager] từ thư mục làm việc của Gradle.
     *
     * @return File nguồn.
     */
    private fun findSource(): File {
        val relativePath = "src/main/java/com/epubpro/core/reader/tts/TtsMediaSessionManager.kt"
        val workingDirectory = requireNotNull(System.getProperty("user.dir"))
        return generateSequence(File(workingDirectory)) { it.parentFile }
            .flatMap { root -> sequenceOf(File(root, relativePath), File(root, "core/playback/$relativePath")) }
            .firstOrNull(File::isFile)
            ?: error("Unable to locate TtsMediaSessionManager.kt from $workingDirectory")
    }
}
