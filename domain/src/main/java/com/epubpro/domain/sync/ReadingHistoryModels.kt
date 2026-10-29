package com.epubpro.domain.sync

/**
 * Một mốc tiến độ đọc được trao đổi giữa các thiết bị.
 *
 * @property bookKey Khóa nội dung ổn định dùng để ghép sách giữa các thiết bị.
 * @property currentCfi Vị trí EPUB đang đọc.
 * @property chapterIndex Chỉ số chương hiện tại, bắt đầu từ 0.
 * @property pageIndex Chỉ số trang hiện tại, bắt đầu từ 1.
 * @property progressPercentage Tỷ lệ hoàn thành trong khoảng 0 đến 1.
 * @property totalChapters Tổng số chương đã biết.
 * @property lastReadAt Thời điểm đọc gần nhất theo epoch milliseconds.
 * @property updatedAt Thời điểm vị trí đọc được lưu gần nhất.
 * @property updatedByDeviceId UUID của thiết bị tạo lần cập nhật này.
 */
data class ReadingHistoryRecord(
    val bookKey: String,
    val currentCfi: String,
    val chapterIndex: Int,
    val pageIndex: Int,
    val progressPercentage: Float,
    val totalChapters: Int,
    val lastReadAt: Long,
    val updatedAt: Long,
    val updatedByDeviceId: String
)

/** Ledger tiến độ do một thiết bị sở hữu trên Google Drive. */
data class ReadingHistoryLedger(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val deviceId: String,
    val records: List<ReadingHistoryRecord>
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

/** Quy tắc gộp tiến độ theo lần cập nhật mới nhất, có thứ tự phân xử ổn định. */
object ReadingHistoryMerger {
    private val precedence = compareBy<ReadingHistoryRecord>(
        ReadingHistoryRecord::updatedAt,
        ReadingHistoryRecord::updatedByDeviceId,
        ReadingHistoryRecord::chapterIndex,
        ReadingHistoryRecord::pageIndex,
        ReadingHistoryRecord::currentCfi,
        ReadingHistoryRecord::progressPercentage
    )

    /**
     * Chọn một bản ghi thắng cho mỗi khóa truyện và sắp xếp kết quả ổn định.
     *
     * @param records Các bản ghi local và remote đã qua kiểm tra định dạng.
     * @return Danh sách duy nhất theo `bookKey`, ưu tiên timestamp mới hơn.
     */
    fun merge(records: Iterable<ReadingHistoryRecord>): List<ReadingHistoryRecord> =
        records.groupBy(ReadingHistoryRecord::bookKey)
            .values
            .map { duplicates -> duplicates.maxWith(precedence) }
            .sortedBy(ReadingHistoryRecord::bookKey)
}
