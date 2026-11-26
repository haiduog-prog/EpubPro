package com.epubpro.core.security

/**
 * Khoảng thời gian app ở nền trước khi bị khoá lại.
 *
 * @property millis Thời gian chờ tính bằng mili giây; `0` nghĩa là khoá ngay khi rời app.
 */
enum class AppLockDelay(val millis: Long) {
    IMMEDIATE(0L),
    ONE_MINUTE(60_000L),
    FIVE_MINUTES(5 * 60_000L),
    FIFTEEN_MINUTES(15 * 60_000L)
}

/**
 * Cấu hình khoá ứng dụng.
 *
 * @property enabled Bật khoá app; mặc định tắt, khi tắt app hoạt động như chưa có tính năng này.
 * @property delay Thời gian ở nền trước khi khoá lại; mặc định 1 phút để màn chọn file hay nhập PIN
 *           của hệ thống không làm app khoá ngay khi quay về.
 * @property secureScreen Chặn chụp màn hình và ảnh xem trước trong Recents; chỉ có tác dụng khi khoá bật.
 */
data class AppLockSettings(
    val enabled: Boolean = false,
    val delay: AppLockDelay = AppLockDelay.ONE_MINUTE,
    val secureScreen: Boolean = false
)

/**
 * Khả năng xác thực của thiết bị cho khoá app (vân tay/khuôn mặt hoặc PIN/mật khẩu màn hình khoá).
 */
enum class BiometricAvailability {
    /** Có thể xác thực. */
    AVAILABLE,

    /** Máy có phần cứng nhưng chưa cài vân tay/khuôn mặt hay khoá màn hình. */
    NONE_ENROLLED,

    /** Máy không có phương thức xác thực phù hợp. */
    NO_HARDWARE,

    /** Tạm thời không dùng được (phần cứng bận, cần cập nhật bảo mật...). */
    UNAVAILABLE
}

/**
 * Kết quả một lần xác thực.
 */
sealed interface AuthResult {
    /** Xác thực thành công. */
    data object Success : AuthResult

    /** Người dùng huỷ hộp thoại hoặc hệ thống đóng nó. */
    data object Cancelled : AuthResult

    /**
     * Xác thực lỗi (bị khoá tạm do thử sai nhiều lần, phần cứng lỗi...).
     *
     * @property code Mã lỗi của `BiometricPrompt`.
     */
    data class Error(val code: Int) : AuthResult
}
