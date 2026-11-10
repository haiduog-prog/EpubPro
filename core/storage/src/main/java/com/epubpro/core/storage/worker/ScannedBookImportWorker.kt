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
import com.epubpro.core.comic.ComicPageDecoder
import com.epubpro.core.designsystem.R
import com.epubpro.core.ocr.BookPageTextRecognizer
import com.epubpro.core.ocr.OcrModelUnavailableException
import com.epubpro.core.ocr.OcrTextBlock
import com.epubpro.core.ocr.ScannedPageTextAssembler
import com.epubpro.core.ocr.VietOcrEngine
import com.epubpro.core.ocr.VietOcrSession
import com.epubpro.core.reader.engine.EpubEngine
import com.epubpro.core.storage.EpubStorageManager
import com.epubpro.core.webnovel.EpubMetadata
import com.epubpro.core.webnovel.EpubWriter
import com.epubpro.core.webnovel.WebNovelChapter
import com.epubpro.domain.repository.BookRepository
import com.epubpro.domain.repository.SearchRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Worker nhận diện chữ (OCR) trên ảnh các trang sách giấy đã quét, dựng lại đoạn văn/chương
 * và ghi thành EPUB trong thư viện để đọc, tìm kiếm và nghe TTS như sách thường.
 *
 * Ảnh trang nằm trong app-private storage nên WorkManager chạy tiếp được sau process death;
 * tiến độ dùng chung tag/khóa với [LocalBookImportWorker] để thư viện hiển thị cùng widget import.
 */
