package com.epubpro.core.storage.bookbible

import android.content.Context
import android.content.SharedPreferences
import com.epubpro.domain.model.BookBibleSource
import com.epubpro.domain.model.BookBibleSourceType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lưu lựa chọn của người dùng về việc cho phép gửi nội dung chương lên máy chủ Book Bible, theo từng sách.
 *
 * Sách EPUB cục bộ mặc định **không** được gửi cho tới khi người dùng bật; nguồn không cần đồng ý
 * ([BookBibleSource.requiresSubmissionConsent] = `false`) luôn được phép.
 *
 * @param context Context ứng dụng để mở SharedPreferences.
 */
@Singleton
class BookBibleConsentStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    // Tập khóa nguồn đã bật, giữ trong bộ nhớ để Flow phát lại ngay khi người dùng đổi công tắc.
    private val enabledKeys = MutableStateFlow(readEnabledKeys())

    /**
     * Kiểm tra nguồn có được phép gửi nội dung chương hay không.
     *
     * @param source Nguồn sách.
     * @return `true` nếu nguồn không cần đồng ý hoặc người dùng đã bật.
     */
    fun isEnabled(source: BookBibleSource): Boolean =
        !source.requiresSubmissionConsent || source.uniqueKey in enabledKeys.value

    /**
     * Kiểm tra theo khóa nguồn đã lưu trong submission (`TYPE:sourceId`), dùng trong worker.
     *
     * @param localSourceKey Khóa nguồn dạng [BookBibleSource.uniqueKey].
     * @return `true` nếu được phép gửi; khóa không đọc được loại nguồn coi như cần đồng ý và chỉ cho phép khi
     *         đã được bật.
     */
    fun isEnabled(localSourceKey: String): Boolean {
        val type = runCatching {
            BookBibleSourceType.valueOf(localSourceKey.substringBefore(':'))
        }.getOrNull()
        val sourceId = localSourceKey.substringAfter(':', missingDelimiterValue = "")
        return if (type != null) {
            isEnabled(BookBibleSource(type, sourceId))
        } else {
            localSourceKey in enabledKeys.value
        }
    }

    /**
     * Quan sát trạng thái cho phép gửi của một nguồn.
     *
     * @param source Nguồn sách.
     * @return [Flow] phát trạng thái hiện tại và mỗi lần thay đổi.
     */
    fun observe(source: BookBibleSource): Flow<Boolean> =
        enabledKeys.map { !source.requiresSubmissionConsent || source.uniqueKey in it }.distinctUntilChanged()

    /**
     * Ghi lựa chọn của người dùng cho một nguồn. Ghi đồng bộ (`commit`) để worker đọc lại ngay sau đó thấy
     * đúng giá trị.
     *
     * @param source Nguồn sách.
     * @param enabled `true` để cho phép gửi.
     */
    @Synchronized
    fun setEnabled(source: BookBibleSource, enabled: Boolean) {
        if (!source.requiresSubmissionConsent) return
        val key = source.uniqueKey
        val editor = prefs.edit()
        if (enabled) editor.putBoolean(prefKey(key), true) else editor.remove(prefKey(key))
        editor.commit()
        enabledKeys.value = if (enabled) enabledKeys.value + key else enabledKeys.value - key
    }

    /**
     * Đọc các nguồn đã bật từ SharedPreferences khi khởi tạo.
     *
     * @return Tập khóa nguồn đã bật.
     */
    private fun readEnabledKeys(): Set<String> =
        prefs.all.entries
            .filter { (key, value) -> key.startsWith(KEY_PREFIX) && value == true }
            .mapTo(HashSet()) { it.key.removePrefix(KEY_PREFIX) }

    /**
     * Tạo khóa SharedPreferences cho một nguồn.
     *
     * @param sourceKey Khóa nguồn dạng [BookBibleSource.uniqueKey].
     * @return Khóa SharedPreferences.
     */
    private fun prefKey(sourceKey: String): String = KEY_PREFIX + sourceKey

    private companion object {
        const val PREF_NAME = "book_bible_consent"
        const val KEY_PREFIX = "enabled_"
    }
}

/**
 * Ném ra khi một thao tác cần gọi máy chủ Book Bible cho nguồn mà người dùng chưa bật Book Bible.
 *
 * @param sourceKey Khóa nguồn bị chặn.
 */
class BookBibleSubmissionDisabledException(sourceKey: String) :
    IllegalStateException("Book Bible is disabled for source $sourceKey")
