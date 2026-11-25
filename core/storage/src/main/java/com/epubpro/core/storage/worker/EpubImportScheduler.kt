package com.epubpro.core.storage.worker

import android.content.Context
import android.net.Uri
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.epubpro.core.storage.EpubStorageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Điều phối việc sao lưu EPUB và lập lịch import lên server bằng [EpubImportWorker].
 *
 * @property context Context ứng dụng dùng để truy cập [WorkManager].
 * @property storageManager Thành phần sao lưu URI vào bộ nhớ nội bộ.
 */
@Singleton
class EpubImportScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val storageManager: EpubStorageManager
) {
    private val enqueueMutex = Mutex()

    /**
     * Sao lưu file EPUB ở luồng I/O và lập lịch một tác vụ upload duy nhất.
     *
     * @param uri URI file được chọn từ Storage Access Framework.
     * @param originalName Tên file gốc dùng để hiển thị và truyền vào worker.
     * @param isTranslated Đánh dấu EPUB đã được dịch hay chưa.
     * @param autoScanCharacters Cho phép server tự động quét nhân vật.
     * @param novelId ID truyện hiện có nếu upload bổ sung, hoặc null nếu tạo truyện mới.
     * @param contentType MIME type thực tế của file, có thể null nếu provider không cung cấp.
     * @return true nếu đã enqueue, false nếu đang có upload khác chạy.
     * @throws com.epubpro.core.storage.BookFileTooLargeException Nếu file vượt giới hạn upload.
     * @throws Exception Nếu không đọc được URI hoặc không lập lịch được công việc.
     */
    suspend fun enqueue(
        uri: Uri,
        originalName: String?,
        isTranslated: Boolean,
        autoScanCharacters: Boolean,
        novelId: String? = null,
        contentType: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        enqueueMutex.withLock {
            val workManager = WorkManager.getInstance(context)
            val hasActiveWork = workManager
                .getWorkInfosForUniqueWork(EpubImportWorker.UNIQUE_WORK_NAME)
                .get()
                .any { !it.state.isFinished }

            if (hasActiveWork) return@withLock false

            // Không còn upload nào chạy hoặc chờ chạy lại: dọn file sót lại của lần upload đã bị hủy
            // (worker giữ file khi bị hệ thống tạm dừng để lần chạy lại vẫn dùng được).
            storageManager.clearUploadStaging()
            val tempFile = storageManager.stageEpubForUpload(uri, originalName)
            try {
                val request = OneTimeWorkRequestBuilder<EpubImportWorker>()
                    .setInputData(
                        workDataOf(
                            EpubImportWorker.KEY_FILE_PATH to tempFile.absolutePath,
                            EpubImportWorker.KEY_ORIGINAL_NAME to (originalName ?: "book.epub"),
                            EpubImportWorker.KEY_IS_TRANSLATED to isTranslated,
                            EpubImportWorker.KEY_AUTO_SCAN_CHARACTERS to autoScanCharacters,
                            EpubImportWorker.KEY_NOVEL_ID to novelId,
                            EpubImportWorker.KEY_CONTENT_TYPE to contentType
                        )
                    )
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build()
                    )
                        .build()

                workManager.enqueueUniqueWork(
                    EpubImportWorker.UNIQUE_WORK_NAME,
                    ExistingWorkPolicy.KEEP,
                    request
                )
                true
            } catch (e: Exception) {
                tempFile.delete()
                throw e
            }
        }
    }
}
