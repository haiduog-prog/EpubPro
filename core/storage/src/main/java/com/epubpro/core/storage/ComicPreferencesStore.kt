package com.epubpro.core.storage

import android.content.Context
import android.content.SharedPreferences
import com.epubpro.domain.model.ComicReadingMode
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lưu cài đặt Reader truyện tranh theo từng cuốn, để manga đọc phải sang trái không ảnh hưởng webtoon khác.
 */
@Singleton
class ComicPreferencesStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("comic_reader_prefs", Context.MODE_PRIVATE)

    /**
     * Đọc chế độ đọc người dùng đã chọn cho cuốn truyện.
     *
     * @param bookId ID sách trong thư viện.
     * @return Chế độ đã lưu, hoặc null nếu chưa chọn (caller tự áp dụng mặc định từ metadata).
     */
    fun getReadingMode(bookId: String): ComicReadingMode? =
        prefs.getString(modeKey(bookId), null)
            ?.let { runCatching { ComicReadingMode.valueOf(it) }.getOrNull() }

    /**
     * Ghi chế độ đọc cho cuốn truyện.
     *
     * @param bookId ID sách trong thư viện.
     * @param mode Chế độ đọc mới.
     */
    fun setReadingMode(bookId: String, mode: ComicReadingMode) {
        prefs.edit().putString(modeKey(bookId), mode.name).apply()
    }

    /**
     * Xóa toàn bộ cài đặt của cuốn truyện khi sách bị xóa khỏi thư viện.
     *
     * @param bookId ID sách trong thư viện.
     */
    fun clear(bookId: String) {
        prefs.edit().remove(modeKey(bookId)).apply()
    }

    /**
     * Tạo khóa SharedPreferences cho chế độ đọc của một cuốn.
     *
     * @param bookId ID sách.
     * @return Khóa lưu trữ.
     */
    private fun modeKey(bookId: String): String = "mode_$bookId"
}
