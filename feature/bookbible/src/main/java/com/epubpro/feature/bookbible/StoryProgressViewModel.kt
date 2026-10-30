package com.epubpro.feature.bookbible

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.epubpro.domain.model.Book
import com.epubpro.domain.model.BookBibleProgressSummary
import com.epubpro.domain.model.BookBibleReviewBook
import com.epubpro.domain.model.BookBibleSource
import com.epubpro.domain.model.BookBibleSourceType
import com.epubpro.domain.repository.BookBibleRepository
import com.epubpro.domain.repository.BookRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Trạng thái hiển thị của màn hình Tiến trình truyện.
 *
 * @property items Danh sách truyện local, online và các tiến trình Book Bible đã lưu.
 * @property isLoading Cờ đang tải dữ liệu lần đầu hoặc sau khi thử lại.
 * @property errorMessage Thông điệp lỗi khi không thể tải dữ liệu local hoặc cache.
 */
data class StoryProgressUiState(
    val items: List<BookBibleProgressSummary> = emptyList(),
    val isLoading: Boolean = true,
    val errorMessage: String? = null
)

/**
 * ViewModel cung cấp danh sách truyện để duyệt và mở Book Bible mà không cần đi qua Reader.
 */
@HiltViewModel
class StoryProgressViewModel @Inject constructor(
    private val bookBibleRepository: BookBibleRepository,
    private val bookRepository: BookRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(StoryProgressUiState())
    val uiState: StateFlow<StoryProgressUiState> = _uiState.asStateFlow()

    private val localBooks = MutableStateFlow<List<Book>>(emptyList())
    private val bibleSummaries = MutableStateFlow<List<BookBibleProgressSummary>>(emptyList())
    private val reviewBooks = MutableStateFlow<List<BookBibleReviewBook>>(emptyList())
    private val sourceError = MutableStateFlow<String?>(null)
    private var observeJob: Job? = null

    init {
        observeStories()
    }

    /**
     * Đăng ký lại các nguồn dữ liệu khi người dùng yêu cầu tải lại danh sách truyện.
     */
    fun retry() {
        observeStories()
    }

    /**
     * Quan sát truyện local, truyện online và tiến trình Book Bible trong cùng vòng đời ViewModel.
     */
    private fun observeStories() {
        observeJob?.cancel()
        sourceError.value = null
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }

        observeJob = viewModelScope.launch {
            launch {
                bookRepository.getAllBooks()
                    .catch { error -> sourceError.value = error.message ?: error.toString() }
                    // Book Bible chỉ phân tích truyện chữ nên bỏ qua truyện tranh CBZ.
                    .collect { books -> localBooks.value = books.filterNot { it.sourceFormat.isComic } }
            }
            launch {
                bookBibleRepository.observeProgressSummaries()
                    .catch { error -> sourceError.value = error.message ?: error.toString() }
                    .collect { bibleSummaries.value = it }
            }
            launch {
                bookBibleRepository.getReviewBooks()
                    .onSuccess { reviewBooks.value = it }
                    .onFailure { error -> sourceError.value = error.message ?: error.toString() }
            }
            launch {
                combine(localBooks, bibleSummaries, reviewBooks) {
                        books,
                        summaries,
                        remoteBooks
                    ->
                    mergeStorySources(books, summaries, remoteBooks)
                }.collect { items ->
                    _uiState.update { state ->
                        state.copy(
                            items = items,
                            isLoading = false,
                            errorMessage = if (items.isEmpty()) sourceError.value else null
                        )
                    }
                }
            }
        }
    }

    /**
     * Hợp nhất danh sách EPUB local với tiến trình Book Bible theo đúng định danh nguồn.
     * Các tiến trình online đã cache vẫn được giữ lại để người dùng mở lại mà không cần gọi mạng.
     *
     * @param books Danh sách EPUB đã nhập vào thiết bị.
     * @param summaries Các tiến trình Book Bible đã lưu trong Room.
     * @param remoteBooks Danh sách sách và số lượng event từ backend.
     * @return Danh sách truyện duy nhất, đã gắn trạng thái Book Bible nếu có.
     */
    private fun mergeStorySources(
        books: List<Book>,
        summaries: List<BookBibleProgressSummary>,
        remoteBooks: List<BookBibleReviewBook>
    ): List<BookBibleProgressSummary> {
        val summariesByKey = summaries.associateBy { it.source.uniqueKey }
        val remoteByBookId = remoteBooks.associateBy { it.bookId }
        val visibleKeys = mutableSetOf<String>()

        val localItems = books.map { book ->
            val source = BookBibleSource(BookBibleSourceType.LOCAL_EPUB, book.id)
            visibleKeys += source.uniqueKey
            val remoteMatch = book.onlineNovelId?.let { remoteByBookId[it] }
            mergeWithProgress(
                source = source,
                title = book.title,
                author = book.author,
                totalChapters = book.totalChapters,
                updatedAt = maxOf(book.lastReadAt, book.addedAt),
                progress = summariesByKey[source.uniqueKey]
            ).copy(
                backendBookId = book.onlineNovelId,
                eventCount = remoteMatch?.eventCount ?: 0,
                pendingEventCount = remoteMatch?.pendingEventCount ?: 0
            )
        }
        val cachedOnlyItems = summaries.filterNot { it.source.uniqueKey in visibleKeys }

        val remoteItems = remoteBooks.map { book ->
            BookBibleProgressSummary(
                source = BookBibleSource(BookBibleSourceType.ONLINE_NOVEL, book.bookId),
                title = book.title,
                author = book.author,
                totalChapters = 0,
                latestChapterNumber = 0,
                updatedAt = 0L,
                backendBookId = book.bookId,
                eventCount = book.eventCount,
                pendingEventCount = book.pendingEventCount
            )
        }

        return (localItems + remoteItems + cachedOnlyItems)
            .distinctBy { it.backendBookId ?: it.source.sourceId }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
    }

    /**
     * Giữ lại trạng thái Book Bible đã lưu và cập nhật metadata mới nhất của truyện.
     *
     * @param source Định danh nguồn truyện.
     * @param title Tên truyện lấy từ nguồn hiện tại.
     * @param author Tác giả lấy từ nguồn hiện tại.
     * @param totalChapters Tổng số chương lấy từ nguồn hiện tại.
     * @param updatedAt Thời điểm cập nhật metadata local.
     * @param progress Tiến trình Book Bible đã lưu, nếu có.
     * @return Tóm tắt dùng để hiển thị trong danh sách duyệt truyện.
     */
    private fun mergeWithProgress(
        source: BookBibleSource,
        title: String,
        author: String,
        totalChapters: Int,
        updatedAt: Long,
        progress: BookBibleProgressSummary?
    ): BookBibleProgressSummary {
        if (progress == null) {
            return BookBibleProgressSummary(
                source = source,
                title = title,
                author = author,
                totalChapters = totalChapters,
                latestChapterNumber = 0,
                updatedAt = updatedAt
            )
        }

        return progress.copy(
            title = title.ifBlank { progress.title },
            author = author.ifBlank { progress.author },
            totalChapters = totalChapters.takeIf { it > 0 } ?: progress.totalChapters,
            updatedAt = maxOf(progress.updatedAt, updatedAt)
        )
    }

    override fun onCleared() {
        observeJob?.cancel()
        super.onCleared()
    }
}
