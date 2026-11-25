package com.epubpro.feature.bookbible

import androidx.lifecycle.SavedStateHandle
import com.epubpro.domain.model.*
import com.epubpro.domain.repository.BookBibleRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

@OptIn(ExperimentalCoroutinesApi::class)
class BookBibleViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var repository: BookBibleRepository
    private lateinit var savedStateHandle: SavedStateHandle
    private lateinit var viewModel: BookBibleViewModel

    private val source = BookBibleSource(BookBibleSourceType.LOCAL_EPUB, "book_1")
    private val sampleSnapshot = BookBibleSnapshot(
        bookId = "book_1",
        editionId = "edition_1",
        requestedChapter = 3,
        canonicalChapter = 3,
        status = SnapshotStatus.COMPLETE,
        coverage = SnapshotCoverage(processedRanges = listOf(ChapterRange(1, 3))),
        characters = listOf(
            CharacterProfile(
                id = "char_1",
                name = "Lâm Phong",
                role = "Nhân vật phụ",
                cultivationRealm = "Trúc Cơ sơ kỳ",
                changedInCurrentChapter = false
            ),
            CharacterProfile(
                id = "char_2",
                name = "Diệp Thần",
                role = "Nhân vật phụ",
                cultivationRealm = "Kim Đan trung kỳ",
                changedInCurrentChapter = true
            ),
            CharacterProfile(
                id = "char_3",
                name = "Đường Vũ Lân",
                role = "Nhân vật chính",
                cultivationRealm = "Hồn Tôn",
                changedInCurrentChapter = false
            )
        )
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        repository = mock(BookBibleRepository::class.java)
        savedStateHandle = SavedStateHandle(
            mapOf(
                "sourceType" to "LOCAL_EPUB",
                "sourceId" to "book_1",
                "chapterNumber" to "3"
            )
        )

        `when`(repository.observeSnapshot(source, 3)).thenReturn(flowOf(sampleSnapshot))
        // Mặc định người dùng đã bật Book Bible cho sách; test riêng bên dưới kiểm tra trạng thái tắt.
        `when`(repository.observeSubmissionEnabled(source)).thenReturn(flowOf(true))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testSortedCharactersPutsProtagonistFirstThenChangedInCurrentChapter() = runTest {
        `when`(repository.refreshSnapshot(source, 3)).thenReturn(Result.success(sampleSnapshot))

        viewModel = BookBibleViewModel(savedStateHandle, repository)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(3, state.chapterNumber)
        assertNotNull(state.snapshot)

        val sorted = state.sortedCharacters
        assertEquals(3, sorted.size)
        // Đường Vũ Lân is Protagonist, so he must ALWAYS be first!
        assertEquals("Đường Vũ Lân", sorted[0].name)
        // Diệp Thần has changedInCurrentChapter = true, so he is second
        assertEquals("Diệp Thần", sorted[1].name)
        // Lâm Phong is third
        assertEquals("Lâm Phong", sorted[2].name)
    }

    @Test
    fun testSelectCharacterAndTabUpdatesState() = runTest {
        `when`(repository.refreshSnapshot(source, 3)).thenReturn(Result.success(sampleSnapshot))

        viewModel = BookBibleViewModel(savedStateHandle, repository)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.selectCharacter("char_1")
        assertEquals("char_1", viewModel.uiState.value.selectedCharacterId)
        assertEquals("Lâm Phong", viewModel.uiState.value.selectedCharacter?.name)

        viewModel.selectTab(1)
        assertEquals(1, viewModel.uiState.value.selectedTab)

        viewModel.selectCharacter(null)
        assertNull(viewModel.uiState.value.selectedCharacterId)
        assertNull(viewModel.uiState.value.selectedCharacter)
    }

    /**
     * Kiểm tra sách EPUB chưa bật Book Bible: hiện trạng thái tắt và không gọi máy chủ làm mới snapshot.
     */
    @Test
    fun testDisabledSubmissionDoesNotRefreshFromServer() = runTest {
        `when`(repository.observeSubmissionEnabled(source)).thenReturn(flowOf(false))

        viewModel = BookBibleViewModel(savedStateHandle, repository)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(false, state.isSubmissionEnabled)
        assertFalse(state.isLoading)
        assertFalse(state.canDisableSubmission)
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.never()).refreshSnapshot(source, 3)
    }

    /**
     * Kiểm tra sách đã bật Book Bible thì cho phép tắt lại từ màn hình.
     */
    @Test
    fun testEnabledLocalBookCanDisableSubmission() = runTest {
        `when`(repository.refreshSnapshot(source, 3)).thenReturn(Result.success(sampleSnapshot))

        viewModel = BookBibleViewModel(savedStateHandle, repository)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(true, viewModel.uiState.value.isSubmissionEnabled)
        assertTrue(viewModel.uiState.value.canDisableSubmission)
    }
}
