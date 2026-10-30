package com.epubpro.feature.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Kiểm thử chuẩn hóa ô địa chỉ và chặn scheme không an toàn. */
class BrowserAddressTest {

    /** URL đầy đủ giữ nguyên, tên miền được thêm https, từ khóa chuyển thành tìm kiếm. */
    @Test
    fun resolvesAddresses() {
        assertEquals("https://a.com/x", resolveBrowserAddress(" https://a.com/x "))
        assertEquals("https://truyen.example.vn/chuong-1", resolveBrowserAddress("truyen.example.vn/chuong-1"))
        assertEquals("https://localhost:8080/a", resolveBrowserAddress("localhost:8080/a"))
        assertTrue(resolveBrowserAddress("kiếm đạo độc tôn")!!.startsWith("https://www.google.com/search?q="))
        assertNull(resolveBrowserAddress("   "))
    }

    /** Scheme nguy hiểm không bao giờ được mở trực tiếp. */
    @Test
    fun blocksUnsafeSchemes() {
        assertTrue(resolveBrowserAddress("javascript:alert(1)")!!.startsWith("https://www.google.com/search?q="))
        assertTrue(resolveBrowserAddress("file:///sdcard/a")!!.startsWith("https://www.google.com/search?q="))
        assertFalse(isAllowedBrowserUrl("intent://scan#Intent;end"))
        assertFalse(isAllowedBrowserUrl(null))
        assertTrue(isAllowedBrowserUrl("HTTPS://a.com"))
    }
}
