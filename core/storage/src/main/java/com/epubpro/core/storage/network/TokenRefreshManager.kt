package com.epubpro.core.storage.network

import android.util.Log
import com.epubpro.core.storage.AuthPreferencesManager
import com.google.gson.Gson
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Trình quản lý làm mới Token xác thực tự động và an toàn đa luồng cho ứng dụng.
 *
 * Kiểm tra hạn sử dụng của Access Token và tự động gửi request làm mới tới Supabase Auth
 * trước khi gửi request hoặc khi backend phản hồi mã lỗi HTTP 401 Unauthorized.
 */
@Singleton
class TokenRefreshManager @Inject constructor(
    private val authPreferencesManager: AuthPreferencesManager,
    private val gson: Gson
) {
    private val lock = Any()

    // Client HTTP thuần không gắn Interceptor tránh đệ quy vòng lặp khi refresh
    private val rawHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * Lấy token xác thực hợp lệ. Tự động làm mới nếu token đã hết hạn hoặc sắp hết hạn (dưới 60 giây).
     *
     * @return Chuỗi Access Token hợp lệ hoặc null nếu chưa đăng nhập.
     */
    fun getValidAuthToken(): String? {
        synchronized(lock) {
            val token = authPreferencesManager.getAuthToken() ?: return null
            val expiresAt = authPreferencesManager.getExpiresAt()
            val nowSeconds = System.currentTimeMillis() / 1000

            // Nếu không có mốc hết hạn hoặc còn hạn trên 60s thì dùng luôn token hiện tại
            if (expiresAt == 0L || nowSeconds < expiresAt - 60) {
                return token
            }

            Log.d(TAG, "⏳ [AUTH] Token sắp hoặc đã hết hạn (expiresAt=$expiresAt, now=$nowSeconds). Bắt đầu làm mới token...")
            return performRefreshTokenLocked() ?: token
        }
    }

    /**
     * Thực hiện làm mới token khi nhận phản hồi HTTP 401 Unauthorized từ server.
     *
     * @param failedToken Token đã gửi đi và bị server từ chối 401.
     * @return Token mới nếu làm mới thành công, hoặc null nếu thất bại.
     */
    fun refreshOn401(failedToken: String?): String? {
        synchronized(lock) {
            val currentToken = authPreferencesManager.getAuthToken()
            // Nếu một thread khác đã hoàn tất refresh và cập nhật token mới hơn failedToken
            if (!currentToken.isNullOrBlank() && currentToken != failedToken) {
                Log.d(TAG, "🔄 [AUTH] Đã có Token mới từ tiến trình trước, sử dụng lại ngay.")
                return currentToken
            }
            return performRefreshTokenLocked()
        }
    }

    /**
     * Gửi request đồng bộ tới Supabase Auth endpoint để lấy bộ token mới.
     *
     * @return Access token mới nếu thành công, ngược lại trả về null.
     */
    private fun performRefreshTokenLocked(): String? {
        val refreshToken = authPreferencesManager.getRefreshToken()
        if (refreshToken.isNullOrBlank()) {
            Log.w(TAG, "⚠️ [AUTH] Không có Refresh Token để làm mới phiên.")
            return null
        }

        val authConfig = authPreferencesManager.getAuthConfig()
        if (authConfig == null || !authConfig.isSupabaseMode ||
            authConfig.supabaseUrl.isNullOrBlank() || authConfig.supabasePublishableKey.isNullOrBlank()
        ) {
            Log.w(TAG, "⚠️ [AUTH] Cấu hình Supabase Auth không đầy đủ để làm mới token.")
            return null
        }

        return try {
            val supabaseUrl = authConfig.supabaseUrl.trimEnd('/')
            val endpoint = "$supabaseUrl/auth/v1/token?grant_type=refresh_token"
            val requestBodyJson = gson.toJson(mapOf("refresh_token" to refreshToken))
            val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()

            val request = Request.Builder()
                .url(endpoint)
                .addHeader("apikey", authConfig.supabasePublishableKey)
                .addHeader("Content-Type", "application/json")
                .post(requestBodyJson.toRequestBody(mediaType))
                .build()

            rawHttpClient.newCall(request).execute().use { response ->
                val body = response.body
                if (response.isSuccessful && body != null) {
                    val responseBodyStr = body.string()
                    val tokenResponse = gson.fromJson(responseBodyStr, SupabaseTokenResponseDto::class.java)

                    if (tokenResponse.accessToken.isNotBlank()) {
                        val expiresAt = tokenResponse.expiresAt
                            ?: (System.currentTimeMillis() / 1000 + tokenResponse.expiresIn)

                        // Chỉ nhận kết quả nếu phiên vẫn là phiên đã gửi refresh token này; người dùng có thể
                        // đã đăng xuất hoặc đổi tài khoản trong lúc chờ mạng.
                        val stored = authPreferencesManager.replaceTokensIfCurrent(
                            expectedRefreshToken = refreshToken,
                            accessToken = tokenResponse.accessToken,
                            refreshToken = tokenResponse.refreshToken ?: refreshToken,
                            expiresAt = expiresAt
                        )
                        if (!stored) {
                            Log.w(TAG, "⚠️ [AUTH] Bỏ token vừa làm mới vì phiên đã đăng xuất hoặc đổi tài khoản.")
                            return null
                        }
                        Log.i(TAG, "✅ [AUTH] Tự động làm mới Token thành công! (Thời hạn mới: $expiresAt)")
                        return tokenResponse.accessToken
                    }
                } else {
                    Log.e(TAG, "❌ [AUTH] Yêu cầu làm mới Token thất bại: HTTP ${response.code}")
                }
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ [AUTH] Ngoại lệ khi làm mới Token: ${e.message}", e)
            null
        }
    }

    companion object {
        private const val TAG = "API_HTTP"
    }
}
