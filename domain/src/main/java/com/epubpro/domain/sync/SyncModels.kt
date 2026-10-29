package com.epubpro.domain.sync

import kotlinx.coroutines.flow.Flow

/**
 * Metadata của một file được đưa vào manifest đồng bộ.
 *
 * @property size Kích thước file theo byte.
 * @property mtimeNs Thời điểm sửa đổi theo nano giây, chỉ mang tính tham khảo trên Android.
 * @property sha256 SHA-256 của toàn bộ nội dung file.
 */
data class SyncFileEntry(
    val size: Long,
    val mtimeNs: Long,
    val sha256: String
)

/**
 * Metadata của snapshot SQLite trong manifest.
 *
 * @property size Kích thước snapshot theo byte.
 * @property mtimeNs Thời điểm tạo snapshot theo nano giây.
 * @property sha256 SHA-256 của file snapshot vật lý.
 * @property contentSha256 Fingerprint logic tương thích với contract backend.
 */
data class SyncDatabaseEntry(
    val size: Long,
    val mtimeNs: Long,
    val sha256: String,
    val contentSha256: String?
)

/**
 * Manifest mô tả trạng thái dữ liệu tại một thời điểm.
 *
 * @property schemaVersion Phiên bản contract manifest.
 * @property createdAt Thời điểm tạo manifest theo ISO-8601.
 * @property machine Định danh máy tạo manifest.
 * @property storage Các file thuộc bucket `novels` và `uploads`.
 * @property database Snapshot SQLite.
 */
data class SyncManifest(
    val schemaVersion: Int,
    val createdAt: String,
    val machine: String,
    val storage: Map<String, SyncFileEntry>,
    val database: SyncDatabaseEntry?
)

/**
 * Tùy chọn điều khiển một phiên đồng bộ.
 *
 * @property allowDeletions Cho phép áp dụng thao tác xóa sau khi người dùng xác nhận.
 * @property force Bỏ qua thay đổi phía Drive sau khi người dùng xác nhận rõ ràng.
 */
data class SyncOptions(
    val allowDeletions: Boolean = false,
    val force: Boolean = false
)

/** Trạng thái public được UI và WorkManager sử dụng. */
enum class SyncStatus {
    READY,
    CHANGES_PENDING,
    SYNCING_UP,
    SYNCING_DOWN,
    SYNCED,
    DRIVE_PENDING,
    CONFLICT,
    AUTH_REQUIRED,
    ERROR
}

/** Loại thay đổi được suy ra từ local, Drive và baseline. */
enum class SyncChangeType {
    UNCHANGED,
    UPLOAD,
    DOWNLOAD,
    DELETE_REMOTE,
    DELETE_LOCAL,
    CONFLICT,
    INCOMPATIBLE
}

/**
 * Thay đổi của một key sau khi so sánh ba phiên bản.
 *
 * @property key Relative key dạng POSIX.
 * @property type Loại thay đổi.
 */
data class SyncChange(
    val key: String,
    val type: SyncChangeType
)

/**
 * Kết quả so sánh manifest.
 *
 * @property changes Danh sách thay đổi theo key.
 * @property databaseChange Thay đổi riêng của database nếu có.
 */
data class SyncComparison(
    val changes: List<SyncChange>,
    val databaseChange: SyncChangeType
) {
    /** Số key cần upload. */
    val uploadCount: Int get() = changes.count { it.type == SyncChangeType.UPLOAD }

    /** Số key cần download. */
    val downloadCount: Int get() = changes.count { it.type == SyncChangeType.DOWNLOAD }

    /** Các key đang conflict hoặc không tương thích. */
    val blockingKeys: List<String>
        get() = buildList {
            addAll(changes.filter { it.type == SyncChangeType.CONFLICT || it.type == SyncChangeType.INCOMPATIBLE }
                .map(SyncChange::key))
            if (databaseChange == SyncChangeType.CONFLICT || databaseChange == SyncChangeType.INCOMPATIBLE) {
                add(DATABASE_KEY)
            }
        }

    private companion object {
        const val DATABASE_KEY = "database/local_db.sqlite3"
    }
}

/**
 * Kết quả kiểm tra trước khi sync.
 *
 * @property status Trạng thái suy ra.
 * @property comparison So sánh local với Drive.
 * @property message Thông báo kỹ thuật dành cho lớp trình bày.
 */
data class SyncCheckResult(
    val status: SyncStatus,
    val comparison: SyncComparison,
    val message: String? = null
)

/**
 * Kết quả của backup hoặc restore.
 *
 * @property status Trạng thái cuối phiên.
 * @property uploadedCount Số file đã upload.
 * @property downloadedCount Số file đã download.
 * @property deletedCount Số file đã xóa theo tùy chọn.
 * @property conflictKeys Các key khiến phiên bị dừng.
 * @property message Thông báo lỗi hoặc summary.
 */
data class SyncResult(
    val status: SyncStatus,
    val uploadedCount: Int = 0,
    val downloadedCount: Int = 0,
    val deletedCount: Int = 0,
    val conflictKeys: List<String> = emptyList(),
    val message: String? = null
)

/**
 * State dùng để render màn hình sync.
 *
 * @property status Trạng thái hiện tại.
 * @property progress Giá trị từ 0 đến 1 nếu đã biết tiến độ.
 * @property completedItems Số item đã xử lý.
 * @property totalItems Tổng item cần xử lý.
 * @property conflictKeys Các key đang conflict.
 * @property message Thông báo hiển thị.
 */
data class SyncUiState(
    val status: SyncStatus = SyncStatus.READY,
    val progress: Float = 0f,
    val completedItems: Int = 0,
    val totalItems: Int = 0,
    val conflictKeys: List<String> = emptyList(),
    val message: String? = null
)

/**
 * Public contract để UI và background worker điều khiển đồng bộ.
 */
interface SyncCoordinator {
    /** Kiểm tra thay đổi mà không ghi đè dữ liệu. */
    suspend fun check(): SyncCheckResult

    /** Backup các thay đổi local lên Drive. */
    /** Gộp tiến độ đọc giữa Room và các ledger trên Google Drive. */
    suspend fun syncReadingHistory(): SyncResult

    suspend fun backup(options: SyncOptions = SyncOptions()): SyncResult

    /** Restore các thay đổi từ Drive về local. */
    suspend fun restore(options: SyncOptions = SyncOptions()): SyncResult

    /** Theo dõi state phiên sync hiện tại. */
    fun observeState(): Flow<SyncUiState>
}
