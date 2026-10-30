package com.epubpro.feature.browser

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.epubpro.core.designsystem.R
import com.epubpro.core.storage.WebNovelSourceStore
import com.epubpro.core.storage.worker.WebNovelDownloadScheduler
import com.epubpro.core.storage.worker.WebNovelDownloadState
import com.epubpro.domain.repository.BookRepository
import java.util.UUID
import com.epubpro.core.webnovel.ArticleExtractor
import com.epubpro.core.webnovel.WebArticle
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Trạng thái trình duyệt.
 *
 * @property addressText Nội dung ô địa chỉ đang sửa.
 * @property loadRequest URL cần WebView nạp; tăng [loadRequestId] để nạp lại cùng URL.
 * @property loadRequestId Bộ đếm yêu cầu nạp.
 * @property currentUrl URL trang đang hiển thị.
 * @property pageTitle Tiêu đề trang.
 * @property progress Tiến độ tải trang 0..100.
 * @property readerMode Đang bật chế độ đọc.
 * @property isExtracting Đang bóc tách nội dung.
 * @property article Nội dung đã bóc tách của trang hiện tại.
 * @property extractFailed Trang hiện tại không bóc tách được.
 * @property showSaveDialog Đang hiện hộp thoại lưu thành truyện.
 * @property readerPreparation Tiến độ chuẩn bị truyện để mở bằng Reader, null nếu không chuẩn bị.
 * @property showPreparationDialog Đang hiện hộp thoại tiến độ (ẩn khi người dùng chọn chạy nền).
 */
data class BrowserUiState(
    val addressText: String = "",
    val loadRequest: String? = null,
    val loadRequestId: Int = 0,
    val currentUrl: String = "",
    val pageTitle: String = "",
    val progress: Int = 0,
    val readerMode: Boolean = false,
    val isExtracting: Boolean = false,
    val article: WebArticle? = null,
    val extractFailed: Boolean = false,
    val showSaveDialog: Boolean = false,
    val readerPreparation: WebNovelDownloadState.Running? = null,
    val showPreparationDialog: Boolean = false
)

/**
 * ViewModel trình duyệt tích hợp: điều khiển địa chỉ, chế độ đọc và lưu truyện thành EPUB.
 */
