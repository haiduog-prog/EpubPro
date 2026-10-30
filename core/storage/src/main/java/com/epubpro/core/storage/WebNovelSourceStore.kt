package com.epubpro.core.storage

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONObject

/**
 * Nguồn web của một cuốn truyện đã lưu từ trình duyệt.
 *
 * @property startUrl URL chương bắt đầu khi lưu lần đầu (dùng để nhận ra truyện đã lưu).
 * @property lastChapterUrl URL chương cuối đã tải; "tải thêm chương" sẽ đi tiếp từ đây.
 * @property userAgent User-Agent của WebView lúc lưu, để cookie chống bot (Cloudflare) còn hiệu lực.
 */
data class WebNovelSource(
    val startUrl: String,
    val lastChapterUrl: String,
    val userAgent: String?
)

/**
 * Lưu nguồn web theo từng sách để mở lại truyện đã lưu và tải thêm chương mới.
 */
@Singleton
class WebNovelSourceStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs: SharedPreferences = context.getSharedPreferences("web_novel_sources", Context.MODE_PRIVATE)

    /**
     * Đọc nguồn web của sách.
     *
     * @param bookId ID sách.
     * @return Nguồn web, hoặc null nếu sách không phải truyện lưu từ web.
     */
    fun get(bookId: String): WebNovelSource? =
        prefs.getString(sourceKey(bookId), null)?.let(::decode)

    /**
     * Ghi nguồn web của sách.
     *
     * @param bookId ID sách.
     * @param source Nguồn web.
     */
    fun save(bookId: String, source: WebNovelSource) {
        prefs.edit().putString(sourceKey(bookId), encode(source)).apply()
    }

    /**
     * Ghi mốc phục hồi trước khi database chuyển sang EPUB mới.
     *
     * @param bookId ID sách.
     * @param filePath Đường dẫn EPUB mới.
     * @param source Nguồn tương ứng với EPUB mới.
     * @throws IllegalStateException Khi không ghi được mốc phục hồi.
     */
    fun prepareReplacement(bookId: String, filePath: String, source: WebNovelSource) {
        val pending = JSONObject().put("path", filePath).put("source", encode(source)).toString()
        check(prefs.edit().putString("pending_$bookId", pending).commit())
    }

    /**
     * Phục hồi mốc chương khi database đã trỏ tới EPUB mới.
     *
     * @param bookId ID sách.
     * @param filePath Đường dẫn EPUB đang được database tham chiếu.
     * @throws IllegalStateException Khi không lưu được mốc đã phục hồi.
     */
    fun reconcileReplacement(bookId: String, filePath: String) {
        val pendingKey = "pending_$bookId"
        val pending = prefs.getString(pendingKey, null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return
        if (pending.optString("path") != filePath) return
        check(prefs.edit().putString(sourceKey(bookId), pending.getString("source")).remove(pendingKey).commit())
    }

    /**
     * Tìm sách đã lưu bắt đầu từ URL này.
     *
     * @param startUrl URL chương bắt đầu.
     * @return ID sách, hoặc null nếu chưa lưu.
     */
    fun findBookByStartUrl(startUrl: String): String? {
        val normalized = startUrl.substringBefore('#')
        return prefs.all.entries.firstOrNull { (key, value) ->
            key.startsWith(KEY_PREFIX) && (value as? String)?.let(::decode)?.startUrl?.substringBefore('#') == normalized
        }?.key?.removePrefix(KEY_PREFIX)
    }

    /**
     * Xóa nguồn web khi sách bị xóa khỏi thư viện.
     *
     * @param bookId ID sách.
     */
    fun clear(bookId: String) {
        prefs.edit().remove(sourceKey(bookId)).remove("pending_$bookId").apply()
    }

    /**
     * Mã hóa nguồn thành JSON.
     *
     * @param source Nguồn web.
     * @return Chuỗi JSON.
     */
    private fun encode(source: WebNovelSource): String = JSONObject()
        .put("start", source.startUrl)
        .put("last", source.lastChapterUrl)
        .put("ua", source.userAgent ?: JSONObject.NULL)
        .toString()

    /**
     * Giải mã JSON thành nguồn web.
     *
     * @param json Chuỗi JSON.
     * @return Nguồn web, hoặc null nếu dữ liệu hỏng.
     */
    private fun decode(json: String): WebNovelSource? = runCatching {
        val obj = JSONObject(json)
        WebNovelSource(
            startUrl = obj.getString("start"),
            lastChapterUrl = obj.getString("last"),
            userAgent = obj.optString("ua").takeIf { it.isNotBlank() && !obj.isNull("ua") }
        )
    }.getOrNull()

    /**
     * Khóa lưu trữ của sách.
     *
     * @param bookId ID sách.
     * @return Khóa SharedPreferences.
     */
    private fun sourceKey(bookId: String): String = KEY_PREFIX + bookId

    private companion object {
        const val KEY_PREFIX = "source_"
    }
}
