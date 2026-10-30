package com.epubpro.feature.reader

import com.epubpro.core.convert.ChineseTextDetector
import com.epubpro.core.convert.HanVietConverter
import com.epubpro.core.convert.HtmlConverter
import com.epubpro.core.storage.WebNovelSourceStore
import com.epubpro.core.storage.worker.WebNovelDownloadScheduler
import com.epubpro.core.storage.worker.WebNovelDownloadState
import com.epubpro.core.designsystem.R
import com.epubpro.core.storage.ConvertDictionaryStore
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import android.content.Context
import android.content.Intent
import android.util.Log
import android.util.LruCache
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.epubpro.core.ai.AiVietnameseService
import com.epubpro.core.reader.engine.EpubCacheFingerprint
import com.epubpro.core.reader.engine.EpubChapterHeader
import com.epubpro.core.reader.engine.EpubEngine
import com.epubpro.core.reader.filter.EpubHtmlSanitizer
import com.epubpro.core.reader.filter.SanitizedEpubHtml
import com.epubpro.core.reader.tts.TtsOpenBookContract
import com.epubpro.core.reader.tts.TtsService
import com.epubpro.core.reader.tts.TtsTextParser
import com.epubpro.core.reader.tts.TtsWidgetContract
import com.epubpro.core.storage.AiPreferencesManager
import com.epubpro.core.storage.EpubStorageManager
import com.epubpro.core.storage.ReaderPreferencesManager
import com.epubpro.core.storage.ReaderResumeSnapshotStore
import com.epubpro.core.storage.TtsPlaybackSnapshot
import com.epubpro.core.storage.TtsPlaybackSnapshotStore
import com.epubpro.core.storage.TtsPreferencesManager
import com.epubpro.core.storage.TtsWidgetPlaybackStatus
import com.epubpro.core.storage.TtsWidgetState
import com.epubpro.core.storage.TtsWidgetStateStore
import com.epubpro.domain.model.AiRule
import com.epubpro.domain.model.AiRuleAction
import com.epubpro.domain.model.AiRuleScope
import com.epubpro.domain.model.AiSettings
import com.epubpro.domain.model.Book
import com.epubpro.domain.model.BookBibleSource
import com.epubpro.domain.model.BookBibleSourceType
import com.epubpro.domain.model.Bookmark
import com.epubpro.domain.model.ContentFilterPreferences
import com.epubpro.domain.model.ContentFilterRule
import com.epubpro.domain.model.isJavaScriptCompatibleRegex
import com.epubpro.domain.model.Highlight
import com.epubpro.domain.model.ReaderSettings
import com.epubpro.domain.model.ReadingProgress
import com.epubpro.domain.model.SleepTimerOption
import com.epubpro.domain.model.TtsChunk
import com.epubpro.domain.model.TtsPlayerState
import com.epubpro.domain.model.TtsSettings
import com.epubpro.domain.repository.AiRuleRepository
import com.epubpro.domain.repository.BookBibleRepository
import com.epubpro.domain.repository.BookRepository
import com.epubpro.domain.repository.BookmarkRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import org.jsoup.Jsoup

private const val WIDGET_TEXT_MAX_CHARS = 800
private const val SANITIZED_CHAPTER_CACHE_MAX_BYTES = 4 * 1024 * 1024
private const val CONVERT_DETECTION_SAMPLE = 8000

/** Còn từng này chương chưa đọc thì tự tải thêm chương cho truyện lưu từ web. */
private const val AUTO_APPEND_REMAINING_CHAPTERS = 2

/** Số chương tải thêm mỗi lần. */
private const val APPEND_BATCH_CHAPTERS = 20

enum class ReaderContentVersion {
    ORIGINAL,
    AI
}

data class ReaderUiState(
    val book: Book? = null,
    val chapters: List<EpubChapterHeader> = emptyList(),
    val currentChapterIndex: Int = 0,
    val currentChapterHtml: String = "",
    val sanitizedCurrentChapterHtml: SanitizedEpubHtml? = null,
    val previousChapterHtml: String? = null,
    val nextChapterHtml: String? = null,
    val sanitizedPreviousChapterHtml: SanitizedEpubHtml? = null,
    val sanitizedNextChapterHtml: SanitizedEpubHtml? = null,
    val currentPageInChapter: Int = 1,
    val initialPageRequest: Int = 1,
    val totalPagesInChapter: Int = 1,
    val firstVisibleParagraphIndex: Int = 0,
    val currentCfi: String = "",
    val settings: ReaderSettings = ReaderSettings(),
    val showControls: Boolean = true,
    val isLoading: Boolean = true,
    val loadError: String? = null,
    val isTtsSpeaking: Boolean = false,
    val showTtsSetupBottomSheet: Boolean = false,
    val showTtsPlayerScreen: Boolean = false,
    val ttsSettings: TtsSettings = TtsSettings(),
    val ttsPlayerState: TtsPlayerState = TtsPlayerState.Idle,
    val selectedSleepTimer: SleepTimerOption = SleepTimerOption.OFF,
    val showAiBottomSheet: Boolean = false,
    val aiSettings: AiSettings = AiSettings(),
    val aiRules: List<AiRule> = emptyList(),
    val aiChapterHtml: String? = null,
    val contentVersion: ReaderContentVersion = ReaderContentVersion.ORIGINAL,
    val aiCreatedWithOldConfiguration: Boolean = false,
    val isAiProcessing: Boolean = false,
    val aiCompletedParts: Int = 0,
    val aiTotalParts: Int = 0,
    val aiError: String? = null,
    val isTestingAiConnection: Boolean = false,
    val aiConnectionMessage: String? = null,
    val filterPreferences: ContentFilterPreferences = ContentFilterPreferences(),
    val replaceTextPattern: String? = null,
    val convertEnabled: Boolean = false,
    val convertedChapterHtml: String? = null,
    val sanitizedConvertedChapterHtml: SanitizedEpubHtml? = null,
    val showConvertSheet: Boolean = false,
    val convertNames: List<Pair<String, String>> = emptyList(),
    val convertHasDictionary: Boolean = false,
    val convertSuggested: Boolean = false,
    @androidx.annotation.StringRes val convertMessageRes: Int? = null,
    val isWebNovel: Boolean = false,
    val isAppendingChapters: Boolean = false,
    @androidx.annotation.StringRes val webNovelMessageRes: Int? = null,
    val webNovelMessageArg: Int = 0,
    val webNovelFailedUrl: String? = null
) {
    /** true khi đang hiển thị bản Convert Hán-Việt của chương hiện tại. */
    val isShowingConverted: Boolean
        get() = contentVersion == ReaderContentVersion.ORIGINAL && convertEnabled && convertedChapterHtml != null

    /** true khi HTML hiển thị trùng bản gốc, nên được dùng bản đã sanitize sẵn trong cache. */
    val usesOriginalHtml: Boolean
        get() = contentVersion == ReaderContentVersion.ORIGINAL && !isShowingConverted

    /**
     * HTML đã sanitize khớp đúng bản đang hiển thị. Bản Convert cũng phải có bản sanitize sẵn để key
     * commit chương kề trong WebView trùng với key nạp chương mới, tránh reload trang.
     */
    val preSanitizedDisplayedHtml: SanitizedEpubHtml?
        get() = when {
            usesOriginalHtml -> sanitizedCurrentChapterHtml
            isShowingConverted -> sanitizedConvertedChapterHtml
            else -> null
        }

    val displayedChapterHtml: String
        get() = when {
            contentVersion == ReaderContentVersion.AI -> aiChapterHtml ?: currentChapterHtml
            isShowingConverted -> convertedChapterHtml ?: currentChapterHtml
            else -> currentChapterHtml
        }
}

/**
 * Lấy chapter override hợp lệ từ navigation request, độc lập với việc có mở màn hình TTS hay không.
 *
 * @param chapterIndex Chỉ số chapter nhận từ navigation, có thể là null hoặc giá trị sentinel âm.
 * @return Chỉ số chapter không âm nếu hợp lệ; ngược lại trả về null.
 */
internal fun resolveRequestedChapterIndex(chapterIndex: Int?): Int? =
    chapterIndex?.takeIf { it >= 0 }

/**
 * Tính tỷ lệ tiến độ đọc dựa trên chapter hiện tại và trang hiện tại trong chapter.
 *
 * @param state Trạng thái reader chứa chapter, trang hiện tại và tổng số trang.
 * @return Tiến độ trong khoảng từ 0.0 đến 1.0; trang cuối của sách trả về 1.0.
 */
