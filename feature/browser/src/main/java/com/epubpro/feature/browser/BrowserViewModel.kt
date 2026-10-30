package com.epubpro.feature.browser

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.epubpro.core.designsystem.R
import com.epubpro.core.storage.WebNovelSourceStore
import com.epubpro.core.storage.VisibleWebChapterSaver
import com.epubpro.core.storage.VisibleChapterResult
import com.epubpro.core.storage.worker.WebNovelDownloadScheduler
import com.epubpro.core.storage.worker.WebNovelDownloadState
import com.epubpro.domain.repository.BookRepository
import java.util.UUID
import com.epubpro.core.webnovel.ArticleExtractor
import com.epubpro.core.webnovel.WebArticle
import com.epubpro.core.webnovel.NovelChapterLink
import com.epubpro.core.webnovel.NovelLandingDetector
import com.epubpro.domain.model.Book
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
 * @property preparationFailure Kết quả tải bị gián đoạn và trang cần kiểm tra.
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
    val showPreparationDialog: Boolean = false,
    val preparationFailure: BrowserPreparationFailure? = null,
    val savingVisibleChapter: Boolean = false,
    val visibleChapterChoices: List<Book>? = null,
    val chapterChoices: List<NovelChapterLink>? = null
)

/** Yêu cầu tải truyện đang chờ chọn chương bắt đầu. */
private data class PendingChapterRequest(val userAgent: String?, val title: String?, val count: Int?)

/**
 * Thông tin trang bị lỗi và sách đã lưu một phần, nếu có.
 *
 * @property url Trang nguồn cần kiểm tra.
 * @property bookId ID sách đã lưu hoặc null nếu không có chương nào.
 * @property chapters Số chương đã lưu.
 */
data class BrowserPreparationFailure(val url: String, val bookId: String?, val chapters: Int)

/**
 * ViewModel trình duyệt tích hợp: điều khiển địa chỉ, chế độ đọc và lưu truyện thành EPUB.
 */
