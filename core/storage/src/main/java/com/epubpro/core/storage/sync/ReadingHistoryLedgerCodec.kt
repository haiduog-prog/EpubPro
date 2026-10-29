package com.epubpro.core.storage.sync

import com.epubpro.domain.sync.ReadingHistoryLedger
import com.epubpro.domain.sync.ReadingHistoryRecord
import com.google.gson.Gson
import com.google.gson.JsonParseException
import java.util.UUID
import javax.inject.Inject

/** Mã hóa và kiểm tra tính hợp lệ của ledger lịch sử đọc trước khi áp dụng vào Room. */
class ReadingHistoryLedgerCodec @Inject constructor(
    private val gson: Gson
) {
    /**
     * Kiểm tra rồi mã hóa ledger thành JSON UTF-8.
     *
     * @param ledger Ledger cần đưa lên Google Drive.
     * @return JSON theo schema hiện tại.
     */
    fun encode(ledger: ReadingHistoryLedger): String {
        validateLedger(ledger)
        return gson.toJson(ledger)
    }

    /**
     * Giải mã và xác thực toàn bộ dữ liệu nhận từ Google Drive.
     *
     * @param json Nội dung JSON tải từ ledger Drive.
     * @return Ledger hợp lệ theo schema hiện tại.
     * @throws IllegalArgumentException Nếu schema hoặc dữ liệu không hợp lệ.
     */
    fun decode(json: String): ReadingHistoryLedger {
        require(json.length <= MAX_JSON_LENGTH) { "Ledger lịch sử đọc vượt quá giới hạn" }
        val ledger = try {
            gson.fromJson(json, ReadingHistoryLedger::class.java)
        } catch (error: JsonParseException) {
            throw IllegalArgumentException("Ledger lịch sử đọc không phải JSON hợp lệ", error)
        } ?: throw IllegalArgumentException("Ledger lịch sử đọc rỗng")
        validateLedger(ledger)
        return ledger
    }

    /**
     * Kiểm tra schema, UUID, kích thước và từng bản ghi để tránh áp dụng dữ liệu tùy ý.
     *
     * @param ledger Ledger cần kiểm tra.
     * @throws IllegalArgumentException Nếu bất kỳ trường nào vượt contract.
     */
    private fun validateLedger(ledger: ReadingHistoryLedger) {
        require(ledger.schemaVersion == ReadingHistoryLedger.CURRENT_SCHEMA_VERSION) {
            "Schema ledger lịch sử đọc không được hỗ trợ"
        }
        require(isUuid(ledger.deviceId)) { "Ledger lịch sử đọc thiếu device ID hợp lệ" }
        require(ledger.records.size <= MAX_RECORDS) { "Ledger lịch sử đọc có quá nhiều bản ghi" }
        require(ledger.records.map(ReadingHistoryRecord::bookKey).distinct().size == ledger.records.size) {
            "Ledger lịch sử đọc chứa khóa truyện trùng"
        }
        ledger.records.forEach(::validateRecord)
    }

    /**
     * Kiểm tra miền giá trị của một mốc tiến độ.
     *
     * @param record Mốc tiến độ cần xác thực.
     * @throws IllegalArgumentException Nếu dữ liệu không thể dùng để khôi phục vị trí đọc.
     */
    private fun validateRecord(record: ReadingHistoryRecord) {
        require(record.bookKey.isNotBlank() && record.bookKey.length <= MAX_BOOK_KEY_LENGTH) {
            "Ledger lịch sử đọc chứa book key không hợp lệ"
        }
        require(record.currentCfi.length <= MAX_CFI_LENGTH) { "Ledger lịch sử đọc chứa CFI quá dài" }
        require(record.chapterIndex >= 0 && record.pageIndex >= 1 && record.totalChapters >= 0) {
            "Ledger lịch sử đọc chứa vị trí chương/trang không hợp lệ"
        }
        require(record.progressPercentage.isFinite() && record.progressPercentage in 0f..1f) {
            "Ledger lịch sử đọc chứa phần trăm không hợp lệ"
        }
        require(record.lastReadAt >= 0 && record.updatedAt >= 0) {
            "Ledger lịch sử đọc chứa timestamp không hợp lệ"
        }
        require(isUuid(record.updatedByDeviceId)) {
            "Ledger lịch sử đọc chứa device ID cập nhật không hợp lệ"
        }
    }

    /**
     * Kiểm tra chuỗi có đúng cấu trúc UUID mà app tạo cho thiết bị hay không.
     *
     * @param value Chuỗi cần kiểm tra.
     * @return true nếu chuỗi có thể phân tích thành UUID.
     */
    private fun isUuid(value: String): Boolean = runCatching { UUID.fromString(value) }.isSuccess

    private companion object {
        const val MAX_JSON_LENGTH = 8 * 1024 * 1024
        const val MAX_RECORDS = 25_000
        const val MAX_BOOK_KEY_LENGTH = 2_048
        const val MAX_CFI_LENGTH = 32_768
    }
}
