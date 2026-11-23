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
import com.epubpro.core.designsystem.R
import com.epubpro.domain.model.ImportJobStatus
import com.epubpro.domain.repository.OnlineNovelRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.File

/**
 * Worker chạy ngầm phụ trách tải file EPUB lên Server và thực hiện Polling tiến trình xử lý,
 * đồng thời hiển thị thông báo (Notification) tiến độ thời gian thực cho người dùng ngay cả khi thoát ứng dụng.
 *
 * @property onlineNovelRepository Repository tương tác với API Kho Truyện Online.
 */
@HiltWorker
class EpubImportWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val onlineNovelRepository: OnlineNovelRepository
) : CoroutineWorker(appContext, workerParams) {

    private val notificationManager =
        appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    /**
     * Thực thi quy trình Upload file EPUB và Polling trạng thái ngầm.
     *
     * @return [Result.success] khi hoàn tất nạp truyện 100%, hoặc [Result.failure] khi có lỗi xảy ra.
     */
    override suspend fun doWork(): Result {
        val filePath = inputData.getString(KEY_FILE_PATH) ?: return Result.failure()
        val originalName = inputData.getString(KEY_ORIGINAL_NAME) ?: "book.epub"
        val isTranslated = inputData.getBoolean(KEY_IS_TRANSLATED, true)
        val autoScanCharacters = inputData.getBoolean(KEY_AUTO_SCAN_CHARACTERS, true)
        val novelId = inputData.getString(KEY_NOVEL_ID)
        val contentType = inputData.getString(KEY_CONTENT_TYPE)

        createNotificationChannel()

        var bookTitle = originalName
        showProgressNotification(
            title = bookTitle,
            step = appContext.getString(R.string.epub_import_notification_starting),
            progress = 0
        )

        val file = File(filePath)
        if (!file.exists()) {
            showErrorNotification(
                title = bookTitle,
                error = appContext.getString(R.string.epub_import_notification_failed, "File không tồn tại")
            )
            return Result.failure(workDataOf(KEY_ERROR_MESSAGE to "File không tồn tại: $filePath"))
        }

        try {
            // Bước 1: Gửi file EPUB lên Server
            val uploadResult = onlineNovelRepository.uploadEpub(
                filePath = filePath,
                isTranslated = isTranslated,
                novelId = novelId,
                autoScanCharacters = autoScanCharacters,
                contentType = contentType,
                originalName = originalName
            )

            val initialJob = uploadResult.getOrElse { error ->
                val errorMsg = error.message ?: "Lỗi tải file lên server"
                showErrorNotification(
                    title = bookTitle,
                    error = appContext.getString(R.string.epub_import_notification_failed, errorMsg)
                )
                return Result.failure(workDataOf(KEY_ERROR_MESSAGE to errorMsg))
            }

            bookTitle = initialJob.title ?: bookTitle
            val jobId = initialJob.jobId

            // Cập nhật trạng thái khởi tạo
            updateWorkerProgress(initialJob)
            showProgressNotification(
                title = bookTitle,
                step = initialJob.currentStep ?: appContext.getString(R.string.epub_import_notification_starting),
                progress = initialJob.progressPercentage
            )

            // Bước 2: Vòng lặp Polling hỏi vòng tiến trình từ Server
            var isRunning = true
            var consecutiveNetworkErrors = 0
            while (isRunning) {
                delay(POLL_INTERVAL_MS)

                val statusResult = onlineNovelRepository.getImportJobStatus(jobId)
                statusResult.onSuccess { status ->
                    consecutiveNetworkErrors = 0
                    bookTitle = status.title ?: bookTitle
                    updateWorkerProgress(status)

                    if (status.isCompleted) {
                        isRunning = false
                        showSuccessNotification(title = bookTitle)
                        return Result.success(
                            workDataOf(
                                KEY_STATUS to status.status,
                                KEY_TITLE to bookTitle,
                                KEY_NOVEL_ID to status.novelId,
                                KEY_PROGRESS to 100
                            )
                        )
                    } else if (status.isFailed) {
                        isRunning = false
                        val errorMsg = status.errorMessage ?: "Tiến trình xử lý thất bại"
                        showErrorNotification(
                            title = bookTitle,
                            error = appContext.getString(R.string.epub_import_notification_failed, errorMsg)
                        )
                        return Result.failure(
                            workDataOf(
                                KEY_STATUS to status.status,
                                KEY_ERROR_MESSAGE to errorMsg
                            )
                        )
                    } else {
                        // Cập nhật % và mô tả bước hiện tại lên Notification
                        val stepText = status.currentStep ?: "Đang xử lý..."
                        showProgressNotification(
                            title = bookTitle,
                            step = stepText,
                            progress = status.progressPercentage
                        )
                    }
                }.onFailure { e ->
                    // Kiểm tra lỗi 4xx từ Server (đặc biệt 404: job không tồn tại / đã bị xóa)
                    val fatalErrorMsg = when (e) {
                        is retrofit2.HttpException -> {
                            val code = e.code()
                            val rawBody = runCatching { e.response()?.errorBody()?.string() }.getOrNull()
                            val detail = runCatching {
                                org.json.JSONObject(rawBody ?: "").optString("detail", "")
                            }.getOrNull().takeIf { !it.isNullOrBlank() }

                            if (code == 404) {
                                detail ?: "Không tìm thấy tiến trình upload trên máy chủ (HTTP 404)."
                            } else if (code in 400..499) {
                                detail ?: "Lỗi yêu cầu máy chủ (HTTP $code)."
                            } else {
                                null // 5xx -> tiếp tục thử lại
                            }
                        }
                        else -> null
                    }

                    if (fatalErrorMsg != null) {
                        isRunning = false
                        showErrorNotification(
                            title = bookTitle,
                            error = appContext.getString(R.string.epub_import_notification_failed, fatalErrorMsg)
                        )
                        return Result.failure(
                            workDataOf(
                                KEY_STATUS to "failed",
                                KEY_ERROR_MESSAGE to fatalErrorMsg
                            )
                        )
                    }

                    consecutiveNetworkErrors++
                    if (consecutiveNetworkErrors >= MAX_CONSECUTIVE_NETWORK_ERRORS) {
                        isRunning = false
                        val networkErrorMsg = "Mất kết nối với máy chủ khi theo dõi tiến trình."
                        showErrorNotification(
                            title = bookTitle,
                            error = appContext.getString(R.string.epub_import_notification_failed, networkErrorMsg)
                        )
                        return Result.failure(
                            workDataOf(
                                KEY_STATUS to "failed",
                                KEY_ERROR_MESSAGE to networkErrorMsg
                            )
                        )
                    }
                }
            }

            return Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val errorMsg = e.message ?: "Lỗi không xác định"
            showErrorNotification(
                title = bookTitle,
                error = appContext.getString(R.string.epub_import_notification_failed, errorMsg)
            )
            return Result.failure(workDataOf(KEY_ERROR_MESSAGE to errorMsg))
        } finally {
            file.delete()
        }
    }

    /**
     * Phát dữ liệu tiến độ cho các thành phần UI (ViewModel/Compose) lắng nghe qua WorkManager.
     */
    private suspend fun updateWorkerProgress(status: ImportJobStatus) {
        setProgress(
            workDataOf(
                KEY_STATUS to status.status,
                KEY_TITLE to status.title,
                KEY_NOVEL_ID to status.novelId,
                KEY_PROGRESS to status.progressPercentage,
                KEY_CURRENT_STEP to status.currentStep,
                KEY_ERROR_MESSAGE to status.errorMessage
            )
        )
    }

    /**
     * Tạo Notification Channel cho các thiết bị Android 8.0 (API 26) trở lên.
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                appContext.getString(R.string.epub_import_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = appContext.getString(R.string.epub_import_channel_desc)
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Tạo [ForegroundInfo] để đăng ký dịch vụ chạy ngầm ưu tiên cao với hệ thống Android.
     */
    private fun createForegroundInfo(title: String, step: String, progress: Int): ForegroundInfo {
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setContentTitle(appContext.getString(R.string.epub_import_notification_title, title))
            .setContentText(step)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setProgress(100, progress.coerceIn(0, 100), false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(createLaunchAppPendingIntent())
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    /**
     * Hiển thị hoặc cập nhật Notification tiến độ đang chạy.
     */
    private suspend fun showProgressNotification(title: String, step: String, progress: Int) {
        val foregroundInfo = createForegroundInfo(title, step, progress)
        try {
            setForeground(foregroundInfo)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            notificationManager.notify(NOTIFICATION_ID, foregroundInfo.notification)
        }
    }

    /**
     * Hiển thị Notification khi nạp truyện thành công.
     */
    private fun showSuccessNotification(title: String) {
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setContentTitle(appContext.getString(R.string.epub_import_channel_name))
            .setContentText(appContext.getString(R.string.epub_import_notification_success, title))
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setProgress(0, 0, false)
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(createLaunchAppPendingIntent())
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * Hiển thị Notification khi có lỗi xảy ra.
     */
    private fun showErrorNotification(title: String, error: String) {
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setContentTitle(appContext.getString(R.string.epub_import_notification_title, title))
            .setContentText(error)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setProgress(0, 0, false)
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(createLaunchAppPendingIntent())
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * Tạo PendingIntent khởi chạy lại Activity chính của ứng dụng khi người dùng bấm vào Notification.
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
        const val UNIQUE_WORK_NAME = "epub_import_work"
        const val CHANNEL_ID = "epub_import_channel"
        const val NOTIFICATION_ID = 4001
        // Render Free cần chu kỳ thăm dò thưa hơn để tránh tạo quá nhiều request.
        private const val POLL_INTERVAL_MS = 10_000L
        private const val MAX_CONSECUTIVE_NETWORK_ERRORS = 12

        const val KEY_FILE_PATH = "file_path"
        const val KEY_ORIGINAL_NAME = "original_name"
        const val KEY_IS_TRANSLATED = "is_translated"
        const val KEY_AUTO_SCAN_CHARACTERS = "auto_scan_characters"
        const val KEY_NOVEL_ID = "novel_id"
        const val KEY_CONTENT_TYPE = "content_type"

        const val KEY_PROGRESS = "progress"
        const val KEY_CURRENT_STEP = "current_step"
        const val KEY_STATUS = "status"
        const val KEY_TITLE = "title"
        const val KEY_ERROR_MESSAGE = "error_message"
    }
}
