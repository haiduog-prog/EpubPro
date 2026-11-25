package com.epubpro.app

import android.content.Context
import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.epubpro.core.bookconverter.BookConversionException
import com.epubpro.core.designsystem.R
import com.epubpro.core.reader.tts.TtsOpenBookRequest
import com.epubpro.core.storage.BookFileTooLargeException
import com.epubpro.core.storage.worker.LocalBookImportScheduler
import com.epubpro.core.storage.worker.LocalBookImportWorker
import com.epubpro.domain.model.BookSourceFormat
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Thông báo ngắn (Toast) mà [MainActivity] cần hiển thị cho người dùng.
 */
sealed interface MainIntentNotice {
    /**
     * Thông báo lấy từ string resource.
     *
     * @property resId ID chuỗi trong `strings.xml`.
     * @property args Tham số định dạng cho chuỗi.
     * @property isLong `true` để hiển thị lâu hơn (dùng cho lỗi).
     */
    data class Res(
        @StringRes val resId: Int,
        val args: List<Any> = emptyList(),
        val isLong: Boolean = false
    ) : MainIntentNotice

    /**
     * Thông báo đã được Worker bản địa hóa sẵn (ví dụ lý do nhập sách thất bại).
     *
     * @property message Nội dung đã sẵn sàng hiển thị.
     */
    data class Text(val message: String) : MainIntentNotice
}

/**
 * ViewModel nhận yêu cầu điều hướng từ Intent (widget, notification, app khác) và chuyển cho Compose.
 *
 * Việc nạp sách từ app ngoài chạy trong [viewModelScope] để không bị hủy khi xoay màn hình hay đổi
 * cấu hình, nên kết quả (mở sách vừa nạp hoặc báo lỗi) vẫn tới được người dùng.
 */
@HiltViewModel
class MainIntentViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val localBookImportScheduler: LocalBookImportScheduler
) : ViewModel() {
    private val requestChannel = Channel<TtsOpenBookRequest>(Channel.BUFFERED)
    private val libraryChannel = Channel<Unit>(Channel.BUFFERED)
    private val comicChannel = Channel<String>(Channel.BUFFERED)
    private val browserChannel = Channel<String>(Channel.BUFFERED)
    private val noticeChannel = Channel<MainIntentNotice>(Channel.BUFFERED)
    val openBookRequests: Flow<TtsOpenBookRequest> = requestChannel.receiveAsFlow()
    val openLibraryRequests: Flow<Unit> = libraryChannel.receiveAsFlow()
    val openComicRequests: Flow<String> = comicChannel.receiveAsFlow()
    val openBrowserRequests: Flow<String> = browserChannel.receiveAsFlow()

    /** Luồng thông báo một lần; Activity chỉ thu khi đang hiển thị nên thông báo không bị mất khi ở nền. */
    val notices: Flow<MainIntentNotice> = noticeChannel.receiveAsFlow()

    /**
     * Yêu cầu mở Reader cho một cuốn sách.
     *
     * @param request Thông tin sách, chương và cờ mở trình phát TTS.
     */
    fun dispatch(request: TtsOpenBookRequest) {
        requestChannel.trySend(request)
    }

    /** Yêu cầu quay về màn hình Kệ sách. */
    fun dispatchOpenLibrary() {
        libraryChannel.trySend(Unit)
    }

    /**
     * Yêu cầu mở Reader truyện tranh cho một cuốn CBZ (sau import từ app ngoài hoặc tự mở sách gần nhất).
     *
     * @param bookId ID sách trong thư viện.
     */
    fun dispatchOpenComic(bookId: String) {
        comicChannel.trySend(bookId)
    }

    /**
     * Yêu cầu mở trình duyệt tích hợp tại một URL được chia sẻ từ ứng dụng khác.
     *
     * @param url URL http(s) đã kiểm tra.
     */
    fun dispatchOpenBrowser(url: String) {
        browserChannel.trySend(url)
    }

    /**
     * Về Kệ sách, báo đã bắt đầu nạp, rồi lập lịch nạp file và chờ kết quả trong [viewModelScope].
     *
     * Khi nạp xong sẽ tự mở sách (hoặc truyện tranh CBZ); khi lỗi sẽ phát thông báo phù hợp.
     * URI chỉ được đọc trong `enqueue` (sao chép sang bộ nhớ app) trước khi quyền tạm thời hết hạn.
     *
     * @param uri URI file sách đã được [com.epubpro.app.intent.ExternalBookImportHandler] thẩm định.
     * @param displayName Tên hiển thị của file, hoặc null nếu không xác định được.
     */
    fun importExternalBook(uri: Uri, displayName: String?) {
        dispatchOpenLibrary()
        val bookTitle = displayName ?: appContext.getString(R.string.nav_library)
        noticeChannel.trySend(MainIntentNotice.Res(R.string.import_external_started, listOf(bookTitle)))

        viewModelScope.launch {
            try {
                val workId = localBookImportScheduler.enqueue(uri, displayName) ?: return@launch
                val workInfo = WorkManager.getInstance(appContext)
                    .getWorkInfoByIdFlow(workId)
                    .first { it?.state?.isFinished == true }
                when (workInfo?.state) {
                    WorkInfo.State.SUCCEEDED -> openImportedBook(workInfo)
                    WorkInfo.State.FAILED -> {
                        val failure = workInfo.outputData
                            .getString(LocalBookImportWorker.KEY_ERROR_MESSAGE)
                            ?.takeIf { it.isNotBlank() }
                        noticeChannel.trySend(
                            failure?.let(MainIntentNotice::Text)
                                ?: MainIntentNotice.Res(R.string.library_import_failed, isLong = true)
                        )
                    }
                    else -> Unit
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                noticeChannel.trySend(importErrorNotice(error))
            }
        }
    }

    /**
     * Mở sách vừa nạp thành công dựa trên dữ liệu đầu ra của Worker.
     *
     * @param workInfo Thông tin tác vụ đã thành công.
     */
    private fun openImportedBook(workInfo: WorkInfo) {
        val bookId = workInfo.outputData
            .getString(LocalBookImportWorker.KEY_BOOK_ID)
            ?.takeIf { it.isNotBlank() }
            ?: return
        val importedFormat = workInfo.outputData.getString(LocalBookImportWorker.KEY_SOURCE_FORMAT)
        if (importedFormat == BookSourceFormat.CBZ.name) {
            dispatchOpenComic(bookId)
            return
        }
        dispatch(TtsOpenBookRequest(bookId = bookId, chapterIndex = 0, openTtsPlayer = false))
    }

    /**
     * Chuyển lỗi lập lịch nạp sách thành thông báo cho người dùng.
     *
     * @param error Lỗi ném ra khi sao chép hoặc lập lịch nạp file.
     * @return Thông báo tương ứng với loại lỗi.
     */
    private fun importErrorNotice(error: Exception): MainIntentNotice = when (error) {
        is BookFileTooLargeException ->
            MainIntentNotice.Res(R.string.book_import_error_too_large, listOf(error.limitMiB), isLong = true)
        is BookConversionException ->
            MainIntentNotice.Res(R.string.library_import_book_failed, isLong = true)
        else -> MainIntentNotice.Res(R.string.library_import_failed, isLong = true)
    }
}
