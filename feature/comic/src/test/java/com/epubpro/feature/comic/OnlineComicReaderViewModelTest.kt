package com.epubpro.feature.comic

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.SavedStateHandle
import com.epubpro.core.comic.ComicChapterInfo
import com.epubpro.core.comic.ComicProtectionException
import com.epubpro.core.comic.OnlineComicFetcher
import com.epubpro.core.comic.OnlineComicImageLoader
import com.epubpro.core.designsystem.R
import com.epubpro.core.storage.OnlineComicProgress
import com.epubpro.core.storage.OnlineComicProgressStore
import com.epubpro.core.storage.ReaderPreferencesManager
import com.epubpro.domain.model.ComicReadingMode
import com.epubpro.domain.repository.BookRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.lang.reflect.Proxy

/**
 * Unit test cho [OnlineComicReaderViewModel].
 *
 * Kiểm tra các kịch bản nạp chương, khôi phục và lưu tiến độ, chuyển chương an toàn
 * khi có lỗi mạng và bảo vệ chống cào dữ liệu.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnlineComicReaderViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val mockFetcher = mock<OnlineComicFetcher>()
    private val mockImageLoader = mock<OnlineComicImageLoader>()
    private val mockProgressStore = mock<OnlineComicProgressStore>()
    private val mockBookRepository = mock<BookRepository>()
    private lateinit var readerPreferences: ReaderPreferencesManager

    private val chapter1Url = "https://nettruyen.gg/truyen-tranh/manga-test/chuong-1"
    private val chapter2Url = "https://nettruyen.gg/truyen-tranh/manga-test/chuong-2"

    private val sampleChapter1 = ComicChapterInfo(
        sourceHost = "nettruyen.gg",
        chapterUrl = chapter1Url,
        title = "Manga Test - Chương 1",
        pages = listOf(
            "https://cdn.example.com/1.jpg",
            "https://cdn.example.com/2.jpg",
            "https://cdn.example.com/3.jpg"
        ),
        previousChapterUrl = null,
        nextChapterUrl = chapter2Url,
        availableServers = emptyList()
    )

    private val sampleChapter2 = ComicChapterInfo(
        sourceHost = "nettruyen.gg",
        chapterUrl = chapter2Url,
        title = "Manga Test - Chương 2",
        pages = listOf(
            "https://cdn.example.com/c2_1.jpg",
            "https://cdn.example.com/c2_2.jpg"
        ),
        previousChapterUrl = chapter1Url,
        nextChapterUrl = null,
        availableServers = emptyList()
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        val mockContext = mock<Context>()
        val fakePrefs = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getBoolean" -> args[1] ?: false
                "getFloat" -> args[1] ?: 0f
                "getString" -> args[1]
                "getInt" -> args[1] ?: 0
                "getLong" -> args[1] ?: 0L
                "contains" -> false
                else -> null
            }
        } as SharedPreferences
        whenever(mockContext.getSharedPreferences(any(), any())).thenReturn(fakePrefs)
        readerPreferences = ReaderPreferencesManager(mockContext)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(
        initialUrl: String = chapter1Url,
        resumeProgress: Boolean = false
    ): OnlineComicReaderViewModel {
        val savedStateHandle = SavedStateHandle(
            mapOf(
                OnlineComicReaderViewModel.ARG_CHAPTER_URL to initialUrl,
                OnlineComicReaderViewModel.ARG_RESUME_PROGRESS to resumeProgress
            )
        )
        return OnlineComicReaderViewModel(
            savedStateHandle = savedStateHandle,
            fetcher = mockFetcher,
            imageLoader = mockImageLoader,
            progressStore = mockProgressStore,
            readerPreferences = readerPreferences,
            bookRepository = mockBookRepository
        )
    }

    @Test
    fun initialLoad_success_loadsChapterAndUpdatesState() = runTest(testDispatcher) {
        whenever(mockFetcher.fetchChapter(chapter1Url)).thenReturn(sampleChapter1)
        whenever(mockProgressStore.getProgress(chapter1Url)).thenReturn(null)

        val viewModel = createViewModel(chapter1Url)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse("isLoading phải là false sau khi tải xong", state.isLoading)
        assertNull("errorRes phải là null khi tải thành công", state.errorRes)
        assertEquals("Tiêu đề chương phải khớp", sampleChapter1.title, state.chapterInfo?.title)
        assertEquals("Tổng số trang phải khớp", 3, state.chapterInfo?.pages?.size)
        assertEquals("Trang ban đầu phải là 0", 0, state.currentPage)
    }

    @Test
    fun initialLoad_withSavedProgress_restoresPageAndMode() = runTest(testDispatcher) {
        val savedProgress = OnlineComicProgress(
            chapterUrl = chapter2Url,
            pageIndex = 1,
            readingMode = ComicReadingMode.RIGHT_TO_LEFT,
            lastRead = 12345L
        )
        whenever(mockProgressStore.getProgress(chapter1Url)).thenReturn(savedProgress)
        whenever(mockFetcher.fetchChapter(chapter2Url)).thenReturn(sampleChapter2)

        val viewModel = createViewModel(chapter1Url, resumeProgress = true)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("Phải nạp chương 2 từ tiến độ đã lưu", chapter2Url, state.chapterInfo?.chapterUrl)
        assertEquals("Phải khôi phục trang 1 từ tiến độ", 1, state.currentPage)
        assertEquals("Phải khôi phục chế độ đọc RTL", ComicReadingMode.RIGHT_TO_LEFT, state.mode)
    }

    @Test
    fun initialLoad_pageOutOfRange_clampsToLastPage() = runTest(testDispatcher) {
        val savedProgress = OnlineComicProgress(
            chapterUrl = chapter1Url,
            pageIndex = 999,
            readingMode = ComicReadingMode.WEBTOON,
            lastRead = 12345L
        )
        whenever(mockProgressStore.getProgress(chapter1Url)).thenReturn(savedProgress)
        whenever(mockFetcher.fetchChapter(chapter1Url)).thenReturn(sampleChapter1)

        val viewModel = createViewModel(chapter1Url, resumeProgress = true)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("Trang phải được ép về trang cuối cùng hợp lệ (index 2)", 2, state.currentPage)
    }

    @Test
    fun navigateToNextChapter_success_updatesChapterAndResetsPage() = runTest(testDispatcher) {
        whenever(mockFetcher.fetchChapter(chapter1Url)).thenReturn(sampleChapter1)
        whenever(mockFetcher.fetchChapter(chapter2Url)).thenReturn(sampleChapter2)
        whenever(mockProgressStore.getProgress(chapter1Url)).thenReturn(null)

        val viewModel = createViewModel(chapter1Url)
        advanceUntilIdle()

        viewModel.navigateToNextChapter()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("Chương hiện tại phải chuyển sang chương 2", chapter2Url, state.chapterInfo?.chapterUrl)
        assertEquals("Trang hiện tại phải reset về 0 khi chuyển chương mới", 0, state.currentPage)
        assertFalse("isChangingChapter phải là false khi hoàn tất", state.isChangingChapter)
    }

    @Test
    fun navigateToNextChapter_failure_keepsCurrentChapterAndShowsMessage() = runTest(testDispatcher) {
        whenever(mockFetcher.fetchChapter(chapter1Url)).thenReturn(sampleChapter1)
        whenever(mockFetcher.fetchChapter(chapter2Url)).thenAnswer { throw java.io.IOException("Mất kết nối mạng") }
        whenever(mockProgressStore.getProgress(chapter1Url)).thenReturn(null)

        val viewModel = createViewModel(chapter1Url)
        advanceUntilIdle()

        viewModel.navigateToNextChapter()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("Chương cũ vẫn phải được giữ nguyên", chapter1Url, state.chapterInfo?.chapterUrl)
        assertFalse("isChangingChapter phải reset về false", state.isChangingChapter)
        assertEquals(
            "Phải hiển thị thông báo chuyển chương thất bại",
            R.string.online_comic_chapter_load_failed,
            state.messageRes
        )
    }

    @Test
    fun protectionBlocked_setsProtectionBlockedState() = runTest(testDispatcher) {
        whenever(mockFetcher.fetchChapter(chapter1Url)).thenAnswer {
            throw ComicProtectionException("Cloudflare challenge")
        }
        whenever(mockProgressStore.getProgress(chapter1Url)).thenReturn(null)

        val viewModel = createViewModel(chapter1Url)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("isProtectionBlocked phải là true", state.isProtectionBlocked)
        assertEquals(
            R.string.online_comic_load_failed_js_required,
            state.errorRes
        )
    }

    @Test
    fun setReadingMode_updatesStateAndSavesToStore() = runTest(testDispatcher) {
        whenever(mockFetcher.fetchChapter(chapter1Url)).thenReturn(sampleChapter1)
        whenever(mockProgressStore.getProgress(chapter1Url)).thenReturn(null)

        val viewModel = createViewModel(chapter1Url)
        advanceUntilIdle()

        viewModel.setReadingMode(ComicReadingMode.LEFT_TO_RIGHT)

        assertEquals(ComicReadingMode.LEFT_TO_RIGHT, viewModel.uiState.value.mode)
        verify(mockProgressStore).saveReadingMode(chapter1Url, ComicReadingMode.LEFT_TO_RIGHT)
    }

    @Test
    fun flushProgress_savesImmediately() = runTest(testDispatcher) {
        whenever(mockFetcher.fetchChapter(chapter1Url)).thenReturn(sampleChapter1)
        whenever(mockProgressStore.getProgress(chapter1Url)).thenReturn(null)

        val viewModel = createViewModel(chapter1Url)
        advanceUntilIdle()

        viewModel.flushProgress()
        advanceUntilIdle()

        verify(mockProgressStore, org.mockito.kotlin.atLeast(2)).saveProgress(eq(chapter1Url), eq(0), any())
    }

    @Test
    fun initialLoad_syncsComicToLibrary() = runTest(testDispatcher) {
        whenever(mockFetcher.fetchChapter(chapter1Url)).thenReturn(sampleChapter1)
        whenever(mockProgressStore.getProgress(chapter1Url)).thenReturn(null)
        whenever(mockProgressStore.computeComicKey(chapter1Url)).thenReturn("testKey")

        createViewModel(chapter1Url)
        advanceUntilIdle()

        verify(mockBookRepository).insertBook(any())
        verify(mockBookRepository).saveReadingProgress(any())
    }
}
