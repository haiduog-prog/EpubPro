package com.epubpro.core.storage.sync

import com.epubpro.domain.sync.ReadingHistoryLedger
import com.epubpro.domain.sync.ReadingHistoryRecord
import com.epubpro.domain.sync.SyncStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.UUID

class ReadingHistorySyncCoordinatorTest {
    private val local = mock<LocalSnapshotDataSource>()
    private val drive = mock<GoogleDriveDataSource>()
    private val stateStore = mock<SyncStateStore>()
    private val rollbackStore = mock<RollbackStore>()
    private val readingHistoryLocal = mock<ReadingHistoryLocalDataSource>()
    private lateinit var coordinator: SyncCoordinatorImpl
    private val deviceId = UUID.randomUUID().toString()

    /** Khởi tạo coordinator bằng các collaborator giả lập cho mỗi test. */
    @Before
    fun setUp() {
        coordinator = SyncCoordinatorImpl(local, drive, stateStore, rollbackStore, readingHistoryLocal)
    }

    /** Kiểm tra gộp tiến độ mới nhất, áp dụng local và ghi ledger của thiết bị hiện tại. */
    @Test
    fun syncMergesNewestProgressAndWritesDeviceLedger() = runTest {
        val localRecord = record(updatedAt = 100, deviceId = deviceId, chapterIndex = 2)
        val remoteRecord = record(updatedAt = 200, deviceId = UUID.randomUUID().toString(), chapterIndex = 5)
        whenever(stateStore.getOrCreateReadingHistoryDeviceId()).thenReturn(deviceId)
        whenever(drive.readReadingHistoryLedgers()).thenReturn(
            listOf(ReadingHistoryLedger(deviceId = remoteRecord.updatedByDeviceId, records = listOf(remoteRecord)))
        )
        whenever(readingHistoryLocal.readRecords(deviceId)).thenReturn(
            listOf(localRecord),
            listOf(remoteRecord.copy(updatedByDeviceId = deviceId))
        )

        val result = coordinator.syncReadingHistory()

        assertEquals(SyncStatus.SYNCED, result.status)
        val applied = argumentCaptor<List<ReadingHistoryRecord>>()
        verify(readingHistoryLocal).applyRecords(applied.capture())
        assertEquals(5, applied.firstValue.single().chapterIndex)
        val ledger = argumentCaptor<ReadingHistoryLedger>()
        verify(drive).writeReadingHistoryLedger(ledger.capture())
        assertEquals(deviceId, ledger.firstValue.deviceId)
        assertEquals(5, ledger.firstValue.records.single().chapterIndex)
        assertEquals(remoteRecord.updatedByDeviceId, ledger.firstValue.records.single().updatedByDeviceId)
    }

    /** Kiểm tra lỗi xác thực dừng trước khi thay đổi Room hoặc upload ledger. */
    @Test
    fun authFailureDoesNotApplyLocalChanges() = runTest {
        whenever(stateStore.getOrCreateReadingHistoryDeviceId()).thenReturn(deviceId)
        whenever(drive.readReadingHistoryLedgers()).thenThrow(
            DriveSyncException("auth required", requiresAuth = true)
        )

        val result = coordinator.syncReadingHistory()

        assertEquals(SyncStatus.AUTH_REQUIRED, result.status)
        verify(readingHistoryLocal, never()).applyRecords(any())
        verify(drive, never()).writeReadingHistoryLedger(any())
        assertTrue(result.message!!.contains("auth"))
    }

    /**
     * Tạo một bản ghi tiến độ để kiểm tra thứ tự timestamp.
     *
     * @param updatedAt Timestamp cập nhật.
     * @param deviceId UUID thiết bị tạo bản ghi.
     * @param chapterIndex Chỉ số chương.
     * @return Bản ghi hợp lệ cho test coordinator.
     */
    private fun record(updatedAt: Long, deviceId: String, chapterIndex: Int) = ReadingHistoryRecord(
        bookKey = "epub:abc123",
        currentCfi = "cfi-$chapterIndex",
        chapterIndex = chapterIndex,
        pageIndex = 1,
        progressPercentage = chapterIndex / 10f,
        totalChapters = 10,
        lastReadAt = updatedAt,
        updatedAt = updatedAt,
        updatedByDeviceId = deviceId
    )
}
