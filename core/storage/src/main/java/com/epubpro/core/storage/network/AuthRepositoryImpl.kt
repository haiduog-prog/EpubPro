package com.epubpro.core.storage.network

import com.epubpro.core.storage.AuthPreferencesManager
import com.epubpro.core.storage.ServerPreferencesManager
import com.epubpro.domain.model.AuthProvider
import com.epubpro.domain.model.AuthState
import com.epubpro.domain.model.User
import com.epubpro.domain.repository.AuthRepository
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Triển khai [AuthRepository] tích hợp luồng xác thực Backend Config và Supabase Auth.
 *
 * Thực hiện 5 bước chuẩn hóa:
 * 1. Lấy cấu hình xác thực từ Backend API (`GET /api/auth/config`).
 * 2. Đăng nhập trực tiếp tới Supabase Auth (`POST /auth/v1/token?grant_type=password`).
 * 3. Làm mới Access Token khi hết hạn (`POST /auth/v1/token?grant_type=refresh_token`).
 * 4. Đăng xuất và thu hồi phiên xác thực (`POST /auth/v1/logout`).
 * 5. Lưu trữ token để gắn Bearer token vào các request gọi tới Backend.
 */
@Singleton
class AuthRepositoryImpl @Inject constructor(
    private val authPreferencesManager: AuthPreferencesManager,
    private val serverPreferencesManager: ServerPreferencesManager,
    private val backendAuthApiService: BackendAuthApiService,
    private val supabaseAuthApiService: SupabaseAuthApiService,
    private val gson: Gson
) : AuthRepository {

    override val authState: Flow<AuthState> = authPreferencesManager.authStateFlow

    private val emailPattern = "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$".toRegex()

    override suspend fun getCurrentUser(): User? = withContext(Dispatchers.IO) {
        authPreferencesManager.getSavedUser()
    }

    override suspend fun login(email: String, password: String): Result<User> = withContext(Dispatchers.IO) {
        try {
            val trimmedEmail = email.trim()
            val trimmedPassword = password.trim()

            if (trimmedEmail.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("Vui lòng nhập địa chỉ email."))
            }
            if (!isValidEmail(trimmedEmail)) {
                return@withContext Result.failure(IllegalArgumentException("Định dạng email không hợp lệ."))
            }
            if (trimmedPassword.length < 6) {
                return@withContext Result.failure(IllegalArgumentException("Mật khẩu phải có ít nhất 6 ký tự."))
            }

            // Bước 1: Lấy cấu hình Auth từ Backend API
            val authConfig = fetchAuthConfig()
                ?: return@withContext Result.failure(
                    IllegalStateException("Không thể kết nối đến máy chủ xác thực (${serverPreferencesManager.getBaseUrl()}). Vui lòng kiểm tra lại kết nối mạng.")
                )

            // Bước 2: Thực hiện đăng nhập theo cấu hình từ Backend
            if (authConfig.isSupabaseMode &&
                !authConfig.supabaseUrl.isNullOrBlank() && !authConfig.supabasePublishableKey.isNullOrBlank()
            ) {
                val supabaseUrl = authConfig.supabaseUrl.trimEnd('/')
                val tokenEndpoint = "$supabaseUrl/auth/v1/token?grant_type=password"
                logDebug("API_HTTP", "🚀 [AUTH] Đang gửi đăng nhập tới Supabase: $tokenEndpoint (email: $trimmedEmail)")

                try {
                    val tokenResponse = supabaseAuthApiService.loginWithPassword(
                        url = tokenEndpoint,
                        apiKey = authConfig.supabasePublishableKey,
                        body = SupabaseLoginRequestDto(
                            email = trimmedEmail,
                            password = trimmedPassword
                        )
                    )

                    val supabaseUser = tokenResponse.user
                    val existingUser = authPreferencesManager.getSavedUser()
                    val displayName = extractNameFromEmail(supabaseUser?.email ?: trimmedEmail)

                    val user = User(
                        id = supabaseUser?.id ?: UUID.randomUUID().toString(),
                        email = supabaseUser?.email ?: trimmedEmail,
                        displayName = displayName,
                        avatarUrl = existingUser?.avatarUrl,
                        token = tokenResponse.accessToken,
                        refreshToken = tokenResponse.refreshToken,
                        provider = AuthProvider.EMAIL,
                        membershipTier = "Thành viên EpubPro",
                        readingStreakDays = existingUser?.readingStreakDays ?: 1,
                        totalReadBooks = existingUser?.totalReadBooks ?: 0,
                        totalReadHours = existingUser?.totalReadHours ?: 0.0,
                        joinedDate = existingUser?.joinedDate ?: getCurrentFormattedDate()
                    )

                    authPreferencesManager.saveUser(user)
                    authPreferencesManager.saveTokens(
                        accessToken = tokenResponse.accessToken,
                        refreshToken = tokenResponse.refreshToken,
                        expiresAt = tokenResponse.expiresAt ?: (System.currentTimeMillis() / 1000 + tokenResponse.expiresIn)
                    )
                    logDebug("API_HTTP", "✅ [AUTH] Đăng nhập Supabase thành công! User=${user.email}, Token length=${tokenResponse.accessToken.length}")

                    Result.success(user)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: HttpException) {
                    val errorMsg = parseSupabaseError(e)
                    logError("API_HTTP", "❌ [AUTH] Supabase từ chối đăng nhập (HTTP ${e.code()}): $errorMsg", e)
                    Result.failure(IllegalArgumentException(errorMsg))
                } catch (e: Exception) {
                    val errorMsg = "Lỗi kết nối tới Supabase Auth: ${e.message ?: "Không thể kết nối"}"
                    logError("API_HTTP", "❌ [AUTH] $errorMsg", e)
                    Result.failure(IllegalStateException(errorMsg))
                }
            } else if (authConfig.isLocalMode && !authConfig.authRequired) {
                // Chỉ khi Backend trả về rõ ràng: { "mode": "local", "auth_required": false }
                val existingUser = authPreferencesManager.getSavedUser()
                val displayName = if (existingUser != null && existingUser.email.equals(trimmedEmail, ignoreCase = true)) {
                    existingUser.displayName
                } else {
                    extractNameFromEmail(trimmedEmail)
                }

                val localToken = "local_jwt_${UUID.randomUUID()}"
                val user = User(
                    id = existingUser?.id ?: UUID.randomUUID().toString(),
                    email = trimmedEmail,
                    displayName = displayName,
                    avatarUrl = existingUser?.avatarUrl,
                    token = localToken,
                    refreshToken = "local_refresh_${UUID.randomUUID()}",
                    provider = AuthProvider.EMAIL,
                    membershipTier = "Thành viên Local Dev",
                    readingStreakDays = existingUser?.readingStreakDays ?: 1,
                    totalReadBooks = existingUser?.totalReadBooks ?: 0,
                    totalReadHours = existingUser?.totalReadHours ?: 0.0,
                    joinedDate = existingUser?.joinedDate ?: getCurrentFormattedDate()
                )

                authPreferencesManager.saveUser(user)
                Result.success(user)
            } else {
                val errorMsg = "Cấu hình xác thực từ máy chủ không hợp lệ (mode=${authConfig.mode}). Vui lòng kiểm tra lại cấu hình."
                logError("API_HTTP", "❌ [AUTH] $errorMsg")
                Result.failure(IllegalStateException(errorMsg))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logError("API_HTTP", "❌ [AUTH] Lỗi đăng nhập: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun register(
        email: String,
        password: String,
        displayName: String
    ): Result<User> = withContext(Dispatchers.IO) {
        try {
            val trimmedEmail = email.trim()
            val trimmedPassword = password.trim()
            val trimmedName = displayName.trim()

            if (trimmedName.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("Vui lòng nhập tên hiển thị."))
            }
            if (trimmedEmail.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("Vui lòng nhập địa chỉ email."))
            }
            if (!isValidEmail(trimmedEmail)) {
                return@withContext Result.failure(IllegalArgumentException("Định dạng email không hợp lệ."))
            }
            if (trimmedPassword.length < 6) {
                return@withContext Result.failure(IllegalArgumentException("Mật khẩu phải có ít nhất 6 ký tự."))
            }

            val authConfig = fetchAuthConfig()
            if (authConfig != null && authConfig.isSupabaseMode &&
                !authConfig.supabaseUrl.isNullOrBlank() && !authConfig.supabasePublishableKey.isNullOrBlank()
            ) {
                val supabaseUrl = authConfig.supabaseUrl.trimEnd('/')
                val signupEndpoint = "$supabaseUrl/auth/v1/signup"

                try {
                    val signupResponse = supabaseAuthApiService.signUp(
                        url = signupEndpoint,
                        apiKey = authConfig.supabasePublishableKey,
                        body = SupabaseSignupRequestDto(
                            email = trimmedEmail,
                            password = trimmedPassword,
                            data = mapOf("display_name" to trimmedName)
                        )
                    )

                    val accessToken = signupResponse.accessToken ?: "jwt_${UUID.randomUUID()}"
                    val refreshToken = signupResponse.refreshToken ?: "refresh_${UUID.randomUUID()}"

                    val user = User(
                        id = signupResponse.id ?: signupResponse.user?.id ?: UUID.randomUUID().toString(),
                        email = trimmedEmail,
                        displayName = trimmedName,
                        avatarUrl = null,
                        token = accessToken,
                        refreshToken = refreshToken,
                        provider = AuthProvider.EMAIL,
                        membershipTier = "Thành viên EpubPro",
                        readingStreakDays = 1,
                        totalReadBooks = 0,
                        totalReadHours = 0.0,
                        joinedDate = getCurrentFormattedDate()
                    )

                    authPreferencesManager.saveUser(user)
                    authPreferencesManager.saveTokens(
                        accessToken = accessToken,
                        refreshToken = refreshToken,
                        expiresAt = signupResponse.expiresAt ?: (System.currentTimeMillis() / 1000 + signupResponse.expiresIn)
                    )

                    Result.success(user)
                } catch (e: HttpException) {
                    val errorMsg = parseSupabaseError(e)
                    Result.failure(IllegalArgumentException(errorMsg))
                }
            } else if (authConfig != null && (authConfig.isLocalMode || !authConfig.authRequired)) {
                val user = User(
                    id = UUID.randomUUID().toString(),
                    email = trimmedEmail,
                    displayName = trimmedName,
                    avatarUrl = null,
                    token = "jwt_${UUID.randomUUID()}",
                    refreshToken = "refresh_${UUID.randomUUID()}",
                    provider = AuthProvider.EMAIL,
                    membershipTier = "Thành viên Local Dev",
                    readingStreakDays = 1,
                    totalReadBooks = 0,
                    totalReadHours = 0.0,
                    joinedDate = getCurrentFormattedDate()
                )

                authPreferencesManager.saveUser(user)
                Result.success(user)
            } else {
                val errorMsg = "Không thể lấy cấu hình xác thực từ máy chủ. Vui lòng kiểm tra lại kết nối mạng."
                Result.failure(IllegalStateException(errorMsg))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun refreshToken(): Result<User> = withContext(Dispatchers.IO) {
        try {
            val currentRefreshToken = authPreferencesManager.getRefreshToken()
                ?: return@withContext Result.failure(IllegalStateException("Không tìm thấy Refresh Token."))

            val authConfig = fetchAuthConfig()
            if (authConfig != null && authConfig.isSupabaseMode &&
                !authConfig.supabaseUrl.isNullOrBlank() && !authConfig.supabasePublishableKey.isNullOrBlank()
            ) {
                val supabaseUrl = authConfig.supabaseUrl.trimEnd('/')
                val tokenEndpoint = "$supabaseUrl/auth/v1/token?grant_type=refresh_token"

                val tokenResponse = supabaseAuthApiService.refreshToken(
                    url = tokenEndpoint,
                    apiKey = authConfig.supabasePublishableKey,
                    body = SupabaseRefreshTokenRequestDto(refreshToken = currentRefreshToken)
                )

                authPreferencesManager.saveTokens(
                    accessToken = tokenResponse.accessToken,
                    refreshToken = tokenResponse.refreshToken,
                    expiresAt = tokenResponse.expiresAt ?: (System.currentTimeMillis() / 1000 + tokenResponse.expiresIn)
                )

                val updatedUser = authPreferencesManager.getSavedUser()
                    ?: return@withContext Result.failure(IllegalStateException("Không tìm thấy thông tin người dùng."))
                Result.success(updatedUser)
            } else {
                val currentUser = authPreferencesManager.getSavedUser()
                    ?: return@withContext Result.failure(IllegalStateException("Chưa đăng nhập."))
                Result.success(currentUser)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun logout(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val accessToken = authPreferencesManager.getAuthToken()
            val authConfig = authPreferencesManager.getAuthConfig()

            // Bước 4: Gọi Supabase logout nếu có token
            if (!accessToken.isNullOrBlank() && authConfig != null && authConfig.isSupabaseMode &&
                !authConfig.supabaseUrl.isNullOrBlank() && !authConfig.supabasePublishableKey.isNullOrBlank()
            ) {
                try {
                    val supabaseUrl = authConfig.supabaseUrl.trimEnd('/')
                    val logoutEndpoint = "$supabaseUrl/auth/v1/logout"
                    supabaseAuthApiService.logout(
                        url = logoutEndpoint,
                        apiKey = authConfig.supabasePublishableKey,
                        authorization = "Bearer $accessToken"
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Bỏ qua lỗi mạng khi logout để luôn xóa phiên cục bộ
                }
            }

            authPreferencesManager.clearSession()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            authPreferencesManager.clearSession()
            Result.failure(e)
        }
    }

    override suspend fun loginWithGoogle(
        idToken: String?,
        email: String?,
        displayName: String?
    ): Result<User> = withContext(Dispatchers.IO) {
        try {
            val googleEmail = email?.trim()?.takeIf { it.isNotBlank() } ?: "google_user@gmail.com"
            val googleName = displayName?.trim()?.takeIf { it.isNotBlank() } ?: "Google Reader"

            val user = User(
                id = UUID.randomUUID().toString(),
                email = googleEmail,
                displayName = googleName,
                avatarUrl = null,
                token = "google_jwt_${UUID.randomUUID()}",
                refreshToken = "google_refresh_${UUID.randomUUID()}",
                provider = AuthProvider.GOOGLE,
                membershipTier = "Thành viên EpubPro",
                readingStreakDays = 1,
                totalReadBooks = 0,
                totalReadHours = 0.0,
                joinedDate = getCurrentFormattedDate()
            )

            authPreferencesManager.saveUser(user)
            Result.success(user)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun loginAsGuest(): Result<User> = withContext(Dispatchers.IO) {
        try {
            val user = User(
                id = "guest_${UUID.randomUUID().toString().take(8)}",
                email = "guest@epubpro.app",
                displayName = "Khách trải nghiệm",
                avatarUrl = null,
                token = "guest_token_${UUID.randomUUID()}",
                refreshToken = null,
                provider = AuthProvider.GUEST,
                membershipTier = "Khách",
                readingStreakDays = 1,
                totalReadBooks = 0,
                totalReadHours = 0.0,
                joinedDate = getCurrentFormattedDate()
            )

            authPreferencesManager.saveUser(user)
            Result.success(user)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun updateProfile(
        displayName: String,
        avatarUrl: String?
    ): Result<User> = withContext(Dispatchers.IO) {
        try {
            val updated = authPreferencesManager.updateUser { current ->
                current.copy(
                    displayName = displayName.trim().ifBlank { current.displayName },
                    avatarUrl = avatarUrl ?: current.avatarUrl
                )
            }
            if (updated != null) {
                Result.success(updated)
            } else {
                Result.failure(IllegalStateException("Chưa đăng nhập tài khoản."))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun sendPasswordResetEmail(email: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val trimmedEmail = email.trim()
            if (!isValidEmail(trimmedEmail)) {
                return@withContext Result.failure(IllegalArgumentException("Định dạng email không hợp lệ."))
            }

            val authConfig = fetchAuthConfig()
            if (authConfig != null && authConfig.isSupabaseMode &&
                !authConfig.supabaseUrl.isNullOrBlank() && !authConfig.supabasePublishableKey.isNullOrBlank()
            ) {
                val supabaseUrl = authConfig.supabaseUrl.trimEnd('/')
                val recoverEndpoint = "$supabaseUrl/auth/v1/recover"
                supabaseAuthApiService.recoverPassword(
                    url = recoverEndpoint,
                    apiKey = authConfig.supabasePublishableKey,
                    body = SupabaseRecoverRequestDto(email = trimmedEmail)
                )
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Lấy cấu hình xác thực từ Backend API (`GET {{BASE_URL}}/api/auth/config`), có lưu đệm trong AuthPreferencesManager.
     */
    private suspend fun fetchAuthConfig(): AuthConfigDto? {
        val baseUrl = serverPreferencesManager.getBaseUrl()
        val rootHost = if (baseUrl.contains("/api/")) {
            baseUrl.substringBefore("/api/")
        } else {
            baseUrl.trimEnd('/')
        }
        val configUrl = "$rootHost/api/auth/config"

        return try {
            logDebug("API_HTTP", "🔍 [AUTH] Đang lấy Auth Config từ: $configUrl")
            val config = backendAuthApiService.getAuthConfigFromUrl(configUrl)
            logDebug("API_HTTP", "✅ [AUTH] Lấy Auth Config thành công: mode=${config.mode}, supabaseUrl=${config.supabaseUrl}")
            authPreferencesManager.saveAuthConfig(config)
            config
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logError("API_HTTP", "❌ [AUTH] Lấy Auth Config từ $configUrl thất bại: ${e.message}", e)
            authPreferencesManager.getAuthConfig()
        }
    }

    /**
     * Trích xuất thông điệp lỗi trả về từ Supabase HTTP response body.
     */
    private fun parseSupabaseError(e: HttpException): String {
        return try {
            val errorBody = e.response()?.errorBody()?.string()
            if (!errorBody.isNullOrBlank()) {
                val errorDto = gson.fromJson(errorBody, SupabaseErrorDto::class.java)
                val msg = errorDto.getDisplayMessage()
                if (msg.contains("Invalid login credentials", ignoreCase = true)) {
                    "Email hoặc mật khẩu không chính xác."
                } else if (msg.contains("Email not confirmed", ignoreCase = true)) {
                    "Email chưa được xác thực tài khoản."
                } else {
                    msg
                }
            } else {
                "Lỗi xác thực (Mã lỗi ${e.code()})."
            }
        } catch (_: Exception) {
            "Đăng nhập thất bại. Vui lòng kiểm tra lại thông tin."
        }
    }

    private fun logDebug(tag: String, msg: String) {
        try {
            android.util.Log.d(tag, msg)
        } catch (_: Throwable) {
            println("[$tag] $msg")
        }
    }

    private fun logWarn(tag: String, msg: String) {
        try {
            android.util.Log.w(tag, msg)
        } catch (_: Throwable) {
            println("[$tag] $msg")
        }
    }

    private fun logError(tag: String, msg: String, tr: Throwable? = null) {
        try {
            android.util.Log.e(tag, msg, tr)
        } catch (_: Throwable) {
            System.err.println("[$tag] $msg: ${tr?.message}")
        }
    }

    private fun isValidEmail(email: String): Boolean {
        return emailPattern.matches(email)
    }

    private fun extractNameFromEmail(email: String): String {
        val prefix = email.substringBefore("@")
        return prefix.split(".", "_", "-")
            .filter { it.isNotBlank() }
            .joinToString(" ") { part ->
                part.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
            }.ifBlank { "Bạn đọc EpubPro" }
    }

    private fun getCurrentFormattedDate(): String {
        return SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date())
    }
}
