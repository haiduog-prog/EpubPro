package com.epubpro.core.storage.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.webkit.CookieManager
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.epubpro.core.designsystem.R
import com.epubpro.core.reader.engine.EpubEngine
import com.epubpro.core.reader.engine.EpubPackageStructureParser
import com.epubpro.core.storage.EpubStorageManager
import com.epubpro.core.storage.WebNovelSource
import com.epubpro.core.storage.WebNovelSourceStore
import com.epubpro.core.storage.WebNovelMutationLock
import com.epubpro.core.webnovel.CrawlStopReason
import com.epubpro.core.webnovel.EpubMetadata
import com.epubpro.core.webnovel.EpubWriter
import com.epubpro.core.webnovel.HtmlCharsetDecoder
import com.epubpro.core.webnovel.PublicAddressPolicy
import com.epubpro.core.webnovel.WebFetchException
import com.epubpro.core.webnovel.WebNovelCrawler
import com.epubpro.domain.repository.BookRepository
import com.epubpro.domain.repository.SearchRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Worker tải truyện từ trang web bất kỳ theo liên kết "chương sau" và đóng gói thành EPUB trong thư viện.
 *
 * Dùng OkHttp client riêng, không có interceptor xác thực của server EpubPro, để token không bao giờ bị gửi
 * tới site bên thứ ba. Cookie của WebView (ví dụ sau khi vượt màn hình xác minh) được gửi kèm cho đúng host.
 */
