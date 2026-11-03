package com.epubpro.core.storage

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONArray
import org.json.JSONObject

/**
 * Nguồn của một cuốn CBZ được tải về từ truyện tranh online.
 *
 * @property comicKey Khóa bộ truyện (host + đường dẫn truyện) do [OnlineComicProgressStore.computeComicKey] tạo.
 * @property chapterUrls URL các chương đã có trong CBZ theo thứ tự đọc; không rỗng.
 */
data class OfflineComicSource(
    val comicKey: String,
    val chapterUrls: List<String>
) {
    /** URL chương cuối đã tải; lần tải tiếp sẽ đi tiếp từ chương sau của chương này. */
    val lastChapterUrl: String get() = chapterUrls.last()
}

/**
 * Lưu nguồn web của các cuốn CBZ tải về từ truyện tranh online, để lần tải sau nối tiếp vào đúng cuốn.
 */
@Singleton
class OfflineComicStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("offline_comic_sources", Context.MODE_PRIVATE)

    /**
     * Đọc nguồn của sách CBZ.
     *
     * @param bookId ID sách.
     * @return Nguồn đã lưu, hoặc null nếu sách không được tải từ truyện tranh online.
     */
    fun get(bookId: String): OfflineComicSource? =
        prefs.getString(BOOK_PREFIX + bookId, null)?.let(::decode)

    /**
     * Tìm cuốn CBZ đã tải gần nhất của một bộ truyện.
     *
     * @param comicKey Khóa bộ truyện.
     * @return ID sách, hoặc null nếu chưa tải về máy.
     */
    fun findBookId(comicKey: String): String? = prefs.getString(KEY_PREFIX + comicKey, null)

    /**
     * Ghi nguồn của sách và đánh dấu đây là cuốn offline hiện tại của bộ truyện.
     *
     * @param bookId ID sách.
     * @param source Nguồn sau lần tải mới nhất.
     * @throws IllegalStateException Khi không ghi được dữ liệu.
     */
    fun save(bookId: String, source: OfflineComicSource) {
        check(
            prefs.edit()
                .putString(BOOK_PREFIX + bookId, encode(source))
                .putString(KEY_PREFIX + source.comicKey, bookId)
                .commit()
        )
    }

    /**
     * Xóa nguồn khi sách bị xóa khỏi thư viện.
     *
     * @param bookId ID sách.
     */
    fun clear(bookId: String) {
        val source = get(bookId)
        val editor = prefs.edit().remove(BOOK_PREFIX + bookId)
        if (source != null && findBookId(source.comicKey) == bookId) editor.remove(KEY_PREFIX + source.comicKey)
        editor.apply()
    }

    /**
     * Mã hóa nguồn thành JSON.
     *
     * @param source Nguồn cần lưu.
     * @return Chuỗi JSON.
     */
    private fun encode(source: OfflineComicSource): String = JSONObject()
        .put("key", source.comicKey)
        .put("chapters", JSONArray(source.chapterUrls))
        .toString()

    /**
     * Giải mã JSON thành nguồn.
     *
     * @param json Chuỗi JSON.
     * @return Nguồn, hoặc null nếu dữ liệu hỏng hoặc không có chương nào.
     */
    private fun decode(json: String): OfflineComicSource? = runCatching {
        val obj = JSONObject(json)
        val array = obj.getJSONArray("chapters")
        val urls = (0 until array.length()).map { array.getString(it) }
        OfflineComicSource(obj.getString("key"), urls).takeIf { urls.isNotEmpty() }
    }.getOrNull()

    private companion object {
        const val BOOK_PREFIX = "book_"
        const val KEY_PREFIX = "comic_"
    }
}
