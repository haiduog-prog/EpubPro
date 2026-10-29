package com.epubpro.core.storage.sync

import com.epubpro.domain.sync.ManifestComparator
import com.epubpro.domain.sync.ReadingHistoryLedger
import com.epubpro.domain.sync.ReadingHistoryMerger
import com.epubpro.domain.sync.SyncChangeType
import com.epubpro.domain.sync.SyncCheckResult
import com.epubpro.domain.sync.SyncCoordinator
import com.epubpro.domain.sync.SyncManifest
import com.epubpro.domain.sync.SyncOptions
import com.epubpro.domain.sync.SyncResult
import com.epubpro.domain.sync.SyncStatus
import com.epubpro.domain.sync.SyncUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Điều phối một phiên sync duy nhất và ánh xạ lỗi data source thành domain result.
 */
@Singleton
class SyncCoordinatorImpl @Inject constructor(
    private val local: LocalSnapshotDataSource,
    private val drive: GoogleDriveDataSource,
    private val stateStore: SyncStateStore,
    private val rollbackStore: RollbackStore,
    private val readingHistoryLocal: ReadingHistoryLocalDataSource
) : SyncCoordinator {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(SyncUiState())

    /** Kiểm tra thay đổi mà không ghi dữ liệu. */
    override suspend fun check(): SyncCheckResult = mutex.withLock {
        try {
            val localSnapshot = local.createSnapshot()
            val remoteSnapshot = drive.readSnapshot()
            val comparison = compare(localSnapshot.manifest, remoteSnapshot.manifest)
            val status = statusFor(comparison)
            val result = SyncCheckResult(status, comparison, messageFor(status))
            _state.value = SyncUiState(
                status = status,
                conflictKeys = comparison.blockingKeys,
                message = result.message
            )
            result
        } catch (error: CancellationException) {
            throw error
        } catch (error: DriveSyncException) {
            val status = if (error.requiresAuth) SyncStatus.AUTH_REQUIRED else SyncStatus.ERROR
            _state.value = SyncUiState(status = status, message = error.message)
            SyncCheckResult(status, emptyComparison(), error.message)
        } catch (error: Exception) {
            _state.value = SyncUiState(SyncStatus.ERROR, message = error.message)
            SyncCheckResult(SyncStatus.ERROR, emptyComparison(), error.message)
        }
    }

    /**
     * Gộp vị trí đọc mới nhất từ Room và ledger của mọi thiết bị trên Drive.
     *
     * @return Kết quả sync, giữ nguyên dữ liệu local nếu tải hoặc xác thực ledger thất bại.
     */
    override suspend fun syncReadingHistory(): SyncResult = mutex.withLock {
        try {
            val deviceId = stateStore.getOrCreateReadingHistoryDeviceId()
            _state.value = SyncUiState(SyncStatus.SYNCING_DOWN, totalItems = 1)
            val remoteLedgers = drive.readReadingHistoryLedgers()
            val remoteRecords = remoteLedgers.flatMap { it.records }
            val localRecords = readingHistoryLocal.readRecords(deviceId)
            val mergedRecords = ReadingHistoryMerger.merge(remoteRecords + localRecords)

            readingHistoryLocal.applyRecords(mergedRecords)
            val refreshedLocalRecords = readingHistoryLocal.readRecords(deviceId)
            val mergedByBookKey = mergedRecords.associateBy { it.bookKey }
            val refreshedRecordsWithOrigin = refreshedLocalRecords.map { refreshed ->
                val applied = mergedByBookKey[refreshed.bookKey]
                if (applied != null && refreshed.hasSameProgress(applied)) applied else refreshed
            }
            val finalRecords = ReadingHistoryMerger.merge(
                remoteRecords + mergedRecords + refreshedRecordsWithOrigin
            )
            val localLedger = ReadingHistoryLedger(deviceId = deviceId, records = finalRecords)

            _state.value = SyncUiState(SyncStatus.SYNCING_UP, completedItems = 1, totalItems = 2)
            drive.writeReadingHistoryLedger(localLedger)
            finish(
                SyncResult(
                    status = SyncStatus.SYNCED,
                    uploadedCount = 1,
                    downloadedCount = remoteLedgers.size
                )
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: DriveSyncException) {
            val status = if (error.requiresAuth) SyncStatus.AUTH_REQUIRED else SyncStatus.ERROR
            finish(SyncResult(status, message = error.message))
        } catch (error: Exception) {
            finish(SyncResult(SyncStatus.ERROR, message = error.message))
        }
    }

    /**
     * Backup local lên Drive sau khi xác nhận baseline và conflict.
     *
     * @param options Tùy chọn xóa hoặc force đã được UI xác nhận.
     * @return Kết quả domain, không ném raw HTTP/IO exception lên UI.
     */
    override suspend fun backup(options: SyncOptions): SyncResult = mutex.withLock {
        var rollback: RollbackSnapshot? = null
        try {
            val localSnapshot = local.createSnapshot()
            val remoteSnapshot = drive.readSnapshot()
            val comparison = compare(localSnapshot.manifest, remoteSnapshot.manifest)
            val blocking = comparison.blockingKeys
            val remoteChanged = comparison.changes.any {
                it.type == SyncChangeType.DOWNLOAD || it.type == SyncChangeType.DELETE_LOCAL
            } || comparison.databaseChange == SyncChangeType.DOWNLOAD || comparison.databaseChange == SyncChangeType.DELETE_LOCAL
            if (comparison.databaseChange == SyncChangeType.INCOMPATIBLE) {
                return@withLock finish(SyncResult(SyncStatus.ERROR, message = "Database snapshot không tương thích contract backend"))
            }
            if ((!options.force && blocking.isNotEmpty()) || (!options.force && remoteChanged)) {
                val status = if (blocking.isNotEmpty()) SyncStatus.CONFLICT else SyncStatus.DRIVE_PENDING
                return@withLock finish(SyncResult(status, conflictKeys = blocking, message = messageFor(status)))
            }
            val changes = comparison.changes.filter { it.type != SyncChangeType.UNCHANGED }
            val deletionChanges = changes.filter { it.type == SyncChangeType.DELETE_REMOTE }
            if (deletionChanges.isNotEmpty() && !options.allowDeletions && !options.force) {
                return@withLock finish(SyncResult(SyncStatus.CHANGES_PENDING, message = "Cần xác nhận xóa dữ liệu trên Drive"))
            }
            if (options.force) {
                rollback = rollbackStore.create(changes.map { it.key }, includeDatabase = true)
            }
            _state.value = SyncUiState(SyncStatus.SYNCING_UP, totalItems = changes.size + 1)
            var completed = 0
            var deleted = 0
            changes.filter { it.type == SyncChangeType.UPLOAD || (options.force && it.type == SyncChangeType.CONFLICT) }
                .forEach { change ->
                    val file = localSnapshot.files[change.key] ?: return@forEach
                    drive.uploadFile(change.key, file) { _, _ -> }
                    completed++
                    emitProgress(SyncStatus.SYNCING_UP, completed, changes.size + 1)
                }
            changes.filter { it.type == SyncChangeType.DELETE_REMOTE }.forEach { change ->
                remoteSnapshot.files[change.key]?.let { drive.deleteFile(it) }
                deleted++
                completed++
                emitProgress(SyncStatus.SYNCING_UP, completed, changes.size + 1)
            }
            val databaseChange = comparison.databaseChange
            if (databaseChange == SyncChangeType.UPLOAD || (options.force && databaseChange == SyncChangeType.CONFLICT)) {
                drive.uploadFile(DATABASE_KEY, localSnapshot.databaseFile) { _, _ -> }
                completed++
                emitProgress(SyncStatus.SYNCING_UP, completed, changes.size + 1)
            }
            drive.uploadManifest(localSnapshot.manifest)
            stateStore.saveBaseline(localSnapshot.manifest, UUID.randomUUID().toString(), System.currentTimeMillis())
            rollback?.let { rollbackStore.cleanup(it) }
            finish(SyncResult(SyncStatus.SYNCED, uploadedCount = completed - deleted, deletedCount = deleted))
        } catch (error: CancellationException) {
            rollback?.let { snapshot ->
                withContext(NonCancellable) { rollbackStore.restore(snapshot) }
            }
            throw error
        } catch (error: DriveSyncException) {
            rollback?.let { snapshot ->
                withContext(NonCancellable) { rollbackStore.restore(snapshot) }
            }
            val status = if (error.requiresAuth) SyncStatus.AUTH_REQUIRED else SyncStatus.ERROR
            finish(SyncResult(status, message = error.message))
        } catch (error: Exception) {
            rollback?.let { snapshot ->
                withContext(NonCancellable) { rollbackStore.restore(snapshot) }
            }
            finish(SyncResult(SyncStatus.ERROR, message = error.message))
        }
    }

    /**
     * Restore từ Drive, luôn tạo rollback trước khi ghi file local.
     *
     * @param options Tùy chọn xác nhận xóa hoặc force.
     * @return Kết quả domain của phiên restore.
     */
    override suspend fun restore(options: SyncOptions): SyncResult = mutex.withLock {
        var rollback: RollbackSnapshot? = null
        try {
            val localSnapshot = local.createSnapshot()
            val remoteSnapshot = drive.readSnapshot()
            val comparison = compare(localSnapshot.manifest, remoteSnapshot.manifest)
            if (comparison.databaseChange == SyncChangeType.INCOMPATIBLE) {
                return@withLock finish(SyncResult(SyncStatus.ERROR, message = "Database snapshot không tương thích contract backend"))
            }
            val localChanged = comparison.changes.any { it.type == SyncChangeType.UPLOAD || it.type == SyncChangeType.DELETE_REMOTE } ||
                comparison.databaseChange == SyncChangeType.UPLOAD || comparison.databaseChange == SyncChangeType.DELETE_REMOTE ||
                comparison.databaseChange == SyncChangeType.CONFLICT
            val localChangedKeys = comparison.changes.filter {
                it.type == SyncChangeType.UPLOAD || it.type == SyncChangeType.DELETE_REMOTE
            }.map { it.key } + if (
                comparison.databaseChange == SyncChangeType.UPLOAD ||
                comparison.databaseChange == SyncChangeType.DELETE_REMOTE ||
                comparison.databaseChange == SyncChangeType.CONFLICT
            ) listOf(DATABASE_KEY) else emptyList()
            if (localChanged && !options.force) {
                return@withLock finish(SyncResult(SyncStatus.CONFLICT, conflictKeys = localChangedKeys, message = "Local có thay đổi chưa được backup"))
            }
            val downloads = comparison.changes.filter {
                it.type == SyncChangeType.DOWNLOAD || (options.force && it.type == SyncChangeType.CONFLICT)
            }
            val deletes = comparison.changes.filter { it.type == SyncChangeType.DELETE_LOCAL }
            if (deletes.isNotEmpty() && !options.allowDeletions && !options.force) {
                return@withLock finish(SyncResult(SyncStatus.DRIVE_PENDING, message = "Cần xác nhận xóa dữ liệu local"))
            }
            rollback = rollbackStore.create(
                keys = (downloads + deletes).map { it.key },
                includeDatabase = comparison.databaseChange == SyncChangeType.DOWNLOAD ||
                    (options.force && comparison.databaseChange == SyncChangeType.CONFLICT)
            )
            val databaseDownload = comparison.databaseChange == SyncChangeType.DOWNLOAD ||
                (options.force && comparison.databaseChange == SyncChangeType.CONFLICT)
            val total = downloads.size + deletes.size + if (databaseDownload) 1 else 0
            _state.value = SyncUiState(SyncStatus.SYNCING_DOWN, totalItems = total)
            var completed = 0
            downloads.forEach { change ->
                val expected = remoteSnapshot.manifest.storage[change.key]
                    ?: throw IllegalStateException("Drive thiếu metadata cho ${change.key}")
                val ref = remoteSnapshot.files[change.key]
                    ?: throw IllegalStateException("Drive thiếu file ${change.key}")
                val temporary = local.createTemporaryFile(change.key)
                drive.downloadFile(ref, temporary) { _, _ -> }
                check(sha256(temporary) == expected.sha256) { "Checksum mismatch cho ${change.key}" }
                local.commitFile(change.key, temporary)
                completed++
                emitProgress(SyncStatus.SYNCING_DOWN, completed, total)
            }
            deletes.forEach {
                local.deleteFile(it.key)
                completed++
                emitProgress(SyncStatus.SYNCING_DOWN, completed, total)
            }
            if (databaseDownload) {
                val ref = remoteSnapshot.files[DATABASE_KEY]
                    ?: throw IllegalStateException("Drive thiếu database snapshot")
                val temporary = local.createTemporaryDatabaseFile()
                drive.downloadFile(ref, temporary) { _, _ -> }
                val expected = remoteSnapshot.manifest.database?.sha256
                check(expected != null && sha256(temporary) == expected) { "Checksum mismatch database" }
                local.replaceDatabase(temporary)
                completed++
                emitProgress(SyncStatus.SYNCING_DOWN, completed, total)
            }
            stateStore.saveBaseline(remoteSnapshot.manifest, UUID.randomUUID().toString(), System.currentTimeMillis())
            rollback?.let { rollbackStore.cleanup(it) }
            finish(SyncResult(SyncStatus.SYNCED, downloadedCount = completed))
        } catch (error: CancellationException) {
            rollback?.let { snapshot ->
                withContext(NonCancellable) { rollbackStore.restore(snapshot) }
            }
            throw error
        } catch (error: DriveSyncException) {
            rollback?.let { snapshot ->
                withContext(NonCancellable) { rollbackStore.restore(snapshot) }
            }
            val status = if (error.requiresAuth) SyncStatus.AUTH_REQUIRED else SyncStatus.ERROR
            finish(SyncResult(status, message = error.message))
        } catch (error: Exception) {
            rollback?.let { snapshot ->
                withContext(NonCancellable) { rollbackStore.restore(snapshot) }
            }
            finish(SyncResult(SyncStatus.ERROR, message = error.message))
        }
    }

    /** Trả về Flow state bất biến cho UI. */
    override fun observeState(): Flow<SyncUiState> = _state.asStateFlow()

    private suspend fun compare(local: SyncManifest, drive: SyncManifest) =
        ManifestComparator.compare(local, drive, stateStore.read().baseline)

    private fun statusFor(comparison: com.epubpro.domain.sync.SyncComparison): SyncStatus = when {
        comparison.databaseChange == SyncChangeType.INCOMPATIBLE -> SyncStatus.ERROR
        comparison.blockingKeys.isNotEmpty() -> SyncStatus.CONFLICT
        comparison.uploadCount > 0 || comparison.databaseChange == SyncChangeType.UPLOAD -> SyncStatus.CHANGES_PENDING
        comparison.downloadCount > 0 || comparison.databaseChange == SyncChangeType.DOWNLOAD -> SyncStatus.DRIVE_PENDING
        else -> SyncStatus.READY
    }

    private fun messageFor(status: SyncStatus): String = when (status) {
        SyncStatus.READY -> "Dữ liệu đã đồng bộ"
        SyncStatus.CHANGES_PENDING -> "Local có thay đổi chưa backup"
        SyncStatus.DRIVE_PENDING -> "Drive có thay đổi cần restore"
        SyncStatus.CONFLICT -> "Phát hiện conflict, không ghi đè dữ liệu"
        else -> ""
    }

    private fun emitProgress(status: SyncStatus, completed: Int, total: Int) {
        _state.value = SyncUiState(
            status = status,
            progress = if (total == 0) 1f else completed.toFloat() / total,
            completedItems = completed,
            totalItems = total
        )
    }

    private fun finish(result: SyncResult): SyncResult {
        _state.value = SyncUiState(result.status, conflictKeys = result.conflictKeys, message = result.message)
        return result
    }

    private fun emptyComparison() = com.epubpro.domain.sync.SyncComparison(emptyList(), SyncChangeType.UNCHANGED)

    /**
     * So sánh nội dung tiến độ để giữ nguyên nguồn gốc của bản ghi vừa áp dụng từ Drive.
     *
     * @receiver Tiến độ đọc snapshot sau khi đã áp dụng ledger.
     * @param other Bản ghi đã thắng trong bước merge.
     * @return true nếu vị trí và timestamp đang khớp hoàn toàn.
     */
    private fun com.epubpro.domain.sync.ReadingHistoryRecord.hasSameProgress(
        other: com.epubpro.domain.sync.ReadingHistoryRecord
    ): Boolean =
        currentCfi == other.currentCfi &&
            chapterIndex == other.chapterIndex &&
            pageIndex == other.pageIndex &&
            progressPercentage == other.progressPercentage &&
            totalChapters == other.totalChapters &&
            lastReadAt == other.lastReadAt &&
            updatedAt == other.updatedAt

    private fun sha256(file: java.io.File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val DATABASE_KEY = "database/local_db.sqlite3"
    }
}
