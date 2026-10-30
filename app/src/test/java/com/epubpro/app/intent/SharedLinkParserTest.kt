package com.epubpro.app.intent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Kiểm thử trích liên kết từ văn bản chia sẻ. */
class SharedLinkParserTest {

    /** Lấy URL đầu tiên và bỏ dấu câu dính cuối. */
    @Test
    fun extractsFirstUrl() {
        assertEquals(
            "https://truyen.vn/chuong-1",
            SharedLinkParser.extractUrl("Đọc chương này: https://truyen.vn/chuong-1. Hay lắm https://b.com")
        )
    }

    /** Văn bản không có liên kết http(s) bị bỏ qua. */
    @Test
    fun ignoresTextWithoutHttpUrl() {
        assertNull(SharedLinkParser.extractUrl("chỉ là chữ"))
        assertNull(SharedLinkParser.extractUrl("javascript:alert(1)"))
        assertNull(SharedLinkParser.extractUrl(null))
    }
}