@HiltWorker
class ScannedBookImportWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val recognizer: BookPageTextRecognizer,
    private val vietOcrEngine: VietOcrEngine,
    private val epubEngine: EpubEngine,
    private val bookRepository: BookRepository,
    private val searchRepository: SearchRepository,
    private val storageManager: EpubStorageManager
) : CoroutineWorker(appContext, workerParams) {

    private val notificationManager =
        appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    /**
     * OCR từng trang, ghép văn bản, ghi EPUB, lưu sách và lập chỉ mục tìm kiếm.
     *
     * @return Thành công kèm ID sách; retry khi model OCR chưa sẵn sàng hoặc lỗi I/O tạm thời;
     * thất bại khi không nhận dạng được chữ nào.
     */
    override suspend fun doWork(): Result {
        val pagesDir = inputData.getString(KEY_PAGES_DIR)?.let(::File) ?: return Result.failure()
        val outputFile = inputData.getString(KEY_OUTPUT_PATH)?.let(::File) ?: return Result.failure()
        val title = inputData.getString(KEY_BOOK_TITLE)?.trim().orEmpty().ifEmpty { outputFile.nameWithoutExtension }
        val author = inputData.getString(KEY_BOOK_AUTHOR)?.trim().orEmpty()

        val committed = bookRepository.getBookById(outputFile.name)
        if (committed != null && committed.filePath == outputFile.absolutePath) {
            pagesDir.deleteRecursively()
            return success(committed.id, committed.title)
        }
        val pages = pagesDir.listFiles { file -> file.isFile && file.name.startsWith(PAGE_FILE_PREFIX) }
            ?.sortedBy { it.name }
            .orEmpty()
        if (pages.isEmpty()) return failure(title, appContext.getString(R.string.scan_book_error_no_text), pagesDir, outputFile)

        createNotificationChannel()
        updateProgress(title, appContext.getString(R.string.book_conversion_notification_starting), 0)
        return try {
            // Một phiên VietOCR cho cả cuốn; không nạp được model thì vẫn chạy với chữ ML Kit.
            val vietOcr = vietOcrEngine.openSessionOrNull()
            val recognized = try {
                pages.mapIndexed { index, page ->
                    updateProgress(
                        title,
                        appContext.getString(R.string.scan_book_notification_recognizing, index + 1, pages.size),
                        index * OCR_PROGRESS_SHARE / pages.size
                    )
                    recognizePage(page, vietOcr)
                }
            } finally {
                vietOcr?.close()
            }
            val chapters = ScannedPageTextAssembler.assemble(recognized)
            if (chapters.isEmpty()) {
                return failure(title, appContext.getString(R.string.scan_book_error_no_text), pagesDir, outputFile)
            }
            updateProgress(title, appContext.getString(R.string.book_conversion_notification_packaging, OCR_PROGRESS_SHARE), OCR_PROGRESS_SHARE)
            val sample = chapters.first().paragraphs.joinToString(" ").take(LANGUAGE_SAMPLE_CHARS)
            EpubWriter.write(
                outputFile,
                EpubMetadata(title = title, author = author, language = guessLanguage(sample)),
                chapters.map { WebNovelChapter(title = it.title ?: title, paragraphs = it.paragraphs) }
            )
            // Trang đầu thường là ảnh bìa sách; lỗi tạo bìa không làm hỏng tác vụ.
            val coverPath = runCatching {
                ComicPageDecoder.writeCover(pages.first().readBytes(), storageManager.comicCoverFile(outputFile))
            }.getOrNull()
            val book = epubEngine.parseEpubMetadataStrict(outputFile).let { parsed ->
                if (coverPath != null) parsed.copy(coverPath = coverPath) else parsed
            }
            updateProgress(title, appContext.getString(R.string.book_conversion_notification_indexing), INDEXING_PROGRESS)
            bookRepository.insertBook(book)
            epubEngine.indexBookContent(outputFile, book.id, searchRepository)
            pagesDir.deleteRecursively()
            showResultNotification(appContext.getString(R.string.book_conversion_notification_success, book.title))
            success(book.id, book.title)
        } catch (error: CancellationException) {
            // Hủy chủ động: dọn ảnh và EPUB dở; process death không đi qua nhánh này.
            withContext(NonCancellable) {
                pagesDir.deleteRecursively()
                deleteUncommittedOutput(outputFile)
            }
            throw error
        } catch (_: OcrModelUnavailableException) {
            retryOrFail(title, appContext.getString(R.string.scan_book_error_model_unavailable), pagesDir, outputFile)
        } catch (_: IOException) {
            retryOrFail(title, appContext.getString(R.string.book_conversion_error_output), pagesDir, outputFile)
        } catch (_: Exception) {
            failure(title, appContext.getString(R.string.book_conversion_error_invalid), pagesDir, outputFile)
        }
    }

    /**
     * OCR một trang; ảnh hỏng không giải mã được coi như trang trắng để không bỏ cả cuốn sách.
     *
     * @param page File ảnh trang.
     * @param vietOcr Phiên VietOCR đọc lại chữ từng dòng, `null` nếu không nạp được model.
     * @return Các dòng chữ của trang.
     * @throws OcrModelUnavailableException Nếu model OCR chưa được Play services tải xong.
     */
    private suspend fun recognizePage(page: File, vietOcr: VietOcrSession?): List<OcrTextBlock> = try {
        recognizer.recognize(page, vietOcr)
    } catch (_: IOException) {
        emptyList()
    }

    /**
     * Đoán ngôn ngữ để ghi `dc:language`, giúp TTS chọn giọng phù hợp.
     *
     * @param sample Mẫu văn bản đầu sách.
     * @return `vi` khi có chữ cái tiếng Việt (hoặc mẫu quá ngắn), ngược lại `en`.
     */
    private fun guessLanguage(sample: String): String {
        val letters = sample.count { it.isLetter() }
        if (letters < MIN_LANGUAGE_LETTERS) return "vi"
        val vietnamese = sample.lowercase().count { it in VIETNAMESE_LETTERS }
        return if (vietnamese * VIETNAMESE_RATIO_DIVISOR >= letters) "vi" else "en"
    }

    /**
     * Retry khi còn lượt (giữ ảnh trang), hết lượt thì báo lỗi và dọn dẹp.
     *
     * @param title Tên sách.
     * @param message Thông báo lỗi đã bản địa hóa.
     * @param pagesDir Thư mục ảnh trang.
     * @param outputFile File EPUB đích.
     * @return Kết quả retry hoặc thất bại.
     */
    private suspend fun retryOrFail(title: String, message: String, pagesDir: File, outputFile: File): Result {
        if (runAttemptCount < MAX_RETRY_COUNT) {
            outputFile.delete()
            return Result.retry()
        }
        return failure(title, message, pagesDir, outputFile)
    }

    /**
     * Báo lỗi, dọn ảnh trang và EPUB chưa commit, trả kết quả thất bại kèm thông báo cho UI.
     *
     * @param title Tên sách.
     * @param message Thông báo lỗi đã bản địa hóa.
     * @param pagesDir Thư mục ảnh trang.
     * @param outputFile File EPUB đích.
     * @return Kết quả thất bại.
     */
    private suspend fun failure(title: String, message: String, pagesDir: File, outputFile: File): Result {
        pagesDir.deleteRecursively()
        deleteUncommittedOutput(outputFile)
        showResultNotification(appContext.getString(R.string.book_conversion_notification_failed, title, message))
        return Result.failure(workDataOf(LocalBookImportWorker.KEY_ERROR_MESSAGE to message))
    }

    /**
     * Kết quả thành công theo khóa mà thư viện đang đọc cho tác vụ import local.
     *
     * @param bookId ID sách đã lưu.
     * @param title Tên sách.
     * @return Kết quả thành công.
     */
    private fun success(bookId: String, title: String): Result = Result.success(
        workDataOf(
            LocalBookImportWorker.KEY_TITLE to title,
            LocalBookImportWorker.KEY_BOOK_ID to bookId,
            LocalBookImportWorker.KEY_PROGRESS to 100
        )
    )

    /**
     * Chỉ xóa EPUB chưa được ghi vào thư viện.
     *
     * @param outputFile File EPUB đích.
     */
    private suspend fun deleteUncommittedOutput(outputFile: File) {
        if (bookRepository.getBookById(outputFile.name)?.filePath != outputFile.absolutePath) {
            searchRepository.clearIndexForBook(outputFile.name)
            storageManager.comicCoverFile(outputFile).delete()
            outputFile.delete()
        }
    }

    /**
     * Ghi tiến độ WorkManager (cùng khóa với [LocalBookImportWorker]) và cập nhật notification foreground.
     *
     * @param title Tên sách.
     * @param step Mô tả bước hiện tại.
     * @param progress Phần trăm hoàn thành.
     */
    private suspend fun updateProgress(title: String, step: String, progress: Int) {
        setProgress(
            workDataOf(
                LocalBookImportWorker.KEY_PROGRESS to progress,
                LocalBookImportWorker.KEY_TITLE to title,
                LocalBookImportWorker.KEY_CURRENT_STEP to step
            )
        )
        val notification = NotificationCompat.Builder(appContext, LocalBookImportWorker.CHANNEL_ID)
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
        try {
            setForeground(foreground)
        } catch (_: IllegalStateException) {
            // Android 12+ chặn chạy foreground service khi app ở nền: vẫn OCR tiếp như worker thường.
            notificationManager.notify(NOTIFICATION_ID, notification)
        }
    }

    /** Tạo notification channel chuyển đổi sách (dùng chung với [LocalBookImportWorker]). */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    LocalBookImportWorker.CHANNEL_ID,
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
     * Hiển thị notification kết quả sau khi tác vụ kết thúc.
     *
     * @param message Nội dung đã bản địa hóa.
     */
    private fun showResultNotification(message: String) {
        createNotificationChannel()
        notificationManager.notify(
            RESULT_NOTIFICATION_ID,
            NotificationCompat.Builder(appContext, LocalBookImportWorker.CHANNEL_ID)
                .setContentTitle(appContext.getString(R.string.book_conversion_channel_name))
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
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
        return PendingIntent.getActivity(
            appContext, 0, launchIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        const val KEY_PAGES_DIR = "pages_dir"
        const val KEY_OUTPUT_PATH = "output_path"
        const val KEY_BOOK_TITLE = "book_title"
        const val KEY_BOOK_AUTHOR = "book_author"
        const val PAGE_FILE_PREFIX = "page_"
        private const val NOTIFICATION_ID = 4030
        private const val RESULT_NOTIFICATION_ID = 4031
        /** Phần trăm tiến độ dành cho bước OCR; phần còn lại là ghi EPUB và lập chỉ mục. */
        private const val OCR_PROGRESS_SHARE = 90
        private const val INDEXING_PROGRESS = 95
        private const val MAX_RETRY_COUNT = 3
        private const val LANGUAGE_SAMPLE_CHARS = 2000
        private const val MIN_LANGUAGE_LETTERS = 50
        /** Có ít nhất 1 chữ cái đặc trưng tiếng Việt trên mỗi 50 chữ cái thì coi là tiếng Việt. */
        private const val VIETNAMESE_RATIO_DIVISOR = 50
        private const val VIETNAMESE_LETTERS =
            "ăâđêôơưàáảãạằắẳẵặầấẩẫậèéẻẽẹềếểễệìíỉĩịòóỏõọồốổỗộờớởỡợùúủũụừứửữựỳýỷỹỵ"
    }
}
