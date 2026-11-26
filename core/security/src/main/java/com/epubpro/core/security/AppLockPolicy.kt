package com.epubpro.core.security

/**
 * Quy tắc quyết định có khoá app khi quay lại foreground hay không (tách riêng để unit test).
 */
internal object AppLockPolicy {

    /**
     * Quyết định có khoá app khi tiến trình trở lại foreground.
     *
     * @param settings Cấu hình khoá hiện tại.
     * @param backgroundAtElapsed Thời điểm (`SystemClock.elapsedRealtime`) app vào nền, `null` nếu chưa ghi nhận
     *        (ví dụ app vào nền trong lúc đang xác thực).
     * @param nowElapsed Thời điểm hiện tại theo cùng đồng hồ.
     * @return `true` nếu cần khoá.
     */
    fun shouldLockOnForeground(settings: AppLockSettings, backgroundAtElapsed: Long?, nowElapsed: Long): Boolean {
        if (!settings.enabled || backgroundAtElapsed == null) return false
        // Đồng hồ elapsedRealtime không lùi; giá trị âm chỉ xảy ra nếu dữ liệu sai, khi đó khoá cho an toàn.
        val elapsed = nowElapsed - backgroundAtElapsed
        return elapsed < 0 || elapsed >= settings.delay.millis
    }

    /**
     * Quyết định có khoá app sau một lần rời app trong lúc đang xác thực, khi xác thực đó không thành công.
     *
     * Màn nhập PIN của hệ thống là Activity riêng nên luôn làm app "vào nền"; dùng khoảng chờ tối thiểu
     * [AUTH_TRIP_GRACE_MS] để huỷ màn PIN rồi quay lại ngay không bị khoá, kể cả khi chọn khoá "ngay".
     *
     * @param settings Cấu hình khoá lúc xác thực kết thúc.
     * @param backgroundAtElapsed Thời điểm app vào nền.
     * @param foregroundAtElapsed Thời điểm app quay lại.
     * @return `true` nếu cần khoá.
     */
    fun shouldLockAfterAuthTrip(settings: AppLockSettings, backgroundAtElapsed: Long, foregroundAtElapsed: Long): Boolean {
        if (!settings.enabled) return false
        val away = foregroundAtElapsed - backgroundAtElapsed
        return away < 0 || away >= maxOf(settings.delay.millis, AUTH_TRIP_GRACE_MS)
    }

    /** Thời gian tối thiểu rời app (trong lúc xác thực) mới bị coi là rời app thật. */
    const val AUTH_TRIP_GRACE_MS = 30_000L
}
