package com.epubpro.core.storage.worker

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.webkit.CookieManager
import com.epubpro.core.storage.EpubStorageManager
import com.epubpro.core.webnovel.HtmlCharsetDecoder
import com.epubpro.core.webnovel.WebNovelCoverFinder
import com.epubpro.domain.model.Book
import com.epubpro.domain.repository.BookRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

/**
 * Kết quả một lần tìm ảnh bìa cho truyện web.
 */
enum class CoverFetchResult {
    /** Đã lưu bìa mới và gắn vào sách. */
    SAVED,

    /** Sách đã có bìa, hoặc site trả lời được nhưng không có ảnh bìa hợp lệ: không cần thử lại sớm. */
    NOT_AVAILABLE,

    /** Không tải được trang/ảnh (mạng lỗi, bị chặn, HTTP lỗi): nên thử lại ở lần sau. */
    NETWORK_ERROR
}

/**
 * Lỗi nội bộ đánh dấu request mạng thất bại, để phân biệt với trường hợp trang không có bìa.
 */
private class CoverNetworkException : Exception()

/**
 * Tải ảnh bìa chuẩn cho truyện web đã lưu thành EPUB (EPUB tự đóng gói không chứa ảnh).
 *
 * Luồng: tải trang chương nguồn → tìm trang giới thiệu truyện → lấy `og:image`/ảnh bìa ở đó (hoặc ở chính
 * trang chương nếu không có trang giới thiệu) → tải ảnh, kiểm tra là ảnh dọc hợp lệ → thu nhỏ, lưu JPEG vào
 * thư mục covers → ghi `coverPath` nếu sách vẫn chưa có bìa.
 *
 * Dùng chung HTTP client an toàn của [WebNovelDownloadWorker] (chỉ địa chỉ công khai, không gửi token EpubPro).
 */
