package com.epubpro.feature.comic

import android.util.LruCache
import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.epubpro.core.comic.ComicArchive
import com.epubpro.core.comic.ComicPageDecoder
import com.epubpro.core.comic.ComicPageImage
import com.epubpro.core.designsystem.R
import com.epubpro.core.ocr.BubbleGrouper
import com.epubpro.core.ocr.ComicTextRecognizer
import com.epubpro.core.ocr.OcrModelUnavailableException
import com.epubpro.core.ocr.OnDeviceComicTranslator
import com.epubpro.core.storage.ComicPreferencesStore
import com.epubpro.core.storage.ComicTranslationSettings
import com.epubpro.core.storage.ComicTranslationStore
import com.epubpro.core.storage.ConvertDictionaryStore
import com.epubpro.core.storage.ReaderPreferencesManager
import com.epubpro.domain.model.ComicReadingMode
import com.epubpro.domain.model.ComicSourceLanguage
import com.epubpro.domain.model.ComicTranslationEngine
import com.epubpro.domain.model.TranslatedBubble
import com.epubpro.domain.model.ReadingProgress
import com.epubpro.domain.model.comicLocator
import com.epubpro.domain.repository.BookRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Trạng thái màn hình đọc truyện tranh.
 *
 * @property isLoading Đang mở archive.
 * @property errorRes Thông báo lỗi khi không mở được truyện.
 * @property title Tên truyện.
 * @property pageCount Tổng số trang.
 * @property currentPage Trang đang hiển thị (0-based).
 * @property mode Chế độ đọc hiện tại.
 * @property showControls Thanh điều khiển trên/dưới đang hiện.
 * @property volumeKeyNavigation Cho phép lật trang bằng phím âm lượng (theo cài đặt Reader chung).
 * @property keepScreenOn Giữ màn hình sáng khi đọc (theo cài đặt Reader chung).
 * @property translation Cài đặt dịch truyện tranh.
 * @property pageTranslations Trạng thái dịch theo trang.
 * @property showTranslateSheet Đang mở bảng dịch.
 * @property pendingModelDownload Ngôn ngữ cần người dùng xác nhận tải gói dịch.
 * @property isDownloadingModel Đang tải gói dịch.
 * @property selectedBubble Bóng thoại đang xem chi tiết.
 * @property messageRes Thông báo một lần.
 */
data class ComicReaderUiState(
    val isLoading: Boolean = true,
    @StringRes val errorRes: Int? = null,
    val title: String = "",
    val pageCount: Int = 0,
    val currentPage: Int = 0,
    val mode: ComicReadingMode = ComicReadingMode.LEFT_TO_RIGHT,
    val showControls: Boolean = false,
    val volumeKeyNavigation: Boolean = false,
    val keepScreenOn: Boolean = false,
    val translation: ComicTranslationSettings = ComicTranslationSettings(),
    val pageTranslations: Map<Int, PageTranslation> = emptyMap(),
    val showTranslateSheet: Boolean = false,
    val pendingModelDownload: ComicSourceLanguage? = null,
    val isDownloadingModel: Boolean = false,
    val selectedBubble: TranslatedBubble? = null,
    @StringRes val messageRes: Int? = null
)

/** Trạng thái dịch của một trang. */
sealed interface PageTranslation {
    /** Đang OCR/dịch. */
    data object Translating : PageTranslation

    /**
     * Đã dịch xong.
     *
     * @property bubbles Bóng thoại đã dịch.
     */
    data class Done(val bubbles: List<TranslatedBubble>) : PageTranslation

    /** Dịch thất bại. */
    data object Failed : PageTranslation
}

/**
 * ViewModel của Reader truyện tranh: mở archive CBZ, giải mã trang có cache và lưu tiến độ.
 */
