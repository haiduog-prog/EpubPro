package com.epubpro.feature.library

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.epubpro.core.designsystem.R
import com.epubpro.core.bookconverter.BookConversionException
import com.epubpro.core.reader.engine.EpubEngine
import com.epubpro.core.reader.tts.TtsWidgetContract
import com.epubpro.core.storage.BookFileTooLargeException
import com.epubpro.core.storage.ComicPreferencesStore
import com.epubpro.core.storage.ComicTranslationStore
import com.epubpro.core.storage.ConvertDictionaryStore
import com.epubpro.core.storage.EpubStorageManager
import com.epubpro.core.storage.WebNovelSourceStore
import com.epubpro.core.storage.ReaderResumeSnapshotStore
import com.epubpro.core.storage.TtsPlaybackSnapshotStore
import com.epubpro.core.storage.TtsWidgetState
import com.epubpro.core.storage.TtsWidgetStateStore
import com.epubpro.core.storage.worker.EpubImportScheduler
import com.epubpro.core.storage.worker.EpubImportWorker
import com.epubpro.core.storage.worker.LocalBookImportScheduler
import com.epubpro.core.storage.worker.LocalBookImportWorker
import com.epubpro.domain.model.Book
import com.epubpro.domain.model.ImportJobStatus
import com.epubpro.domain.repository.BookBibleRepository
import com.epubpro.domain.repository.BookRepository
import com.epubpro.domain.repository.SearchRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlinx.coroutines.withContext

/**
 * Trạng thái giao diện biểu diễn một cuốn sách và tiến độ đọc tương ứng.
 *
 * @property book Đối tượng sách trong cơ sở dữ liệu.
 * @property currentChapter Vị trí chương hiện tại (1-indexed).
 * @property totalChapters Tổng số chương của cuốn sách.
 * @property progressPercentage Tỷ lệ hoàn thành đọc (0.0 -> 1.0).
 */
data class BookItemUiState(
    val book: Book,
    val currentChapter: Int = 0,
    val totalChapters: Int = 0,
    val progressPercentage: Float = 0f
)

/**
 * Bộ lọc danh mục sách trong thư viện theo tiến độ đọc.
 */
enum class LibraryFilter {
    ALL,
    READING,
    UNREAD,
    COMPLETED
}

/**
 * Trạng thái toàn diện của màn hình Thư Viện.
 *
 * @property books Danh sách sách sau khi lọc tìm kiếm và bộ lọc tiến độ.
 * @property totalBookCount Tổng số sách trong thư viện trước khi lọc.
 * @property readingBookCount Số lượng sách đang đọc dở.
 * @property unreadBookCount Số lượng sách chưa bắt đầu đọc.
 * @property completedBookCount Số lượng sách đã hoàn thành.
 * @property selectedFilter Bộ lọc đang được kích hoạt.
 * @property isLoading Trạng thái tải dữ liệu ban đầu.
 * @property searchQuery Từ khóa tìm kiếm hiện tại.
 * @property uploadJobStatus Trạng thái tiến trình tải sách lên server chạy ngầm.
 * @property localImportJobStatus Trạng thái tiến trình nạp sách cục bộ chạy ngầm.
 */
data class LibraryUiState(
    val books: List<BookItemUiState> = emptyList(),
    val totalBookCount: Int = 0,
    val readingBookCount: Int = 0,
    val unreadBookCount: Int = 0,
    val completedBookCount: Int = 0,
    val selectedFilter: LibraryFilter = LibraryFilter.ALL,
    val isLoading: Boolean = false,
    val searchQuery: String = "",
    val uploadJobStatus: ImportJobStatus? = null,
    val localImportJobStatus: ImportJobStatus? = null
)

/**
 * Mô tả broadcast thay đổi trạng thái widget trước khi chuyển thành Android Intent.
 *
 * @property action Action nội bộ của broadcast.
 * @property packageName Package đích nhận broadcast.
 */
internal data class TtsWidgetStateChangedRequest(
    val action: String,
    val packageName: String
)