@HiltViewModel
class BrowserViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val downloadScheduler: WebNovelDownloadScheduler,
    private val sourceStore: WebNovelSourceStore,
    private val bookRepository: BookRepository,
    private val visibleSaver: VisibleWebChapterSaver
) : ViewModel() {

    private val _uiState = MutableStateFlow(BrowserUiState())
    val uiState: StateFlow<BrowserUiState> = _uiState.asStateFlow()

    private val _messages = Channel<Int>(Channel.BUFFERED)

    /** Thông báo một lần (string resource) cho Snackbar. */
    val messages: Flow<Int> = _messages.receiveAsFlow()

    private val _openBookEvents = Channel<String>(Channel.BUFFERED)

    /** Yêu cầu mở sách trong Reader EPUB (ID sách) sau khi chuẩn bị xong. */
    val openBookEvents: Flow<String> = _openBookEvents.receiveAsFlow()

    private val _savedChapterEvents = Channel<VisibleChapterResult.Saved>(Channel.BUFFERED)
    private val _openComicChapterEvents = Channel<String>(Channel.BUFFERED)
    /** Yêu cầu mở chương truyện tranh online (URL chương). */
    val openComicChapterEvents: Flow<String> = _openComicChapterEvents.receiveAsFlow()
    /** Thông báo chương đã lưu để người dùng chọn mở trong Reader. */
    val savedChapterEvents: Flow<VisibleChapterResult.Saved> = _savedChapterEvents.receiveAsFlow()
    private var pendingArticle: WebArticle? = null
    private var pendingUserAgent: String? = null
    private var pendingChapterRequest: PendingChapterRequest? = null

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
     * Yêu cầu mở chương truyện tranh đang duyệt trong màn hình đọc online.
     *
     * @param url URL của trang chương hiện tại.
     */
    fun openComicReader(url: String) {
        if (isAllowedBrowserUrl(url)) {
            _openComicChapterEvents.trySend(url)
        }
    }

    /**
     * Ghi nhận WebView bắt đầu nạp trang mới; xóa nội dung bóc tách cũ.
     *
     * @param url URL đang nạp.
     */
    fun onPageStarted(url: String) {
        extractJob?.cancel()
        pendingArticle = null
        pendingChapterRequest = null
        _uiState.update {
            it.copy(
                currentUrl = url,
                addressText = url,
                progress = 0,
                article = null,
                extractFailed = false,
                isExtracting = it.readerMode,
                visibleChapterChoices = null,
                chapterChoices = null
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
     * Lưu nội dung DOM hiện tại sau khi người dùng chủ động kiểm tra trang.
     *
     * @param html HTML lấy từ WebView.
     * @param url URL của trang tương ứng.
     * @param userAgent User-Agent của WebView.
     */
    fun saveVisibleHtml(html: String, url: String, userAgent: String?) {
        if (_uiState.value.savingVisibleChapter || _uiState.value.currentUrl != url) return
        _uiState.update { it.copy(savingVisibleChapter = true) }
        viewModelScope.launch {
            try {
                val article = withContext(Dispatchers.Default) { ArticleExtractor.extract(html, url) }
                if (_uiState.value.currentUrl != url) return@launch
                if (article == null) { sendMessage(R.string.browser_visible_not_ready); return@launch }
                pendingArticle = article
                pendingUserAgent = userAgent
                handleVisibleResult(visibleSaver.save(article, userAgent))
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                sendMessage(R.string.web_novel_download_failed)
            } finally { _uiState.update { it.copy(savingVisibleChapter = false) } }
        }
    }

    /** Thông báo khi người dùng yêu cầu lưu trước khi trang nạp xong. */
    fun reportVisibleNotReady() { sendMessage(R.string.browser_visible_not_ready) }

    /**
     * Lưu chương đang chờ vào sách đã chọn hoặc tạo sách riêng.
     *
     * @param bookId ID sách đích, null để tạo sách mới.
     */
    fun chooseVisibleChapterBook(bookId: String?) {
        val article = pendingArticle ?: return
        if (_uiState.value.savingVisibleChapter || article.url != _uiState.value.currentUrl) return
        _uiState.update { it.copy(savingVisibleChapter = true, visibleChapterChoices = null) }
        viewModelScope.launch {
            try {
                handleVisibleResult(visibleSaver.save(article, pendingUserAgent, bookId, createNew = bookId == null))
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { sendMessage(R.string.web_novel_download_failed) }
            finally { _uiState.update { it.copy(savingVisibleChapter = false) } }
        }
    }

    /** Đóng danh sách chọn sách cho chương hiện tại. */
    fun dismissVisibleChapterChoices() { _uiState.update { it.copy(visibleChapterChoices = null) } }

    /**
     * Xác định trang chương bắt đầu từ DOM trước khi thực hiện yêu cầu tải.
     *
     * @param html HTML của trang đang mở.
     * @param url URL trang hiện tại.
     * @param userAgent User-Agent của WebView.
     * @param title Tên sách người dùng nhập khi lưu, null khi mở Reader.
     * @param count Số chương cần tải, null khi mở Reader.
     */
    fun resolveChapterStart(html: String, url: String, userAgent: String?, title: String? = null, count: Int? = null) {
        if (_uiState.value.currentUrl != url) return
        viewModelScope.launch {
            val landing = withContext(Dispatchers.Default) { NovelLandingDetector.detect(html, url) }
            if (_uiState.value.currentUrl != url) return@launch
            val request = PendingChapterRequest(userAgent, title, count)
            val first = landing?.firstChapter
            if (landing == null) executeChapterRequest(request, url)
            else if (first != null) executeChapterRequest(request, first.url)
            else {
                pendingChapterRequest = request
                _uiState.update { it.copy(chapterChoices = landing.chapters) }
            }
        }
    }

    /**
     * Tiếp tục tải từ chương do người dùng chọn trong danh sách.
     *
     * @param url URL chương được chọn.
     */
    fun selectStartChapter(url: String) {
        val request = pendingChapterRequest ?: return
        pendingChapterRequest = null
        _uiState.update { it.copy(chapterChoices = null) }
        executeChapterRequest(request, url)
    }

    /** Đóng danh sách chọn chương bắt đầu. */
    fun dismissChapterChoices() {
        pendingChapterRequest = null
        _uiState.update { it.copy(chapterChoices = null) }
    }

    /**
     * Thực thi yêu cầu lưu hoặc mở Reader sau khi xác định URL chương.
     *
     * @param request Thông số thao tác đã chọn.
     * @param url URL chương bắt đầu.
     */
    private fun executeChapterRequest(request: PendingChapterRequest, url: String) {
        if (request.count == null) openInReader(request.userAgent, url)
        else saveAsNovel(request.title.orEmpty(), request.count, request.userAgent, url)
    }

    /**
     * Chuyển kết quả lưu thành trạng thái hoặc thông báo giao diện.
     *
     * @param result Kết quả lưu chương.
     */
    private suspend fun handleVisibleResult(result: VisibleChapterResult) {
        when (result) {
            is VisibleChapterResult.Saved -> _savedChapterEvents.send(result)
            is VisibleChapterResult.ChooseBook -> _uiState.update { it.copy(visibleChapterChoices = result.books) }
            VisibleChapterResult.NotAdjacent -> sendMessage(R.string.browser_visible_not_adjacent)
            VisibleChapterResult.NotReady -> sendMessage(R.string.browser_visible_not_ready)
        }
    }

    /**
     * Mở trang hiện tại bằng Reader EPUB: nếu đã lưu truyện từ trang này thì mở luôn, nếu chưa thì tải
     * nhanh một số chương đầu thành EPUB rồi tự mở khi xong.
     *
     * @param userAgent User-Agent của WebView, gửi kèm cookie để vượt lớp chống bot như trình duyệt.
     * @param startUrl URL chương bắt đầu đã xác định.
     */
    fun openInReader(userAgent: String?, startUrl: String = _uiState.value.currentUrl) {
        val url = startUrl
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
                        val failedUrl = state.failedUrl
                        if (state.interrupted && failedUrl != null) {
                            _uiState.update { it.copy(preparationFailure = BrowserPreparationFailure(failedUrl, state.bookId, state.newChapters)) }
                        } else _openBookEvents.send(state.bookId)
                        cancel()
                    }
                    is WebNovelDownloadState.FailedAt -> {
                        finishPreparation()
                        _uiState.update { it.copy(preparationFailure = BrowserPreparationFailure(state.url, null, 0)) }
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

    /** Đóng thông báo trang lỗi sau khi người dùng chọn thao tác. */
    fun dismissPreparationFailure() {
        _uiState.update { it.copy(preparationFailure = null) }
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
     * @param startUrl URL chương bắt đầu đã xác định.
     */
    fun saveAsNovel(title: String, maxChapters: Int, userAgent: String?, startUrl: String = _uiState.value.currentUrl) {
        val url = startUrl
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