internal fun calculateReaderProgress(state: ReaderUiState): Float {
    if (state.chapters.isEmpty()) return 0f

    val totalChapters = state.chapters.size
    val currentChapter = state.currentChapterIndex.coerceIn(0, totalChapters - 1)
    val totalPages = state.totalPagesInChapter.coerceAtLeast(1)
    val currentPage = state.currentPageInChapter.coerceIn(1, totalPages)
    val pageProgress = if (totalPages == 1) {
        if (currentChapter == totalChapters - 1 && currentPage == 1) 1f else 0f
    } else {
        (currentPage - 1).toFloat() / (totalPages - 1)
    }
    return ((currentChapter + pageProgress) / totalChapters.toFloat())
        .coerceIn(0f, 1f)
}

/**
 * Bật bộ lọc và thêm/cập nhật quy tắc thay thế từ ngữ, tự động kích hoạt nếu đang tắt.
 *
 * @param pattern Chuỗi từ gốc hoặc mẫu Regex cần tìm kiếm.
 * @param replacement Chuỗi mới thay thế (để trống nếu muốn xóa).
 * @param isRegex `true` nếu mẫu tìm kiếm là biểu thức chính quy.
 * @return Cấu hình bộ lọc mới sau khi đã cập nhật.
 */
internal fun ContentFilterPreferences.withEnabledReplaceRule(
    pattern: String,
    replacement: String = "",
    isRegex: Boolean = false
): ContentFilterPreferences {
    val normalizedPattern = pattern.trim()
    if (normalizedPattern.isEmpty()) return this
    if (isRegex && !isJavaScriptCompatibleRegex(normalizedPattern)) return this

    val matchingRule = rules.firstOrNull { rule ->
        rule.isRegex == isRegex && rule.pattern.equals(normalizedPattern, ignoreCase = true)
    }
    if (matchingRule != null) {
        return copy(
            isFilterEnabled = true,
            rules = rules.map { rule ->
                if (rule.id == matchingRule.id) {
                    rule.copy(
                        replacement = replacement,
                        isRegex = isRegex,
                        isEnabled = true
                    )
                } else rule
            }
        )
    }

    return copy(
        isFilterEnabled = true,
        rules = rules + ContentFilterRule(
            pattern = normalizedPattern,
            replacement = replacement,
            isRegex = isRegex,
            isEnabled = true
        )
    )
}

/**
 * Bật bộ lọc và thêm selection dưới dạng rule literal, đồng thời tái sử dụng rule trùng đã có.
 *
 * @param selectedText Văn bản người dùng chọn trong màn đọc.
 * @return Cấu hình bộ lọc mới; giữ nguyên cấu hình nếu selection rỗng.
 */
internal fun ContentFilterPreferences.withEnabledLiteralRule(
    selectedText: String
): ContentFilterPreferences = withEnabledReplaceRule(pattern = selectedText, replacement = "", isRegex = false)

private data class ChapterHtmlBundle(
    val current: String,
    val previous: String?,
    val next: String?
)

