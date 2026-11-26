package com.epubpro.core.storage.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.epubpro.core.storage.WebNovelSourceStore
import com.epubpro.core.webnovel.EpubWriter
import com.epubpro.domain.model.BookSourceFormat
import com.epubpro.domain.repository.BookRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Quét bù ảnh bìa cho các truyện web đã lưu trước khi app biết lấy bìa từ trang nguồn.
 *
 * Chỉ xét sách EPUB chưa có bìa và có URL nguồn (từ [WebNovelSourceStore] hoặc `dc:source` trong EPUB truyện web
 * do app tạo). Mỗi sách có hạn thử lại riêng: [RETRY_NOT_AVAILABLE_MS] khi site trả lời nhưng không có bìa hoặc sách
 * không có nguồn, [RETRY_NETWORK_ERROR_MS] khi lỗi mạng (site bị chặn, mất mạng), để không gọi site nguồn
 * hay mở file EPUB ở mọi lần mở app. Nghỉ [POLITE_DELAY_MS] giữa hai lần gọi mạng.
 */
@HiltWorker
class WebNovelCoverBackfillWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val bookRepository: BookRepository,
    private val sourceStore: WebNovelSourceStore,
    private val coverFetcher: WebNovelCoverFetcher
) : CoroutineWorker(appContext, workerParams) {

    /**
     * Thử lấy bìa cho từng truyện web còn thiếu bìa.
     *
     * @return Luôn thành công; sách không lấy được bìa sẽ được thử lại ở lần chạy sau khi hết hạn chờ.
     */
    override suspend fun doWork(): Result {
        val schedule = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val candidateIds = bookRepository.getAllBooks().first()
            .filter { book -> book.sourceFormat == BookSourceFormat.EPUB && book.coverPath.isNullOrBlank() }
            .map { it.id }
        for (bookId in candidateIds) {
            if (System.currentTimeMillis() < schedule.getLong(nextAttemptKey(bookId), 0L)) continue
            // Đọc lại ngay trước khi xử lý: sách có thể đã bị xoá, có bìa hoặc đổi file từ lúc lấy danh sách.
            val book = bookRepository.getBookById(bookId) ?: continue
            if (!book.coverPath.isNullOrBlank()) continue
            val source = sourceStore.get(book.id)
            val sourceUrl = source?.startUrl
                ?: withContext(Dispatchers.IO) { EpubWriter.readSourceUrl(File(book.filePath)) }
            if (sourceUrl == null) {
                scheduleNextAttempt(schedule, book.id, RETRY_NOT_AVAILABLE_MS)
                continue
            }
            val result = coverFetcher.fetchCoverIfMissing(book, sourceUrl, source?.userAgent)
            when (result) {
                CoverFetchResult.SAVED -> schedule.edit().remove(nextAttemptKey(book.id)).apply()
                CoverFetchResult.NOT_AVAILABLE -> scheduleNextAttempt(schedule, book.id, RETRY_NOT_AVAILABLE_MS)
                CoverFetchResult.NETWORK_ERROR -> scheduleNextAttempt(schedule, book.id, RETRY_NETWORK_ERROR_MS)
            }
            delay(POLITE_DELAY_MS)
        }
        return Result.success()
    }

    /**
     * Hẹn thời điểm sớm nhất được thử lại cho một sách.
     *
     * @param schedule SharedPreferences lưu lịch thử lại.
     * @param bookId ID sách.
     * @param delayMs Khoảng chờ tính từ bây giờ.
     */
    private fun scheduleNextAttempt(schedule: android.content.SharedPreferences, bookId: String, delayMs: Long) {
        schedule.edit().putLong(nextAttemptKey(bookId), System.currentTimeMillis() + delayMs).apply()
    }

    /**
     * Khoá SharedPreferences chứa thời điểm thử lại của một sách.
     *
     * @param bookId ID sách.
     * @return Tên khoá.
     */
    private fun nextAttemptKey(bookId: String): String = NEXT_ATTEMPT_PREFIX + bookId

    companion object {
        /** Tên unique work của tác vụ quét bù bìa. */
        const val UNIQUE_WORK_NAME = "web_novel_cover_backfill"
        private const val PREFS_NAME = "web_novel_cover_backfill"
        private const val NEXT_ATTEMPT_PREFIX = "next_"
        private const val RETRY_NOT_AVAILABLE_MS = 7L * 24L * 60L * 60L * 1000L
        private const val RETRY_NETWORK_ERROR_MS = 24L * 60L * 60L * 1000L
        private const val POLITE_DELAY_MS = 1_500L
    }
}

/**
 * Lập lịch [WebNovelCoverBackfillWorker] khi có mạng.
 */
@Singleton
class WebNovelCoverBackfillScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /**
     * Đưa tác vụ quét bù bìa vào hàng đợi.
     *
     * @param afterNewBook `true` khi vừa lưu một truyện mới: nối thêm một lượt sau lượt đang chạy để không
     *        bỏ sót truyện đó. `false` (lúc mở app): giữ lượt đang chờ, tránh dồn nhiều lượt khi chưa có mạng.
     */
    fun enqueue(afterNewBook: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<WebNovelCoverBackfillWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            WebNovelCoverBackfillWorker.UNIQUE_WORK_NAME,
            if (afterNewBook) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP,
            request
        )
    }
}
