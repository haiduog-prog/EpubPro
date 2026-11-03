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
import com.epubpro.core.comic.ComicArchive
import com.epubpro.core.comic.ComicCbzWriter
import com.epubpro.core.comic.ComicChapterInfo
import com.epubpro.core.comic.ComicChapterParseException
import com.epubpro.core.comic.ComicPageDecoder
import com.epubpro.core.comic.ComicProtectionException
import com.epubpro.core.comic.ComicReadLimits
import com.epubpro.core.comic.DownloadedComicChapter
import com.epubpro.core.comic.OnlineComicFetcher
import com.epubpro.core.comic.OnlineComicImageLoader
import com.epubpro.core.designsystem.R
import com.epubpro.core.storage.EpubStorageManager
import com.epubpro.core.storage.OfflineComicSource
import com.epubpro.core.storage.OfflineComicStore
import com.epubpro.core.storage.OnlineComicProgressStore
import com.epubpro.domain.model.Book
import com.epubpro.domain.model.BookSourceFormat
import com.epubpro.domain.model.parseComicLocator
import com.epubpro.domain.repository.BookRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Worker tải ảnh truyện tranh online về máy và đóng gói thành một cuốn CBZ trên Kệ sách.
 *
 * Đi theo liên kết "chương sau" từ chương bắt đầu, tải ảnh từng trang (thử server ảnh thay thế khi lỗi),
 * lưu tạm từng chương ra đĩa rồi mới ghi CBZ để không giữ cả chương trong RAM. Khi nối vào cuốn đã có,
 * bản CBZ mới gồm toàn bộ trang cũ + chương mới, giữ nguyên ID sách và tiến độ (trang cũ không đổi chỉ số).
 */