@HiltViewModel
class ReaderViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bookRepository: BookRepository,
    private val bookmarkRepository: BookmarkRepository,
    private val storageManager: EpubStorageManager,
    private val epubEngine: EpubEngine,
    private val preferencesManager: ReaderPreferencesManager,
    private val ttsPreferencesManager: TtsPreferencesManager,
    private val aiPreferencesManager: AiPreferencesManager,
    private val aiVietnameseService: AiVietnameseService,
    private val aiRuleRepository: AiRuleRepository,
    private val savedStateHandle: SavedStateHandle,
    private val widgetStateStore: TtsWidgetStateStore,
    private val playbackSnapshotStore: TtsPlaybackSnapshotStore,
    private val resumeSnapshotStore: ReaderResumeSnapshotStore,
    private val bookBibleRepository: BookBibleRepository,
    private val convertStore: ConvertDictionaryStore,
    private val webNovelSourceStore: WebNovelSourceStore,
    private val webNovelScheduler: WebNovelDownloadScheduler
) : ViewModel() {

    val bookId: String = checkNotNull(savedStateHandle["bookId"])
    private val requestedChapterIndex: Int? = resolveRequestedChapterIndex(
        savedStateHandle.get<Int>(TtsOpenBookContract.NAV_ARGUMENT_CHAPTER_INDEX)
    )
    private var bookFile: File? = null
    private var chapterLoadJob: Job? = null
    private var adjacentPreloadJob: Job? = null
    private var chapterPrefetchJob: Job? = null
    private var aiProcessingJob: Job? = null
    private var progressSaveJob: Job? = null
    private var widgetProjectionJob: Job? = null
    private var resumeSnapshotJob: Job? = null
    private var progressSaveVersion: Long = 0L
    private var chapterNavigationGeneration: Int = 0
    private var cachedTtsChunks: Pair<String, List<TtsChunk>>? = null

    private val sanitizedChapterCache = object : LruCache<String, SanitizedEpubHtml>(
        SANITIZED_CHAPTER_CACHE_MAX_BYTES
    ) {
        override fun sizeOf(key: String, value: SanitizedEpubHtml): Int {
            return (value.rawHtml.length * 2).coerceAtLeast(1)
        }
    }

    private val _uiState = MutableStateFlow(
        ReaderUiState(
            settings = preferencesManager.getSettings(),
            filterPreferences = preferencesManager.getFilterPreferences(),
            showTtsPlayerScreen = savedStateHandle.get<Boolean>(STATE_SHOW_TTS_PLAYER) == true
        )
    )
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    /** Bộ convert đã nạp cho sách này; null khi chưa dùng hoặc từ điển vừa thay đổi. */
    private var hanVietConverter: HanVietConverter? = null

    private var appendJob: Job? = null

    /** Số chương tại lần tự tải thêm gần nhất, để mỗi mốc chỉ tự tải một lần. */
    private var autoAppendChapterCount = -1

    init {
        _uiState.update {
            it.copy(
                convertEnabled = convertStore.isConvertEnabled(bookId),
                convertHasDictionary = convertStore.hasBaseDictionary(),
                isWebNovel = webNovelSourceStore.get(bookId) != null
            )
        }
        viewModelScope.launch {
            // Truyện lưu từ web: đọc tới gần chương cuối thì tự tải thêm chương ở nền.
            _uiState.map { it.currentChapterIndex to it.chapters.size }
                .distinctUntilChanged()
                .collect { (index, size) ->
                    if (size > 0 && index >= size - AUTO_APPEND_REMAINING_CHAPTERS) loadMoreWebChapters(auto = true)
                }
        }
        viewModelScope.launch {
            combine(bookRepository.getAllBooks(), _uiState.map { it.book?.filePath }.distinctUntilChanged()) { books, path -> books to path }
                .collect { (books, currentPath) ->
                val updated = books.firstOrNull { it.id == bookId } ?: return@collect
                if (webNovelSourceStore.get(bookId) == null) return@collect
                withContext(Dispatchers.IO) { webNovelSourceStore.reconcileReplacement(bookId, updated.filePath) }
                if (currentPath != null && currentPath != updated.filePath) refreshAppendedChapters()
            }
        }
        viewModelScope.launch {
            // Từ điển hoặc Name thay đổi: convert lại chương hiện tại, giữ nguyên trang đang đọc.
            convertStore.revision.drop(1).collect {
                hanVietConverter = null
                _uiState.update { it.copy(convertHasDictionary = convertStore.hasBaseDictionary()) }
                refreshConvertedChapter()
            }
        }

        viewModelScope.launch {
            preferencesManager.settings.collect { settings ->
                _uiState.update { it.copy(settings = settings) }
            }
        }

        viewModelScope.launch {
            preferencesManager.filterPreferences.collect { filterPrefs ->
                _uiState.update { it.copy(filterPreferences = filterPrefs) }
            }
        }

        viewModelScope.launch {
            ttsPreferencesManager.settingsFlow.collect { settings ->
                _uiState.update { it.copy(ttsSettings = settings) }
            }
        }

        viewModelScope.launch {
            TtsService.playerState.collect { state ->
                val stateToEmit = when (state) {
                    is TtsPlayerState.Preparing -> if (state.bookId == bookId) state else TtsPlayerState.Idle
                    is TtsPlayerState.Playing -> if (state.bookId == bookId) state else TtsPlayerState.Idle
                    is TtsPlayerState.Paused -> if (state.bookId == bookId) state else TtsPlayerState.Idle
                    is TtsPlayerState.Completed -> if (state.bookId == bookId) state else TtsPlayerState.Idle
                    else -> state
                }

                if (stateToEmit is TtsPlayerState.Completed) {
                    _uiState.update {
                        it.copy(
                            ttsPlayerState = TtsPlayerState.Idle,
                            isTtsSpeaking = false
                        )
                    }
                    ttsPreferencesManager.saveLastTtsChunkIndex(
                        bookId,
                        stateToEmit.chapterIndex,
                        0
                    )
                } else {
                    val playbackChapterIndex = when (stateToEmit) {
                        is TtsPlayerState.Preparing -> stateToEmit.chapterIndex
                        is TtsPlayerState.Playing -> stateToEmit.chapterIndex
                        is TtsPlayerState.Paused -> stateToEmit.chapterIndex
                        else -> null
                    }
                    val currentPlaybackChunk = when (stateToEmit) {
                        is TtsPlayerState.Preparing -> stateToEmit.currentChunk
                        is TtsPlayerState.Playing -> stateToEmit.currentChunk
                        is TtsPlayerState.Paused -> stateToEmit.currentChunk
                        else -> null
                    }
                    if (playbackChapterIndex != null &&
                        playbackChapterIndex != _uiState.value.currentChapterIndex &&
                        playbackChapterIndex in _uiState.value.chapters.indices
                    ) {
                        onChapterSelected(playbackChapterIndex)
                    }
                    if (playbackChapterIndex != null && currentPlaybackChunk != null) {
                        ttsPreferencesManager.saveLastTtsChunkIndex(
                            bookId,
                            playbackChapterIndex,
                            currentPlaybackChunk.paragraphIndex
                        )
                    }

                    _uiState.update {
                        it.copy(
                            ttsPlayerState = stateToEmit,
                            isTtsSpeaking = stateToEmit is TtsPlayerState.Playing ||
                                    stateToEmit is TtsPlayerState.Preparing
                        )
                    }
                }
            }
        }

        viewModelScope.launch {
            aiPreferencesManager.settingsFlow.collect { settings ->
                _uiState.update { it.copy(aiSettings = settings) }
                refreshCurrentAiCache()
            }
        }

        viewModelScope.launch {
            aiRuleRepository.observeRulesForBook(bookId).collect { rules ->
                _uiState.update { it.copy(aiRules = rules) }
                refreshCurrentAiCache()
            }
        }
        loadBookData()
    }

    private fun loadBookData() {
        chapterLoadJob?.cancel()
        adjacentPreloadJob?.cancel()
        aiProcessingJob?.cancel()
        resumeSnapshotJob?.cancel()
        chapterPrefetchJob?.cancel()
        val loadGen = ++chapterNavigationGeneration
        viewModelScope.launch {
            try {
                val book = bookRepository.getBookById(bookId)
                    ?: error("Không tìm thấy sách trong thư viện")
                bookRepository.updateLastRead(bookId, System.currentTimeMillis())
                val file = storageManager.getBookFile(book.filePath)
                require(file.isFile) { "Tệp EPUB không còn tồn tại" }
                bookFile = file
                val fingerprint = withContext(Dispatchers.IO) {
                    com.epubpro.core.reader.engine.EpubCacheFingerprint.fromFile(file)
                }
                val headers = epubEngine.extractChapterHeaders(file)
                require(headers.isNotEmpty()) { "Không thể đọc cấu trúc EPUB" }

                val savedProgress = bookRepository.getReadingProgress(bookId).firstOrNull()
                val requestedIndex = requestedChapterIndex?.takeIf { it in headers.indices }
                val initialIndex = requestedIndex
                    ?: (savedProgress?.chapterIndex ?: 0)
                        .coerceIn(0, (headers.size - 1).coerceAtLeast(0))
                val canRestoreSavedLocation = savedProgress?.chapterIndex == initialIndex
                val initialPage = if (canRestoreSavedLocation) {
                    (savedProgress?.pageIndex ?: 1).coerceAtLeast(1)
                } else {
                    1
                }
                val initialCfi =
                    savedProgress?.currentCfi.takeIf { canRestoreSavedLocation }.orEmpty()
                val shouldOpenTtsPlayer = consumeOpenTtsPlayerRequest()
                val savedSettings = preferencesManager.getSettings()

                val entryName = headers[initialIndex].entryName
                val cachedSnapshot = withContext(Dispatchers.IO) {
                    resumeSnapshotStore.loadSnapshot(
                        bookId = bookId,
                        expectedChapterIndex = initialIndex,
                        expectedEntryName = entryName,
                        canonicalPath = fingerprint.canonicalPath,
                        fileLength = fingerprint.fileLength,
                        lastModified = fingerprint.lastModified
                    )
                }
                val snapshotSanitizedHtml = cachedSnapshot?.let { snapshot ->
                    withContext(Dispatchers.Default) {
                        SanitizedEpubHtml.restoreFromSnapshot(
                            html = snapshot.sanitizedHtml,
                            sourceHash = snapshot.sourceHash,
                            actualSourceHtml = snapshot.normalizedHtml,
                            sanitizerVersion = snapshot.sanitizerVersion
                        )
                    }
                }
                val sanitizedCacheKey = sanitizedChapterCacheKey(fingerprint, entryName)
                snapshotSanitizedHtml?.let { sanitizedChapterCache.put(sanitizedCacheKey, it) }
                val restoredSanitizedHtml = snapshotSanitizedHtml ?: sanitizedChapterCache.get(sanitizedCacheKey)
                val currentHtml = cachedSnapshot?.normalizedHtml ?: epubEngine.loadChapterHtml(file, entryName)
                require(currentHtml.isNotBlank()) { "Chương hiện tại không có nội dung" }
                val convertedHtml = convertChapterHtml(currentHtml)
                val suggestConvert = convertedHtml == null && !_uiState.value.convertEnabled &&
                    withContext(Dispatchers.Default) { looksChinese(currentHtml) }

                // EMIT CURRENT CHAPTER IMMEDIATELY
                _uiState.update {
                    it.copy(
                        book = book,
                        chapters = headers,
                        currentChapterIndex = initialIndex,
                        currentChapterHtml = currentHtml,
                        sanitizedCurrentChapterHtml = restoredSanitizedHtml,
                        previousChapterHtml = null,
                        nextChapterHtml = null,
                        sanitizedPreviousChapterHtml = null,
                        sanitizedNextChapterHtml = null,
                        aiChapterHtml = null,
                        contentVersion = ReaderContentVersion.ORIGINAL,
                        aiCreatedWithOldConfiguration = false,
                        convertedChapterHtml = convertedHtml?.html,
                        sanitizedConvertedChapterHtml = convertedHtml?.sanitized,
                        convertSuggested = suggestConvert,
                        currentPageInChapter = initialPage,
                        initialPageRequest = initialPage,
                        firstVisibleParagraphIndex = parseParagraphLocator(initialCfi),
                        currentCfi = initialCfi,
                        settings = savedSettings,
                        showTtsPlayerScreen = it.showTtsPlayerScreen || shouldOpenTtsPlayer,
                        isLoading = false,
                        loadError = null
                    )
                }

                if (cachedSnapshot == null || restoredSanitizedHtml == null) {
                    scheduleResumeSnapshot(
                        fingerprint = fingerprint,
                        chapterIndex = initialIndex,
                        entryName = entryName,
                        normalizedHtml = currentHtml,
                        generation = loadGen
                    )
                }

                enqueueBookBibleChapter(
                    book = book,
                    chapterIndex = initialIndex,
                    chapterTitle = headers.getOrNull(initialIndex)?.title ?: "Chương ${initialIndex + 1}",
                    originalHtml = currentHtml
                )

                // Asynchronously preload adjacent chapters and AI cache in background
                adjacentPreloadJob?.cancel()
                adjacentPreloadJob = viewModelScope.launch(Dispatchers.IO) {
                    val prev = headers.getOrNull(initialIndex - 1)?.let { header ->
                        runCatching { epubEngine.loadChapterHtml(file, header.entryName) }
                            .getOrNull()
                            ?.also { html ->
                                runCatching {
                                    sanitizeAndCacheChapter(fingerprint, header.entryName, html)
                                }
                            }
                    }
                    val next = headers.getOrNull(initialIndex + 1)?.let { header ->
                        runCatching { epubEngine.loadChapterHtml(file, header.entryName) }
                            .getOrNull()
                            ?.also { html ->
                                runCatching {
                                    sanitizeAndCacheChapter(fingerprint, header.entryName, html)
                                }
                            }
                    }
                    val cachedAi = aiVietnameseService.loadCachedChapter(bookId, initialIndex, currentHtml)
                    val convertedPrev = convertChapterHtml(prev)
                    val convertedNext = convertChapterHtml(next)

                    if (chapterNavigationGeneration == loadGen && _uiState.value.currentChapterIndex == initialIndex) {
                        _uiState.update {
                            it.copy(
                                previousChapterHtml = convertedPrev?.html ?: prev,
                                nextChapterHtml = convertedNext?.html ?: next,
                                // Bản preview đã convert dùng HTML sanitize của chính nó, không dùng cache bản gốc.
                                sanitizedPreviousChapterHtml = convertedPrev?.sanitized ?: headers
                                    .getOrNull(initialIndex - 1)
                                    ?.let { sanitizedChapterCache.get(sanitizedChapterCacheKey(fingerprint, it.entryName)) },
                                sanitizedNextChapterHtml = convertedNext?.sanitized ?: headers
                                    .getOrNull(initialIndex + 1)
                                    ?.let { sanitizedChapterCache.get(sanitizedChapterCacheKey(fingerprint, it.entryName)) },
                                aiChapterHtml = cachedAi?.html,
                                aiCreatedWithOldConfiguration = cachedAi?.createdWithOldConfiguration == true
                            )
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e("EpubPro_VM", "Failed to load book $bookId", error)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        loadError = error.message ?: "Không thể tải nội dung sách"
                    )
                }
            }
        }
    }

    fun retryLoad() {
        _uiState.update { it.copy(isLoading = true, loadError = null) }
        loadBookData()
    }

    fun onChapterSelected(
        index: Int,
        openAtLastPage: Boolean = false,
        autoStartTts: Boolean = false,
        ttsService: TtsService? = null
    ) {
        val state = _uiState.value
        val file = bookFile
        if (file != null && index in 0 until state.chapters.size) {
            chapterLoadJob?.cancel()
            adjacentPreloadJob?.cancel()
            aiProcessingJob?.cancel()
            resumeSnapshotJob?.cancel()
            progressSaveJob?.cancel()
            widgetProjectionJob?.cancel()
            progressSaveVersion++
            val requestedContentVersion = state.contentVersion
            val loadGen = ++chapterNavigationGeneration

            chapterLoadJob = viewModelScope.launch {
                val entryName = state.chapters[index].entryName
                val currentHtml = epubEngine.loadChapterHtml(file, entryName)

                val fingerprint = withContext(Dispatchers.IO) {
                    EpubCacheFingerprint.fromFile(file)
                }
                val preSanitizedHtml = sanitizedChapterCache.get(
                    sanitizedChapterCacheKey(fingerprint, entryName)
                )
                val convertedHtml = convertChapterHtml(currentHtml)

                _uiState.update {
                    it.copy(
                        currentChapterIndex = index,
                        currentChapterHtml = currentHtml,
                        sanitizedCurrentChapterHtml = preSanitizedHtml,
                        previousChapterHtml = null,
                        nextChapterHtml = null,
                        sanitizedPreviousChapterHtml = null,
                        sanitizedNextChapterHtml = null,
                        aiChapterHtml = null,
                        contentVersion = ReaderContentVersion.ORIGINAL,
                        aiCreatedWithOldConfiguration = false,
                        convertedChapterHtml = convertedHtml?.html,
                        sanitizedConvertedChapterHtml = convertedHtml?.sanitized,
                        isAiProcessing = false,
                        aiCompletedParts = 0,
                        aiTotalParts = 0,
                        aiError = null,
                        currentPageInChapter = 1,
                        initialPageRequest = if (openAtLastPage) Int.MAX_VALUE else 1,
                        totalPagesInChapter = 1,
                        firstVisibleParagraphIndex = 0,
                        isLoading = false,
                        ttsPlayerState = if (autoStartTts) TtsPlayerState.Loading else it.ttsPlayerState
                    )
                }

                scheduleResumeSnapshot(
                    fingerprint = fingerprint,
                    chapterIndex = index,
                    entryName = entryName,
                    normalizedHtml = currentHtml,
                    generation = loadGen
                )

                state.book?.let { book ->
                    enqueueBookBibleChapter(
                        book = book,
                        chapterIndex = index,
                        chapterTitle = state.chapters.getOrNull(index)?.title ?: "Chương ${index + 1}",
                        originalHtml = currentHtml
                    )
                }

                if (autoStartTts) {
                    ttsPreferencesManager.saveLastTtsChunkIndex(bookId, index, 0)
                    startTtsServicePlayback(ttsService)
                }

                // Asynchronously preload adjacent preview chapters and AI cache in background
                adjacentPreloadJob = viewModelScope.launch(Dispatchers.IO) {
                    val prev = state.chapters.getOrNull(index - 1)?.let { header ->
                        runCatching { epubEngine.loadChapterHtml(file, header.entryName) }
                            .getOrNull()
                            ?.also { html ->
                                runCatching {
                                    sanitizeAndCacheChapter(fingerprint, header.entryName, html)
                                }
                            }
                    }
                    val next = state.chapters.getOrNull(index + 1)?.let { header ->
                        runCatching { epubEngine.loadChapterHtml(file, header.entryName) }
                            .getOrNull()
                            ?.also { html ->
                                runCatching {
                                    sanitizeAndCacheChapter(fingerprint, header.entryName, html)
                                }
                            }
                    }
                    val cachedAi = aiVietnameseService.loadCachedChapter(bookId, index, currentHtml)
                    val convertedPrev = convertChapterHtml(prev)
                    val convertedNext = convertChapterHtml(next)

                    if (chapterNavigationGeneration == loadGen && _uiState.value.currentChapterIndex == index) {
                        _uiState.update {
                            it.copy(
                                previousChapterHtml = convertedPrev?.html ?: prev,
                                nextChapterHtml = convertedNext?.html ?: next,
                                sanitizedPreviousChapterHtml = convertedPrev?.sanitized ?: state.chapters
                                    .getOrNull(index - 1)
                                    ?.let { sanitizedChapterCache.get(sanitizedChapterCacheKey(fingerprint, it.entryName)) },
                                sanitizedNextChapterHtml = convertedNext?.sanitized ?: state.chapters
                                    .getOrNull(index + 1)
                                    ?.let { sanitizedChapterCache.get(sanitizedChapterCacheKey(fingerprint, it.entryName)) },
                                aiChapterHtml = cachedAi?.html,
                                contentVersion = if (
                                    requestedContentVersion == ReaderContentVersion.AI && cachedAi != null
                                ) {
                                    ReaderContentVersion.AI
                                } else {
                                    ReaderContentVersion.ORIGINAL
                                },
                                aiCreatedWithOldConfiguration = cachedAi?.createdWithOldConfiguration == true
                            )
                        }
                    }
                }
            }
        }
    }

    fun nextChapter() {
        onChapterSelected(_uiState.value.currentChapterIndex + 1)
    }

    fun previousChapter() {
        onChapterSelected(_uiState.value.currentChapterIndex - 1, openAtLastPage = true)
    }

    /**
     * Nạp trước và sanitize chương đích ngay khi gesture vượt ngưỡng chuyển chương.
     *
     * @param index Chỉ số chương cần chuẩn bị trong danh sách chương hiện tại.
     */
    fun prefetchChapter(index: Int) {
        val state = _uiState.value
        val file = bookFile ?: return
        val header = state.chapters.getOrNull(index) ?: return
        if (index == state.currentChapterIndex) return

        chapterPrefetchJob?.cancel()
        chapterPrefetchJob = viewModelScope.launch(Dispatchers.IO) {
            val fingerprint = EpubCacheFingerprint.fromFile(file)
            val normalizedHtml = runCatching {
                epubEngine.loadChapterHtml(file, header.entryName)
            }.getOrNull() ?: return@launch
            if (!isActive) return@launch
            sanitizeAndCacheChapter(fingerprint, header.entryName, normalizedHtml)
        }
    }

    fun updatePageMetrics(currentPage: Int, totalPages: Int, firstVisibleChunkIndex: Int) {
        val currentState = _uiState.value
        if (currentState.isLoading) return

        // Save the visible chunk index for TTS resuming
        if (!currentState.chapters.isEmpty()) {
            ttsPreferencesManager.saveLastTtsChunkIndex(
                bookId,
                currentState.currentChapterIndex,
                firstVisibleChunkIndex
            )
        }

        if (
            currentState.currentPageInChapter == currentPage &&
            currentState.totalPagesInChapter == totalPages &&
            currentState.initialPageRequest == currentPage &&
            currentState.firstVisibleParagraphIndex == firstVisibleChunkIndex
        ) {
            return
        }
        _uiState.update {
            it.copy(
                currentPageInChapter = currentPage,
                initialPageRequest = currentPage,
                totalPagesInChapter = totalPages,
                firstVisibleParagraphIndex = firstVisibleChunkIndex,
                currentCfi = paragraphLocator(firstVisibleChunkIndex)
            )
        }
        saveProgress()
    }

    private suspend fun loadChapterBundle(
        file: File,
        headers: List<EpubChapterHeader>,
        index: Int
    ): ChapterHtmlBundle {
        val current = epubEngine.loadChapterHtml(file, headers[index].entryName)
        val previous = headers.getOrNull(index - 1)?.let { header ->
            runCatching { epubEngine.loadChapterHtml(file, header.entryName) }.getOrNull()
        }
        val next = headers.getOrNull(index + 1)?.let { header ->
            runCatching { epubEngine.loadChapterHtml(file, header.entryName) }.getOrNull()
        }
        return ChapterHtmlBundle(current, previous, next)
    }

    fun updateSettings(newSettings: ReaderSettings) {
        preferencesManager.saveSettings(newSettings)
    }

    fun toggleControls() {
        _uiState.update { it.copy(showControls = !it.showControls) }
    }

    fun updateCfiPosition(cfi: String) {
        val state = _uiState.value
        if (state.isLoading) return
        _uiState.update { it.copy(currentCfi = cfi) }
        saveProgress()
    }

    private fun saveProgress() {
        val saveVersion = ++progressSaveVersion
        progressSaveJob?.cancel()
        progressSaveJob = viewModelScope.launch {
            val state = _uiState.value
            if (state.isLoading || state.chapters.isEmpty()) {
                return@launch
            }
            kotlinx.coroutines.delay(200) // 200ms debounce
            if (saveVersion != progressSaveVersion) return@launch

            // TTS owns the shared playback/widget projection while it is active.
            if (TtsService.isPlaybackProjectionOwned()) {
                return@launch
            }

            val overallProgress = calculateReaderProgress(state)

            bookRepository.saveReadingProgress(
                ReadingProgress(
                    bookId = bookId,
                    currentCfi = state.currentCfi,
                    chapterIndex = state.currentChapterIndex,
                    pageIndex = state.currentPageInChapter,
                    progressPercentage = overallProgress,
                    totalChapters = state.chapters.size
                )
            )

            scheduleWidgetProjection(state, overallProgress, saveVersion)
        }
    }

    /**
     * Ghi ngay progress cuối cùng, bỏ qua debounce khi reader đi vào background hoặc bị dispose.
     */
    fun flushPendingProgress() {
        val state = _uiState.value
        if (state.isLoading || state.chapters.isEmpty()) return

        progressSaveVersion++
        progressSaveJob?.cancel()
        val overallProgress = calculateReaderProgress(state)

        viewModelScope.launch(Dispatchers.IO) {
            bookRepository.saveReadingProgress(
                ReadingProgress(
                    bookId = bookId,
                    currentCfi = state.currentCfi,
                    chapterIndex = state.currentChapterIndex,
                    pageIndex = state.currentPageInChapter,
                    progressPercentage = overallProgress,
                    totalChapters = state.chapters.size
                )
            )
        }
    }

    private fun scheduleWidgetProjection(state: ReaderUiState, overallProgress: Float, saveVersion: Long) {
        widgetProjectionJob?.cancel()
        widgetProjectionJob = viewModelScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(500) // 500ms debounce for heavier widget projection
            if (saveVersion != progressSaveVersion || TtsService.isPlaybackProjectionOwned()) {
                return@launch
            }

            val currentChapterTitle =
                state.chapters.getOrNull(state.currentChapterIndex)?.title.orEmpty()
            val coverPath = state.book?.coverPath
            val paragraphIndex = state.firstVisibleParagraphIndex.coerceAtLeast(0)
            val displayedChapterHtml = state.displayedChapterHtml
            val bookTitle = state.book?.title.orEmpty()
            val isTtsSpeaking = state.isTtsSpeaking
            val preferAiContent = state.contentVersion == ReaderContentVersion.AI

            // Memoized TtsChunk parsing to avoid repeated Jsoup DOM allocations
            val chapterChunks = if (cachedTtsChunks?.first == displayedChapterHtml) {
                cachedTtsChunks!!.second
            } else {
                val parsed = TtsTextParser.parseHtmlToChunks(displayedChapterHtml)
                cachedTtsChunks = displayedChapterHtml to parsed
                parsed
            }

            val totalParagraphs = chapterChunks
                .maxOfOrNull { it.paragraphIndex + 1 }
                ?.coerceAtLeast(1)
                ?: 1
            val pageText = buildWidgetParagraphText(
                chunks = chapterChunks,
                paragraphIndex = paragraphIndex,
                fallbackHtml = displayedChapterHtml
            )

            if (saveVersion != progressSaveVersion || TtsService.isPlaybackProjectionOwned()) {
                return@launch
            }

            playbackSnapshotStore.saveSnapshot(
                TtsPlaybackSnapshot(
                    bookId = bookId,
                    chapterIndex = state.currentChapterIndex,
                    paragraphIndex = paragraphIndex,
                    sentenceIndex = 0,
                    timelinePositionMs = 0L,
                    preferAiContent = preferAiContent
                )
            )

            widgetStateStore.saveState(
                TtsWidgetState(
                    bookTitle = bookTitle,
                    chapterTitle = currentChapterTitle,
                    playbackStatus = if (isTtsSpeaking) TtsWidgetPlaybackStatus.PLAYING else TtsWidgetPlaybackStatus.IDLE,
                    progress = overallProgress,
                    positionMs = 0L,
                    durationMs = 0L,
                    hasSnapshot = true,
                    coverPath = coverPath,
                    paragraphIndex = paragraphIndex,
                    totalParagraphs = totalParagraphs,
                    paragraphText = pageText
                )
            )
            context.sendBroadcast(Intent(TtsWidgetContract.ACTION_STATE_CHANGED).setPackage(context.packageName))
        }
    }


    /**
     * Chuẩn hóa, băm và ghi snapshot chương trên dispatcher nền sau khi current đã được hiển thị.
     *
     * @param fingerprint Dấu vân tay của tệp EPUB.
     * @param chapterIndex Chỉ số chương cần lưu.
     * @param entryName Tên entry chương.
     * @param normalizedHtml HTML đã chuẩn hóa của chương.
     * @param generation Thế hệ điều hướng dùng để loại bỏ kết quả cũ.
     */
    private fun scheduleResumeSnapshot(
        fingerprint: com.epubpro.core.reader.engine.EpubCacheFingerprint,
        chapterIndex: Int,
        entryName: String,
        normalizedHtml: String,
        generation: Int
    ) {
        resumeSnapshotJob?.cancel()
        resumeSnapshotJob = viewModelScope.launch(Dispatchers.Default) {
            val sanitizedHtml = EpubHtmlSanitizer.sanitize(normalizedHtml)
            sanitizedChapterCache.put(
                sanitizedChapterCacheKey(fingerprint, entryName),
                sanitizedHtml
            )
            val sourceHash = com.epubpro.core.storage.ReaderResumeSnapshot.computeContentHash(normalizedHtml)
            if (generation != chapterNavigationGeneration || !isActive) return@launch
            withContext(Dispatchers.IO) {
                resumeSnapshotStore.saveSnapshot(
                    com.epubpro.core.storage.ReaderResumeSnapshot(
                        bookId = bookId,
                        chapterIndex = chapterIndex,
                        entryName = entryName,
                        canonicalPath = fingerprint.canonicalPath,
                        fileLength = fingerprint.fileLength,
                        lastModified = fingerprint.lastModified,
                        sourceHash = sourceHash,
                        normalizedHtml = normalizedHtml,
                        sanitizedHtml = sanitizedHtml.rawHtml
                    )
                )
            }
        }
    }

    /**
     * Tạo khóa cache sanitizer gắn với đúng phiên bản tệp EPUB và entry chương.
     *
     * @param fingerprint Dấu vân tay hiện tại của tệp EPUB.
     * @param entryName Tên entry chương trong EPUB.
     * @return Khóa ổn định dùng cho cache RAM giới hạn dung lượng.
     */
    private fun sanitizedChapterCacheKey(
        fingerprint: EpubCacheFingerprint,
        entryName: String
    ): String {
        return EpubCacheFingerprint.computeSha256(
            fingerprint.canonicalPath + "|" + fingerprint.fileLength + "|" +
                fingerprint.lastModified + "|" + entryName + "|" +
                EpubHtmlSanitizer.CURRENT_SANITIZER_VERSION
        )
    }

    /**
     * Làm sạch HTML trên dispatcher CPU và lưu kết quả vào cache RAM giới hạn dung lượng.
     *
     * @param fingerprint Dấu vân tay hiện tại của tệp EPUB.
     * @param entryName Tên entry chương.
     * @param normalizedHtml HTML đã chuẩn hóa cần sanitize.
     * @return HTML đã sanitize an toàn.
     */
    private suspend fun sanitizeAndCacheChapter(
        fingerprint: EpubCacheFingerprint,
        entryName: String,
        normalizedHtml: String
    ): SanitizedEpubHtml {
        val sanitizedHtml = withContext(Dispatchers.Default) {
            EpubHtmlSanitizer.sanitize(normalizedHtml)
        }
        sanitizedChapterCache.put(
            sanitizedChapterCacheKey(fingerprint, entryName),
            sanitizedHtml
        )
        return sanitizedHtml
    }

    private fun parseParagraphLocator(locator: String): Int {
        return locator
            .removePrefix(PARAGRAPH_LOCATOR_PREFIX)
            .toIntOrNull()
            ?.takeIf { locator.startsWith(PARAGRAPH_LOCATOR_PREFIX) }
            ?.coerceAtLeast(0)
            ?: 0
    }

    private fun paragraphLocator(index: Int): String =
        PARAGRAPH_LOCATOR_PREFIX + index.coerceAtLeast(0)

    private fun buildWidgetParagraphText(
        chunks: List<TtsChunk>,
        paragraphIndex: Int,
        fallbackHtml: String
    ): String {
        val paragraphText = chunks
            .filter { it.paragraphIndex == paragraphIndex }
            .joinToString(" ") { it.text.trim() }
            .trim()
        if (paragraphText.isNotBlank()) return paragraphText.take(WIDGET_TEXT_MAX_CHARS)

        return runCatching {
            android.text.Html.fromHtml(
                fallbackHtml,
                android.text.Html.FROM_HTML_MODE_LEGACY
            ).toString().trim().take(WIDGET_TEXT_MAX_CHARS)
        }.getOrDefault("")
    }

    fun addBookmark() {
        viewModelScope.launch {
            val state = _uiState.value
            val chapterTitle = state.chapters.getOrNull(state.currentChapterIndex)?.title
                ?: "Chapter ${state.currentChapterIndex + 1}"
            bookmarkRepository.addBookmark(
                Bookmark(
                    id = UUID.randomUUID().toString(),
                    bookId = bookId,
                    chapterIndex = state.currentChapterIndex,
                    chapterTitle = chapterTitle,
                    cfi = state.currentCfi,
                    createdAt = System.currentTimeMillis()
                )
            )
        }
    }

    fun addHighlight(selectedText: String, colorHex: String = "#FFEB3B", note: String? = null) {
        viewModelScope.launch {
            val state = _uiState.value
            bookmarkRepository.addHighlight(
                Highlight(
                    id = UUID.randomUUID().toString(),
                    bookId = bookId,
                    chapterIndex = state.currentChapterIndex,
                    startCfi = state.currentCfi,
                    endCfi = state.currentCfi,
                    selectedText = selectedText,
                    colorHex = colorHex,
                    note = note,
                    createdAt = System.currentTimeMillis()
                )
            )
        }
    }

    /**
     * Mở BottomSheet thay thế từ ngữ với văn bản được chọn.
     *
     * @param selectedText Chuỗi văn bản người dùng vừa bôi đen trong WebView.
     */
    fun openReplaceTextBottomSheet(selectedText: String) {
        _uiState.update { it.copy(replaceTextPattern = selectedText.trim()) }
    }

    /**
     * Đóng BottomSheet thay thế từ ngữ.
     */
    fun dismissReplaceTextBottomSheet() {
        _uiState.update { it.copy(replaceTextPattern = null) }
    }

    /**
     * Lưu quy tắc thay thế từ ngữ vào cấu hình bộ lọc và áp dụng ngay lập tức cho Reader.
     *
     * @param pattern Chuỗi từ/câu gốc cần tìm kiếm.
     * @param replacement Chuỗi mới thay thế (để trống nếu muốn xóa).
     * @param isRegex `true` nếu mẫu tìm kiếm là biểu thức chính quy.
     */
    fun saveReplaceRule(pattern: String, replacement: String, isRegex: Boolean) {
        preferencesManager.updateFilterPreferences { preferences ->
            preferences.withEnabledReplaceRule(pattern, replacement, isRegex)
        }
        dismissReplaceTextBottomSheet()
    }

    /**
     * Thêm đoạn văn bản đang chọn vào content filter hoặc mở hộp thoại thay thế từ ngữ.
     *
     * @param selectedText Văn bản được chọn trong WebView.
     */
    fun addSelectionToContentFilter(selectedText: String) {
        openReplaceTextBottomSheet(selectedText)
    }

    /**
     * Mở bảng Convert Hán-Việt và nạp danh sách Name của truyện.
     */
    fun openConvertSheet() {
        _uiState.update { it.copy(showConvertSheet = true, convertSuggested = false) }
        viewModelScope.launch {
            val names = convertStore.bookNames(bookId)
            _uiState.update { it.copy(convertNames = names, convertHasDictionary = convertStore.hasBaseDictionary()) }
        }
    }

    /** Đóng bảng Convert Hán-Việt. */
    fun dismissConvertSheet() {
        _uiState.update { it.copy(showConvertSheet = false) }
    }

    /** Đánh dấu gợi ý bật Convert đã hiển thị, để không lặp lại trong phiên đọc này. */
    fun consumeConvertSuggestion() {
        _uiState.update { it.copy(convertSuggested = false) }
    }

    /**
     * Tải thêm chương cho truyện lưu từ web, rồi cập nhật mục lục mà không nạp lại trang đang đọc.
     *
     * @param auto true khi tự kích hoạt lúc đọc gần hết: chỉ chạy một lần mỗi mốc và không báo lỗi.
     */
    fun loadMoreWebChapters(auto: Boolean = false) {
        val state = _uiState.value
        if (!state.isWebNovel || appendJob?.isActive == true || state.chapters.isEmpty()) return
        if (auto && autoAppendChapterCount == state.chapters.size) return
        autoAppendChapterCount = state.chapters.size
        // Ghi vị trí mới nhất trước khi worker thay file, để bản mới giữ đúng chương/trang đang đọc.
        flushPendingProgress()
        appendJob = viewModelScope.launch {
            _uiState.update { it.copy(isAppendingChapters = true) }
            val result = try {
                val workId = withContext(Dispatchers.IO) { webNovelScheduler.enqueueAppend(bookId, APPEND_BATCH_CHAPTERS) }
                webNovelScheduler.observe(workId).first { workState ->
                    if (workState is WebNovelDownloadState.Running && workState.waitingForNetwork) {
                        _uiState.update { it.copy(webNovelMessageRes = R.string.reader_web_waiting_network) }
                    }
                    workState !is WebNovelDownloadState.Running
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                WebNovelDownloadState.Failed
            } finally {
                _uiState.update { it.copy(isAppendingChapters = false) }
            }
            when {
                result is WebNovelDownloadState.Succeeded && result.newChapters > 0 -> {
                    refreshAppendedChapters()
                    _uiState.update {
                        it.copy(webNovelMessageRes = if (result.interrupted) R.string.reader_web_partial else R.string.reader_web_chapters_added, webNovelMessageArg = result.newChapters, webNovelFailedUrl = result.failedUrl)
                    }
                }
                result is WebNovelDownloadState.Succeeded ->
                    if (!auto) _uiState.update { it.copy(webNovelMessageRes = R.string.web_novel_no_new_chapters) }
                else -> _uiState.update { it.copy(webNovelMessageRes = R.string.web_novel_download_failed, webNovelFailedUrl = (result as? WebNovelDownloadState.FailedAt)?.url) }
            }
        }
    }

    /** Xóa thông báo tải thêm chương đã hiển thị. */
    fun consumeWebNovelMessage() {
        _uiState.update { it.copy(webNovelMessageRes = null, webNovelFailedUrl = null) }
    }

    /**
     * Cập nhật sách/mục lục sang file EPUB mới sau khi tải thêm chương. Các chương cũ giữ nguyên tên entry và
     * nội dung nên chương đang đọc không cần nạp lại; chỉ nạp preview chương kế để vuốt qua ranh giới mượt.
     */
    private suspend fun refreshAppendedChapters() {
        val book = bookRepository.getBookById(bookId) ?: return
        val file = storageManager.getBookFile(book.filePath)
        if (!file.isFile) return
        val headers = runCatching { epubEngine.extractChapterHeaders(file) }.getOrNull() ?: return
        bookFile = file
        _uiState.update { it.copy(book = book, chapters = headers) }

        val index = _uiState.value.currentChapterIndex
        val header = headers.getOrNull(index + 1) ?: return
        val html = withContext(Dispatchers.IO) {
            runCatching { epubEngine.loadChapterHtml(file, header.entryName) }.getOrNull()
        } ?: return
        val converted = convertChapterHtml(html)
        val sanitized = converted?.sanitized ?: run {
            val fingerprint = withContext(Dispatchers.IO) { EpubCacheFingerprint.fromFile(file) }
            sanitizeAndCacheChapter(fingerprint, header.entryName, html)
        }
        _uiState.update {
            if (it.currentChapterIndex != index || it.nextChapterHtml != null) it
            else it.copy(nextChapterHtml = converted?.html ?: html, sanitizedNextChapterHtml = sanitized)
        }
    }

    /** Xóa thông báo Convert đã hiển thị. */
    fun consumeConvertMessage() {
        _uiState.update { it.copy(convertMessageRes = null) }
    }

    /**
     * Bật/tắt Convert cho sách và nạp lại chương hiện tại theo chế độ mới.
     *
     * @param enabled Trạng thái mới.
     */
    fun setConvertEnabled(enabled: Boolean) {
        if (enabled && !convertStore.hasBaseDictionary()) {
            _uiState.update { it.copy(convertMessageRes = R.string.convert_no_dictionary) }
            return
        }
        convertStore.setConvertEnabled(bookId, enabled)
        hanVietConverter = null
        _uiState.update { it.copy(convertEnabled = enabled, convertSuggested = false) }
        onChapterSelected(_uiState.value.currentChapterIndex)
    }

    /**
     * Thêm hoặc sửa một Name của truyện; chương hiện tại được convert lại qua [ConvertDictionaryStore.revision].
     *
     * @param source Chữ Hán.
     * @param target Tên tiếng Việt.
     */
    fun saveConvertName(source: String, target: String) {
        viewModelScope.launch {
            convertStore.saveBookName(bookId, source, target)
            _uiState.update { it.copy(convertNames = convertStore.bookNames(bookId)) }
        }
    }

    /**
     * Xóa một Name của truyện.
     *
     * @param source Chữ Hán cần xóa.
     */
    fun deleteConvertName(source: String) {
        viewModelScope.launch {
            convertStore.deleteBookName(bookId, source)
            _uiState.update { it.copy(convertNames = convertStore.bookNames(bookId)) }
        }
    }

    /**
     * Bản Convert của một chương kèm HTML đã sanitize tương ứng.
     *
     * @property html HTML đã convert (dùng cho hiển thị và TTS).
     * @property sanitized Kết quả sanitize của [html], truyền vào WebView như bản gốc dùng cache sanitize.
     */
    private data class ConvertedChapter(val html: String, val sanitized: SanitizedEpubHtml)

    /**
     * Convert HTML chương nếu sách đang bật Convert và sanitize luôn bản kết quả.
     *
     * Sanitize ở đây (thay vì để WebView tự làm) giúp key commit chương kề và key nạp chương mới cùng
     * tính trên một chuỗi đã sanitize, nên WebView giữ được DOM khi vuốt qua ranh giới chương.
     * Book Bible và cache AI vẫn dùng bản gốc nên hàm này chỉ phục vụ hiển thị và TTS.
     *
     * @param html HTML gốc, có thể null.
     * @return Bản convert, hoặc null nếu không bật/không có từ điển/convert lỗi.
     */
    private suspend fun convertChapterHtml(html: String?): ConvertedChapter? {
        if (html.isNullOrBlank() || !_uiState.value.convertEnabled) return null
        val converter = hanVietConverter ?: convertStore.createConverter(bookId)?.also { hanVietConverter = it }
            ?: return null
        return withContext(Dispatchers.Default) {
            try {
                val converted = HtmlConverter.convert(html, converter)
                ConvertedChapter(converted, EpubHtmlSanitizer.sanitize(converted))
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
        }
    }

    /** Convert lại chương hiện tại sau khi từ điển đổi, giữ trang đang đọc. */
    private suspend fun refreshConvertedChapter() {
        val state = _uiState.value
        if (!state.convertEnabled || state.currentChapterHtml.isBlank()) return
        val converted = convertChapterHtml(state.currentChapterHtml) ?: return
        _uiState.update {
            if (it.currentChapterHtml != state.currentChapterHtml) it
            else it.copy(
                convertedChapterHtml = converted.html,
                sanitizedConvertedChapterHtml = converted.sanitized,
                initialPageRequest = it.currentPageInChapter
            )
        }
    }

    /**
     * Ước lượng chương là tiếng Trung từ mẫu văn bản đã bỏ thẻ HTML.
     *
     * @param html HTML chương.
     * @return true nếu nên gợi ý bật Convert.
     */
    private fun looksChinese(html: String): Boolean =
        ChineseTextDetector.isLikelyChinese(html.take(CONVERT_DETECTION_SAMPLE).replace(Regex("<[^>]*>"), " "))

    fun openAiBottomSheet() {
        _uiState.update {
            it.copy(showAiBottomSheet = true, aiError = null, aiConnectionMessage = null)
        }
    }

    fun dismissAiBottomSheet() {
        _uiState.update { it.copy(showAiBottomSheet = false) }
    }

    fun saveAiConfiguration(apiKey: String?, modelId: String) {
        runCatching { aiPreferencesManager.saveConfiguration(apiKey, modelId) }
            .onSuccess {
                _uiState.update {
                    it.copy(aiConnectionMessage = "Đã lưu cấu hình AI.", aiError = null)
                }
            }
            .onFailure { error ->
                _uiState.update { it.copy(aiError = error.message ?: "Không thể lưu cấu hình AI.") }
            }
    }

    fun clearAiApiKey() {
        aiPreferencesManager.clearApiKey()
        _uiState.update { it.copy(aiConnectionMessage = "Đã xóa API key.", aiError = null) }
    }

    fun testAiConnection(apiKey: String?, modelId: String) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(isTestingAiConnection = true, aiConnectionMessage = null, aiError = null)
            }
            runCatching { aiVietnameseService.testConnection(apiKey, modelId) }
                .onSuccess {
                    _uiState.update {
                        it.copy(
                            isTestingAiConnection = false,
                            aiConnectionMessage = "Kết nối Gemini thành công."
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isTestingAiConnection = false,
                            aiError = error.message ?: "Không thể kết nối Gemini."
                        )
                    }
                }
        }
    }

    fun setContentVersion(version: ReaderContentVersion) {
        val state = _uiState.value
        if (version == ReaderContentVersion.AI && state.aiChapterHtml == null) return
        _uiState.update {
            it.copy(
                contentVersion = version,
                initialPageRequest = 1,
                currentPageInChapter = 1,
                totalPagesInChapter = 1,
                firstVisibleParagraphIndex = 0,
                currentCfi = ""
            )
        }
    }

    fun startAiPolish() {
        val state = _uiState.value
        if (!state.aiSettings.hasApiKey) {
            _uiState.update { it.copy(aiError = "Hãy nhập Gemini API key trước.") }
            return
        }
        if (state.currentChapterHtml.isBlank() || state.isAiProcessing) return

        val chapterIndex = state.currentChapterIndex
        val sourceHtml = state.currentChapterHtml
        aiProcessingJob?.cancel()
        aiProcessingJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isAiProcessing = true,
                    aiCompletedParts = 0,
                    aiTotalParts = 0,
                    aiError = null
                )
            }
            try {
                val html = aiVietnameseService.polishChapter(
                    bookId = bookId,
                    chapterIndex = chapterIndex,
                    sourceHtml = sourceHtml
                ) { progress ->
                    if (_uiState.value.currentChapterIndex == chapterIndex) {
                        _uiState.update {
                            it.copy(
                                aiCompletedParts = progress.completedParts,
                                aiTotalParts = progress.totalParts
                            )
                        }
                    }
                }
                if (_uiState.value.currentChapterIndex == chapterIndex) {
                    _uiState.update {
                        it.copy(
                            aiChapterHtml = html,
                            contentVersion = ReaderContentVersion.AI,
                            aiCreatedWithOldConfiguration = false,
                            isAiProcessing = false,
                            aiError = null
                        )
                    }
                }
            } catch (error: CancellationException) {
                if (_uiState.value.currentChapterIndex == chapterIndex) {
                    _uiState.update { it.copy(isAiProcessing = false) }
                }
                throw error
            } catch (error: Exception) {
                if (_uiState.value.currentChapterIndex == chapterIndex) {
                    _uiState.update {
                        it.copy(
                            isAiProcessing = false,
                            aiError = error.message ?: "Không thể thuần Việt chương này."
                        )
                    }
                }
            }
        }
    }

    fun cancelAiPolish() {
        aiProcessingJob?.cancel()
        _uiState.update { it.copy(isAiProcessing = false) }
    }

    fun deleteCurrentAiChapter() {
        val chapterIndex = _uiState.value.currentChapterIndex
        viewModelScope.launch {
            aiVietnameseService.deleteChapter(bookId, chapterIndex)
            if (_uiState.value.currentChapterIndex == chapterIndex) {
                _uiState.update {
                    it.copy(
                        aiChapterHtml = null,
                        contentVersion = ReaderContentVersion.ORIGINAL,
                        aiCreatedWithOldConfiguration = false,
                        aiCompletedParts = 0,
                        aiTotalParts = 0,
                        aiError = null
                    )
                }
            }
        }
    }

    fun saveAiRule(
        ruleId: String?,
        scope: AiRuleScope,
        source: String,
        action: AiRuleAction,
        replacement: String?,
        caseSensitive: Boolean
    ) {
        val normalizedSource = source.trim()
        val normalizedReplacement = replacement?.trim()
        if (normalizedSource.isBlank()) {
            _uiState.update { it.copy(aiError = "Thuật ngữ không được để trống.") }
            return
        }
        if (action == AiRuleAction.REPLACE && normalizedReplacement.isNullOrBlank()) {
            _uiState.update { it.copy(aiError = "Hãy nhập nội dung thay thế.") }
            return
        }

        val duplicate = _uiState.value.aiRules.any { existing ->
            existing.id != ruleId &&
                    existing.scope == scope &&
                    existing.source.equals(normalizedSource, ignoreCase = !caseSensitive)
        }
        if (duplicate) {
            _uiState.update { it.copy(aiError = "Đã có quy tắc cùng phạm vi cho thuật ngữ này.") }
            return
        }

        viewModelScope.launch {
            aiRuleRepository.upsertRule(
                AiRule(
                    id = ruleId ?: UUID.randomUUID().toString(),
                    scope = scope,
                    bookId = if (scope == AiRuleScope.BOOK) bookId else null,
                    source = normalizedSource,
                    action = action,
                    replacement = normalizedReplacement.takeIf { action == AiRuleAction.REPLACE },
                    caseSensitive = caseSensitive,
                    updatedAt = System.currentTimeMillis()
                )
            )
            _uiState.update { it.copy(aiError = null) }
        }
    }

    fun deleteAiRule(ruleId: String) {
        viewModelScope.launch {
            aiRuleRepository.deleteRule(ruleId)
            _uiState.update { it.copy(aiError = null) }
        }
    }

    private suspend fun refreshCurrentAiCache() {
        val state = _uiState.value
        if (state.currentChapterHtml.isBlank() || state.isLoading || state.isAiProcessing) return
        val cached = aiVietnameseService.loadCachedChapter(
            bookId,
            state.currentChapterIndex,
            state.currentChapterHtml
        )
        if (_uiState.value.currentChapterIndex == state.currentChapterIndex) {
            _uiState.update {
                it.copy(
                    aiChapterHtml = cached?.html,
                    contentVersion = if (
                        it.contentVersion == ReaderContentVersion.AI && cached != null
                    ) {
                        ReaderContentVersion.AI
                    } else {
                        ReaderContentVersion.ORIGINAL
                    },
                    aiCreatedWithOldConfiguration = cached?.createdWithOldConfiguration == true
                )
            }
        }
    }

    private fun consumeOpenTtsPlayerRequest(): Boolean {
        if (savedStateHandle.get<Boolean>(TtsOpenBookContract.NAV_ARGUMENT_OPEN_TTS_PLAYER) != true) {
            return false
        }
        if (savedStateHandle.get<Boolean>(STATE_TTS_PLAYER_REQUEST_CONSUMED) == true) {
            return false
        }
        savedStateHandle[STATE_TTS_PLAYER_REQUEST_CONSUMED] = true
        savedStateHandle[STATE_SHOW_TTS_PLAYER] = true
        return true
    }

    fun onTtsIconButtonClicked() {
        val ttsSettings = ttsPreferencesManager.getSettings()
        if (!ttsSettings.isConfigured) {
            _uiState.update { it.copy(showTtsSetupBottomSheet = true) }
        } else {
            setTtsPlayerVisible(true)
        }
    }

    fun dismissTtsSetupBottomSheet() {
        _uiState.update { it.copy(showTtsSetupBottomSheet = false) }
    }

    fun openTtsSetupBottomSheet() {
        _uiState.update { it.copy(showTtsSetupBottomSheet = true) }
    }

    fun openTtsPlayerScreen() {
        setTtsPlayerVisible(true)
    }

    fun closeTtsPlayerScreen() {
        setTtsPlayerVisible(false)
    }

    fun updateTtsSettings(newSettings: TtsSettings, ttsService: TtsService?) {
        ttsPreferencesManager.saveSettings(newSettings)
        ttsService?.updateSettings(newSettings)
    }

    fun onStartListeningFromSetup(newSettings: TtsSettings, ttsService: TtsService?) {
        ttsPreferencesManager.saveSettings(newSettings)
        savedStateHandle[STATE_SHOW_TTS_PLAYER] = true
        _uiState.update {
            it.copy(
                ttsSettings = newSettings,
                showTtsSetupBottomSheet = false,
                showTtsPlayerScreen = true
            )
        }
        ttsService?.updateSettings(newSettings)
        startTtsServicePlayback(ttsService)
    }

    fun startTtsServicePlayback(ttsService: TtsService?) {
        val state = _uiState.value
        val html = state.displayedChapterHtml
        val cachedChunks = cachedTtsChunks?.takeIf { it.first == html }?.second
        if (cachedChunks != null) {
            loadTtsContent(state, cachedChunks, ttsService)
            return
        }

        viewModelScope.launch(Dispatchers.Default) {
            val chunks = TtsTextParser.parseHtmlToChunks(html)
            cachedTtsChunks = html to chunks
            withContext(Dispatchers.Main.immediate) {
                if (_uiState.value.currentChapterIndex == state.currentChapterIndex &&
                    _uiState.value.displayedChapterHtml == html
                ) {
                    loadTtsContent(state, chunks, ttsService)
                }
            }
        }
    }

    /**
     * Gửi các chunk TTS đã chuẩn bị tới service trên Main thread với vị trí đọc hợp lệ.
     *
     * @param state Trạng thái reader tại thời điểm yêu cầu phát.
     * @param chunks Danh sách chunk đã parse.
     * @param ttsService Service TTS đích.
     */
    private fun loadTtsContent(
        state: ReaderUiState,
        chunks: List<TtsChunk>,
        ttsService: TtsService?
    ) {
        val savedChunkIndex =
            ttsPreferencesManager.getLastTtsChunkIndex(bookId, state.currentChapterIndex)
        val validIndex = savedChunkIndex.coerceIn(0, (chunks.size - 1).coerceAtLeast(0))

        ttsService?.loadContent(
            id = bookId,
            title = state.book?.title ?: "EpubPro",
            bookAuthor = state.book?.author ?: "Unknown",
            parsedChunks = chunks,
            startIndex = validIndex,
            chapterIndex = state.currentChapterIndex,
            chapterTitle = state.chapters.getOrNull(state.currentChapterIndex)?.title.orEmpty(),
            preferAiContent = state.contentVersion == ReaderContentVersion.AI
        )
    }

    fun toggleTtsPlayback(ttsService: TtsService?) {
        val state = _uiState.value
        when (state.ttsPlayerState) {
            is TtsPlayerState.Preparing,
            is TtsPlayerState.Playing -> ttsService?.pause()

            is TtsPlayerState.Paused -> ttsService?.resume()
            else -> startTtsServicePlayback(ttsService)
        }
    }

    fun stopTtsPlayback(ttsService: TtsService?) {
        ttsService?.stop()
        _uiState.update { it.copy(ttsPlayerState = TtsPlayerState.Idle) }
    }

    fun nextTtsChunk(ttsService: TtsService?) {
        ttsService?.nextChunk()
    }

    fun prevTtsChunk(ttsService: TtsService?) {
        ttsService?.previousChunk()
    }

    private companion object {
        const val STATE_TTS_PLAYER_REQUEST_CONSUMED = "tts_player_request_consumed"
        const val STATE_SHOW_TTS_PLAYER = "show_tts_player_screen"
        const val PARAGRAPH_LOCATOR_PREFIX = "epubpro:paragraph:"
    }

    private fun setTtsPlayerVisible(visible: Boolean) {
        savedStateHandle[STATE_SHOW_TTS_PLAYER] = visible
        _uiState.update { it.copy(showTtsPlayerScreen = visible) }
    }

    fun seekTtsChunk(index: Int, ttsService: TtsService?) {
        ttsService?.seekToChunk(index)
    }

    fun setSleepTimerOption(option: SleepTimerOption, ttsService: TtsService?) {
        _uiState.update { it.copy(selectedSleepTimer = option) }
        ttsService?.setSleepTimer(option)
    }

    /**
     * Đưa chương nguồn vừa nạp vào hàng đợi gửi Book Bible theo cơ chế bất đồng bộ, không chặn UI.
     */
    private fun enqueueBookBibleChapter(
        book: Book,
        chapterIndex: Int,
        chapterTitle: String,
        originalHtml: String
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val plainText = Jsoup.parse(originalHtml).text()
                if (plainText.isNotBlank()) {
                    val totalChapters = book.totalChapters.takeIf { it > 0 } ?: _uiState.value.chapters.size
                    bookBibleRepository.enqueueChapterSubmission(
                        source = BookBibleSource(BookBibleSourceType.LOCAL_EPUB, book.id),
                        chapterNumber = chapterIndex + 1,
                        chapterTitle = chapterTitle,
                        totalChapters = totalChapters,
                        sourceContent = plainText,
                        bookTitle = book.title,
                        author = book.author
                    )
                }
            }
        }
    }
}
