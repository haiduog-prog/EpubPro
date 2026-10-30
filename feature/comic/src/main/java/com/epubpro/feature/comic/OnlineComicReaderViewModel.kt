package com.epubpro.feature.comic

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.epubpro.core.comic.ComicChapterInfo
import com.epubpro.core.comic.ComicChapterParseException
import com.epubpro.core.comic.ComicPageImage
import com.epubpro.core.comic.ComicProtectionException
import com.epubpro.core.comic.OnlineComicFetcher
import com.epubpro.core.comic.OnlineComicImageLoader
import com.epubpro.core.designsystem.R
import com.epubpro.core.storage.OnlineComicProgressStore
import com.epubpro.core.storage.ReaderPreferencesManager
import com.epubpro.domain.model.Book
import com.epubpro.domain.model.BookSourceFormat
import com.epubpro.domain.model.ComicReadingMode
import com.epubpro.domain.model.ReadingProgress
import com.epubpro.domain.repository.BookRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel điều khiển trình đọc truyện tranh trực tuyến.
 *
 * Tự động tải thông tin chương từ trang nguồn bằng [OnlineComicFetcher], quản lý nạp ảnh
 * theo trang và giải mã có giới hạn đồng thời bằng [OnlineComicImageLoader], đồng thời
 * lưu và khôi phục tiến độ đọc độc lập qua [OnlineComicProgressStore].
 */
