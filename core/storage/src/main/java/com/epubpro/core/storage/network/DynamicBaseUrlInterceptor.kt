package com.epubpro.core.storage.network

import com.epubpro.core.storage.ServerPreferencesManager
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OkHttp Interceptor hỗ trợ ghi đè và tái cấu trúc URL của request Retrofit theo địa chỉ Base URL động lúc runtime.
 *
 * Xử lý bóc tách linh hoạt các path segments (bỏ qua tiền tố `/api/v1` mặc định của Retrofit) để ghép chính xác
 * vào Base URL mới mà không gây trùng lặp đường dẫn.
 */
@Singleton
class DynamicBaseUrlInterceptor @Inject constructor(
    private val serverPreferencesManager: ServerPreferencesManager
) : Interceptor {

    /**
     * Chặn và biến đổi request để cập nhật Host, Port, Scheme và Base Path theo giá trị trong [ServerPreferencesManager].
     *
     * @param chain Chuỗi interceptor của OkHttp.
     * @return [Response] Phản hồi mạng sau khi đã thực thi request với URL mới.
     */
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val originalUrl = originalRequest.url
        val path = originalUrl.encodedPath

        // 1. Nếu request tới Supabase Auth trực tiếp
        if (isSupabaseHost(originalUrl.host)) {
            val cleanRequest = originalRequest.newBuilder()
                .removeHeader(SKIP_DYNAMIC_BASE_URL_HEADER)
                .build()
            return chain.proceed(cleanRequest)
        }

        // 2. Nếu request tới endpoint cấu hình xác thực Backend (/api/auth/...) hoặc chỉ định bỏ qua
        if (path.startsWith("/api/auth/") || path == "/api/auth/config" || originalRequest.header(SKIP_DYNAMIC_BASE_URL_HEADER) != null) {
            val currentBaseUrlString = serverPreferencesManager.getBaseUrl()
            val newBaseUrl = currentBaseUrlString.toHttpUrlOrNull()
            val finalUrl = if (newBaseUrl != null) {
                originalUrl.newBuilder()
                    .scheme(newBaseUrl.scheme)
                    .host(newBaseUrl.host)
                    .port(newBaseUrl.port)
                    .build()
            } else {
                originalUrl
            }

            val cleanRequest = originalRequest.newBuilder()
                .url(finalUrl)
                .removeHeader(SKIP_DYNAMIC_BASE_URL_HEADER)
                .build()
            return chain.proceed(cleanRequest)
        }

        val overrideBaseUrl = originalRequest.header(BASE_URL_OVERRIDE_HEADER)
        val request = if (overrideBaseUrl != null) {
            originalRequest.newBuilder()
                .removeHeader(BASE_URL_OVERRIDE_HEADER)
                .header(BASE_URL_OVERRIDDEN_MARKER_HEADER, "true")
                .build()
        } else {
            originalRequest
        }
        val currentBaseUrlString = overrideBaseUrl ?: serverPreferencesManager.getBaseUrl()
        val newBaseUrl = currentBaseUrlString.toHttpUrlOrNull()
            ?: if (overrideBaseUrl != null) {
                throw IllegalArgumentException("Invalid base URL override: $overrideBaseUrl")
            } else {
                return chain.proceed(request)
            }

        val requestUrl = request.url
        
        // Bóc tách relative path segments từ endpoint gốc (bỏ qua /api/v1 ở đầu nếu có)
        val relativePathSegments = requestUrl.pathSegments
            .dropWhile { it.equals("api", ignoreCase = true) || it.equals("v1", ignoreCase = true) }

        val newUrlBuilder = newBaseUrl.newBuilder()
        val baseSegments = newBaseUrl.pathSegments.filter { it.isNotEmpty() }

        // Xóa sạch các segments cũ và xây dựng lại path chuẩn
        for (i in 0 until newBaseUrl.pathSegments.size) {
            newUrlBuilder.removePathSegment(0)
        }
        for (seg in baseSegments) {
            newUrlBuilder.addPathSegment(seg)
        }
        for (seg in relativePathSegments) {
            newUrlBuilder.addPathSegment(seg)
        }
        
        newUrlBuilder.encodedQuery(requestUrl.encodedQuery)

        val newRequest = request.newBuilder()
            .url(newUrlBuilder.build())
            .build()

        return chain.proceed(newRequest)
    }

    companion object {
        /** Header nội bộ dùng để ghi đè Base URL cho riêng một request và được xóa trước khi gửi lên server. */
        const val BASE_URL_OVERRIDE_HEADER = "X-EpubPro-Base-Url-Override"

        /** Header nội bộ đánh dấu request đã được ghi đè Base URL (ví dụ testServerConnection) để không tự đính kèm Bearer token. */
        const val BASE_URL_OVERRIDDEN_MARKER_HEADER = "X-EpubPro-Base-Url-Overridden"

        /** Header nội bộ chỉ thị bỏ qua việc thay đổi URL động (dùng cho external APIs như Supabase). */
        const val SKIP_DYNAMIC_BASE_URL_HEADER = "X-Skip-Dynamic-Base-Url"

        /**
         * Kiểm tra tên miền thuộc Supabase (`supabase.co` hoặc `*.supabase.co`).
         *
         * @param host Tên miền cần kiểm tra.
         * @return `true` nếu là domain chính thức của Supabase.
         */
        fun isSupabaseHost(host: String): Boolean {
            return host.equals("supabase.co", ignoreCase = true) ||
                host.endsWith(".supabase.co", ignoreCase = true)
        }
    }
}
