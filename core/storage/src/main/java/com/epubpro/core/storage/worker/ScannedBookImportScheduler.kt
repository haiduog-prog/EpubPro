package com.epubpro.core.storage.worker

import android.content.Context
import android.net.Uri
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.epubpro.core.storage.EpubStorageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Số trang quét vượt giới hạn một lần tạo sách.
 *
 * @property maxPages Số trang tối đa cho phép.
 */
class ScannedBookTooManyPagesException(val maxPages: Int) : IllegalArgumentException("Too many scanned pages")

/**
 * Sao lưu ảnh trang sách giấy vào storage nội bộ và lập lịch [ScannedBookImportWorker].
 *
 * URI của trình quét/Photo Picker chỉ được đọc một lần ở bước này; phần OCR nặng chạy trên Worker
 * nên đóng app hoặc process death không làm mất ảnh trang.
 */
@Singleton
class ScannedBookImportScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val storageManager: EpubStorageManager
) {

    /**
     * Sao chép ảnh các trang theo thứ tự và enqueue tác vụ OCR → EPUB.
     *
     * @param pageUris URI ảnh các trang, theo đúng thứ tự trang sách.
     * @param title Tên sách người dùng nhập.
     * @param author Tác giả, rỗng nếu không rõ.
     * @return ID tác vụ WorkManager.
     * @throws IllegalArgumentException Nếu danh sách trang rỗng.
     * @throws ScannedBookTooManyPagesException Nếu vượt [MAX_PAGES] trang.
     * @throws IOException Nếu không đọc được ảnh hoặc ảnh vượt [MAX_PAGE_BYTES].
     */
    suspend fun enqueue(pageUris: List<Uri>, title: String, author: String): UUID = withContext(Dispatchers.IO) {
        require(pageUris.isNotEmpty()) { "Chưa có trang nào" }
        if (pageUris.size > MAX_PAGES) throw ScannedBookTooManyPagesException(MAX_PAGES)
        val jobId = UUID.randomUUID()
        val pagesDir = File(File(context.filesDir, PAGES_ROOT_DIR), jobId.toString())
        val outputFile = storageManager.createConvertedEpubFile()
        try {
            if (!pagesDir.mkdirs()) throw IOException("Không tạo được thư mục ảnh trang")
            pageUris.forEachIndexed { index, uri ->
                ensureActive()
                val target = File(pagesDir, "${ScannedBookImportWorker.PAGE_FILE_PREFIX}%05d.jpg".format(Locale.US, index + 1))
                val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Không mở được ảnh trang")
                input.use { source -> target.outputStream().use { copyLimited(source, it) } }
            }
            val request = OneTimeWorkRequestBuilder<ScannedBookImportWorker>()
                .setInputData(
                    workDataOf(
                        ScannedBookImportWorker.KEY_PAGES_DIR to pagesDir.absolutePath,
                        ScannedBookImportWorker.KEY_OUTPUT_PATH to outputFile.absolutePath,
                        ScannedBookImportWorker.KEY_BOOK_TITLE to title.trim(),
                        ScannedBookImportWorker.KEY_BOOK_AUTHOR to author.trim()
                    )
                )
                // Lần đầu dùng OCR, Play services có thể cần vài phút để tải model.
                .setBackoffCriteria(BackoffPolicy.LINEAR, RETRY_BACKOFF_SECONDS, TimeUnit.SECONDS)
                .addTag(LocalBookImportWorker.TAG)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "${UNIQUE_WORK_NAME}_$jobId",
                ExistingWorkPolicy.KEEP,
                request
            ).result.get()
            request.id
        } catch (error: Throwable) {
            pagesDir.deleteRecursively()
            outputFile.delete()
            throw error
        }
    }

    /**
     * Sao chép luồng với giới hạn dung lượng để một ảnh bất thường không làm đầy bộ nhớ máy.
     *
     * @param source Luồng ảnh nguồn.
     * @param target Luồng file đích.
     * @throws IOException Nếu ảnh vượt [MAX_PAGE_BYTES].
     */
    private fun copyLimited(source: InputStream, target: OutputStream) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = source.read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_PAGE_BYTES) throw IOException("Ảnh trang quá lớn")
            target.write(buffer, 0, read)
        }
    }

    companion object {
        const val MAX_PAGES = 1000
        private const val MAX_PAGE_BYTES = 40L * 1024 * 1024
        private const val PAGES_ROOT_DIR = "scanned_pages"
        private const val UNIQUE_WORK_NAME = "scanned_book_import_work"
        private const val RETRY_BACKOFF_SECONDS = 60L
    }
}