@HiltViewModel
class OnlineComicReaderViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val fetcher: OnlineComicFetcher,
    private val imageLoader: OnlineComicImageLoader,
    private val progressStore: OnlineComicProgressStore,
    private val bookRepository: BookRepository,
    readerPreferences: ReaderPreferencesManager
) : ViewModel() {

    private val initialChapterUrl: String = checkNotNull(savedStateHandle[ARG_CHAPTER_URL]) {
        "Thiếu tham số bắt buộc chapterUrl khi khởi tạo OnlineComicReaderViewModel"
    }
    private val resumeProgressOnOpen: Boolean = savedStateHandle[ARG_RESUME_PROGRESS] ?: false

    private val _uiState = MutableStateFlow(
        readerPreferences.getSettings().let { settings ->
            OnlineComicReaderUiState(
                volumeKeyNavigation = settings.enableVolumeKeyNavigation,
                keepScreenOn = settings.keepScreenOn
            )
        }
    )
    val uiState: StateFlow<OnlineComicReaderUiState> = _uiState.asStateFlow()

    private var progressSaveJob: Job? = null
    private var loadChapterJob: Job? = null
    private var requestedChapterUrl: String = initialChapterUrl

    init {
        val savedProgress = if (resumeProgressOnOpen) progressStore.getProgress(initialChapterUrl) else null
        if (savedProgress != null) {
            val targetMode = savedProgress.readingMode ?: ComicReadingMode.WEBTOON
            _uiState.update { it.copy(mode = targetMode) }
            loadChapter(savedProgress.chapterUrl, savedProgress.pageIndex)
        } else {
            loadChapter(initialChapterUrl, 0)
        }
    }

    /**
     * Tải và hiển thị nội dung của một chương truyện tranh.
     *
     * Nếu đang có chương hiện tại và việc tải chương mới thất bại, chương hiện tại sẽ được giữ nguyên
     * để không làm gián đoạn trải nghiệm của người dùng.
     *
     * @param url URL tuyệt đối của chương cần tải.
     * @param targetPage Vị trí trang ban đầu cần hiển thị sau khi tải thành công (0-based).
     */
    fun loadChapter(url: String, targetPage: Int = 0) {
        requestedChapterUrl = url
        loadChapterJob?.cancel()
        val hasExistingChapter = _uiState.value.chapterInfo != null

        if (hasExistingChapter) {
            _uiState.update { it.copy(isChangingChapter = true) }
        } else {
            _uiState.update { it.copy(isLoading = true, errorRes = null, isProtectionBlocked = false) }
        }

        loadChapterJob = viewModelScope.launch {
            try {
                val chapterInfo = fetcher.fetchChapter(url)
                val safePage = targetPage.coerceIn(0, (chapterInfo.pages.size - 1).coerceAtLeast(0))
                val savedMode = progressStore.getReadingMode(url) ?: _uiState.value.mode

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isChangingChapter = false,
                        errorRes = null,
                        errorMessage = null,
                        isProtectionBlocked = false,
                        chapterInfo = chapterInfo,
                        currentPage = safePage,
                        mode = savedMode,
                        selectedServer = chapterInfo.availableServers.firstOrNull {
                            chapterInfo.pagesByServer[it.id]?.size == chapterInfo.pages.size
                        }
                    )
                }
                progressStore.saveProgress(url, safePage, savedMode)
                syncComicToLibrary(url, chapterInfo, safePage)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ComicProtectionException) {
                if (hasExistingChapter) {
                    _uiState.update {
                        it.copy(
                            isChangingChapter = false,
                            messageRes = R.string.online_comic_load_failed_js_required
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorRes = R.string.online_comic_load_failed_js_required,
                            errorMessage = e.message,
                            isProtectionBlocked = true
                        )
                    }
                }
            } catch (e: ComicChapterParseException) {
                if (hasExistingChapter) {
                    _uiState.update {
                        it.copy(
                            isChangingChapter = false,
                            messageRes = R.string.online_comic_no_images
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorRes = R.string.online_comic_no_images,
                            errorMessage = e.message
                        )
                    }
                }
            } catch (e: Exception) {
                if (hasExistingChapter) {
                    _uiState.update {
                        it.copy(
                            isChangingChapter = false,
                            messageRes = R.string.online_comic_chapter_load_failed
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorRes = R.string.comic_reader_open_failed,
                            errorMessage = e.message
                        )
                    }
                }
            }
        }
    }

    /** Thử tải lại chương gần nhất được yêu cầu, kể cả khi lần tải đầu chưa có dữ liệu chương. */
    fun retryCurrentChapter() {
        loadChapter(requestedChapterUrl, _uiState.value.currentPage)
    }

    /**
     * Nạp ảnh cho một trang truyện với chiều rộng hiển thị yêu cầu.
     *
     * @param pageIndex Chỉ số trang cần nạp (0-based).
     * @param targetWidth Chiều rộng hiển thị mong muốn (px).
     * @return [ComicPageImage] đã giải mã thành các dải bitmap.
     * @throws IllegalStateException Nếu chưa tải xong thông tin chương.
     * @throws IOException Nếu tải ảnh thất bại.
     */
    suspend fun loadPage(pageIndex: Int, targetWidth: Int): ComicPageImage {
        val chapter = checkNotNull(_uiState.value.chapterInfo) {
            "Thông tin chương chưa sẵn sàng để nạp ảnh"
        }
        val selectedServerId = _uiState.value.selectedServer?.id
        val imageUrl = selectedServerId?.let { chapter.pagesByServer[it]?.getOrNull(pageIndex) }
            ?: chapter.pages[pageIndex]
        return imageLoader.loadPage(imageUrl, targetWidth, refererUrl = chapter.chapterUrl)
    }

    /** Chọn server ảnh khác khi chương có danh sách URL đầy đủ cho server đó. */
    fun selectImageServer(server: com.epubpro.core.comic.ComicImageServer) {
        val chapter = _uiState.value.chapterInfo ?: return
        if (chapter.pagesByServer[server.id]?.size != chapter.pages.size) return
        if (server !in chapter.availableServers) return
        _uiState.update { it.copy(selectedServer = server) }
    }

    /**
     * Ghi nhận vị trí trang đang hiển thị và kích hoạt lưu tiến độ có debounce.
     *
     * @param pageIndex Trang hiện tại (0-based).
     */
    fun onPageChanged(pageIndex: Int) {
        val chapter = _uiState.value.chapterInfo ?: return
        if (chapter.pages.isEmpty() || pageIndex == _uiState.value.currentPage) return

        val clampedPage = pageIndex.coerceIn(0, chapter.pages.size - 1)
        _uiState.update { it.copy(currentPage = clampedPage) }

        progressSaveJob?.cancel()
        progressSaveJob = viewModelScope.launch {
            delay(PROGRESS_SAVE_DEBOUNCE_MS)
            progressStore.saveProgress(chapter.chapterUrl, clampedPage, _uiState.value.mode)
            syncComicToLibrary(chapter.chapterUrl, chapter, clampedPage)
        }
    }

    /**
     * Ghi ngay lập tức tiến độ hiện tại khi ứng dụng chuyển sang nền hoặc đóng màn hình.
     */
    fun flushProgress() {
        progressSaveJob?.cancel()
        val chapter = _uiState.value.chapterInfo ?: return
        viewModelScope.launch(NonCancellable) {
            progressStore.saveProgress(chapter.chapterUrl, _uiState.value.currentPage, _uiState.value.mode)
            syncComicToLibrary(chapter.chapterUrl, chapter, _uiState.value.currentPage)
        }
    }

    /**
     * Đổi chế độ hiển thị và lưu lại cấu hình cho bộ truyện hiện tại.
     *
     * @param mode Chế độ đọc mới.
     */
    fun setReadingMode(mode: ComicReadingMode) {
        val chapter = _uiState.value.chapterInfo
        if (chapter != null) {
            progressStore.saveReadingMode(chapter.chapterUrl, mode)
        }
        _uiState.update { it.copy(mode = mode) }
    }

    /**
     * Chuyển đến chương truyện tiếp theo nếu có.
     */
    fun navigateToNextChapter() {
        val nextUrl = _uiState.value.chapterInfo?.nextChapterUrl ?: return
        loadChapter(nextUrl, targetPage = 0)
    }

    /**
     * Chuyển về chương truyện trước đó nếu có.
     */
    fun navigateToPreviousChapter() {
        val prevUrl = _uiState.value.chapterInfo?.previousChapterUrl ?: return
        loadChapter(prevUrl, targetPage = 0)
    }

    /**
     * Ẩn hoặc hiện thanh công cụ điều khiển giao diện.
     */
    fun toggleControls() {
        _uiState.update { it.copy(showControls = !it.showControls) }
    }

    /**
     * Tiêu thụ thông báo một lần sau khi đã hiển thị.
     */
    fun consumeMessage() {
        _uiState.update { it.copy(messageRes = null) }
    }

    override fun onCleared() {
        imageLoader.clearCache()
        super.onCleared()
    }

    /**
     * Đồng bộ thông tin bộ truyện tranh và tiến độ đọc vào Tủ sách (Kệ sách / Room DB).
     *
     * Giúp truyện tranh online xuất hiện ở tab "Đang đọc" và "Tất cả sách" trên LibraryScreen.
     *
     * @param url URL chương hiện tại.
     * @param chapter Thông tin chương truyện đã phân tích.
     * @param page Vị trí trang hiện tại (0-based).
     */
    private suspend fun syncComicToLibrary(
        url: String,
        chapter: ComicChapterInfo,
        page: Int
    ) {
        try {
            val comicKey = progressStore.computeComicKey(url)
            val bookId = COMIC_BOOK_ID_PREFIX + comicKey
            val existingBook = bookRepository.getBookById(bookId)
            val totalPages = chapter.pages.size.coerceAtLeast(1)
            val safePercentage = ((page + 1).toFloat() / totalPages).coerceIn(0.01f, 0.99f)

            val bookTitle = chapter.comicTitle.ifBlank { chapter.title }
            val coverUrl = existingBook?.coverPath ?: chapter.coverUrl ?: chapter.pages.firstOrNull()

            val book = Book(
                id = bookId,
                title = bookTitle,
                author = chapter.sourceHost,
                coverPath = coverUrl,
                filePath = url,
                addedAt = existingBook?.addedAt ?: System.currentTimeMillis(),
                lastReadAt = System.currentTimeMillis(),
                totalChapters = 0,
                sourceFormat = BookSourceFormat.CBZ
            )

            val progress = ReadingProgress(
                bookId = bookId,
                currentCfi = url,
                chapterIndex = 0,
                pageIndex = page + 1,
                progressPercentage = safePercentage,
                totalChapters = totalPages,
                updatedAt = System.currentTimeMillis()
            )

            bookRepository.insertBook(book)
            bookRepository.saveReadingProgress(progress)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Không để lỗi đồng bộ thư viện làm gián đoạn việc đọc truyện
        }
    }

    companion object {
        const val COMIC_BOOK_ID_PREFIX = OnlineComicProgressStore.COMIC_BOOK_ID_PREFIX
        const val ARG_CHAPTER_URL = "chapterUrl"
        const val ARG_RESUME_PROGRESS = "resumeProgress"
        private const val PROGRESS_SAVE_DEBOUNCE_MS = 400L
    }
}
