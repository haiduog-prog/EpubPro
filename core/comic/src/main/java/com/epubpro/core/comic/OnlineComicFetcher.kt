package com.epubpro.core.comic

import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Ngoại lệ ném ra khi trang web yêu cầu xác minh danh tính (Cloudflare, Captcha, DDOS protection)
 * hoặc yêu cầu JavaScript để vượt qua bảo vệ, không thể tải HTML thô bằng HTTP client.
 *
 * @param message Mô tả chi tiết nguyên nhân.
 */
class ComicProtectionException(message: String) : IOException(message)

/**
 * Trình tải và phân tích dữ liệu chương truyện tranh trực tuyến.
 *
 * Tải HTML bằng OkHttp với client độc lập, không mang cookie phiên người dùng ([CookieJar.NO_COOKIES]),
 * sau đó phân tích qua [NetTruyenChapterParser].
 */
@Singleton
open class OnlineComicFetcher @Inject constructor() {

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(CookieJar.NO_COOKIES)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /**
     * Tải và bóc tách thông tin chương truyện tranh từ [chapterUrl].
     *
     * @param chapterUrl URL tuyệt đối của chương cần tải.
     * @return [ComicChapterInfo] chứa danh sách ảnh và liên kết điều hướng.
     * @throws ComicProtectionException Nếu trang nguồn chặn bot hoặc yêu cầu xác minh trình duyệt.
     * @throws ComicChapterParseException Nếu không thể bóc tách ảnh từ HTML.
     * @throws IOException Nếu có lỗi mạng hoặc mã trạng thái HTTP không thành công.
     */
    open suspend fun fetchChapter(chapterUrl: String): ComicChapterInfo = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(chapterUrl)
            .header("User-Agent", DEFAULT_USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
            .header("Accept-Language", "vi,en-US;q=0.9,en;q=0.8")
            .build()

        val response = httpClient.newCall(request).execute()
        val statusCode = response.code
        val bodyString = response.body?.string().orEmpty()

        if (!response.isSuccessful) {
            if (statusCode == 403 || statusCode == 503) {
                if (detectProtectionBlock(bodyString)) {
                    throw ComicProtectionException("Trang web yêu cầu xác minh bảo vệ trình duyệt (mã $statusCode)")
                }
            }
            throw IOException("Không thể tải trang chương (HTTP $statusCode)")
        }

        if (detectProtectionBlock(bodyString)) {
            throw ComicProtectionException("Trang web yêu cầu xác minh danh tính hoặc bật JavaScript")
        }

        NetTruyenChapterParser.parse(bodyString, chapterUrl)
    }

    /**
     * Kiểm tra nhanh dấu hiệu trang bảo vệ chống cào dữ liệu (Cloudflare challenge, DDOS guard, captcha).
     *
     * @param html Nội dung HTML trả về.
     * @return true nếu phát hiện dấu hiệu chặn bảo vệ.
     */
    private fun detectProtectionBlock(html: String): Boolean {
        val lower = html.lowercase()
        return lower.contains("just a moment...") ||
            lower.contains("cloudflare") && lower.contains("ray id") ||
            lower.contains("checking your browser") ||
            lower.contains("ddos-guard") ||
            lower.contains("vui lòng xác minh") ||
            lower.contains("challenge-form")
    }

    companion object {
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
}
