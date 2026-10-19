package com.epubpro.core.storage.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.epubpro.core.bookconverter.BookConversionErrorCode
import com.epubpro.core.bookconverter.BookConversionException
import com.epubpro.core.bookconverter.BookConversionResult
import com.epubpro.core.bookconverter.BookConversionStage
import com.epubpro.core.bookconverter.MobiEpubConverter
import com.epubpro.core.designsystem.R
import com.epubpro.core.reader.engine.EpubEngine
import com.epubpro.domain.repository.BookRepository
import com.epubpro.domain.repository.SearchRepository
import com.epubpro.domain.model.BookSourceFormat
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Worker chuyển đổi file PRC/MOBI/AZW3 thành EPUB và nạp vào thư viện.
 *
 * File nguồn và file đích đều nằm trong app-private storage nên WorkManager có thể tiếp tục
 * sau khi process bị kill; bản ghi sách chỉ được ghi sau khi EPUB đã commit hoàn chỉnh.
 */
@HiltWorker
class LocalBookImportWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val converter: MobiEpubConverter,
    private val epubEngine: EpubEngine,
    private val bookRepository: BookRepository,
    private val searchRepository: SearchRepository,
    private val replacement: LocalBookReplacement
) : CoroutineWorker(appContext, workerParams) {

    private val notificationManager =
        appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    /**
     * Thực thi chuyển đổi, kiểm tra EPUB, lưu metadata và lập chỉ mục tìm kiếm.
     *
     * @return Thành công, thất bại xác định hoặc retry khi lỗi I/O tạm thời.
     */
    override suspend fun doWork(): Result {
        val sourcePath = inputData.getString(KEY_SOURCE_PATH) ?: return Result.failure()
        val outputPath = inputData.getString(KEY_OUTPUT_PATH) ?: return Result.failure()
        val originalName = inputData.getString(KEY_ORIGINAL_NAME) ?: File(sourcePath).name
        val sourceFile = File(sourcePath)
        val outputFile = File(outputPath)
        val scheduledSourceFormat = inputData.getString(KEY_SOURCE_FORMAT)
            ?.let { runCatching { BookSourceFormat.valueOf(it) }.getOrNull() }
        if (!sourceFile.isFile && !outputFile.isFile) {
            return failure(BookConversionErrorCode.INVALID_OR_CORRUPTED_FILE)
        }

        createNotificationChannel()
        updateProgress(BookConversionStage.VALIDATING, 0, originalName)
        return try {
            val targetBookId = inputData.getString(KEY_REPLACEMENT_BOOK_ID) ?: outputFile.name
            val committed = bookRepository.getBookById(targetBookId)
            if (committed != null && committed.filePath == outputFile.absolutePath) {
                sourceFile.delete()
                return Result.success(workDataOf(KEY_TITLE to committed.title, KEY_BOOK_ID to committed.id, KEY_PROGRESS to 100))
            }
            var lastProgress = -1
            val conversion = when {
                sourceFile.isFile && scheduledSourceFormat == BookSourceFormat.EPUB -> {
                    copyExistingEpub(sourceFile, outputFile)
                }
                sourceFile.isFile -> {
                    converter.convert(sourceFile, outputFile) { progress ->
                        if (progress.progress != lastProgress) {
                            lastProgress = progress.progress
                            updateProgress(progress.stage, progress.progress, originalName)
                        }
                    }
                }
                else -> {
                    // Process death có thể xảy ra sau khi source đã xóa nhưng trước khi DB commit.
                    BookConversionResult(outputFile, scheduledSourceFormat ?: BookSourceFormat.EPUB)
                }
            }
            val parsedBook = epubEngine.parseEpubMetadataStrict(conversion.epubFile)
                .copy(sourceFormat = conversion.sourceFormat)
            updateProgress(BookConversionStage.PACKAGING, 92, parsedBook.title)
            val replacementBookId = inputData.getString(KEY_REPLACEMENT_BOOK_ID)
            if (replacementBookId != null) {
                replacement.replace(parsedBook, replacementBookId,
                    inputData.getString(KEY_PUBLICATION_IDENTIFIER).orEmpty())
            } else {
                bookRepository.insertBook(parsedBook)
                epubEngine.indexBookContent(conversion.epubFile, parsedBook.id, searchRepository)
            }
            updateProgress(BookConversionStage.PACKAGING, 95, parsedBook.title)
            updateProgress(BookConversionStage.COMPLETED, 100, parsedBook.title)
            showSuccessNotification(parsedBook.title)
            sourceFile.delete()
            Result.success(
                workDataOf(
                    KEY_TITLE to parsedBook.title,
                    KEY_BOOK_ID to (replacementBookId ?: parsedBook.id),
                    KEY_SOURCE_FORMAT to conversion.sourceFormat.name,
                    KEY_PROGRESS to 100
                )
            )
        } catch (error: CancellationException) {
            // Hủy chủ động không cần giữ lại source; process death không đi qua nhánh này.
            sourceFile.delete()
            deleteUncommittedOutput(outputFile)
            throw error
        } catch (error: BookConversionException) {
            showErrorNotification(originalName, errorMessage(error.code))
            sourceFile.delete()
            deleteUncommittedOutput(outputFile)
            failure(error.code)
        } catch (error: IOException) {
            if (runAttemptCount < MAX_RETRY_COUNT) {
                Result.retry()
            } else {
                showErrorNotification(originalName, error.message ?: errorMessage(BookConversionErrorCode.OUTPUT_FAILED))
                sourceFile.delete()
                deleteUncommittedOutput(outputFile)
                failure(BookConversionErrorCode.OUTPUT_FAILED)
            }
        } catch (error: Exception) {
            showErrorNotification(originalName, error.message ?: errorMessage(BookConversionErrorCode.INVALID_OR_CORRUPTED_FILE))
            sourceFile.delete()
            deleteUncommittedOutput(outputFile)
            failure(BookConversionErrorCode.INVALID_OR_CORRUPTED_FILE)
        }
    }

    /**
     * Chỉ dọn output chưa được commit, kể cả khi cancellation đến ngay sau transaction.
     *
     * @param outputFile File đích của tác vụ.
     */
    private suspend fun deleteUncommittedOutput(outputFile: File) = withContext(NonCancellable) {
        val targetId = inputData.getString(KEY_REPLACEMENT_BOOK_ID) ?: outputFile.name
        if (bookRepository.getBookById(targetId)?.filePath != outputFile.absolutePath) {
            searchRepository.clearIndexForBook(outputFile.name)
            outputFile.delete()
        }
        Unit
    }

    /**
     * Sao chép EPUB đã hợp lệ sang file đích để EPUB cũ dùng chung commit/indexing với converter.
     *
     * @param sourceFile EPUB nguồn đã được sao lưu nội bộ.
     * @param outputFile File đích của tác vụ import.
     * @return Kết quả chuyển đổi logic với định dạng nguồn EPUB.
     */
    private fun copyExistingEpub(sourceFile: File, outputFile: File): BookConversionResult {
        sourceFile.inputStream().use { input ->
            outputFile.outputStream().use { output ->
                input.copyTo(output)
                output.fd.sync()
            }
        }
        return BookConversionResult(outputFile, BookSourceFormat.EPUB)
    }

    /**
     * Ghi tiến độ WorkManager và cập nhật foreground notification.
     *
     * @param stage Giai đoạn converter đang thực hiện.
     * @param progress Phần trăm hoàn thành.
     * @param title Tên file hoặc tiêu đề sách hiển thị.
     */
    private suspend fun updateProgress(stage: BookConversionStage, progress: Int, title: String) {
        val step = when (stage) {
            BookConversionStage.VALIDATING -> appContext.getString(R.string.book_conversion_notification_starting)
            BookConversionStage.DECODING -> appContext.getString(R.string.book_conversion_notification_decoding, progress)
            BookConversionStage.PACKAGING, BookConversionStage.VALIDATING_EPUB -> appContext.getString(R.string.book_conversion_notification_packaging, progress)
            BookConversionStage.COMPLETED -> appContext.getString(R.string.book_conversion_notification_success, title)
        }
        setProgress(
            workDataOf(
                KEY_PROGRESS to progress,
                KEY_STAGE to stage.name,
                KEY_TITLE to title,
                KEY_CURRENT_STEP to step
            )
        )
        showProgressNotification(title, step, progress)
    }

    /**
     * Tạo kết quả thất bại chứa mã lỗi để UI/diagnostic đọc được.
     *
     * @param code Mã lỗi converter.
     * @return Kết quả WorkManager ở trạng thái thất bại.
     */
    private fun failure(code: BookConversionErrorCode): Result = Result.failure(
        workDataOf(
            KEY_ERROR_CODE to code.name,
            KEY_ERROR_MESSAGE to errorMessage(code)
        )
    )

    /**
     * Lấy thông báo bản địa hóa cho mã lỗi converter.
     *
     * @param code Mã lỗi cần hiển thị.
     * @return Chuỗi lỗi từ resources.
     */
    private fun errorMessage(code: BookConversionErrorCode): String = when (code) {
        BookConversionErrorCode.FILE_TOO_LARGE -> appContext.getString(R.string.book_conversion_error_too_large)
        BookConversionErrorCode.ENCRYPTED_OR_DRM -> appContext.getString(R.string.book_conversion_error_drm)
        BookConversionErrorCode.FIXED_LAYOUT_UNSUPPORTED -> appContext.getString(R.string.book_conversion_error_fixed_layout)
        BookConversionErrorCode.OUTPUT_FAILED -> appContext.getString(R.string.book_conversion_error_output)
        BookConversionErrorCode.UNSUPPORTED_EXTENSION,
        BookConversionErrorCode.INVALID_OR_CORRUPTED_FILE -> appContext.getString(R.string.book_conversion_error_invalid)
    }

    /** Tạo notification channel dành riêng cho chuyển đổi sách. */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    appContext.getString(R.string.book_conversion_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = appContext.getString(R.string.book_conversion_channel_desc)
                    setShowBadge(false)
                }
            )
        }
    }

    /**
     * Đăng ký notification foreground cho tác vụ chuyển đổi dài.
     *
     * @param title Tên sách/file.
     * @param step Mô tả bước hiện tại.
     * @param progress Phần trăm hoàn thành.
     */
    private suspend fun showProgressNotification(title: String, step: String, progress: Int) {
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setContentTitle(appContext.getString(R.string.book_conversion_notification_title, title))
            .setContentText(step)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, progress.coerceIn(0, 100), false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(createLaunchAppPendingIntent())
            .build()
        val foreground = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
        setForeground(foreground)
    }

    /** Hiển thị notification khi EPUB đã được thêm vào thư viện. */
    private fun showSuccessNotification(title: String) {
        notificationManager.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(appContext, CHANNEL_ID)
                .setContentTitle(appContext.getString(R.string.book_conversion_channel_name))
                .setContentText(appContext.getString(R.string.book_conversion_notification_success, title))
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setAutoCancel(true)
                .setContentIntent(createLaunchAppPendingIntent())
                .build()
        )
    }

    /**
     * Hiển thị notification lỗi sau khi tác vụ kết thúc thất bại.
     *
     * @param title Tên sách/file.
     * @param message Thông báo lỗi đã bản địa hóa.
     */
    private fun showErrorNotification(title: String, message: String) {
        notificationManager.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(appContext, CHANNEL_ID)
                .setContentTitle(appContext.getString(R.string.book_conversion_notification_title, title))
                .setContentText(message)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setAutoCancel(true)
                .setContentIntent(createLaunchAppPendingIntent())
                .build()
        )
    }

    /**
     * Tạo PendingIntent mở lại Activity chính khi người dùng chạm notification.
     *
     * @return PendingIntent bất biến cho package hiện tại.
     */
    private fun createLaunchAppPendingIntent(): PendingIntent {
        val launchIntent = appContext.packageManager.getLaunchIntentForPackage(appContext.packageName)
            ?: Intent()
        launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getActivity(appContext, 0, launchIntent, flags)
    }

    companion object {
        const val UNIQUE_WORK_NAME = "local_book_import_work"
        const val TAG = "local_book_import"
        const val KEY_REPLACEMENT_BOOK_ID = "replacement_book_id"
        const val KEY_PUBLICATION_IDENTIFIER = "publication_identifier"
        const val KEY_SOURCE_PATH = "source_path"
        const val KEY_OUTPUT_PATH = "output_path"
        const val KEY_ORIGINAL_NAME = "original_name"
        const val KEY_PROGRESS = "progress"
        const val KEY_STAGE = "stage"
        const val KEY_TITLE = "title"
        const val KEY_BOOK_ID = "book_id"
        const val KEY_CURRENT_STEP = "current_step"
        const val KEY_SOURCE_FORMAT = "source_format"
        const val KEY_ERROR_CODE = "error_code"
        const val KEY_ERROR_MESSAGE = "error_message"
        const val CHANNEL_ID = "book_conversion_channel"
        const val NOTIFICATION_ID = 4002
        private const val MAX_RETRY_COUNT = 2
    }
}
