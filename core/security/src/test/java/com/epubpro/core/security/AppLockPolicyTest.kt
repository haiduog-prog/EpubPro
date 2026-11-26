package com.epubpro.core.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Kiểm tra quy tắc khoá lại khi quay về foreground của [AppLockPolicy]. */
class AppLockPolicyTest {

    private val oneMinute = AppLockSettings(enabled = true, delay = AppLockDelay.ONE_MINUTE)

    /** Khoá tắt thì không bao giờ khoá. */
    @Test
    fun neverLocksWhenDisabled() {
        assertFalse(AppLockPolicy.shouldLockOnForeground(AppLockSettings(enabled = false), 0L, 10_000_000L))
    }

    /** Không ghi nhận lúc vào nền (ví dụ đang xác thực) thì không khoá. */
    @Test
    fun doesNotLockWithoutBackgroundTimestamp() {
        assertFalse(AppLockPolicy.shouldLockOnForeground(oneMinute, null, 10_000_000L))
    }

    /** Chưa đủ thời gian chờ thì không khoá; đủ hoặc quá thì khoá. */
    @Test
    fun locksOnlyAfterDelay() {
        assertFalse(AppLockPolicy.shouldLockOnForeground(oneMinute, 1_000L, 1_000L + 59_999L))
        assertTrue(AppLockPolicy.shouldLockOnForeground(oneMinute, 1_000L, 1_000L + 60_000L))
        assertTrue(AppLockPolicy.shouldLockOnForeground(oneMinute, 1_000L, 1_000L + 3_600_000L))
    }

    /** "Ngay khi rời app" khoá ở mọi lần quay về. */
    @Test
    fun immediateLocksEveryTime() {
        val immediate = AppLockSettings(enabled = true, delay = AppLockDelay.IMMEDIATE)
        assertTrue(AppLockPolicy.shouldLockOnForeground(immediate, 5_000L, 5_000L))
    }

    /** Huỷ màn PIN rồi quay lại ngay không bị khoá, kể cả khi chọn khoá "ngay". */
    @Test
    fun authTripShorterThanGraceDoesNotLock() {
        val immediate = AppLockSettings(enabled = true, delay = AppLockDelay.IMMEDIATE)
        assertFalse(AppLockPolicy.shouldLockAfterAuthTrip(immediate, 1_000L, 1_000L + 29_999L))
        assertFalse(AppLockPolicy.shouldLockAfterAuthTrip(oneMinute, 1_000L, 1_000L + 59_999L))
    }

    /** Bỏ dở màn PIN rồi rời app lâu thì khoá khi xác thực không thành công. */
    @Test
    fun longAuthTripLocks() {
        val immediate = AppLockSettings(enabled = true, delay = AppLockDelay.IMMEDIATE)
        assertTrue(AppLockPolicy.shouldLockAfterAuthTrip(immediate, 1_000L, 1_000L + AppLockPolicy.AUTH_TRIP_GRACE_MS))
        assertTrue(AppLockPolicy.shouldLockAfterAuthTrip(oneMinute, 1_000L, 1_000L + 3_600_000L))
    }

    /** Khoá đã tắt (ví dụ vừa bật khoá nhưng bị huỷ) thì không khoá sau lần rời app khi xác thực. */
    @Test
    fun authTripNeverLocksWhenDisabled() {
        assertFalse(AppLockPolicy.shouldLockAfterAuthTrip(AppLockSettings(enabled = false), 0L, 10_000_000L))
    }

    /** Đồng hồ lùi (dữ liệu sai) thì khoá cho an toàn. */
    @Test
    fun locksWhenClockGoesBackwards() {
        assertTrue(AppLockPolicy.shouldLockOnForeground(oneMinute, 10_000L, 5_000L))
    }
}
