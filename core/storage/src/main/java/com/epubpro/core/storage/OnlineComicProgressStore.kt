package com.epubpro.core.storage

import android.content.Context
import android.content.SharedPreferences
import com.epubpro.domain.model.ComicReadingMode
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.URI
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thông tin tiến độ đọc truyện tranh trực tuyến.
 *
 * @property chapterUrl URL tuyệt đối của chương đang đọc gần nhất.
 * @property pageIndex Chỉ số trang đang đọc (0-based).
 * @property readingMode Chế độ đọc người dùng đã chọn cho truyện này.
 * @property lastRead Thời điểm đọc gần nhất (epoch milliseconds).
 */
data class OnlineComicProgress(
    val chapterUrl: String,
    val pageIndex: Int,
    val readingMode: ComicReadingMode?,
    val lastRead: Long
)

/**
 * Kho lưu trữ tiến độ đọc truyện tranh trực tuyến trên SharedPreferences riêng biệt.
 *
 * Tách biệt hoàn toàn với tiến độ CBZ và tiến độ sách EPUB, phân biệt theo nguồn (host)
 * và đường dẫn truyện (bỏ segment chương).
 */
@Singleton
open class OnlineComicProgressStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("online_comic_progress", Context.MODE_PRIVATE)

    /**
     * Tạo khóa định danh duy nhất cho bộ truyện dựa trên host và đường dẫn cơ sở của truyện.
     *
     * @param chapterUrl URL của bất kỳ chương nào trong truyện.
     * @return Chuỗi mã hóa hash đại diện cho bộ truyện, hoặc URL gốc nếu không phân tích được.
     */
    open fun computeComicKey(chapterUrl: String): String {
        val uri = runCatching { URI(chapterUrl) }.getOrNull() ?: return sha256(chapterUrl)
        val host = uri.host.orEmpty().lowercase()
        val path = uri.path.orEmpty().trimEnd('/')
        // Bỏ segment cuối cùng (thường là tên chương / chuong-x) để lấy đường dẫn truyện
        val comicBasePath = if (path.contains('/')) path.substringBeforeLast('/') else path
        val rawKey = "$host:$comicBasePath"
        return sha256(rawKey)
    }

    /**
     * Lưu tiến độ đọc của một chương truyện tranh trực tuyến.
     *
     * @param chapterUrl URL của chương hiện tại.
     * @param pageIndex Vị trí trang hiện tại (0-based).
     * @param mode Chế độ đọc hiện tại.
     */
    open fun saveProgress(chapterUrl: String, pageIndex: Int, mode: ComicReadingMode? = null) {
        val key = computeComicKey(chapterUrl)
        val editor = prefs.edit()
            .putString("${key}_chapter", chapterUrl)
            .putInt("${key}_page", pageIndex.coerceAtLeast(0))
            .putLong("${key}_time", System.currentTimeMillis())

        if (mode != null) {
            editor.putString("${key}_mode", mode.name)
        }
        editor.apply()
    }

    /**
     * Lấy tiến độ đọc đã lưu của bộ truyện chứa [chapterUrl].
     *
     * @param chapterUrl URL của chương cần kiểm tra.
     * @return [OnlineComicProgress] hoặc null nếu chưa có tiến độ.
     */
    open fun getProgress(chapterUrl: String): OnlineComicProgress? {
        val key = computeComicKey(chapterUrl)
        return getProgressByComicKey(key)
    }

    /**
     * Lấy tiến độ đọc đã lưu dựa trên khóa định danh bộ truyện [comicKey].
     *
     * @param comicKey Mã hash đại diện bộ truyện.
     * @return [OnlineComicProgress] hoặc null nếu chưa có tiến độ.
     */
    open fun getProgressByComicKey(comicKey: String): OnlineComicProgress? {
        val savedChapter = prefs.getString("${comicKey}_chapter", null) ?: return null
        val page = prefs.getInt("${comicKey}_page", 0)
        val time = prefs.getLong("${comicKey}_time", 0L)
        val mode = prefs.getString("${comicKey}_mode", null)?.let {
            runCatching { ComicReadingMode.valueOf(it) }.getOrNull()
        }
        return OnlineComicProgress(
            chapterUrl = savedChapter,
            pageIndex = page,
            readingMode = mode,
            lastRead = time
        )
    }

    /**
     * Xóa tiến độ đọc đã lưu của bộ truyện [comicKey].
     *
     * @param comicKey Mã hash đại diện bộ truyện.
     */
    open fun clearComicKey(comicKey: String) {
        prefs.edit()
            .remove("${comicKey}_chapter")
            .remove("${comicKey}_page")
            .remove("${comicKey}_time")
            .remove("${comicKey}_mode")
            .apply()
    }

    /**
     * Lưu riêng chế độ đọc cho bộ truyện.
     *
     * @param chapterUrl URL của chương.
     * @param mode Chế độ đọc mới.
     */
    open fun saveReadingMode(chapterUrl: String, mode: ComicReadingMode) {
        val key = computeComicKey(chapterUrl)
        prefs.edit().putString("${key}_mode", mode.name).apply()
    }

    /**
     * Lấy chế độ đọc đã lưu của bộ truyện.
     *
     * @param chapterUrl URL của chương.
     * @return [ComicReadingMode] hoặc null nếu chưa thiết lập.
     */
    open fun getReadingMode(chapterUrl: String): ComicReadingMode? {
        val key = computeComicKey(chapterUrl)
        return prefs.getString("${key}_mode", null)?.let {
            runCatching { ComicReadingMode.valueOf(it) }.getOrNull()
        }
    }

    /**
     * Tính mã băm SHA-256 dạng hex cho chuỗi đầu vào.
     *
     * @param input Chuỗi văn bản.
     * @return Chuỗi hex 64 ký tự.
     */
    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        /** Tiền tố định danh sách truyện tranh online lưu trữ trong cơ sở dữ liệu. */
        const val COMIC_BOOK_ID_PREFIX = "ONLINE_COMIC_"
    }
}
