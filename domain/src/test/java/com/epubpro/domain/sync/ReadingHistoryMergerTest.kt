package com.epubpro.domain.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadingHistoryMergerTest {
    /** Kiểm tra bản mới hơn thắng riêng từng truyện và truyện chưa ghép vẫn được giữ. */
    @Test
    fun newerTimestampWinsForEachBookAndUnknownBooksAreKept() {
        val older = record("book-a", updatedAt = 10, deviceId = "device-a", chapter = 1)
        val newer = record("book-a", updatedAt = 20, deviceId = "device-b", chapter = 4)
        val unmatched = record("book-b", updatedAt = 5, deviceId = "device-a", chapter = 0)

        val merged = ReadingHistoryMerger.merge(listOf(older, unmatched, newer))

        assertEquals(listOf("book-a", "book-b"), merged.map { it.bookKey })
        assertEquals(4, merged.first().chapterIndex)
        assertEquals(unmatched, merged.last())
    }

    /** Kiểm tra timestamp bằng nhau cho kết quả giống nhau bất kể thứ tự đầu vào. */
    @Test
    fun equalTimestampUsesStableDeviceIdTieBreaker() {
        val fromA = record("book-a", updatedAt = 10, deviceId = "device-a", chapter = 2)
        val fromB = record("book-a", updatedAt = 10, deviceId = "device-b", chapter = 3)

        val forward = ReadingHistoryMerger.merge(listOf(fromA, fromB))
        val reverse = ReadingHistoryMerger.merge(listOf(fromB, fromA))

        assertEquals(3, forward.single().chapterIndex)
        assertEquals(forward, reverse)
    }

    /**
     * Tạo một bản ghi tiến độ mẫu dùng chung cho các tình huống kiểm thử.
     *
     * @param bookKey Khóa truyện.
     * @param updatedAt Timestamp cập nhật.
     * @param deviceId UUID thiết bị tạo bản ghi.
     * @param chapter Chỉ số chương.
     * @return Bản ghi có các trường còn lại hợp lệ.
     */
    private fun record(
        bookKey: String,
        updatedAt: Long,
        deviceId: String,
        chapter: Int
    ) = ReadingHistoryRecord(
        bookKey = bookKey,
        currentCfi = "cfi-$chapter",
        chapterIndex = chapter,
        pageIndex = 1,
        progressPercentage = chapter / 10f,
        totalChapters = 10,
        lastReadAt = updatedAt,
        updatedAt = updatedAt,
        updatedByDeviceId = deviceId
    )
}
