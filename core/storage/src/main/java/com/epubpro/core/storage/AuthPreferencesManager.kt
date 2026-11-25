package com.epubpro.core.storage

import android.content.Context
import com.epubpro.core.storage.network.AuthConfigDto
import com.epubpro.domain.model.AuthState
import com.epubpro.domain.model.User
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Trình quản lý lưu trữ phiên đăng nhập, tokens và trạng thái tài khoản người dùng vào SharedPreferences.
 *
 * Cung cấp luồng dữ liệu [StateFlow] phản ánh trạng thái xác thực [AuthState] realtime cho toàn bộ ứng dụng.
 */
@Singleton
class AuthPreferencesManager @Inject constructor(
    @ApplicationContext context: Context,
    private val gson: Gson
) {
    private val preferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    // Khóa chung cho mọi thao tác đọc-ghi phiên, để lần làm mới token chạy trên luồng OkHttp không ghi
    // đè lên trạng thái vừa bị đăng xuất hoặc vừa chuyển sang tài khoản khác.
    private val sessionLock = Any()

    private val _authState = MutableStateFlow<AuthState>(readInitialAuthState())

    /**
     * Luồng phát ra trạng thái xác thực hiện tại của ứng dụng.
     */
    val authStateFlow: StateFlow<AuthState> = _authState.asStateFlow()

    /**
     * Lấy thông tin người dùng đang lưu trong bộ nhớ nếu có.
     *
     * @return [User] nếu đã có phiên đăng nhập hợp lệ, hoặc null nếu chưa đăng nhập.
     */
    fun getSavedUser(): User? {
        val userJson = preferences.getString(KEY_USER_DATA, null) ?: return null
        return try {
            gson.fromJson(userJson, User::class.java)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Lưu trữ thông tin tài khoản người dùng và phát ra trạng thái [AuthState.Authenticated].
     *
     * @param user Đối tượng [User] chứa thông tin tài khoản cần lưu.
     */
    fun saveUser(user: User) = synchronized(sessionLock) {
        val userJson = gson.toJson(user)
        preferences.edit()
            .putString(KEY_USER_DATA, userJson)
            .putString(KEY_AUTH_TOKEN, user.token)
            .putString(KEY_REFRESH_TOKEN, user.refreshToken)
            .putBoolean(KEY_IS_LOGGED_IN, true)
            .apply()
        _authState.value = AuthState.Authenticated(user)
    }

    /**
     * Lưu trữ bộ Token xác thực phiên đăng nhập (Access Token, Refresh Token, Expires At) cho phiên đang đăng nhập.
     * Bỏ qua nếu không còn phiên nào (đã đăng xuất), để token không "sống lại" sau khi người dùng đăng xuất.
     *
     * @param accessToken Token truy cập Bearer.
     * @param refreshToken Token làm mới phiên.
     * @param expiresAt Thời điểm hết hạn (Unix timestamp).
     * @return `true` nếu đã lưu; `false` nếu không còn phiên đăng nhập.
     */
    fun saveTokens(accessToken: String, refreshToken: String?, expiresAt: Long?): Boolean = synchronized(sessionLock) {
        if (!preferences.getBoolean(KEY_IS_LOGGED_IN, false)) return@synchronized false
        writeTokensLocked(accessToken, refreshToken, expiresAt)
        true
    }

    /**
     * Lưu token mới nhận từ một lần làm mới phiên, chỉ khi phiên vẫn là phiên đã dùng [expectedRefreshToken] để
     * làm mới. Lần làm mới chạy trên luồng mạng có thể kết thúc sau khi người dùng đăng xuất hoặc đăng nhập tài
     * khoản khác; khi đó kết quả cũ phải bị bỏ.
     *
     * @param expectedRefreshToken Refresh token đã gửi lên server cho lần làm mới này.
     * @param accessToken Access token mới.
     * @param refreshToken Refresh token mới, hoặc null nếu server không xoay vòng.
     * @param expiresAt Thời điểm hết hạn mới (Unix timestamp giây).
     * @return `true` nếu đã lưu; `false` nếu phiên đã đổi hoặc đã đăng xuất.
     */
    fun replaceTokensIfCurrent(
        expectedRefreshToken: String,
        accessToken: String,
        refreshToken: String?,
        expiresAt: Long?
    ): Boolean = synchronized(sessionLock) {
        if (!preferences.getBoolean(KEY_IS_LOGGED_IN, false)) return@synchronized false
        if (preferences.getString(KEY_REFRESH_TOKEN, null) != expectedRefreshToken) return@synchronized false
        writeTokensLocked(accessToken, refreshToken, expiresAt)
        true
    }

    /**
     * Ghi token vào SharedPreferences và đồng bộ vào [User] đang lưu; caller phải giữ [sessionLock].
     *
     * @param accessToken Access token mới.
     * @param refreshToken Refresh token mới, hoặc null để giữ token cũ.
     * @param expiresAt Thời điểm hết hạn, hoặc null để giữ giá trị cũ.
     */
    private fun writeTokensLocked(accessToken: String, refreshToken: String?, expiresAt: Long?) {
        val editor = preferences.edit()
            .putString(KEY_AUTH_TOKEN, accessToken)
        if (refreshToken != null) {
            editor.putString(KEY_REFRESH_TOKEN, refreshToken)
        }
        if (expiresAt != null) {
            editor.putLong(KEY_EXPIRES_AT, expiresAt)
        }
        editor.apply()

        // Đồng bộ cập nhật token vào đối tượng User đang lưu
        updateUser { currentUser ->
            currentUser.copy(
                token = accessToken,
                refreshToken = refreshToken ?: currentUser.refreshToken
            )
        }
    }

    /**
     * Lưu cấu hình Auth nhận được từ Backend API (`GET /api/auth/config`).
     *
     * @param config Đối tượng cấu hình [AuthConfigDto].
     */
    fun saveAuthConfig(config: AuthConfigDto) {
        val configJson = gson.toJson(config)
        preferences.edit()
            .putString(KEY_AUTH_CONFIG, configJson)
            .apply()
    }

    /**
     * Lấy cấu hình Auth đã lưu trữ trong bộ nhớ đệm nếu có.
     *
     * @return [AuthConfigDto] hoặc null nếu chưa có.
     */
    fun getAuthConfig(): AuthConfigDto? {
        val configJson = preferences.getString(KEY_AUTH_CONFIG, null) ?: return null
        return try {
            gson.fromJson(configJson, AuthConfigDto::class.java)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Xóa sạch phiên đăng nhập hiện tại và phát ra trạng thái [AuthState.Unauthenticated].
     */
    fun clearSession() = synchronized(sessionLock) {
        preferences.edit()
            .remove(KEY_USER_DATA)
            .remove(KEY_AUTH_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_EXPIRES_AT)
            .putBoolean(KEY_IS_LOGGED_IN, false)
            .apply()
        _authState.value = AuthState.Unauthenticated
    }

    /**
     * Cập nhật thông tin người dùng hiện tại thông qua hàm biến đổi.
     *
     * @param transform Hàm biến đổi nhận vào [User] hiện tại và trả về [User] mới.
     * @return [User] sau khi được cập nhật, hoặc null nếu chưa đăng nhập.
     */
    fun updateUser(transform: (User) -> User): User? = synchronized(sessionLock) {
        val currentUser = getSavedUser() ?: return@synchronized null
        val updated = transform(currentUser)
        saveUser(updated)
        updated
    }

    /**
     * Lấy mã Access Token xác thực phiên nếu có.
     *
     * @return Chuỗi token hoặc null.
     */
    fun getAuthToken(): String? {
        val token = preferences.getString(KEY_AUTH_TOKEN, null)
        if (!token.isNullOrBlank()) {
            return token
        }
        return getSavedUser()?.token
    }

    /**
     * Lấy mã Refresh Token nếu có.
     *
     * @return Chuỗi refresh token hoặc null.
     */
    fun getRefreshToken(): String? {
        return preferences.getString(KEY_REFRESH_TOKEN, null)
    }

    /**
     * Lấy mốc thời gian hết hạn của Access Token (Unix timestamp giây).
     *
     * @return Thời điểm hết hạn hoặc 0 nếu không có.
     */
    fun getExpiresAt(): Long {
        return preferences.getLong(KEY_EXPIRES_AT, 0L)
    }

    /**
     * Đọc trạng thái xác thực ban đầu từ SharedPreferences khi khởi tạo đối tượng.
     *
     * @return [AuthState] tương ứng với dữ liệu đã lưu.
     */
    private fun readInitialAuthState(): AuthState {
        val isLoggedIn = preferences.getBoolean(KEY_IS_LOGGED_IN, false)
        if (!isLoggedIn) {
            return AuthState.Unauthenticated
        }
        val user = getSavedUser()
        return if (user != null) {
            AuthState.Authenticated(user)
        } else {
            AuthState.Unauthenticated
        }
    }

    companion object {
        private const val PREF_NAME = "epubpro_auth_prefs"
        private const val KEY_USER_DATA = "auth_user_data_json"
        private const val KEY_AUTH_TOKEN = "auth_session_token"
        private const val KEY_REFRESH_TOKEN = "auth_refresh_token"
        private const val KEY_EXPIRES_AT = "auth_token_expires_at"
        private const val KEY_AUTH_CONFIG = "auth_config_json"
        private const val KEY_IS_LOGGED_IN = "auth_is_logged_in"
    }
}
