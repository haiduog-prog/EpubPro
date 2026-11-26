package com.epubpro.core.security

import android.content.Context
import android.os.SystemClock
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Điều phối khoá ứng dụng bằng vân tay/khuôn mặt hoặc PIN màn hình khoá của máy.
 *
 * - Process mới khi khoá đang bật luôn bắt đầu ở trạng thái khoá.
 * - Theo dõi [ProcessLifecycleOwner]: ghi thời điểm vào nền và khoá lại khi quay về nếu đã quá [AppLockSettings.delay].
 *   Dùng `elapsedRealtime` nên chỉnh giờ hệ thống không mở khoá được.
 * - Trong lúc hộp thoại xác thực của hệ thống đang mở (màn nhập PIN là Activity riêng), lần rời app đó được
 *   giữ lại và chỉ xét khi xác thực kết thúc: thành công thì bỏ qua (người dùng đã chứng minh danh tính), huỷ/lỗi
 *   thì khoá nếu đã rời app đủ lâu. Nhờ vậy màn PIN không gây vòng lặp khoá, nhưng bỏ dở màn PIN rồi rời app
 *   lâu cũng không mở được nội dung.
 *
 * Khi khoá tắt, lớp này không đổi hành vi app (không hộp thoại, không cờ cửa sổ).
 *
 * @param context Context ứng dụng.
 */