@HiltWorker
class WebNovelDownloadWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val storageManager: EpubStorageManager,
    private val epubEngine: EpubEngine,
    private val bookRepository: BookRepository,
    private val searchRepository: SearchRepository,
    private val sourceStore: WebNovelSourceStore,
    private val replacement: LocalBookReplacement
) : CoroutineWorker(appContext, workerParams) {

    /** User-Agent dùng cho mọi request của tác vụ này; khớp WebView để cookie Cloudflare còn hiệu lực. */
    private var userAgent: String = DEFAULT_USER_AGENT

    private val notificationManager =
        appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    /**
     * Chạy chế độ tải truyện mới hoặc tải thêm chương cho truyện đã lưu (khi có [KEY_APPEND_BOOK_ID]).
     *
     * @return Thành công kèm ID sách và số chương mới, hoặc thất bại.
     */
    override suspend fun doWork(): Result {
        val appendBookId = inputData.getString(KEY_APPEND_BOOK_ID)
        return WebNovelMutationLock.mutex.withLock {
            if (appendBookId != null) appendChapters(appendBookId) else downloadNewNovel()
        }
    }

    /**
     * Thu thập chương, ghi EPUB, lưu sách, lập chỉ mục tìm kiếm và ghi nhớ nguồn web để tải thêm sau này.
     *
     * @return Thành công kèm ID sách, hoặc thất bại khi không tải được chương nào.
     */
    private suspend fun downloadNewNovel(): Result {
        val startUrl = inputData.getString(KEY_START_URL) ?: return Result.failure()
        inputData.getString(KEY_USER_AGENT)?.takeIf { it.isNotBlank() }?.let { userAgent = it }
        val maxChapters = inputData.getInt(KEY_MAX_CHAPTERS, DEFAULT_MAX_CHAPTERS).coerceIn(1, MAX_CHAPTERS)
        val titleOverride = inputData.getString(KEY_TITLE)?.trim()?.takeIf { it.isNotEmpty() }
        val displayName = titleOverride ?: startUrl

        createNotificationChannel()
        updateProgress(displayName, 0, maxChapters)
        val outputFile = storageManager.createConvertedEpubFile()
        return try {
            val crawler = WebNovelCrawler(fetch = ::fetchHtml)
            val result = crawler.crawl(startUrl, maxChapters) { count, _ ->
                updateProgress(displayName, count, maxChapters)
            }
            if (result.chapters.isEmpty()) {
                showResultNotification(displayName, appContext.getString(R.string.web_novel_download_failed_empty), result.failedUrl)
                return Result.failure(workDataOf(KEY_STOP_REASON to result.stoppedReason.name, KEY_FAILED_URL to result.failedUrl))
            }
            val title = titleOverride ?: result.novelTitle ?: result.chapters.first().title
            val sample = result.chapters.first().paragraphs.joinToString("").take(LANGUAGE_SAMPLE_CHARS)
            EpubWriter.write(
                outputFile,
                EpubMetadata(title = title, author = result.author.orEmpty(), language = guessLanguage(sample), sourceUrl = startUrl),
                result.chapters
            )
            val book = epubEngine.parseEpubMetadataStrict(outputFile)
            bookRepository.insertBook(book)
            sourceStore.save(
                book.id,
                WebNovelSource(startUrl, result.lastChapterUrl ?: startUrl, inputData.getString(KEY_USER_AGENT))
            )
            epubEngine.indexBookContent(outputFile, book.id, searchRepository)
            showResultNotification(
                title,
                if (result.failedUrl != null) appContext.getString(R.string.web_novel_append_partial, result.chapters.size, title)
                else appContext.getString(R.string.web_novel_download_success, title, result.chapters.size),
                result.failedUrl
            )
            Result.success(
                workDataOf(
                    KEY_BOOK_ID to book.id,
                    KEY_CHAPTER_COUNT to result.chapters.size,
                    KEY_STOP_REASON to result.stoppedReason.name,
                    KEY_FAILED_URL to result.failedUrl
                )
            )
        } catch (error: CancellationException) {
            outputFile.delete()
            throw error
        } catch (error: Exception) {
            outputFile.delete()
            showResultNotification(displayName, appContext.getString(R.string.web_novel_download_failed))
            Result.failure(workDataOf(KEY_STOP_REASON to CrawlStopReason.FETCH_FAILED.name))
        }
    }

    /**
     * Tải các chương sau chương cuối đã lưu, ghi lại EPUB gồm chương cũ + mới rồi thay file của sách,
     * giữ nguyên ID, tiến độ, bookmark, highlight và cache AI (các chương cũ không đổi chỉ số).
     *
     * @param bookId ID sách đã lưu từ web.
     * @return Thành công kèm số chương mới (0 nếu truyện chưa có chương mới), hoặc thất bại.
     */
    private suspend fun appendChapters(bookId: String): Result {
        val maxChapters = inputData.getInt(KEY_MAX_CHAPTERS, DEFAULT_MAX_CHAPTERS).coerceIn(1, MAX_CHAPTERS)
        val book = bookRepository.getBookById(bookId) ?: return Result.failure()
        sourceStore.reconcileReplacement(bookId, book.filePath)
        val source = sourceStore.get(bookId) ?: return Result.failure()
        source.userAgent?.let { userAgent = it }

        createNotificationChannel()
        updateProgress(book.title, 0, maxChapters)
        val oldFile = File(book.filePath)
        val outputFile = storageManager.createConvertedEpubFile()
        return try {
            val crawler = WebNovelCrawler(fetch = ::fetchHtml)
            val nextUrl = crawler.findNextChapterUrl(source.lastChapterUrl)
            val result = nextUrl?.let { url ->
                crawler.crawl(url, maxChapters) { count, _ -> updateProgress(book.title, count, maxChapters) }
            }
            if (result != null && result.chapters.isEmpty()) {
                showResultNotification(book.title, appContext.getString(R.string.web_novel_download_failed), result.failedUrl)
                return Result.failure(workDataOf(KEY_STOP_REASON to result.stoppedReason.name, KEY_FAILED_URL to result.failedUrl))
            }
            if (result == null) {
                showResultNotification(book.title, appContext.getString(R.string.web_novel_no_new_chapters))
                return Result.success(workDataOf(KEY_BOOK_ID to bookId, KEY_CHAPTER_COUNT to 0))
            }
            val existing = EpubWriter.readChapters(oldFile)
            val identifier = ZipFile(oldFile).use { EpubPackageStructureParser.readPublicationIdentifier(it) }
                ?: error("EPUB truyện web thiếu ID xuất bản")
            val sample = existing.first().paragraphs.joinToString("").take(LANGUAGE_SAMPLE_CHARS)
            EpubWriter.write(
                outputFile,
                EpubMetadata(
                    title = book.title,
                    author = book.author.takeUnless { it == UNKNOWN_AUTHOR } ?: result.author.orEmpty(),
                    language = guessLanguage(sample),
                    sourceUrl = source.startUrl,
                    identifier = identifier
                ),
                existing + result.chapters
            )
            val parsed = epubEngine.parseEpubMetadataStrict(outputFile)
            sourceStore.prepareReplacement(bookId, outputFile.absolutePath, source.copy(lastChapterUrl = result.lastChapterUrl ?: source.lastChapterUrl))
            replacement.replace(parsed, bookId, identifier, keepAiCache = true, expectedFilePath = oldFile.absolutePath)
            sourceStore.reconcileReplacement(bookId, outputFile.absolutePath)
            showResultNotification(
                book.title,
                appContext.getString(if (result.stoppedReason == CrawlStopReason.FETCH_FAILED || result.stoppedReason == CrawlStopReason.EXTRACTION_FAILED || result.stoppedReason == CrawlStopReason.LOOP_DETECTED) R.string.web_novel_append_partial else R.string.web_novel_append_success, result.chapters.size, book.title),
                result.failedUrl
            )
            Result.success(workDataOf(KEY_BOOK_ID to bookId, KEY_CHAPTER_COUNT to result.chapters.size, KEY_STOP_REASON to result.stoppedReason.name, KEY_FAILED_URL to result.failedUrl))
        } catch (error: CancellationException) {
            withContext(NonCancellable) {
                if (bookRepository.getBookById(bookId)?.filePath != outputFile.absolutePath) outputFile.delete()
            }
            throw error
        } catch (error: Exception) {
            withContext(NonCancellable) {
                if (bookRepository.getBookById(bookId)?.filePath != outputFile.absolutePath) outputFile.delete()
            }
            showResultNotification(book.title, appContext.getString(R.string.web_novel_download_failed))
            Result.failure(workDataOf(KEY_STOP_REASON to CrawlStopReason.FETCH_FAILED.name, KEY_FAILED_URL to source.lastChapterUrl))
        }
    }

    /**
     * Tải HTML của một URL, giới hạn dung lượng và giải mã đúng charset.
     *
     * @param url URL cần tải.
     * @return HTML.
     * @throws WebFetchException Khi mạng lỗi, mã HTTP không thành công hoặc trang quá lớn.
     */
    private suspend fun fetchHtml(url: String): String = withContext(Dispatchers.IO) {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Accept-Language", "vi,en;q=0.8,zh;q=0.6")
        runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { builder.header("Cookie", it) }
        try {
            httpClient.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) throw WebFetchException(response.code, "HTTP ${response.code}")
                val body = response.body ?: throw WebFetchException(response.code, "Empty body")
                if (body.contentLength() > MAX_PAGE_BYTES) throw WebFetchException(response.code, "Page too large")
                val bytes = body.source().use { source ->
                    source.request(MAX_PAGE_BYTES + 1)
                    if (source.buffer.size > MAX_PAGE_BYTES) throw WebFetchException(response.code, "Page too large")
                    source.buffer.readByteArray()
                }
                HtmlCharsetDecoder.decode(bytes, response.header("Content-Type"))
            }
        } catch (error: IOException) {
            if (error is WebFetchException) throw error
            // Địa chỉ bị chặn là lỗi vĩnh viễn: dùng mã 403 để crawler không thử lại.
            if (error.message == BLOCKED_MESSAGE) throw WebFetchException(BLOCKED_STATUS, BLOCKED_MESSAGE)
            throw WebFetchException(null, error.javaClass.simpleName)
        }
    }

    /**
     * Đoán ngôn ngữ chính để ghi vào `dc:language` (giúp Reader gợi ý Convert Hán-Việt cho truyện Trung).
     *
     * @param sample Mẫu văn bản chương đầu.
     * @return `zh` nếu phần lớn là chữ Hán, ngược lại `vi`.
     */
    private fun guessLanguage(sample: String): String {
        if (sample.isEmpty()) return "vi"
        val han = sample.count { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }
        return if (han * 10 > sample.length * 3) "zh" else "vi"
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
        setForeground(foreground)
    }

    /**
     * Hiển thị notification kết quả sau khi tác vụ kết thúc.
     *
     * @param title Tiêu đề notification.
     * @param message Nội dung đã bản địa hóa.
     * @param failedUrl Trang nguồn gặp lỗi để người dùng mở kiểm tra, hoặc null.
     */
    private fun showResultNotification(title: String, message: String, failedUrl: String? = null) {
        val builder = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .setContentIntent(createLaunchAppPendingIntent())
        failedUrl?.let { url ->
            val intent = appContext.packageManager.getLaunchIntentForPackage(appContext.packageName)?.apply {
                action = Intent.ACTION_SEND
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, url)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            if (intent != null) {
                val pending = PendingIntent.getActivity(appContext, url.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                builder.addAction(android.R.drawable.ic_menu_view, appContext.getString(R.string.reader_web_open_source), pending)
            }
        }
        notificationManager.notify(
            RESULT_NOTIFICATION_ID,
            builder.build()
        )
    }

    /** Tạo notification channel riêng cho tải truyện từ web. */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    appContext.getString(R.string.web_novel_download_channel_name),
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
        const val TAG = "web_novel_download"
        const val KEY_START_URL = "start_url"
        const val KEY_MAX_CHAPTERS = "max_chapters"
        const val KEY_TITLE = "title"
        const val KEY_BOOK_ID = "book_id"
        const val KEY_CHAPTER_COUNT = "chapter_count"
        const val KEY_STOP_REASON = "stop_reason"
        const val KEY_FAILED_URL = "failed_url"
        const val KEY_USER_AGENT = "user_agent"
        const val KEY_APPEND_BOOK_ID = "append_book_id"
        const val DEFAULT_MAX_CHAPTERS = 50
        const val MAX_CHAPTERS = 2000
        private const val CHANNEL_ID = "web_novel_download_channel"
        private const val NOTIFICATION_ID = 4010
        private const val RESULT_NOTIFICATION_ID = 4011
        private const val MAX_PAGE_BYTES = 5L * 1024L * 1024L
        private const val BLOCKED_STATUS = 403
        private const val BLOCKED_MESSAGE = "Blocked non-public address"
        private const val LANGUAGE_SAMPLE_CHARS = 2000
        private const val UNKNOWN_AUTHOR = "Unknown Author"
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

        /**
         * Client HTTP dùng chung, không gắn interceptor xác thực của server EpubPro.
         *
         * Chỉ kết nối tới địa chỉ công khai: [Dns] lọc kết quả phân giải, còn network interceptor kiểm tra
         * địa chỉ socket thực tế của mọi request (kể cả sau redirect và URL dạng IP mà OkHttp không qua DNS),
         * chặn SSRF vào localhost/mạng LAN và DNS rebinding.
         */
        private val httpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .dns(PublicOnlyDns)
                .addNetworkInterceptor { chain ->
                    val remote = chain.connection()?.socket()?.inetAddress
                    if (remote != null && !PublicAddressPolicy.isPublic(remote)) {
                        throw WebFetchException(BLOCKED_STATUS, BLOCKED_MESSAGE)
                    }
                    chain.proceed(chain.request())
                }
                .build()
        }

        /** DNS chỉ trả về địa chỉ công khai; host chỉ phân giải ra địa chỉ nội bộ bị coi như không tồn tại. */
        private object PublicOnlyDns : Dns {
            /**
             * Phân giải host và loại địa chỉ nội bộ.
             *
             * @param hostname Tên host.
             * @return Danh sách địa chỉ công khai.
             * @throws UnknownHostException Nếu không còn địa chỉ công khai nào.
             */
            override fun lookup(hostname: String): List<InetAddress> {
                val addresses = Dns.SYSTEM.lookup(hostname).filter(PublicAddressPolicy::isPublic)
                if (addresses.isEmpty()) throw UnknownHostException(BLOCKED_MESSAGE)
                return addresses
            }
        }
    }
}
