package com.epubpro.core.storage

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.epubpro.core.storage.worker.shouldDiscardFilesOnStop
import androidx.work.WorkInfo
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Kiểm tra sao file EPUB chờ upload: giới hạn dung lượng, dọn file dở và dọn thư mục chờ.
 */
class EpubUploadStagingTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    /**
     * Tạo storage manager có content resolver trả về [content] cho mọi URI.
     *
     * @param content Nội dung file nguồn giả lập.
     * @return Storage manager dùng thư mục tạm.
     */
    private fun createManager(content: ByteArray = "epub".toByteArray()): EpubStorageManager {
        val resolver = mock<ContentResolver>()
        whenever(resolver.openInputStream(any())).thenAnswer { ByteArrayInputStream(content) }
        val context = mock<Context>()
        whenever(context.filesDir).thenReturn(temporaryFolder.root)
        whenever(context.contentResolver).thenReturn(resolver)
        return EpubStorageManager(context)
    }

    private fun stagingDir() = File(temporaryFolder.root, "epub_upload_staging")

    /** File hợp lệ được sao vào thư mục chờ upload riêng, giữ phần mở rộng an toàn. */
    @Test
    fun stageEpubForUploadCopiesIntoStagingDirectory() {
        val manager = createManager("hello".toByteArray())

        val staged = manager.stageEpubForUpload(mock<Uri>(), "truyen.epub")

        assertEquals(stagingDir().canonicalPath, staged.parentFile!!.canonicalPath)
        assertTrue(staged.name.endsWith(".epub"))
        assertEquals("hello", staged.readText())
    }

    /** Vượt giới hạn thì ném lỗi rõ ràng và không để lại byte nào ở file đích. */
    @Test
    fun copyWithLimitStopsWhenExceedingLimit() {
        val manager = createManager()
        val target = temporaryFolder.newFile("out.bin")

        try {
            FileOutputStream(target).use { output ->
                manager.copyWithLimit(ByteArrayInputStream(ByteArray(64)), output, maxBytes = 10)
            }
            fail("Phải ném BookFileTooLargeException khi vượt giới hạn")
        } catch (error: BookFileTooLargeException) {
            assertEquals(10L, error.limitBytes)
        }
        assertTrue(target.length() <= 10)
    }

    /** Không mở được URI thì không để lại file rỗng trong thư mục chờ. */
    @Test
    fun stageEpubForUploadDeletesPartialFileOnFailure() {
        val resolver = mock<ContentResolver>()
        whenever(resolver.openInputStream(any())).thenReturn(null)
        val context = mock<Context>()
        whenever(context.filesDir).thenReturn(temporaryFolder.root)
        whenever(context.contentResolver).thenReturn(resolver)
        val manager = EpubStorageManager(context)

        try {
            manager.stageEpubForUpload(mock<Uri>(), "a.epub")
            fail("Phải ném lỗi khi không mở được URI")
        } catch (_: IllegalStateException) {
        }
        assertTrue(stagingDir().listFiles().orEmpty().isEmpty())
    }

    /** Dọn thư mục chờ xóa cả file EPUB sót lại lẫn file đánh dấu jobId. */
    @Test
    fun clearUploadStagingRemovesLeftovers() {
        val manager = createManager()
        val staged = manager.stageEpubForUpload(mock<Uri>(), "a.epub")
        File(staged.path + ".job").writeText("job-1")

        manager.clearUploadStaging()

        assertFalse(staged.exists())
        assertTrue(stagingDir().listFiles().orEmpty().isEmpty())
    }

    /** Chỉ dọn file khi tác vụ bị ứng dụng hủy; hệ thống tạm dừng hoặc không rõ lý do thì giữ lại để chạy tiếp. */
    @Test
    fun localImportDiscardsFilesOnlyWhenCancelledByApp() {
        assertTrue(shouldDiscardFilesOnStop(WorkInfo.STOP_REASON_CANCELLED_BY_APP))
        assertFalse(shouldDiscardFilesOnStop(WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY))
        assertFalse(shouldDiscardFilesOnStop(WorkInfo.STOP_REASON_QUOTA))
        assertFalse(shouldDiscardFilesOnStop(WorkInfo.STOP_REASON_UNKNOWN))
    }
}