@Singleton
class WebNovelCoverFetcher @Inject constructor(
    private val storageManager: EpubStorageManager,
    private val bookRepository: BookRepository
) {

    /**
     * Tìm và lưu ảnh bìa cho một cuốn sách truyện web nếu sách chưa có bìa cục bộ.
     *
     * Không ném exception (trừ huỷ coroutine) để không làm hỏng tác vụ tải truyện gọi nó; kết quả cho biết
     * có nên thử lại hay không.
     *
     * @param book Sách cần bìa; `filePath` phải là bản hiện tại để cập nhật đúng bản ghi.
     * @param sourceUrl URL một chương của truyện trên site nguồn (thường là chương đầu).
     * @param userAgent User-Agent của WebView để cookie chống bot còn hiệu lực, null để dùng mặc định.
     * @return [CoverFetchResult.SAVED] nếu đã gắn bìa mới, [CoverFetchResult.NETWORK_ERROR] nếu không tải được,
     *         còn lại [CoverFetchResult.NOT_AVAILABLE].
     */
    suspend fun fetchCoverIfMissing(book: Book, sourceUrl: String, userAgent: String?): CoverFetchResult =
        withContext(Dispatchers.IO) {
            if (hasLocalCover(book)) return@withContext CoverFetchResult.NOT_AVAILABLE
            try {
                val agent = userAgent?.takeIf { it.isNotBlank() } ?: WebNovelDownloadWorker.DEFAULT_USER_AGENT
                val coverUrl = findCoverUrl(sourceUrl, agent) ?: return@withContext CoverFetchResult.NOT_AVAILABLE
                val image = downloadImage(coverUrl.url, coverUrl.referer, agent)
                    ?: return@withContext CoverFetchResult.NOT_AVAILABLE
                val path = storageManager.saveCoverImage(book.id, ByteArrayInputStream(image))
                if (attachCover(book, path)) CoverFetchResult.SAVED else CoverFetchResult.NOT_AVAILABLE
            } catch (error: CancellationException) {
                throw error
            } catch (_: CoverNetworkException) {
                CoverFetchResult.NETWORK_ERROR
            } catch (_: Exception) {
                CoverFetchResult.NOT_AVAILABLE
            }
        }

    /**
     * Gắn ảnh bìa đã lưu vào sách nếu sách vẫn chưa có bìa.
     *
     * Trong lúc tải bìa (vài giây), người dùng có thể nối thêm chương làm `filePath` của sách đổi sang bản EPUB
     * mới; khi đó đọc lại sách và gắn theo `filePath` hiện tại thay vì bỏ cuộc.
     *
     * @param book Sách lúc bắt đầu tải bìa.
     * @param coverPath Đường dẫn file bìa vừa lưu.
     * @return `true` nếu đã gắn bìa vào bản ghi sách.
     */
    private suspend fun attachCover(book: Book, coverPath: String): Boolean {
        if (bookRepository.updateCoverPathIfMissing(book.id, book.filePath, coverPath)) return true
        val current = bookRepository.getBookById(book.id) ?: return false
        if (!current.coverPath.isNullOrBlank() || current.filePath == book.filePath) return false
        return bookRepository.updateCoverPathIfMissing(current.id, current.filePath, coverPath)
    }

    /**
     * URL ảnh bìa kèm trang chứa nó (dùng làm `Referer` vì nhiều CDN chặn hotlink).
     *
     * @property url URL ảnh.
     * @property referer URL trang chứa ảnh.
     */
    private data class CoverUrl(val url: String, val referer: String)

    /**
     * Tìm URL ảnh bìa: ưu tiên trang giới thiệu truyện, sau đó mới tới trang chương.
     *
     * @param chapterUrl URL chương nguồn.
     * @param userAgent User-Agent dùng cho request.
     * @return URL ảnh bìa, hoặc `null` nếu không tìm thấy.
     */
    private fun findCoverUrl(chapterUrl: String, userAgent: String): CoverUrl? {
        val chapterHtml = fetchHtml(chapterUrl, userAgent) ?: return null
        WebNovelCoverFinder.findLandingUrl(chapterHtml, chapterUrl)?.let { landingUrl ->
            fetchHtml(landingUrl, userAgent)
                ?.let { WebNovelCoverFinder.findCoverUrl(it, landingUrl) }
                ?.let { return CoverUrl(it, landingUrl) }
        }
        return WebNovelCoverFinder.findCoverUrl(chapterHtml, chapterUrl)?.let { CoverUrl(it, chapterUrl) }
    }

    /**
     * Tải HTML một trang với giới hạn dung lượng.
     *
     * @param url URL trang.
     * @param userAgent User-Agent dùng cho request.
     * @return HTML, hoặc `null` khi trang quá lớn.
     * @throws CoverNetworkException Khi lỗi kết nối hoặc HTTP 429/5xx.
     */
    private fun fetchHtml(url: String, userAgent: String): String? {
        val request = requestBuilder(url, userAgent)
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Accept-Language", "vi,en;q=0.8,zh;q=0.6")
            .build()
        return execute(request, MAX_PAGE_BYTES) { bytes, contentType ->
            HtmlCharsetDecoder.decode(bytes, contentType)
        }
    }

    /**
     * Tải ảnh bìa, kiểm tra là ảnh dọc đủ lớn rồi thu nhỏ và nén JPEG.
     *
     * Ảnh ngang hoặc quá nhỏ thường là logo/banner của site nên bị loại.
     *
     * @param url URL ảnh.
     * @param referer Trang chứa ảnh.
     * @param userAgent User-Agent dùng cho request.
     * @return Dữ liệu JPEG đã xử lý, hoặc `null` nếu ảnh không hợp lệ.
     * @throws CoverNetworkException Khi lỗi kết nối hoặc HTTP 429/5xx.
     */
    private fun downloadImage(url: String, referer: String, userAgent: String): ByteArray? {
        val request = requestBuilder(url, userAgent)
            .header("Accept", "image/avif,image/webp,image/*,*/*;q=0.8")
            .header("Referer", referer)
            .build()
        val bytes = execute(request, MAX_IMAGE_BYTES) { bytes, contentType ->
            bytes.takeIf { contentType == null || contentType.startsWith("image/", ignoreCase = true) }
        } ?: return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width < MIN_COVER_SIDE_PX || height < MIN_COVER_SIDE_PX || height < width) return null

        var sampleSize = 1
        while (max(width, height) / (sampleSize * 2) >= MAX_COVER_LONG_SIDE_PX) sampleSize *= 2
        val bitmap = BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sampleSize }
        ) ?: return null
        return try {
            ByteArrayOutputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Tạo request với User-Agent và cookie WebView của site (để qua lớp chống bot đã vượt trong trình duyệt).
     *
     * @param url URL cần tải.
     * @param userAgent User-Agent dùng cho request.
     * @return Builder đã gắn header chung.
     */
    private fun requestBuilder(url: String, userAgent: String): Request.Builder {
        val builder = Request.Builder().url(url).header("User-Agent", userAgent)
        runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { builder.header("Cookie", it) }
        return builder
    }

    /**
     * Thực thi request và đọc body có giới hạn dung lượng.
     *
     * @param request Request cần chạy.
     * @param maxBytes Dung lượng body tối đa.
     * @param transform Chuyển body (bytes, Content-Type) thành kết quả.
     * @return Kết quả của [transform], hoặc `null` khi HTTP 4xx, body rỗng hoặc vượt giới hạn.
     * @throws CoverNetworkException Khi lỗi kết nối hoặc HTTP 429/5xx (lỗi tạm thời).
     */
    private fun <T> execute(request: Request, maxBytes: Long, transform: (ByteArray, String?) -> T?): T? = try {
        WebNovelDownloadWorker.httpClient.newCall(request).execute().use { response ->
            // 429/5xx là lỗi tạm thời nên thử lại; 4xx khác (404, 403...) coi như trang không có bìa.
            if (response.code == 429 || response.code >= 500) throw CoverNetworkException()
            if (!response.isSuccessful) return null
            val body = response.body
            if (body == null || body.contentLength() > maxBytes) return null
            val bytes = body.source().use { source ->
                source.request(maxBytes + 1)
                if (source.buffer.size > maxBytes) return null
                source.buffer.readByteArray()
            }
            transform(bytes, response.header("Content-Type"))
        }
    } catch (_: IOException) {
        throw CoverNetworkException()
    }

    /**
     * Kiểm tra sách đã có ảnh bìa là file cục bộ hay chưa (URL từ nguồn online không tính).
     *
     * @param book Sách cần kiểm tra.
     * @return `true` nếu `coverPath` trỏ tới file tồn tại.
     */
    private fun hasLocalCover(book: Book): Boolean =
        book.coverPath?.takeIf { it.isNotBlank() }?.let { File(it).isFile } == true

    private companion object {
        const val MAX_PAGE_BYTES = 5L * 1024L * 1024L
        const val MAX_IMAGE_BYTES = 8L * 1024L * 1024L
        const val MIN_COVER_SIDE_PX = 80
        const val MAX_COVER_LONG_SIDE_PX = 900
        const val JPEG_QUALITY = 88
    }
}
