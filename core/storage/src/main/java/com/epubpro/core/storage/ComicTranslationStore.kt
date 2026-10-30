package com.epubpro.core.storage

import android.content.Context
import android.content.SharedPreferences
import com.epubpro.domain.model.ComicSourceLanguage
import com.epubpro.domain.model.ComicTranslationEngine
import com.epubpro.domain.model.TranslatedBubble
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Cài đặt dịch truyện tranh dùng chung cho mọi cuốn.
 *
 * @property language Ngôn ngữ chữ trên trang.
 * @property engine Cách dịch.
 * @property autoTranslate Tự dịch khi lật sang trang mới.
 * @property showOverlay Hiện lớp bản dịch đè lên trang.
 */
data class ComicTranslationSettings(
    val language: ComicSourceLanguage = ComicSourceLanguage.CHINESE,
    val engine: ComicTranslationEngine = ComicTranslationEngine.ON_DEVICE,
    val autoTranslate: Boolean = false,
    val showOverlay: Boolean = true
)

/**
 * Lưu cài đặt dịch truyện tranh và cache kết quả OCR + dịch theo từng trang để lật lại không phải xử lý lại.
 */
@Singleton
class ComicTranslationStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("comic_translation_prefs", Context.MODE_PRIVATE)
    private val cacheRoot = File(context.filesDir, "comic_translations")

    /**
     * Đọc cài đặt dịch.
     *
     * @return Cài đặt hiện tại (mặc định nếu chưa lưu).
     */
    fun getSettings(): ComicTranslationSettings = ComicTranslationSettings(
        language = prefs.getString(KEY_LANGUAGE, null)
            ?.let { runCatching { ComicSourceLanguage.valueOf(it) }.getOrNull() } ?: ComicSourceLanguage.CHINESE,
        engine = prefs.getString(KEY_ENGINE, null)
            ?.let { runCatching { ComicTranslationEngine.valueOf(it) }.getOrNull() } ?: ComicTranslationEngine.ON_DEVICE,
        autoTranslate = prefs.getBoolean(KEY_AUTO, false),
        showOverlay = prefs.getBoolean(KEY_OVERLAY, true)
    )

    /**
     * Ghi cài đặt dịch.
     *
     * @param settings Cài đặt mới.
     */
    fun saveSettings(settings: ComicTranslationSettings) {
        prefs.edit()
            .putString(KEY_LANGUAGE, settings.language.name)
            .putString(KEY_ENGINE, settings.engine.name)
            .putBoolean(KEY_AUTO, settings.autoTranslate)
            .putBoolean(KEY_OVERLAY, settings.showOverlay)
            .apply()
    }

    /**
     * Đọc bản dịch đã cache của một trang.
     *
     * @param bookId ID sách.
     * @param pageIndex Trang 0-based.
     * @param language Ngôn ngữ nguồn.
     * @param engine Cách dịch.
     * @return Danh sách bóng thoại, hoặc null nếu chưa có/cache hỏng.
     */
    suspend fun loadPage(
        bookId: String,
        pageIndex: Int,
        language: ComicSourceLanguage,
        engine: ComicTranslationEngine
    ): List<TranslatedBubble>? = withContext(Dispatchers.IO) {
        val file = pageFile(bookId, pageIndex, language, engine)
        if (!file.isFile) return@withContext null
        runCatching {
            val array = JSONArray(file.readText(Charsets.UTF_8))
            List(array.length()) { index ->
                val item = array.getJSONObject(index)
                TranslatedBubble(
                    original = item.getString("o"),
                    translated = item.getString("t"),
                    left = item.getDouble("l").toFloat(),
                    top = item.getDouble("tp").toFloat(),
                    right = item.getDouble("r").toFloat(),
                    bottom = item.getDouble("b").toFloat()
                )
            }
        }.getOrNull()
    }

    /**
     * Ghi bản dịch của một trang.
     *
     * @param bookId ID sách.
     * @param pageIndex Trang 0-based.
     * @param language Ngôn ngữ nguồn.
     * @param engine Cách dịch.
     * @param bubbles Bóng thoại đã dịch.
     */
    suspend fun savePage(
        bookId: String,
        pageIndex: Int,
        language: ComicSourceLanguage,
        engine: ComicTranslationEngine,
        bubbles: List<TranslatedBubble>
    ) = withContext(Dispatchers.IO) {
        val array = JSONArray()
        bubbles.forEach { bubble ->
            array.put(
                JSONObject()
                    .put("o", bubble.original)
                    .put("t", bubble.translated)
                    .put("l", bubble.left.toDouble())
                    .put("tp", bubble.top.toDouble())
                    .put("r", bubble.right.toDouble())
                    .put("b", bubble.bottom.toDouble())
            )
        }
        val file = pageFile(bookId, pageIndex, language, engine)
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(array.toString(), Charsets.UTF_8)
        if (!temporary.renameTo(file)) {
            file.delete()
            temporary.renameTo(file)
        }
    }

    /**
     * Xóa toàn bộ cache dịch của sách.
     *
     * @param bookId ID sách.
     */
    suspend fun clearBook(bookId: String) = withContext(Dispatchers.IO) {
        bookDirectory(bookId).deleteRecursively()
        Unit
    }

    /**
     * File cache của một trang.
     *
     * @param bookId ID sách.
     * @param pageIndex Trang.
     * @param language Ngôn ngữ nguồn.
     * @param engine Cách dịch.
     * @return File JSON.
     */
    private fun pageFile(
        bookId: String,
        pageIndex: Int,
        language: ComicSourceLanguage,
        engine: ComicTranslationEngine
    ): File = File(bookDirectory(bookId), "${pageIndex}_${language.name}_${engine.name}.json")

    /**
     * Thư mục cache của sách, tên băm từ ID.
     *
     * @param bookId ID sách.
     * @return Thư mục cache.
     */
    private fun bookDirectory(bookId: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(bookId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(32)
        return File(cacheRoot, digest)
    }

    private companion object {
        const val KEY_LANGUAGE = "language"
        const val KEY_ENGINE = "engine"
        const val KEY_AUTO = "auto_translate"
        const val KEY_OVERLAY = "show_overlay"
    }
}