@Singleton
class AppLockManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val preferences = AppLockPreferences(context)
    private val _locked = MutableStateFlow(preferences.settings.value.enabled)
    private var installed = false

    @Volatile
    private var authenticating = false

    @Volatile
    private var backgroundAtElapsed: Long? = null

    /** Lần rời app xảy ra trong lúc đang xác thực: (lúc vào nền, lúc quay lại), xét khi xác thực kết thúc. */
    @Volatile
    private var tripDuringAuth: Pair<Long, Long>? = null

    private val _foregroundCount = MutableStateFlow(0)

    /**
     * Tăng mỗi lần app quay lại foreground (không tính lần quay về từ màn xác thực của hệ thống).
     * Màn khoá dùng giá trị này để tự mở lại hộp thoại xác thực khi người dùng quay về app.
     */
    val foregroundCount: StateFlow<Int> = _foregroundCount.asStateFlow()

    /** Cấu hình khoá hiện tại. */
    val settings: StateFlow<AppLockSettings> = preferences.settings

    /** `true` khi app đang khoá và phải che nội dung. */
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    /**
     * Gắn quan sát vòng đời process; gọi một lần trong `Application.onCreate` trên main thread.
     */
    fun install() {
        if (installed) return
        installed = true
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = onAppForeground()
            override fun onStop(owner: LifecycleOwner) = onAppBackground()
        })
    }

    /**
     * Kiểm tra máy có xác thực được không (vân tay/khuôn mặt hoặc PIN/mật khẩu màn hình khoá).
     *
     * @return Trạng thái khả dụng.
     */
    fun availability(): BiometricAvailability =
        when (BiometricManager.from(context).canAuthenticate(AUTHENTICATORS)) {
            BiometricManager.BIOMETRIC_SUCCESS -> BiometricAvailability.AVAILABLE
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricAvailability.NONE_ENROLLED
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> BiometricAvailability.NO_HARDWARE
            else -> BiometricAvailability.UNAVAILABLE
        }

    /**
     * Mở hộp thoại xác thực của hệ thống và chờ kết quả.
     *
     * @param activity Activity đang hiển thị (BiometricPrompt cần FragmentActivity).
     * @param title Tiêu đề hộp thoại.
     * @param subtitle Mô tả phụ, null nếu không cần.
     * @return Kết quả xác thực.
     */
    suspend fun authenticate(activity: FragmentActivity, title: String, subtitle: String?): AuthResult {
        authenticating = true
        var result: AuthResult = AuthResult.Cancelled
        try {
            result = suspendCancellableCoroutine { continuation ->
                val prompt = BiometricPrompt(
                    activity,
                    ContextCompat.getMainExecutor(activity),
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            if (continuation.isActive) continuation.resume(AuthResult.Success)
                        }

                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                            if (!continuation.isActive) return
                            val cancelled = errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                                errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                                errorCode == BiometricPrompt.ERROR_CANCELED
                            continuation.resume(if (cancelled) AuthResult.Cancelled else AuthResult.Error(errorCode))
                        }
                        // onAuthenticationFailed: một lần quét sai, hộp thoại vẫn mở để thử lại nên không kết thúc.
                    }
                )
                val info = BiometricPrompt.PromptInfo.Builder()
                    .setTitle(title)
                    .apply { subtitle?.let(::setSubtitle) }
                    // Có DEVICE_CREDENTIAL thì không được đặt nút huỷ riêng; hệ thống tự hiện lựa chọn dùng PIN.
                    .setAllowedAuthenticators(AUTHENTICATORS)
                    .build()
                continuation.invokeOnCancellation { prompt.cancelAuthentication() }
                prompt.authenticate(info)
            }
            return result
        } finally {
            authenticating = false
            val trip = tripDuringAuth
            tripDuringAuth = null
            if (result != AuthResult.Success && trip != null &&
                AppLockPolicy.shouldLockAfterAuthTrip(settings.value, trip.first, trip.second)
            ) {
                _locked.value = true
            }
        }
    }

    /**
     * Xác thực để mở khoá app.
     *
     * Nếu khoá đang bật nhưng máy không còn phương thức xác thực (người dùng đã gỡ hết vân tay và khoá màn hình),
     * tự tắt khoá để người dùng không bị khoá ngoài app.
     *
     * @param activity Activity đang hiển thị.
     * @return Kết quả xác thực; [AuthResult.Success] cả khi khoá được tự tắt vì máy không còn hỗ trợ.
     */
    suspend fun unlock(activity: FragmentActivity): AuthResult {
        if (!_locked.value) return AuthResult.Success
        if (availability().isPermanentlyMissing()) {
            preferences.update { it.copy(enabled = false) }
            _locked.value = false
            return AuthResult.Success
        }
        val result = authenticate(
            activity,
            activity.getString(R.string.app_lock_prompt_title),
            activity.getString(R.string.app_lock_prompt_subtitle)
        )
        if (result == AuthResult.Success) _locked.value = false
        return result
    }

    /**
     * Bật/tắt khoá app; cả hai chiều đều phải xác thực để người cầm máy không tự tắt được khoá.
     *
     * @param activity Activity đang hiển thị.
     * @param enabled Trạng thái mong muốn.
     * @return Kết quả xác thực; cấu hình chỉ đổi khi [AuthResult.Success].
     */
    suspend fun setEnabled(activity: FragmentActivity, enabled: Boolean): AuthResult {
        if (settings.value.enabled == enabled) return AuthResult.Success
        val availability = availability()
        if (enabled && availability != BiometricAvailability.AVAILABLE) return AuthResult.Error(ERROR_NOT_AVAILABLE)
        if (!enabled && availability.isPermanentlyMissing()) {
            // Máy đã gỡ hết vân tay và khoá màn hình: không còn gì để xác thực, cho tắt thẳng.
            preferences.update { it.copy(enabled = false) }
            _locked.value = false
            return AuthResult.Success
        }
        val result = authenticate(
            activity,
            activity.getString(R.string.app_lock_prompt_title),
            activity.getString(
                if (enabled) R.string.app_lock_prompt_enable_subtitle else R.string.app_lock_prompt_disable_subtitle
            )
        )
        if (result == AuthResult.Success) {
            preferences.update { it.copy(enabled = enabled) }
            _locked.value = false
        }
        return result
    }

    /**
     * Đổi thời gian ở nền trước khi khoá lại.
     *
     * @param delay Giá trị mới.
     */
    fun setDelay(delay: AppLockDelay) {
        preferences.update { it.copy(delay = delay) }
    }

    /**
     * Bật/tắt chặn chụp màn hình và ảnh xem trước trong Recents.
     *
     * @param secureScreen Giá trị mới.
     */
    fun setSecureScreen(secureScreen: Boolean) {
        preferences.update { it.copy(secureScreen = secureScreen) }
    }

    /** Ghi thời điểm app vào nền (kể cả khi đang xác thực, để xét lại lúc xác thực kết thúc). */
    private fun onAppBackground() {
        backgroundAtElapsed = SystemClock.elapsedRealtime()
    }

    /**
     * Khoá lại khi app quay về foreground nếu đã ở nền quá lâu. Nếu đang xác thực (vừa quay về từ màn PIN),
     * giữ lần rời app này để [authenticate] xét khi có kết quả.
     */
    private fun onAppForeground() {
        val now = SystemClock.elapsedRealtime()
        val backgroundAt = backgroundAtElapsed
        backgroundAtElapsed = null
        if (authenticating) {
            if (backgroundAt != null) tripDuringAuth = backgroundAt to now
            return
        }
        if (AppLockPolicy.shouldLockOnForeground(settings.value, backgroundAt, now)) {
            _locked.value = true
        }
        _foregroundCount.value += 1
    }

    /**
     * Máy không còn phương thức xác thực nào (chưa cài hoặc không có phần cứng), khác với lỗi tạm thời.
     *
     * @return `true` nếu không thể xác thực lâu dài.
     */
    private fun BiometricAvailability.isPermanentlyMissing(): Boolean =
        this == BiometricAvailability.NONE_ENROLLED || this == BiometricAvailability.NO_HARDWARE

    companion object {
        /** Mã lỗi riêng khi máy không có phương thức xác thực để bật khoá. */
        const val ERROR_NOT_AVAILABLE = -1

        /** Vân tay/khuôn mặt hoặc PIN; tổ hợp này chạy được trên API 26–29 (BIOMETRIC_STRONG thì không). */
        private const val AUTHENTICATORS = BIOMETRIC_WEAK or DEVICE_CREDENTIAL
    }
}
