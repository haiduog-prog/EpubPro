package com.epubpro.core.comic

import android.util.LruCache
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Trình tải và giải mã ảnh trang truyện tranh trực tuyến có kiểm soát bộ nhớ.
 *
 * Tải ảnh qua OkHttp với client riêng biệt không cookie phiên ([CookieJar.NO_COOKIES]),
 * giới hạn giải mã đồng thời qua [Semaphore] và lưu bộ nhớ đệm [LruCache] để cuộn mượt mà.
 */
@Singleton
open class OnlineComicImageLoader @Inject constructor() {

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(CookieJar.NO_COOKIES)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val decodePermits = Semaphore(MAX_PARALLEL_DECODES)

    private val pageCache = object : LruCache<String, ComicPageImage>(computeCacheBytes()) {
        override fun sizeOf(key: String, value: ComicPageImage): Int = value.byteCount
    }

    /**
     * Tải và giải mã một ảnh trang truyện trực tuyến thành [ComicPageImage].
     *
     * @param imageUrl URL tuyệt đối của ảnh.
     * @param targetWidth Chiều rộng hiển thị mong muốn (px).
     * @param refererUrl URL trang chương dùng làm header Referer để tránh bị chặn hotlinking.
     * @return [ComicPageImage] chứa các dải bitmap đã giải mã.
     * @throws IOException Nếu tải ảnh thất bại qua mạng.
     * @throws ComicArchiveException Nếu định dạng ảnh hỏng hoặc không giải mã được.
     */
    open suspend fun loadPage(
        imageUrl: String,
        targetWidth: Int,
        refererUrl: String? = null
    ): ComicPageImage {
        val cacheKey = "$imageUrl:$targetWidth"
        pageCache.get(cacheKey)?.let { return it }

        return decodePermits.withPermit {
            pageCache.get(cacheKey) ?: withContext(Dispatchers.IO) {
                val bytes = fetchImageBytes(imageUrl, refererUrl)
                ComicPageDecoder.decode(bytes, targetWidth)
            }.also { pageCache.put(cacheKey, it) }
        }
    }

    /**
     * Xóa toàn bộ bộ nhớ đệm ảnh trong RAM khi đóng trình đọc hoặc giải phóng tài nguyên.
     */
    open fun clearCache() {
        pageCache.evictAll()
    }

    /**
     * Tải mảng byte ảnh từ URL.
     *
     * @param imageUrl URL ảnh.
     * @param refererUrl URL trang tham chiếu (chương truyện).
     * @return Mảng byte của ảnh.
     * @throws IOException Nếu mã phản hồi HTTP không thành công hoặc lỗi mạng.
     */
    private fun fetchImageBytes(imageUrl: String, refererUrl: String?): ByteArray {
        val requestBuilder = Request.Builder()
            .url(imageUrl)
            .header("User-Agent", DEFAULT_USER_AGENT)
            .header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")

        val effectiveReferer = refererUrl ?: runCatching {
            val uri = URI(imageUrl)
            "${uri.scheme}://${uri.host}/"
        }.getOrNull()

        if (!effectiveReferer.isNullOrBlank()) {
            requestBuilder.header("Referer", effectiveReferer)
        }

        return httpClient.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Không thể tải ảnh trang truyện (mã HTTP ${response.code})")
            }
            val body = response.body ?: throw IOException("Nội dung ảnh trang truyện rỗng")
            if (body.contentLength() > MAX_IMAGE_BYTES) {
                throw IOException("Ảnh trang truyện vượt quá giới hạn dung lượng")
            }
            body.byteStream().use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(BUFFER_SIZE)
                var totalBytes = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    totalBytes += count
                    if (totalBytes > MAX_IMAGE_BYTES) {
                        throw IOException("Ảnh trang truyện vượt quá giới hạn dung lượng")
                    }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        }
    }

    companion object {
        private const val MAX_PARALLEL_DECODES = 2
        private const val MAX_IMAGE_BYTES = 64 * 1024 * 1024
        private const val BUFFER_SIZE = 8 * 1024
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        /**
         * Tính toán dung lượng bộ nhớ đệm ảnh trong RAM (1/6 dung lượng heap tối đa của ứng dụng).
         *
         * @return Số byte tối đa cấp cho [LruCache].
         */
        private fun computeCacheBytes(): Int =
            (Runtime.getRuntime().maxMemory() / 6).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}
