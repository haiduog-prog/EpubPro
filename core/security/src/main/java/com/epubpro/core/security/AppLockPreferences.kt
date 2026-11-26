package com.epubpro.core.security

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Lưu [AppLockSettings] trong SharedPreferences riêng của module.
 *
 * Đọc đồng bộ khi khởi tạo để trạng thái khoá đúng ngay từ khung hình đầu tiên khi mở app.
 *
 * @param context Context ứng dụng.
 */
internal class AppLockPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(read())

    /** Cấu hình hiện tại. */
    val settings: StateFlow<AppLockSettings> = _settings.asStateFlow()

    /**
     * Ghi cấu hình mới và phát cho người quan sát.
     *
     * @param transform Hàm biến đổi từ cấu hình hiện tại.
     */
    @Synchronized
    fun update(transform: (AppLockSettings) -> AppLockSettings) {
        val next = transform(_settings.value)
        prefs.edit()
            .putBoolean(KEY_ENABLED, next.enabled)
            .putString(KEY_DELAY, next.delay.name)
            .putBoolean(KEY_SECURE_SCREEN, next.secureScreen)
            .commit()
        _settings.value = next
    }

    /**
     * Đọc cấu hình đã lưu; giá trị lạ (bản cũ/hỏng) quay về mặc định.
     *
     * @return Cấu hình đã lưu.
     */
    private fun read(): AppLockSettings {
        val defaults = AppLockSettings()
        val delay = prefs.getString(KEY_DELAY, null)
            ?.let { name -> AppLockDelay.entries.firstOrNull { it.name == name } }
            ?: defaults.delay
        return AppLockSettings(
            enabled = prefs.getBoolean(KEY_ENABLED, defaults.enabled),
            delay = delay,
            secureScreen = prefs.getBoolean(KEY_SECURE_SCREEN, defaults.secureScreen)
        )
    }

    private companion object {
        const val PREFERENCES_NAME = "app_lock_preferences"
        const val KEY_ENABLED = "enabled"
        const val KEY_DELAY = "delay"
        const val KEY_SECURE_SCREEN = "secure_screen"
    }
}