@HiltViewModel
class ComicReaderViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val bookRepository: BookRepository,
    private val comicPreferences: ComicPreferencesStore,
    readerPreferences: ReaderPreferencesManager,
    private val translationStore: ComicTranslationStore,
    private val textRecognizer: ComicTextRecognizer,
    private val onDeviceTranslator: OnDeviceComicTranslator,
    private val convertStore: ConvertDictionaryStore
) : ViewModel() {

    private val bookId: String = checkNotNull(savedStateHandle[ARG_BOOK_ID])

    private val _uiState = MutableStateFlow(
        readerPreferences.getSettings().let { settings ->
            ComicReaderUiState(
                volumeKeyNavigation = settings.enableVolumeKeyNavigation,
                keepScreenOn = settings.keepScreenOn,
                translation = translationStore.getSettings()
            )
        }
    )
    val uiState: StateFlow<ComicReaderUiState> = _uiState.asStateFlow()

    @Volatile
    private var archive: ComicArchive? = null
    private val archiveLock = Any()
    private var isCleared = false
    private var progressSaveJob: Job? = null
    private val decodePermits = Semaphore(MAX_PARALLEL_DECODES)
    private val pageCache = object : LruCache<String, ComicPageImage>(pageCacheBytes()) {
        override fun sizeOf(key: String, value: ComicPageImage): Int = value.byteCount
    }

    init {
        openArchive()
    }

    /** Mở archive, đọc trang đã lưu và chế độ đọc trên luồng I/O. */
    private fun openArchive() {
        viewModelScope.launch {
            try {
                val book = bookRepository.getBookById(bookId)
                if (book == null) {
                    _uiState.update { it.copy(isLoading = false, errorRes = R.string.reader_error) }
                    return@launch
                }
                // Gắn archive ngay trong khối I/O: nếu ViewModel bị hủy đúng lúc withContext trả về,
                // archive vẫn đã được giao cho onCleared đóng, không rò file descriptor.
                val opened = withContext(Dispatchers.IO) {
                    ComicArchive.open(File(book.filePath)).also { candidate ->
                        if (!attachArchive(candidate)) throw CancellationException("ViewModel đã bị hủy")
                    }
                }
                bookRepository.updateLastRead(bookId, System.currentTimeMillis())
                val progress = bookRepository.getReadingProgressDirect(bookId)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        title = book.title,
                        pageCount = opened.pageCount,
                        currentPage = resolveInitialComicPage(progress, opened.pageCount),
                        mode = resolveComicReadingMode(
                            comicPreferences.getReadingMode(bookId),
                            opened.info?.isRightToLeft == true
                        )
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                _uiState.update { it.copy(isLoading = false, errorRes = R.string.comic_reader_open_failed) }
            }
        }
    }

    /**
     * Giải mã một trang với chiều rộng đích, dùng cache bộ nhớ và giới hạn số luồng giải mã song song.
     *
     * @param pageIndex Chỉ số trang 0-based.
     * @param targetWidth Chiều rộng hiển thị mong muốn (px).
     * @return Ảnh trang đã giải mã.
     * @throws IllegalStateException Nếu archive chưa mở xong.
     * @throws com.epubpro.core.comic.ComicArchiveException Nếu trang hỏng hoặc vượt giới hạn.
     */
    suspend fun loadPage(pageIndex: Int, targetWidth: Int): ComicPageImage {
        val key = "$pageIndex:$targetWidth"
        pageCache.get(key)?.let { return it }
        val source = checkNotNull(archive)
        return decodePermits.withPermit {
            pageCache.get(key) ?: withContext(Dispatchers.IO) {
                val bytes = source.readPageBytes(pageIndex)
                ComicPageDecoder.decode(bytes, targetWidth)
            }.also { pageCache.put(key, it) }
        }
    }

    /**
     * Ghi nhận trang đang hiển thị và lưu tiến độ có debounce.
     *
     * @param pageIndex Trang hiện tại 0-based.
     */
    fun onPageChanged(pageIndex: Int) {
        val state = _uiState.value
        if (state.pageCount <= 0 || pageIndex == state.currentPage) return
        _uiState.update { it.copy(currentPage = pageIndex.coerceIn(0, it.pageCount - 1)) }
        if (_uiState.value.translation.autoTranslate) translatePage(_uiState.value.currentPage)
        progressSaveJob?.cancel()
        progressSaveJob = viewModelScope.launch {
            delay(PROGRESS_SAVE_DEBOUNCE_MS)
            saveProgress()
        }
    }

    /** Ghi ngay tiến độ khi màn hình dừng hoặc bị hủy; chạy ngoài vòng đời ViewModel để không bị mất. */
    fun flushProgress() {
        progressSaveJob?.cancel()
        if (_uiState.value.pageCount <= 0) return
        viewModelScope.launch(NonCancellable) { saveProgress() }
    }

    /**
     * Đổi chế độ đọc và ghi nhớ cho cuốn truyện này.
     *
     * @param mode Chế độ mới.
     */
    fun setReadingMode(mode: ComicReadingMode) {
        comicPreferences.setReadingMode(bookId, mode)
        _uiState.update { it.copy(mode = mode) }
    }

    /** Ẩn/hiện thanh điều khiển. */
    fun toggleControls() {
        _uiState.update { it.copy(showControls = !it.showControls) }
    }

    /** Mở bảng dịch truyện tranh. */
    fun openTranslateSheet() {
        _uiState.update { it.copy(showTranslateSheet = true) }
    }

    /** Đóng bảng dịch truyện tranh. */
    fun dismissTranslateSheet() {
        _uiState.update { it.copy(showTranslateSheet = false) }
    }

    /**
     * Cập nhật cài đặt dịch; đổi ngôn ngữ hoặc cách dịch thì bỏ kết quả đang hiển thị (cache file vẫn giữ).
     *
     * @param settings Cài đặt mới.
     */
    fun updateTranslationSettings(settings: ComicTranslationSettings) {
        translationStore.saveSettings(settings)
        _uiState.update { state ->
            val changedSource = state.translation.language != settings.language || state.translation.engine != settings.engine
            state.copy(
                translation = settings,
                pageTranslations = if (changedSource) emptyMap() else state.pageTranslations
            )
        }
    }

    /**
     * OCR và dịch một trang; dùng cache nếu đã dịch trước đó.
     *
     * @param pageIndex Trang cần dịch, mặc định trang hiện tại.
     * @param force true để bỏ qua kết quả đang hiển thị (dịch lại sau lỗi).
     */
    fun translatePage(pageIndex: Int = _uiState.value.currentPage, force: Boolean = false) {
        val state = _uiState.value
        val current = state.pageTranslations[pageIndex]
        if (current == PageTranslation.Translating || (!force && current is PageTranslation.Done)) return
        val settings = state.translation
        if (settings.engine == ComicTranslationEngine.HAN_VIET) {
            if (settings.language != ComicSourceLanguage.CHINESE) {
                _uiState.update { it.copy(messageRes = R.string.comic_translate_hanviet_chinese_only) }
                return
            }
            if (!convertStore.hasBaseDictionary()) {
                _uiState.update { it.copy(messageRes = R.string.convert_no_dictionary) }
                return
            }
        }
        val source = archive ?: return
        setPageTranslation(pageIndex, PageTranslation.Translating)
        viewModelScope.launch {
            try {
                if (settings.engine == ComicTranslationEngine.ON_DEVICE &&
                    !onDeviceTranslator.isModelReady(settings.language)
                ) {
                    clearPageTranslation(pageIndex)
                    _uiState.update { it.copy(pendingModelDownload = settings.language) }
                    return@launch
                }
                val bubbles = translationStore.loadPage(bookId, pageIndex, settings.language, settings.engine)
                    ?: recognizeAndTranslate(source, pageIndex, settings).also { result ->
                        translationStore.savePage(bookId, pageIndex, settings.language, settings.engine, result)
                    }
                setPageTranslation(pageIndex, PageTranslation.Done(bubbles))
                if (bubbles.isEmpty()) _uiState.update { it.copy(messageRes = R.string.comic_translate_no_text) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: OcrModelUnavailableException) {
                clearPageTranslation(pageIndex)
                _uiState.update { it.copy(messageRes = R.string.comic_translate_ocr_model_pending) }
            } catch (_: Exception) {
                setPageTranslation(pageIndex, PageTranslation.Failed)
                _uiState.update { it.copy(messageRes = R.string.comic_translate_failed) }
            } catch (_: OutOfMemoryError) {
                setPageTranslation(pageIndex, PageTranslation.Failed)
                _uiState.update { it.copy(messageRes = R.string.comic_translate_failed) }
            }
        }
    }

    /**
     * Người dùng đồng ý tải gói dịch; tải xong thì dịch trang hiện tại.
     *
     * @param wifiOnly true để chỉ tải qua Wi-Fi.
     */
    fun confirmModelDownload(wifiOnly: Boolean) {
        val language = _uiState.value.pendingModelDownload ?: return
        _uiState.update { it.copy(isDownloadingModel = true) }
        viewModelScope.launch {
            val result = runCatching { onDeviceTranslator.downloadModel(language, wifiOnly) }
            _uiState.update {
                it.copy(
                    isDownloadingModel = false,
                    pendingModelDownload = null,
                    messageRes = if (result.isSuccess) null else R.string.comic_translate_model_failed
                )
            }
            if (result.isSuccess) translatePage(_uiState.value.currentPage)
        }
    }

    /** Hủy yêu cầu tải gói dịch. */
    fun cancelModelDownload() {
        if (_uiState.value.isDownloadingModel) return
        _uiState.update { it.copy(pendingModelDownload = null) }
    }

    /**
     * Chọn bóng thoại để xem bản gốc và bản dịch.
     *
     * @param bubble Bóng thoại, null để đóng.
     */
    fun selectBubble(bubble: TranslatedBubble?) {
        _uiState.update { it.copy(selectedBubble = bubble) }
    }

    /** Xóa thông báo đã hiển thị. */
    fun consumeMessage() {
        _uiState.update { it.copy(messageRes = null) }
    }

    /**
     * Giải mã trang ở độ phân giải dành cho OCR, nhận diện chữ, gom bóng thoại và dịch.
     *
     * @param source Archive đang mở.
     * @param pageIndex Trang cần xử lý.
     * @param settings Cài đặt dịch.
     * @return Bóng thoại đã dịch.
     */
    private suspend fun recognizeAndTranslate(
        source: ComicArchive,
        pageIndex: Int,
        settings: ComicTranslationSettings
    ): List<TranslatedBubble> {
        val image = withContext(Dispatchers.IO) {
            ComicPageDecoder.decode(source.readPageBytes(pageIndex), OCR_DECODE_WIDTH)
        }
        val blocks = try {
            textRecognizer.recognize(image.tiles, settings.language)
        } finally {
            image.tiles.forEach { it.recycle() }
        }
        val bubbles = BubbleGrouper.group(
            blocks,
            rightToLeft = _uiState.value.mode == ComicReadingMode.RIGHT_TO_LEFT,
            joinWithSpace = settings.language == ComicSourceLanguage.ENGLISH || settings.language == ComicSourceLanguage.KOREAN
        )
        val texts = bubbles.map { it.text }
        val translated = when (settings.engine) {
            ComicTranslationEngine.ON_DEVICE -> onDeviceTranslator.translate(texts, settings.language)
            ComicTranslationEngine.HAN_VIET -> {
                val converter = checkNotNull(convertStore.createConverter(bookId))
                withContext(Dispatchers.Default) { texts.map { converter.convert(it).text } }
            }
        }
        return bubbles.zip(translated) { bubble, text ->
            TranslatedBubble(bubble.text, text, bubble.left, bubble.top, bubble.right, bubble.bottom)
        }
    }

    /**
     * Ghi trạng thái dịch của một trang.
     *
     * @param pageIndex Trang.
     * @param translation Trạng thái mới.
     */
    private fun setPageTranslation(pageIndex: Int, translation: PageTranslation) {
        _uiState.update { it.copy(pageTranslations = it.pageTranslations + (pageIndex to translation)) }
    }

    /**
     * Bỏ trạng thái dịch của một trang.
     *
     * @param pageIndex Trang.
     */
    private fun clearPageTranslation(pageIndex: Int) {
        _uiState.update { it.copy(pageTranslations = it.pageTranslations - pageIndex) }
    }

    /** Lưu tiến độ hiện tại; trang được lưu như chương để Library và đồng bộ Drive dùng chung schema. */
    private suspend fun saveProgress() {
        val state = _uiState.value
        if (state.pageCount <= 0) return
        bookRepository.saveReadingProgress(
            ReadingProgress(
                bookId = bookId,
                currentCfi = comicLocator(state.currentPage),
                chapterIndex = state.currentPage,
                pageIndex = state.currentPage + 1,
                progressPercentage = comicProgress(state.currentPage, state.pageCount),
                totalChapters = state.pageCount
            )
        )
    }

    /**
     * Gắn archive vừa mở vào ViewModel; nếu ViewModel đã bị hủy thì đóng ngay.
     *
     * @param opened Archive vừa mở.
     * @return true nếu đã gắn, false nếu ViewModel đã bị hủy và archive đã được đóng.
     */
    private fun attachArchive(opened: ComicArchive): Boolean = synchronized(archiveLock) {
        if (isCleared) {
            runCatching { opened.close() }
            false
        } else {
            archive = opened
            true
        }
    }

    /** Đóng archive và giải phóng cache khi rời màn hình. */
    override fun onCleared() {
        pageCache.evictAll()
        synchronized(archiveLock) {
            isCleared = true
            runCatching { archive?.close() }
            archive = null
        }
        super.onCleared()
    }

    companion object {
        const val ARG_BOOK_ID = "bookId"
        private const val MAX_PARALLEL_DECODES = 2
        private const val PROGRESS_SAVE_DEBOUNCE_MS = 400L

        /** Chiều rộng giải mã cho OCR: đủ nét để nhận chữ nhỏ trong bóng thoại mà không quá nặng. */
        private const val OCR_DECODE_WIDTH = 1280

        /**
         * Tính dung lượng cache trang bằng 1/6 heap tối đa của ứng dụng.
         *
         * @return Số byte tối đa cho LruCache.
         */
        private fun pageCacheBytes(): Int =
            (Runtime.getRuntime().maxMemory() / 6).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}
