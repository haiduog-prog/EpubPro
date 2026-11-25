package com.epubpro.core.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Kiểm tra chuẩn hóa Base URL: thiếu scheme thì mặc định HTTPS, chỉ host cục bộ/LAN mới dùng HTTP.
 */
class ServerBaseUrlNormalizationTest {

    /** Host công khai không có scheme phải dùng HTTPS để token không bị gửi dạng thô. */
    @Test
    fun publicHostWithoutSchemeDefaultsToHttps() {
        assertEquals(
            "https://epubbackend.onrender.com/api/v1/",
            normalizeServerBaseUrl("epubbackend.onrender.com/api/v1")
        )
        assertEquals("https://8.8.8.8:8000/", normalizeServerBaseUrl(" 8.8.8.8:8000 "))
    }

    /** Server phát triển trên máy/LAN thường không có chứng chỉ nên vẫn mặc định HTTP. */
    @Test
    fun localAndPrivateHostsWithoutSchemeDefaultToHttp() {
        assertEquals("http://10.0.2.2:8000/api/v1/", normalizeServerBaseUrl("10.0.2.2:8000/api/v1/"))
        assertEquals("http://127.0.0.1:8000/", normalizeServerBaseUrl("127.0.0.1:8000"))
        assertEquals("http://192.168.1.20:8000/", normalizeServerBaseUrl("192.168.1.20:8000"))
        assertEquals("http://localhost:8000/", normalizeServerBaseUrl("localhost:8000"))
        assertEquals("http://my-pc.local/", normalizeServerBaseUrl("my-pc.local"))
    }

    /** Scheme người dùng nhập rõ ràng được giữ nguyên, kể cả HTTP. */
    @Test
    fun explicitSchemeIsKept() {
        assertEquals("http://example.com/", normalizeServerBaseUrl("http://example.com"))
        assertEquals("https://10.0.2.2:8443/", normalizeServerBaseUrl("https://10.0.2.2:8443/"))
    }

    /** Chuỗi rỗng quay về server mặc định. */
    @Test
    fun blankUsesDefaultBaseUrl() {
        assertEquals(ServerPreferencesManager.DEFAULT_BASE_URL, normalizeServerBaseUrl("   "))
    }

    /** Ranh giới các dải IPv4 private không được nhận nhầm IP công khai. */
    @Test
    fun privateRangeBoundaries() {
        assertTrue(isLocalDevelopmentHost("172.16.0.1"))
        assertTrue(isLocalDevelopmentHost("172.31.255.255:80"))
        assertFalse(isLocalDevelopmentHost("172.32.0.1"))
        assertFalse(isLocalDevelopmentHost("192.169.0.1"))
        assertFalse(isLocalDevelopmentHost("10.example.com"))
        assertFalse(isLocalDevelopmentHost("localhost.evil.com"))
    }
}
