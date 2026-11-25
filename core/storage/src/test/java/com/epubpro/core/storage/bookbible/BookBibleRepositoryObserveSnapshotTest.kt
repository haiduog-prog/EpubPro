package com.epubpro.core.storage.bookbible

import android.content.Context
import com.epubpro.core.database.dao.BookBibleDao
import com.epubpro.core.database.dao.BookDao
import com.epubpro.core.database.entity.BookBibleSnapshotEntity
import com.epubpro.core.storage.EpubStorageManager
import com.epubpro.core.storage.network.BookBibleApiService
import com.epubpro.core.storage.network.OnlineNovelApiService
import com.epubpro.domain.model.BookBibleSource
import com.epubpro.domain.model.BookBibleSourceType
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class BookBibleRepositoryObserveSnapshotTest {

    @Test
    fun `observeSnapshot updates lastAccessedAt once without infinite Room invalidation loop`() = runBlocking {
        val source = BookBibleSource(BookBibleSourceType.LOCAL_EPUB, "book-1")
        val initialEntity = BookBibleSnapshotEntity(
            id = "ed-1:5",
            editionId = "ed-1",
            localSourceKey = source.uniqueKey,
            chapterNumber = 5,
            canonicalChapter = 5,
            status = "READY",
            coverageJson = "{}",
            payloadJson = """{"characters":[],"relationships":[],"summary":"Tóm tắt"}""",
            revision = 1L,
            byteSize = 64L,
            updatedAt = 1000L,
            lastAccessedAt = 1000L
        )
        val tableFlow = MutableStateFlow<BookBibleSnapshotEntity?>(initialEntity)
        var updateCalls = 0

        val bookBibleDao: BookBibleDao = mock()
        whenever(bookBibleDao.observeSnapshotByLocalSource(source.uniqueKey, 5)).thenReturn(tableFlow)
        whenever(bookBibleDao.updateSnapshotLastAccessed(any(), any())).doAnswer { invocation ->
            updateCalls++
            val timestamp = invocation.getArgument<Long>(1)
            // Giả lập Room InvalidationTracker phát lại entity với lastAccessedAt mới
            tableFlow.value = tableFlow.value?.copy(lastAccessedAt = timestamp + updateCalls)
            Unit
        }

        val repository = BookBibleRepositoryImpl(
            context = mock<Context>(),
            apiService = mock<BookBibleApiService>(),
            onlineNovelApiService = mock<OnlineNovelApiService>(),
            bookDao = mock<BookDao>(),
            bookBibleDao = bookBibleDao,
            payloadStore = mock<BookBiblePayloadStore>(),
            fingerprintGenerator = mock<BookBibleFingerprintGenerator>(),
            storageManager = mock<EpubStorageManager>(),
            gson = Gson(),
            consentStore = testConsentStore()
        )

        val emitted = mutableListOf<Any?>()
        withTimeout(3_000L) {
            val collectJob = launch {
                repository.observeSnapshot(source, 5)
                    .take(2)
                    .toList(emitted)
            }
            // Chờ emission đầu tiên và giả lập Room chỉ đổi lastAccessedAt
            kotlinx.coroutines.delay(150L)
            assertEquals(1, emitted.size)
            assertEquals(1, updateCalls)

            // Khi revision thay đổi thật, Flow vẫn phát bản mới bình thường
            tableFlow.value = tableFlow.value?.copy(revision = 2L, updatedAt = 2000L)
            collectJob.join()
        }

        assertEquals(2, emitted.size)
        assertEquals(1, updateCalls)
    }

    @Test
    fun `observeSnapshot keeps independent lastTouched state across re-collections`() = runBlocking {
        val source = BookBibleSource(BookBibleSourceType.LOCAL_EPUB, "book-2")
        val entity = BookBibleSnapshotEntity(
            id = "ed-2:1",
            editionId = "ed-2",
            localSourceKey = source.uniqueKey,
            chapterNumber = 1,
            canonicalChapter = 1,
            status = "READY",
            coverageJson = "{}",
            payloadJson = """{"characters":[],"relationships":[],"summary":"Tóm tắt"}""",
            revision = 1L,
            byteSize = 64L,
            updatedAt = 1000L,
            lastAccessedAt = 1000L
        )
        val tableFlow = MutableStateFlow<BookBibleSnapshotEntity?>(entity)
        var updateCalls = 0

        val bookBibleDao: BookBibleDao = mock()
        whenever(bookBibleDao.observeSnapshotByLocalSource(source.uniqueKey, 1)).thenReturn(tableFlow)
        whenever(bookBibleDao.updateSnapshotLastAccessed(any(), any())).doAnswer { invocation ->
            updateCalls++
            val timestamp = invocation.getArgument<Long>(1)
            tableFlow.value = tableFlow.value?.copy(lastAccessedAt = timestamp + updateCalls)
            Unit
        }

        val repository = BookBibleRepositoryImpl(
            context = mock<Context>(),
            apiService = mock<BookBibleApiService>(),
            onlineNovelApiService = mock<OnlineNovelApiService>(),
            bookDao = mock<BookDao>(),
            bookBibleDao = bookBibleDao,
            payloadStore = mock<BookBiblePayloadStore>(),
            fingerprintGenerator = mock<BookBibleFingerprintGenerator>(),
            storageManager = mock<EpubStorageManager>(),
            gson = Gson(),
            consentStore = testConsentStore()
        )

        val sharedFlow = repository.observeSnapshot(source, 1)
        val firstPass = withTimeout(2_000L) { sharedFlow.take(1).toList() }
        val secondPass = withTimeout(2_000L) { sharedFlow.take(1).toList() }

        assertEquals(1, firstPass.size)
        assertEquals(1, secondPass.size)
        assertEquals(2, updateCalls)
    }
}
