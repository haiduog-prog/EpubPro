package com.epubpro.core.storage.sync

import com.epubpro.domain.sync.ReadingHistoryLedger
import com.epubpro.domain.sync.ReadingHistoryRecord
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.UUID

class ReadingHistoryLedgerCodecTest {
    private val codec = ReadingHistoryLedgerCodec(Gson())

    /** Kiểm tra encode/decode bảo toàn ledger hợp lệ. */
    @Test
    fun validLedgerRoundTrips() {
        val ledger = ReadingHistoryLedger(
            deviceId = UUID.randomUUID().toString(),
            records = listOf(
                ReadingHistoryRecord(
                    bookKey = "epub:abc123",
                    currentCfi = "epubcfi(/6/4)",
                    chapterIndex = 3,
                    pageIndex = 2,
                    progressPercentage = 0.35f,
                    totalChapters = 10,
                    lastReadAt = 100,
                    updatedAt = 200,
                    updatedByDeviceId = UUID.randomUUID().toString()
                )
            )
        )

        assertEquals(ledger, codec.decode(codec.encode(ledger)))
    }

    /** Kiểm tra codec từ chối schema chưa được app hỗ trợ. */
    @Test
    fun unsupportedSchemaIsRejected() {
        val json = """
            {"schemaVersion":99,"deviceId":"${UUID.randomUUID()}","records":[]}
        """.trimIndent()

        assertThrows(IllegalArgumentException::class.java) { codec.decode(json) }
    }

    /** Kiểm tra codec từ chối phần trăm tiến độ nằm ngoài miền hợp lệ. */
    @Test
    fun invalidProgressRangeIsRejected() {
        val deviceId = UUID.randomUUID().toString()
        val recordDeviceId = UUID.randomUUID().toString()
        val json = """
            {
              "schemaVersion":1,
              "deviceId":"$deviceId",
              "records":[{
                "bookKey":"epub:abc123",
                "currentCfi":"cfi",
                "chapterIndex":0,
                "pageIndex":1,
                "progressPercentage":1.5,
                "totalChapters":10,
                "lastReadAt":1,
                "updatedAt":2,
                "updatedByDeviceId":"$recordDeviceId"
              }]
            }
        """.trimIndent()

        assertThrows(IllegalArgumentException::class.java) { codec.decode(json) }
    }
}
