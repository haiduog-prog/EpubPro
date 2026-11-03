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

/** Trạng thái tác vụ tải truyện tranh về máy, đã tách khỏi kiểu của WorkManager cho tầng UI. */
sealed interface OnlineComicDownloadState {
    /**
     * Đang chạy hoặc đang chờ mạng.
     *
     * @property downloaded Số chương đã tải.
     * @property max Số chương tối đa.
     */
    data class Running(val downloaded: Int, val max: Int) : OnlineComicDownloadState

    /**
     * Hoàn tất.
     *
     * @property bookId ID cuốn CBZ trên Kệ sách.
     * @property newChapters Số chương mới đã lưu (0 nếu không có chương mới).
     * @property interrupted true khi chỉ lưu được một phần do lỗi trang nguồn hoặc chạm giới hạn dung lượng.
     */
    data class Succeeded(val bookId: String, val newChapters: Int, val interrupted: Boolean) : OnlineComicDownloadState

    /** Thất bại hoặc bị hủy. */
    data object Failed : OnlineComicDownloadState
}

/**
 * Lập lịch tải truyện tranh online về máy thành CBZ bằng WorkManager.
 */
@Singleton
class OnlineComicDownloadScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /**
     * Đưa tác vụ tải vào hàng đợi; mỗi bộ truyện chỉ chạy một tác vụ tại một thời điểm.
     * Phải gọi ngoài main thread vì có truy vấn đồng bộ hàng đợi WorkManager.
     *
     * @param comicKey Khóa bộ truyện, dùng đặt tên tác vụ duy nhất.
     * @param startUrl URL chương bắt đầu (http/https).
     * @param maxChapters Số chương tối đa cần tải.
     * @param appendBookId ID cuốn CBZ cần nối thêm, null để tạo cuốn mới.
     * @param continueFromLast true để bỏ qua [startUrl] và đi tiếp từ chương sau chương cuối đã tải của cuốn cũ.
     * @return ID tác vụ WorkManager (tác vụ đang chạy nếu đã có).
     * @throws IllegalArgumentException Nếu URL không phải http/https.
     */
    fun enqueue(
        comicKey: String,
        startUrl: String,
        maxChapters: Int,
        appendBookId: String?,
        continueFromLast: Boolean
    ): UUID {
        require(startUrl.startsWith("http://") || startUrl.startsWith("https://")) { "URL không hợp lệ" }
        findActive(comicKey)?.let { return it }
        val request = OneTimeWorkRequestBuilder<OnlineComicDownloadWorker>()
            .setInputData(
                workDataOf(
                    OnlineComicDownloadWorker.KEY_START_URL to startUrl,
                    OnlineComicDownloadWorker.KEY_MAX_CHAPTERS to maxChapters,
                    OnlineComicDownloadWorker.KEY_APPEND_BOOK_ID to appendBookId,
                    OnlineComicDownloadWorker.KEY_CONTINUE to continueFromLast
                )
            )
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .addTag(OnlineComicDownloadWorker.TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(uniqueName(comicKey), ExistingWorkPolicy.KEEP, request)
        return request.id
    }

    /**
     * Tìm tác vụ tải chưa kết thúc của một bộ truyện, để màn hình mở lại tiếp tục theo dõi tác vụ đang chạy nền.
     * Phải gọi ngoài main thread vì truy vấn đồng bộ hàng đợi WorkManager.
     *
     * @param comicKey Khóa bộ truyện.
     * @return ID tác vụ đang chờ hoặc đang chạy, null nếu không có.
     */
    fun findActive(comicKey: String): UUID? =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(uniqueName(comicKey)).get()
            .firstOrNull { !it.state.isFinished }
            ?.id

    /**
     * Tên tác vụ duy nhất theo bộ truyện.
     *
     * @param comicKey Khóa bộ truyện.
     * @return Tên dùng cho unique work của WorkManager.
     */
    private fun uniqueName(comicKey: String): String = "${OnlineComicDownloadWorker.TAG}_$comicKey"

    /**
     * Theo dõi trạng thái một tác vụ.
     *
     * @param workId ID tác vụ.
     * @return Luồng trạng thái; kết thúc ở [OnlineComicDownloadState.Succeeded] hoặc [OnlineComicDownloadState.Failed].
     */
    fun observe(workId: UUID): Flow<OnlineComicDownloadState> =
        WorkManager.getInstance(context).getWorkInfoByIdFlow(workId).map { info ->
            when (info?.state) {
                WorkInfo.State.SUCCEEDED -> info.outputData.getString(OnlineComicDownloadWorker.KEY_BOOK_ID)
                    ?.let {
                        OnlineComicDownloadState.Succeeded(
                            bookId = it,
                            newChapters = info.outputData.getInt(OnlineComicDownloadWorker.KEY_CHAPTER_COUNT, 0),
                            interrupted = info.outputData.getBoolean(OnlineComicDownloadWorker.KEY_INTERRUPTED, false)
                        )
                    }
                    ?: OnlineComicDownloadState.Failed
                WorkInfo.State.FAILED, WorkInfo.State.CANCELLED, null -> OnlineComicDownloadState.Failed
                else -> OnlineComicDownloadState.Running(
                    downloaded = info.progress.getInt(OnlineComicDownloadWorker.KEY_CHAPTER_COUNT, 0),
                    max = info.progress.getInt(OnlineComicDownloadWorker.KEY_MAX_CHAPTERS, 0)
                )
            }
        }
}