@HiltWorker
class OnlineComicDownloadWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val storageManager: EpubStorageManager,
    private val bookRepository: BookRepository,
    private val fetcher: OnlineComicFetcher,
    private val imageLoader: OnlineComicImageLoader,
    private val offlineStore: OfflineComicStore,
    private val progressStore: OnlineComicProgressStore
) : CoroutineWorker(appContext, workerParams) {

    private val notificationManager =
        appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    /**
     * Tải chương, ghi CBZ rồi tạo sách mới hoặc cập nhật cuốn đã tải trước đó.
     *
     * @return Thành công kèm ID sách, số chương mới và cờ gián đoạn; thất bại khi không tải được chương nào.
     */
    override suspend fun doWork(): Result {
        val startUrl = inputData.getString(KEY_START_URL) ?: return Result.failure()
        val maxChapters = inputData.getInt(KEY_MAX_CHAPTERS, 1).coerceIn(1, MAX_CHAPTERS)
        val existing = inputData.getString(KEY_APPEND_BOOK_ID)?.let { bookId ->
            val book = bookRepository.getBookById(bookId)?.takeIf { File(it.filePath).isFile }
            val source = offlineStore.get(bookId)
            if (book != null && source != null) book to source else null
        }
        var displayName = existing?.first?.title ?: startUrl

        createNotificationChannel()
        updateProgress(displayName, 0, maxChapters)
        val workDir = File(appContext.cacheDir, "comic_download/$id").apply { deleteRecursively(); mkdirs() }
        val output = storageManager.createComicFile()
        try {
            val known = existing?.second?.chapterUrls?.toMutableSet() ?: mutableSetOf()
            var url: String? = if (existing != null && inputData.getBoolean(KEY_CONTINUE, false)) {
                fetchChapter(existing.second.lastChapterUrl).nextChapterUrl
            } else {
                startUrl
            }
            val downloaded = mutableListOf<Pair<String, DownloadedComicChapter>>()
            var comicTitle: String? = null
            var sourceHost: String? = null
            var totalBytes = existing?.let { File(it.first.filePath).length() } ?: 0L
            var totalPages = existing?.first?.totalChapters ?: 0
            var failedUrl: String? = null
            var limitReached = false
            while (url != null && downloaded.size < maxChapters) {
                // Chương đã có trong sách hoặc vòng lặp liên kết: dừng để không nhân đôi trang.
                if (!known.add(url)) break
                val chapterUrl: String = url
                val info = try {
                    fetchChapter(chapterUrl)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    failedUrl = chapterUrl
                    break
                }
                if (comicTitle == null) {
                    comicTitle = info.comicTitle.ifBlank { info.title }
                    sourceHost = info.sourceHost
                    if (existing == null) displayName = comicTitle
                }
                if (totalPages + info.pages.size > ComicReadLimits.MAX_PAGES) {
                    limitReached = true
                    break
                }
                val pages = try {
                    downloadPages(info, File(workDir, downloaded.size.toString()))
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    failedUrl = chapterUrl
                    break
                }
                val chapterBytes = pages.sumOf { it.length() }
                if (totalBytes + chapterBytes > MAX_ARCHIVE_BUDGET_BYTES) {
                    limitReached = true
                    break
                }
                totalBytes += chapterBytes
                totalPages += pages.size
                downloaded += chapterUrl to DownloadedComicChapter(info.title, pages)
                updateProgress(displayName, downloaded.size, maxChapters)
                url = info.nextChapterUrl
                if (url != null && downloaded.size < maxChapters) delay(POLITE_DELAY_MS)
            }

            if (downloaded.isEmpty()) {
                output.delete()
                if (existing != null && failedUrl == null && !limitReached) {
                    showResultNotification(displayName, appContext.getString(R.string.online_comic_download_no_new))
                    return Result.success(workDataOf(KEY_BOOK_ID to existing.first.id, KEY_CHAPTER_COUNT to 0))
                }
                showResultNotification(displayName, appContext.getString(R.string.online_comic_download_failed))
                return Result.failure(workDataOf(KEY_FAILED_URL to failedUrl))
            }

            val title = existing?.first?.title ?: comicTitle ?: startUrl
            ComicCbzWriter.write(
                target = output,
                existing = existing?.first?.filePath?.let(::File),
                chapters = downloaded.map { it.second },
                startChapterNumber = (existing?.second?.chapterUrls?.size ?: 0) + 1,
                title = title,
                sourceUrl = existing?.second?.chapterUrls?.first() ?: startUrl
            )
            val chapterUrls = existing?.second?.chapterUrls.orEmpty() + downloaded.map { it.first }
            val comicKey = existing?.second?.comicKey ?: progressStore.computeComicKey(startUrl)
            val bookId = ComicArchive.open(output).use { archive ->
                if (existing != null) {
                    val old = existing.first
                    bookRepository.insertBook(old.copy(filePath = output.absolutePath, totalChapters = archive.pageCount))
                    updateProgressPageCount(old.id, archive.pageCount)
                    offlineStore.save(old.id, OfflineComicSource(comicKey, chapterUrls))
                    // Reader đang mở bản cũ giữ ZipFile mở suốt phiên (ComicArchive) nên vẫn đọc được sau khi xóa.
                    storageManager.deleteBookFile(old.filePath)
                    old.id
                } else {
                    val coverPath = runCatching {
                        ComicPageDecoder.writeCover(archive.readPageBytes(0), storageManager.comicCoverFile(output))
                    }.getOrNull()
                    val now = System.currentTimeMillis()
                    val book = Book(
                        id = output.name,
                        title = title,
                        author = sourceHost.orEmpty(),
                        coverPath = coverPath,
                        filePath = output.absolutePath,
                        addedAt = now,
                        lastReadAt = now,
                        totalChapters = archive.pageCount,
                        sourceFormat = BookSourceFormat.CBZ
                    )
                    bookRepository.insertBook(book)
                    offlineStore.save(book.id, OfflineComicSource(comicKey, chapterUrls))
                    book.id
                }
            }
            val interrupted = failedUrl != null || limitReached
            showResultNotification(
                title,
                appContext.getString(
                    if (interrupted) R.string.online_comic_download_partial else R.string.online_comic_download_done,
                    downloaded.size
                )
            )
            return Result.success(
                workDataOf(
                    KEY_BOOK_ID to bookId,
                    KEY_CHAPTER_COUNT to downloaded.size,
                    KEY_INTERRUPTED to interrupted,
                    KEY_FAILED_URL to failedUrl
                )
            )
        } catch (error: CancellationException) {
            withContext(NonCancellable) { deleteUnreferencedOutput(output, existing?.first?.id) }
            throw error
        } catch (_: Exception) {
            withContext(NonCancellable) { deleteUnreferencedOutput(output, existing?.first?.id) }
            showResultNotification(displayName, appContext.getString(R.string.online_comic_download_failed))
            return Result.failure()
        } finally {
            workDir.deleteRecursively()
        }
    }

    /**
     * Tải thông tin chương, thử lại khi lỗi mạng tạm thời; lỗi chặn bot hoặc không có ảnh thì không thử lại.
     *
     * @param url URL chương.
     * @return Thông tin chương đã phân tích.
     * @throws IOException Khi vẫn lỗi sau số lần thử tối đa.
     */
    private suspend fun fetchChapter(url: String): ComicChapterInfo {
        var lastError: IOException? = null
        repeat(FETCH_ATTEMPTS) { attempt ->
            try {
                return fetcher.fetchChapter(url)
            } catch (error: ComicProtectionException) {
                throw error
            } catch (error: ComicChapterParseException) {
                throw IOException(error.message, error)
            } catch (error: IOException) {
                lastError = error
                if (attempt < FETCH_ATTEMPTS - 1) delay(RETRY_DELAY_MS * (attempt + 1))
            }
        }
        throw checkNotNull(lastError)
    }

    /**
     * Tải toàn bộ ảnh của một chương vào [directory]; mỗi trang thử ảnh chính rồi tới ảnh cùng vị trí ở server khác.
     *
     * @param info Thông tin chương.
     * @param directory Thư mục tạm của chương.
     * @return File ảnh theo thứ tự trang.
     * @throws IOException Khi có trang không tải được ở mọi server.
     */
    private suspend fun downloadPages(info: ComicChapterInfo, directory: File): List<File> {
        directory.mkdirs()
        val alternates = info.pagesByServer.values.filter { it.size == info.pages.size }
        return info.pages.mapIndexed { index, primary ->
            val candidates = (listOf(primary) + alternates.map { it[index] }).distinct()
            var bytes: ByteArray? = null
            for (candidate in candidates) {
                bytes = runCatching { imageLoader.downloadImage(candidate, info.chapterUrl) }
                    .onFailure { if (it is CancellationException) throw it }
                    .getOrNull()
                    ?.takeIf { ComicCbzWriter.imageExtension(it) != null }
                if (bytes != null) break
            }
            val data = bytes ?: throw IOException("Không tải được trang ${index + 1}")
            val extension = checkNotNull(ComicCbzWriter.imageExtension(data))
            File(directory, "%04d.%s".format(index + 1, extension)).also { it.writeBytes(data) }
        }
    }

    /**
     * Tính lại tổng trang và phần trăm tiến độ sau khi nối chương, giữ nguyên trang đang đọc.
     *
     * Kệ sách ưu tiên tổng trang trong tiến độ và xếp sách 100% vào "Đã hoàn thành"; không cập nhật thì cuốn
     * vừa có thêm chương vẫn hiện số trang cũ và bị coi là đã đọc xong.
     *
     * @param bookId ID sách vừa nối chương.
     * @param pageCount Tổng số trang của bản CBZ mới.
     */
    private suspend fun updateProgressPageCount(bookId: String, pageCount: Int) {
        if (pageCount <= 0) return
        val progress = bookRepository.getReadingProgressDirect(bookId) ?: return
        val page = (parseComicLocator(progress.currentCfi) ?: progress.chapterIndex).coerceIn(0, pageCount - 1)
        bookRepository.saveReadingProgress(
            progress.copy(
                totalChapters = pageCount,
                progressPercentage = ((page + 1).toFloat() / pageCount).coerceIn(0f, 1f),
                // Lớn hơn mốc cũ để bản ghi mới thắng khi đồng bộ theo thời gian cập nhật.
                updatedAt = maxOf(
                    System.currentTimeMillis(),
                    if (progress.updatedAt == Long.MAX_VALUE) Long.MAX_VALUE else progress.updatedAt + 1L
                )
            )
        )
    }

    /**
     * Xóa file CBZ dở dang khi không có sách nào tham chiếu tới nó.
     *
     * @param output File CBZ đang ghi.
     * @param existingBookId ID sách đang nối, null khi tạo sách mới.
     */
    private suspend fun deleteUnreferencedOutput(output: File, existingBookId: String?) {
        val bookId = existingBookId ?: output.name
        if (bookRepository.getBookById(bookId)?.filePath != output.absolutePath) output.delete()
    }

    /**
     * Cập nhật tiến độ WorkManager và notification foreground.
     *
     * @param title Tên truyện hoặc URL.
     * @param count Số chương đã tải.
     * @param max Số chương tối đa.
     */
    private suspend fun updateProgress(title: String, count: Int, max: Int) {
        setProgress(workDataOf(KEY_CHAPTER_COUNT to count, KEY_MAX_CHAPTERS to max))
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setContentTitle(appContext.getString(R.string.web_novel_download_notification_title, title))
            .setContentText(appContext.getString(R.string.web_novel_download_notification_progress, count, max))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(max, count.coerceIn(0, max), false)
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
            // Android 12+ chặn chạy foreground service khi app ở nền (ForegroundServiceStartNotAllowedException):
            // vẫn tải tiếp như worker thường, chỉ cập nhật notification tiến độ.
            notificationManager.notify(NOTIFICATION_ID, notification)
        }
    }

    /**
     * Hiển thị notification kết quả sau khi tác vụ kết thúc.
     *
     * @param title Tiêu đề notification.
     * @param message Nội dung đã bản địa hóa.
     */
    private fun showResultNotification(title: String, message: String) {
        notificationManager.notify(
            RESULT_NOTIFICATION_ID,
            NotificationCompat.Builder(appContext, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(message)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setAutoCancel(true)
                .setContentIntent(createLaunchAppPendingIntent())
                .build()
        )
    }

    /** Tạo notification channel riêng cho tải truyện tranh về máy. */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    appContext.getString(R.string.online_comic_download_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply { setShowBadge(false) }
            )
        }
    }

    /**
     * Tạo PendingIntent mở lại ứng dụng khi chạm notification.
     *
     * @return PendingIntent bất biến.
     */
    private fun createLaunchAppPendingIntent(): PendingIntent {
        val launchIntent = appContext.packageManager.getLaunchIntentForPackage(appContext.packageName) ?: Intent()
        launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        return PendingIntent.getActivity(
            appContext, 0, launchIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        const val TAG = "online_comic_download"
        const val KEY_START_URL = "start_url"
        const val KEY_MAX_CHAPTERS = "max_chapters"
        const val KEY_APPEND_BOOK_ID = "append_book_id"
        const val KEY_CONTINUE = "continue_from_last"
        const val KEY_BOOK_ID = "book_id"
        const val KEY_CHAPTER_COUNT = "chapter_count"
        const val KEY_INTERRUPTED = "interrupted"
        const val KEY_FAILED_URL = "failed_url"
        const val MAX_CHAPTERS = 200
        private const val CHANNEL_ID = "online_comic_download_channel"
        private const val NOTIFICATION_ID = 4020
        private const val RESULT_NOTIFICATION_ID = 4021
        private const val FETCH_ATTEMPTS = 3
        private const val RETRY_DELAY_MS = 1_000L
        private const val POLITE_DELAY_MS = 500L

        /** Chừa 5% so với giới hạn đọc CBZ để archive tải về luôn mở được bằng Reader. */
        private const val MAX_ARCHIVE_BUDGET_BYTES = ComicReadLimits.MAX_ARCHIVE_BYTES / 100 * 95
    }
}
