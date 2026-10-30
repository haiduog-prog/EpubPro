package com.epubpro.core.storage.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Trạng thái một tác vụ tải truyện web, đã tách khỏi kiểu của WorkManager cho tầng UI. */
sealed interface WebNovelDownloadState {
    /**
     * Đang tải.
     *
     * @property downloaded Số chương đã tải.
     * @property max Số chương tối đa.
     */
    data class Running(val downloaded: Int, val max: Int) : WebNovelDownloadState

    /**
     * Hoàn tất.
     *
     * @property bookId ID sách trong thư viện.
     * @property newChapters Số chương mới được thêm (0 nếu truyện chưa ra chương mới).
     */
    data class Succeeded(val bookId: String, val newChapters: Int) : WebNovelDownloadState

    /** Thất bại hoặc bị hủy. */
    data object Failed : WebNovelDownloadState
}

/**
 * Lập lịch tải truyện từ web thành EPUB bằng WorkManager.
 */
@Singleton
class WebNovelDownloadScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /**
     * Đưa tác vụ tải truyện mới vào hàng đợi; cùng một URL bắt đầu chỉ chạy một tác vụ tại một thời điểm.
     *
     * @param startUrl URL chương đầu tiên (http/https).
     * @param maxChapters Số chương tối đa cần tải.
     * @param title Tên truyện người dùng nhập, null để tự đoán.
     * @param userAgent User-Agent của WebView để cookie chống bot còn hiệu lực, null để dùng mặc định.
     * @return ID tác vụ WorkManager.
     * @throws IllegalArgumentException Nếu URL không phải http/https.
     */
    fun enqueue(startUrl: String, maxChapters: Int, title: String?, userAgent: String? = null): UUID {
        require(startUrl.startsWith("http://") || startUrl.startsWith("https://")) { "URL không hợp lệ" }
        return enqueueRequest(
            uniqueName = "${WebNovelDownloadWorker.TAG}_${startUrl.hashCode()}",
            input = workDataOf(
                WebNovelDownloadWorker.KEY_START_URL to startUrl,
                WebNovelDownloadWorker.KEY_MAX_CHAPTERS to maxChapters,
                WebNovelDownloadWorker.KEY_TITLE to title,
                WebNovelDownloadWorker.KEY_USER_AGENT to userAgent
            )
        )
    }

    /**
     * Đưa tác vụ tải thêm chương cho truyện web đã lưu; mỗi sách chỉ chạy một tác vụ tại một thời điểm.
     *
     * @param bookId ID sách đã lưu từ web.
     * @param maxChapters Số chương mới tối đa.
     * @return ID tác vụ WorkManager (tác vụ đang chạy nếu đã có).
     */
    fun enqueueAppend(bookId: String, maxChapters: Int): UUID = enqueueRequest(
        uniqueName = "${WebNovelDownloadWorker.TAG}_append_$bookId",
        input = workDataOf(
            WebNovelDownloadWorker.KEY_APPEND_BOOK_ID to bookId,
            WebNovelDownloadWorker.KEY_MAX_CHAPTERS to maxChapters
        )
    )

    /**
     * Theo dõi trạng thái một tác vụ.
     *
     * @param workId ID tác vụ.
     * @return Luồng trạng thái; kết thúc ở [WebNovelDownloadState.Succeeded] hoặc [WebNovelDownloadState.Failed].
     */
    fun observe(workId: UUID): Flow<WebNovelDownloadState> =
        WorkManager.getInstance(context).getWorkInfoByIdFlow(workId).map { info ->
            when (info?.state) {
                WorkInfo.State.SUCCEEDED -> info.outputData.getString(WebNovelDownloadWorker.KEY_BOOK_ID)
                    ?.let { WebNovelDownloadState.Succeeded(it, info.outputData.getInt(WebNovelDownloadWorker.KEY_CHAPTER_COUNT, 0)) }
                    ?: WebNovelDownloadState.Failed
                WorkInfo.State.FAILED, WorkInfo.State.CANCELLED, null -> WebNovelDownloadState.Failed
                else -> WebNovelDownloadState.Running(
                    downloaded = info.progress.getInt(WebNovelDownloadWorker.KEY_CHAPTER_COUNT, 0),
                    max = info.progress.getInt(WebNovelDownloadWorker.KEY_MAX_CHAPTERS, 0)
                )
            }
        }

    /**
     * Hủy tác vụ tải.
     *
     * @param workId ID tác vụ.
     */
    fun cancel(workId: UUID) {
        WorkManager.getInstance(context).cancelWorkById(workId)
    }

    /**
     * Tạo và đưa request vào hàng đợi duy nhất; nếu đã có tác vụ cùng tên đang chạy thì trả về ID của tác vụ đó.
     *
     * @param uniqueName Tên duy nhất.
     * @param input Dữ liệu đầu vào worker.
     * @return ID tác vụ.
     */
    private fun enqueueRequest(uniqueName: String, input: androidx.work.Data): UUID {
        val workManager = WorkManager.getInstance(context)
        workManager.getWorkInfosForUniqueWork(uniqueName).get()
            .firstOrNull { !it.state.isFinished }
            ?.let { return it.id }
        val request = OneTimeWorkRequestBuilder<WebNovelDownloadWorker>()
            .setInputData(input)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .addTag(WebNovelDownloadWorker.TAG)
            .build()
        workManager.enqueueUniqueWork(uniqueName, ExistingWorkPolicy.KEEP, request)
        return request.id
    }
}