@HiltViewModel
class BrowserViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val downloadScheduler: WebNovelDownloadScheduler,
    private val sourceStore: WebNovelSourceStore,
    private val bookRepository: BookRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(BrowserUiState())
    val uiState: StateFlow<BrowserUiState> = _uiState.asStateFlow()

    private val _messages = Channel<Int>(Channel.BUFFERED)

    /** Thông báo một lần (string resource) cho Snackbar. */
    val messages: Flow<Int> = _messages.receiveAsFlow()

    private val _openBookEvents = Channel<String>(Channel.BUFFERED)

    /** Yêu cầu mở sách trong Reader EPUB (ID sách) sau khi chuẩn bị xong. */
    val openBookEvents: Flow<String> = _openBookEvents.receiveAsFlow()

    private var extractJob: Job? = null
    private var preparationJob: Job? = null
    private var preparationWorkId: UUID? = null

    init {
        savedStateHandle.get<String>(ARG_URL)
            ?.takeIf { it.isNotBlank() }
            ?.let { openAddress(it) }
    }

    /**
     * Cập nhật ô địa chỉ khi người dùng gõ.
     *
     * @param text Nội dung mới.
     */
    fun onAddressChanged(text: String) {
        _uiState.update { it.copy(addressText = text) }
    }

    /**
     * Mở địa chỉ hoặc tìm kiếm từ khóa.
     *
     * @param input Nội dung ô địa chỉ.
     */
    fun openAddress(input: String) {
        val url = resolveBrowserAddress(input) ?: return
        _uiState.update {
            it.copy(addressText = url, loadRequest = url, loadRequestId = it.loadRequestId + 1)
        }
    }

    /**
     * Ghi nhận WebView bắt đầu nạp trang mới; xóa nội dung bóc tách cũ.
     *
     * @param url URL đang nạp.
     */
    fun onPageStarted(url: String) {
        extractJob?.cancel()
        _uiState.update {
            it.copy(
                currentUrl = url,
                addressText = url,
                article = null,
                extractFailed = false,
                isExtracting = it.readerMode
            )
        }
    }

    /**
     * Cập nhật tiến độ và tiêu đề trang.
     *
     * @param progress Tiến độ 0..100.
     * @param title Tiêu đề trang, null nếu không đổi.
     */
    fun onPageProgress(progress: Int, title: String?) {
        _uiState.update { it.copy(progress = progress, pageTitle = title ?: it.pageTitle) }
    }

    /**
     * Bật/tắt chế độ đọc. Khi bật, màn hình phải gửi HTML hiện tại qua [onPageHtml].
     *
     * @return true nếu chế độ đọc vừa được bật và cần lấy HTML trang.
     */
    fun toggleReaderMode(): Boolean {
        val enable = !_uiState.value.readerMode
        _uiState.update { it.copy(readerMode = enable, isExtracting = enable && it.article == null) }
        return enable && _uiState.value.article == null
    }

    /**
     * Bóc tách nội dung từ HTML trang hiện tại trên luồng nền.
     *
     * @param html HTML lấy từ WebView.
     * @param url URL của HTML.
     */
    fun onPageHtml(html: String, url: String) {
        extractJob?.cancel()
        _uiState.update { it.copy(isExtracting = true, extractFailed = false) }
        extractJob = viewModelScope.launch {
            val article = try {
                withContext(Dispatchers.Default) { ArticleExtractor.extract(html, url) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
            if (_uiState.value.currentUrl.isNotEmpty() && _uiState.value.currentUrl != url) return@launch
            _uiState.update { it.copy(isExtracting = false, article = article, extractFailed = article == null) }
        }
    }

    /**
     * Mở trang hiện tại bằng Reader EPUB: nếu đã lưu truyện từ trang này thì mở luôn, nếu chưa thì tải
     * nhanh một số chương đầu thành EPUB rồi tự mở khi xong.
     *
     * @param userAgent User-Agent của WebView, gửi kèm cookie để vượt lớp chống bot như trình duyệt.
     */
    fun openInReader(userAgent: String?) {
        val url = _uiState.value.currentUrl
        if (!isAllowedBrowserUrl(url)) {
            sendMessage(R.string.browser_save_invalid_url)
            return
        }
        if (preparationJob?.isActive == true) {
            _uiState.update { it.copy(showPreparationDialog = true) }
            return
        }
        preparationJob = viewModelScope.launch {
            val savedBookId = withContext(Dispatchers.IO) {
                sourceStore.findBookByStartUrl(url)?.takeIf { bookRepository.getBookById(it) != null }
            }
            if (savedBookId != null) {
                _openBookEvents.send(savedBookId)
                return@launch
            }
            val title = _uiState.value.article?.novelTitle
            val workId = try {
                withContext(Dispatchers.IO) {
                    downloadScheduler.enqueue(url, READER_PREFETCH_CHAPTERS, title, userAgent)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                sendMessage(R.string.web_novel_download_failed)
                return@launch
            }
            preparationWorkId = workId
            _uiState.update {
                it.copy(
                    readerPreparation = WebNovelDownloadState.Running(0, READER_PREFETCH_CHAPTERS),
                    showPreparationDialog = true
                )
            }
            downloadScheduler.observe(workId).collect { state ->
                when (state) {
                    is WebNovelDownloadState.Running -> _uiState.update { it.copy(readerPreparation = state) }
                    is WebNovelDownloadState.Succeeded -> {
                        finishPreparation()
                        _openBookEvents.send(state.bookId)
                        cancel()
                    }
                    WebNovelDownloadState.Failed -> {
                        finishPreparation()
                        sendMessage(R.string.web_novel_download_failed)
                        cancel()
                    }
                }
            }
        }
    }

    /** Ẩn hộp thoại tiến độ, tác vụ tiếp tục chạy nền và vẫn tự mở Reader khi xong nếu còn ở trình duyệt. */
    fun continuePreparationInBackground() {
        _uiState.update { it.copy(showPreparationDialog = false) }
    }

    /** Hủy việc chuẩn bị truyện để mở bằng Reader. */
    fun cancelPreparation() {
        preparationWorkId?.let(downloadScheduler::cancel)
        preparationJob?.cancel()
        finishPreparation()
    }

    /** Xóa trạng thái chuẩn bị. */
    private fun finishPreparation() {
        preparationWorkId = null
        _uiState.update { it.copy(readerPreparation = null, showPreparationDialog = false) }
    }

    /**
     * Mở/đóng hộp thoại lưu thành truyện.
     *
     * @param visible true để mở.
     */
    fun setSaveDialogVisible(visible: Boolean) {
        _uiState.update { it.copy(showSaveDialog = visible) }
    }

    /**
     * Đưa tác vụ tải truyện vào hàng đợi, bắt đầu từ trang hiện tại.
     *
     * @param title Tên truyện người dùng nhập (có thể rỗng để tự đoán).
     * @param maxChapters Số chương tối đa.
     * @param userAgent User-Agent của WebView.
     */
    fun saveAsNovel(title: String, maxChapters: Int, userAgent: String?) {
        val url = _uiState.value.currentUrl
        _uiState.update { it.copy(showSaveDialog = false) }
        if (!isAllowedBrowserUrl(url)) {
            sendMessage(R.string.browser_save_invalid_url)
            return
        }
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    downloadScheduler.enqueue(url, maxChapters, title.trim().ifEmpty { null }, userAgent)
                }
            }
            sendMessage(if (result.isSuccess) R.string.browser_save_started else R.string.web_novel_download_failed)
        }
    }

    /**
     * Gửi thông báo một lần.
     *
     * @param messageRes String resource.
     */
    private fun sendMessage(@StringRes messageRes: Int) {
        _messages.trySend(messageRes)
    }

    companion object {
        const val ARG_URL = "url"
        val CHAPTER_COUNT_OPTIONS = listOf(10, 50, 200, 1000)

        /** Số chương tải trước khi mở bằng Reader; đọc gần hết sẽ tự tải thêm. */
        const val READER_PREFETCH_CHAPTERS = 20
    }
}