/**
 * Tạo contract broadcast cập nhật widget theo package của ứng dụng.
 *
 * @param packageName Package ứng dụng nhận broadcast.
 * @return Contract gồm action và package đích.
 */
internal fun createTtsWidgetStateChangedRequest(
    packageName: String
): TtsWidgetStateChangedRequest = TtsWidgetStateChangedRequest(
    action = TtsWidgetContract.ACTION_STATE_CHANGED,
    packageName = packageName
)

/**
 * Phát tín hiệu nội bộ để các widget TTS đọc lại trạng thái đã được lưu.
 *
 * @param context Context ứng dụng dùng để gửi broadcast giới hạn trong chính package.
 */
internal fun broadcastTtsWidgetStateChanged(context: Context) {
    val request = createTtsWidgetStateChangedRequest(context.packageName)
    context.sendBroadcast(
        Intent(request.action).setPackage(request.packageName)
    )
}

/**
 * ViewModel quản lý danh sách sách trong thư viện, tìm kiếm, nhập file EPUB nội bộ
 * và điều phối tác vụ tải sách lên server chạy ngầm qua [WorkManager].
 */
@HiltViewModel
class LibraryViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bookRepository: BookRepository,
    private val searchRepository: SearchRepository,
    private val storageManager: EpubStorageManager,
    private val epubEngine: EpubEngine,
    private val epubImportScheduler: EpubImportScheduler,
    private val localBookImportScheduler: LocalBookImportScheduler,
    private val snapshotStore: ReaderResumeSnapshotStore,
    private val ttsPlaybackSnapshotStore: TtsPlaybackSnapshotStore,
    private val ttsWidgetStateStore: TtsWidgetStateStore,
    private val bookBibleRepository: BookBibleRepository,
    private val comicPreferencesStore: ComicPreferencesStore,
    private val convertDictionaryStore: ConvertDictionaryStore,
    private val comicTranslationStore: ComicTranslationStore,
    private val webNovelSourceStore: WebNovelSourceStore
) : ViewModel() {

    val pendingLocalImports = localBookImportScheduler.pendingImports

    /**
     * Áp dụng lựa chọn cho yêu cầu import đang chờ.
     *
     * @param sourcePath Khóa yêu cầu import.
     * @param replacementBookId ID truyện cần cập nhật hoặc null để tạo bản mới.
     */
    fun resolveLocalImport(sourcePath: String, replacementBookId: String?) {
        viewModelScope.launch {
            try {
                localBookImportScheduler.resolve(sourcePath, replacementBookId)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                _events.send(UserMessage(R.string.library_import_failed))
            }
        }
    }

    /**
     * Hủy lựa chọn trùng ID và dọn file chưa nhập vào thư viện.
     *
     * @param sourcePath Khóa yêu cầu cần hủy.
     */
    fun dismissPendingLocalImport(sourcePath: String) {
        viewModelScope.launch { localBookImportScheduler.dismiss(sourcePath) }
    }

    private val _searchQuery = MutableStateFlow("")
    private val _selectedFilter = MutableStateFlow(LibraryFilter.ALL)
    private val _events = Channel<UserMessage>(capacity = Channel.BUFFERED)
    val events = _events.receiveAsFlow()
    private val _uploadJobState = MutableStateFlow<ImportJobStatus?>(null)
    private val _localImportJobState = MutableStateFlow<ImportJobStatus?>(null)
    private var isDialogDismissedByUser = false
    private var hasActiveUploadSession = false
    private var isLocalDialogDismissedByUser = false
    private var hasActiveLocalImportSession = false

    init {
        observeImportWorkerProgress()
        observeLocalImportWorkerProgress()
        ensureMissingCoversExtracted()
    }

    /**
     * Cập nhật bộ lọc tiến độ đọc hiện tại của thư viện.
     *
     * @param filter Bộ lọc mới được chọn.
     */
    fun onFilterSelected(filter: LibraryFilter) {
        _selectedFilter.value = filter
    }

    /**
     * Tự động quét và trích xuất ảnh bìa chạy nền cho những cuốn sách cũ trong thư viện chưa có coverPath.
     */
    private fun ensureMissingCoversExtracted() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                bookRepository.getAllBooks().first().forEach { book ->
                    // Bìa truyện tranh được tạo lúc import; EpubEngine không đọc được archive CBZ.
                    if (book.coverPath.isNullOrBlank() && book.filePath.isNotBlank() && !book.sourceFormat.isComic) {
                        val file = java.io.File(book.filePath)
                        if (file.isFile) {
                            val extractedCover = epubEngine.extractCoverImage(file)
                            if (!extractedCover.isNullOrBlank()) {
                                bookRepository.updateCoverPathIfMissing(
                                    id = book.id,
                                    filePath = book.filePath,
                                    coverPath = extractedCover
                                )
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Background scan không làm gián đoạn luồng chính
            }
        }
    }

    private val bookItems = combine(
        bookRepository.getAllBooks(),
        bookRepository.getAllReadingProgress()
    ) { books, progressList ->
        val progressMap = progressList.associateBy { it.bookId }
        books.map { book ->
            val progress = progressMap[book.id]
            val currentChapter = if (progress != null) progress.chapterIndex + 1 else 0
            val totalChapters = if (progress != null && progress.totalChapters > 0) {
                progress.totalChapters
            } else {
                book.totalChapters
            }
            BookItemUiState(
                book = book,
                currentChapter = currentChapter,
                totalChapters = totalChapters,
                progressPercentage = progress?.progressPercentage ?: 0f
            )
        }
    }

    val uiState: StateFlow<LibraryUiState> = combine(
        bookItems,
        _searchQuery,
        _selectedFilter,
        _uploadJobState,
        _localImportJobState
    ) { items, query, filter, uploadStatus, localImportStatus ->
        val totalCount = items.size
        val readingCount = items.count { it.progressPercentage > 0f && it.progressPercentage < 1f }
        val unreadCount = items.count { it.progressPercentage == 0f && it.currentChapter == 0 }
        val completedCount = items.count { it.progressPercentage >= 1f || (it.totalChapters > 0 && it.currentChapter >= it.totalChapters) }

        val queryFiltered = if (query.isBlank()) items else items.filter {
            it.book.title.contains(query, ignoreCase = true) ||
                it.book.author.contains(query, ignoreCase = true)
        }

        val finalFiltered = when (filter) {
            LibraryFilter.ALL -> queryFiltered
            LibraryFilter.READING -> queryFiltered.filter { it.progressPercentage > 0f && it.progressPercentage < 1f }
            LibraryFilter.UNREAD -> queryFiltered.filter { it.progressPercentage == 0f && it.currentChapter == 0 }
            LibraryFilter.COMPLETED -> queryFiltered.filter { it.progressPercentage >= 1f || (it.totalChapters > 0 && it.currentChapter >= it.totalChapters) }
        }

        LibraryUiState(
            books = finalFiltered,
            totalBookCount = totalCount,
            readingBookCount = readingCount,
            unreadBookCount = unreadCount,
            completedBookCount = completedCount,
            selectedFilter = filter,
            isLoading = false,
            searchQuery = query,
            uploadJobStatus = uploadStatus,
            localImportJobStatus = localImportStatus
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LibraryUiState(isLoading = true))

    /**
     * Lắng nghe trạng thái và tiến độ từ [EpubImportWorker] để đồng bộ lên UI khi màn hình đang mở.
     */
    private fun observeImportWorkerProgress() {
        viewModelScope.launch {
            WorkManager.getInstance(context)
                .getWorkInfosForUniqueWorkFlow(EpubImportWorker.UNIQUE_WORK_NAME)
                .collect { workInfos ->
                    val activeWork = workInfos.firstOrNull { !it.state.isFinished }
                        ?: workInfos.firstOrNull()
                    if (activeWork != null) {
                        if (!activeWork.state.isFinished) {
                            hasActiveUploadSession = true
                        }
                        when (activeWork.state) {
                            WorkInfo.State.RUNNING -> {
                                val progress = activeWork.progress.getInt(EpubImportWorker.KEY_PROGRESS, 0)
                                val currentStep = activeWork.progress.getString(EpubImportWorker.KEY_CURRENT_STEP)
                                val title = activeWork.progress.getString(EpubImportWorker.KEY_TITLE)
                                val novelId = activeWork.progress.getString(EpubImportWorker.KEY_NOVEL_ID)
                                val status = activeWork.progress.getString(EpubImportWorker.KEY_STATUS) ?: "processing"
                                val error = activeWork.progress.getString(EpubImportWorker.KEY_ERROR_MESSAGE)

                                if (!isDialogDismissedByUser) {
                                    _uploadJobState.value = ImportJobStatus(
                                        jobId = "",
                                        novelId = novelId,
                                        title = title,
                                        status = status,
                                        currentStep = currentStep,
                                        currentChapter = 0,
                                        totalChapters = 0,
                                        progressPercentage = progress,
                                        errorMessage = error,
                                        createdAt = null,
                                        completedAt = null
                                    )
                                }
                            }
                            WorkInfo.State.SUCCEEDED -> {
                                val shouldNotify = hasActiveUploadSession
                                hasActiveUploadSession = false
                                isDialogDismissedByUser = false
                                val title = activeWork.outputData.getString(EpubImportWorker.KEY_TITLE) ?: ""
                                if (shouldNotify && title.isNotBlank()) {
                                    _events.send(
                                        UserMessage(
                                            textRes = R.string.epub_import_notification_success,
                                            formatArgs = listOf(title)
                                        )
                                    )
                                }
                                _uploadJobState.value = null
                            }
                            WorkInfo.State.FAILED -> {
                                val shouldNotify = hasActiveUploadSession
                                hasActiveUploadSession = false
                                isDialogDismissedByUser = false
                                val error = activeWork.outputData.getString(EpubImportWorker.KEY_ERROR_MESSAGE)
                                if (shouldNotify && !error.isNullOrBlank()) {
                                    _events.send(
                                        UserMessage(
                                            textRes = R.string.epub_import_notification_failed,
                                            formatArgs = listOf(error)
                                        )
                                    )
                                }
                                _uploadJobState.value = null
                            }
                            WorkInfo.State.CANCELLED -> {
                                hasActiveUploadSession = false
                                isDialogDismissedByUser = false
                                _uploadJobState.value = null
                            }
                            else -> {
                                // ENQUEUED / BLOCKED: Giữ trạng thái hiện tại hoặc đợi RUNNING
                            }
                        }
                    }
                }
        }
    }

    /** Lắng nghe tiến độ chuyển đổi file local từ [LocalBookImportWorker]. */
    private fun observeLocalImportWorkerProgress() {
        viewModelScope.launch {
            WorkManager.getInstance(context)
                .getWorkInfosByTagFlow(LocalBookImportWorker.TAG)
                .collect { workInfos ->
                    val activeWork = workInfos.lastOrNull { !it.state.isFinished }
                        ?: workInfos.lastOrNull()
                    if (activeWork == null) return@collect

                    when (activeWork.state) {
                        WorkInfo.State.ENQUEUED,
                        WorkInfo.State.RUNNING -> {
                            hasActiveLocalImportSession = true
                            if (!isLocalDialogDismissedByUser) {
                                _localImportJobState.value = ImportJobStatus(
                                    jobId = activeWork.id.toString(),
                                    novelId = null,
                                    title = activeWork.progress.getString(LocalBookImportWorker.KEY_TITLE),
                                    status = "processing",
                                    currentStep = activeWork.progress.getString(LocalBookImportWorker.KEY_CURRENT_STEP),
                                    currentChapter = 0,
                                    totalChapters = 0,
                                    progressPercentage = activeWork.progress.getInt(LocalBookImportWorker.KEY_PROGRESS, 0),
                                    errorMessage = null,
                                    createdAt = null,
                                    completedAt = null
                                )
                            }
                        }
                        WorkInfo.State.SUCCEEDED -> {
                            val shouldNotify = hasActiveLocalImportSession
                            hasActiveLocalImportSession = false
                            isLocalDialogDismissedByUser = false
                            val title = activeWork.outputData.getString(LocalBookImportWorker.KEY_TITLE).orEmpty()
                            _localImportJobState.value = null
                            if (shouldNotify && title.isNotBlank()) {
                                _events.send(UserMessage(R.string.library_import_success, listOf(title)))
                            }
                        }
                        WorkInfo.State.FAILED -> {
                            val shouldNotify = hasActiveLocalImportSession
                            hasActiveLocalImportSession = false
                            isLocalDialogDismissedByUser = false
                            _localImportJobState.value = null
                            if (shouldNotify) {
                                _events.send(UserMessage(R.string.library_import_book_failed))
                            }
                        }
                        WorkInfo.State.CANCELLED -> {
                            hasActiveLocalImportSession = false
                            isLocalDialogDismissedByUser = false
                            _localImportJobState.value = null
                        }
                        else -> Unit
                    }
                }
        }
    }

    /**
     * Cập nhật từ khóa tìm kiếm sách trong thư viện.
     *
     * @param query Từ khóa người dùng nhập vào ô tìm kiếm.
     */
    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
    }

    /**
     * Nạp file sách từ thiết bị hoặc cloud provider vào thư viện sách cục bộ.
     *
     * @param uri URI nguồn của file sách được chọn từ Storage Access Framework.
     * @param originalName Tên gốc của file; null để đọc metadata trên dispatcher I/O.
     */
    fun importBook(uri: Uri, originalName: String?) {
        viewModelScope.launch {
            try {
                hasActiveLocalImportSession = true
                isLocalDialogDismissedByUser = false
                _localImportJobState.value = ImportJobStatus(
                    jobId = "",
                    novelId = null,
                    title = originalName,
                    status = "pending",
                    currentStep = context.getString(R.string.book_conversion_notification_starting),
                    currentChapter = 0,
                    totalChapters = 0,
                    progressPercentage = 0,
                    errorMessage = null,
                    createdAt = null,
                    completedAt = null
                )
                val displayName = originalName ?: withContext(Dispatchers.IO) {
                    context.contentResolver.query(
                        uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
                    )?.use { cursor ->
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (cursor.moveToFirst() && nameIndex >= 0) cursor.getString(nameIndex) else null
                    }
                }
                if (localBookImportScheduler.enqueue(uri, displayName) == null) {
                    hasActiveLocalImportSession = false
                    _localImportJobState.value = null
                }
            } catch (error: CancellationException) {
                hasActiveLocalImportSession = false
                throw error
            } catch (error: BookFileTooLargeException) {
                hasActiveLocalImportSession = false
                _localImportJobState.value = null
                _events.send(UserMessage(R.string.book_import_error_too_large, listOf(error.limitMiB)))
            } catch (_: BookConversionException) {
                hasActiveLocalImportSession = false
                _localImportJobState.value = null
                _events.send(UserMessage(R.string.library_import_book_failed))
            } catch (_: Exception) {
                hasActiveLocalImportSession = false
                _localImportJobState.value = null
                _events.send(UserMessage(R.string.library_import_failed))
            }
        }
    }

    /**
     * Tương thích với caller cũ trước khi luồng local hỗ trợ nhiều định dạng.
     *
     * @param uri URI file được chọn.
     * @param originalName Tên file gốc.
     */
    fun importEpub(uri: Uri, originalName: String?) = importBook(uri, originalName)

    /**
     * Lập lịch tải file sách lên máy chủ backend thông qua [WorkManager] và [EpubImportWorker].
     * Tác vụ sẽ tự động hiển thị Notification cập nhật tiến trình và tiếp tục chạy ngay cả khi tắt ứng dụng.
     *
     * @param uri URI của file sách trên thiết bị.
     * @param originalName Tên hiển thị ban đầu của file.
     */
    fun uploadEpubToServer(uri: Uri, originalName: String?, contentType: String? = null) {
        viewModelScope.launch {
            try {
                isDialogDismissedByUser = false
                // Hiển thị trạng thái khởi tạo ảo trên UI
                _uploadJobState.value = ImportJobStatus(
                    jobId = "",
                    novelId = null,
                    title = originalName,
                    status = "pending",
                    currentStep = context.getString(R.string.epub_import_notification_starting),
                    currentChapter = 0,
                    totalChapters = 0,
                    progressPercentage = 0,
                    errorMessage = null,
                    createdAt = null,
                    completedAt = null
                )

                val enqueued = epubImportScheduler.enqueue(
                    uri = uri,
                    originalName = originalName,
                    isTranslated = true,
                    autoScanCharacters = true,
                    contentType = contentType
                )
                if (enqueued) {
                    hasActiveUploadSession = true
                    _events.send(UserMessage(R.string.upload_started_background))
                } else {
                    hasActiveUploadSession = false
                    _events.send(UserMessage(R.string.upload_already_running))
                    _uploadJobState.value = null
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                hasActiveUploadSession = false
                _events.send(UserMessage(R.string.upload_prepare_failed))
                _uploadJobState.value = null
            }
        }
    }

    /**
     * Ẩn hộp thoại tiến trình trên giao diện mà không làm hủy tác vụ Worker đang chạy ngầm.
     */
    fun dismissUploadDialog() {
        isDialogDismissedByUser = true
        _uploadJobState.value = null
    }

    /** Ẩn dialog chuyển đổi local nhưng giữ Worker tiếp tục chạy nền. */
    fun dismissLocalImportDialog() {
        isLocalDialogDismissedByUser = true
        _localImportJobState.value = null
    }

    /** Hủy các tác vụ chuyển đổi local đang chờ hoặc đang chạy. */
    fun cancelLocalImportWork() {
        hasActiveLocalImportSession = false
        isLocalDialogDismissedByUser = false
        WorkManager.getInstance(context).cancelAllWorkByTag(LocalBookImportWorker.TAG)
        _localImportJobState.value = null
    }

    /**
     * Hủy bỏ hoàn toàn tác vụ nạp truyện đang chạy ngầm trong [WorkManager].
     */
    fun cancelUploadWork() {
        hasActiveUploadSession = false
        isDialogDismissedByUser = false
        WorkManager.getInstance(context).cancelUniqueWork(EpubImportWorker.UNIQUE_WORK_NAME)
        _uploadJobState.value = null
    }

    /**
     * Xóa một cuốn sách khỏi thư viện và dọn toàn bộ dữ liệu phụ thuộc của sách.
     *
     * Luồng này xóa cache EPUB, resume snapshot, TTS snapshot/widget state, AI cache,
     * Book Bible data và bản ghi sách trong database. Nếu sách đang là snapshot TTS hiện tại,
     * broadcast nội bộ sẽ được gửi để các widget cập nhật ngay lập tức.
     *
     * @param item Trạng thái UI chứa sách cần xóa.
     */
    fun deleteBook(item: BookItemUiState) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (ttsPlaybackSnapshotStore.getSnapshot()?.bookId == item.book.id) {
                        ttsPlaybackSnapshotStore.clearSnapshot()
                        ttsWidgetStateStore.saveState(TtsWidgetState())
                        broadcastTtsWidgetStateChanged(context)
                    }
                    epubEngine.deleteBookCache(item.book.filePath)
                    snapshotStore.deleteSnapshot(item.book.id)
                    storageManager.deleteBookFile(item.book.filePath)
                    storageManager.deleteCoverFile(item.book.coverPath)
                    storageManager.deleteAiBookCache(item.book.id)
                    bookBibleRepository.deleteDataForBook(item.book.id)
                    comicPreferencesStore.clear(item.book.id)
                    convertDictionaryStore.clearBook(item.book.id)
                    comicTranslationStore.clearBook(item.book.id)
                    webNovelSourceStore.clear(item.book.id)
                    bookRepository.deleteBook(item.book.id)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                _events.send(
                    UserMessage(
                        textRes = R.string.library_delete_failed,
                        formatArgs = listOf(item.book.title)
                    )
                )
            }
        }
    }
}
