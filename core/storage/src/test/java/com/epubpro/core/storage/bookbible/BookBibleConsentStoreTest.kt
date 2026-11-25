package com.epubpro.core.storage.bookbible

import android.content.Context
import android.content.SharedPreferences
import com.epubpro.domain.model.BookBibleSource
import com.epubpro.domain.model.BookBibleSourceType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Kiểm tra công tắc Book Bible theo từng sách: EPUB cục bộ mặc định tắt, truyện online luôn được phép.
 */
class BookBibleConsentStoreTest {

    private val localBook = BookBibleSource(BookBibleSourceType.LOCAL_EPUB, "book_1")
    private val otherLocalBook = BookBibleSource(BookBibleSourceType.LOCAL_EPUB, "book_2")
    private val onlineNovel = BookBibleSource(BookBibleSourceType.ONLINE_NOVEL, "novel_1")

    /** Sách EPUB cục bộ chưa bật thì không được gửi; truyện online không cần đồng ý. */
    @Test
    fun localEpubIsDisabledByDefaultAndOnlineNovelIsAlwaysEnabled() {
        val store = testConsentStore()

        assertFalse(store.isEnabled(localBook))
        assertFalse(store.isEnabled(localBook.uniqueKey))
        assertTrue(store.isEnabled(onlineNovel))
        assertTrue(store.isEnabled(onlineNovel.uniqueKey))
    }

    /** Bật/tắt chỉ ảnh hưởng đúng sách đó và được lưu bền để lần khởi động sau vẫn còn. */
    @Test
    fun toggleIsPerBookAndPersisted() {
        val prefs = InMemorySharedPreferences()
        val store = testConsentStore(prefs)

        store.setEnabled(localBook, true)
        assertTrue(store.isEnabled(localBook))
        assertFalse(store.isEnabled(otherLocalBook))
        assertTrue(testConsentStore(prefs).isEnabled(localBook))

        store.setEnabled(localBook, false)
        assertFalse(store.isEnabled(localBook))
        assertFalse(testConsentStore(prefs).isEnabled(localBook))
    }

    /** Flow phát lại trạng thái mới khi người dùng đổi công tắc. */
    @Test
    fun observeEmitsLatestState() = runBlocking {
        val store = testConsentStore()

        assertEquals(false, store.observe(localBook).first())
        store.setEnabled(localBook, true)
        assertEquals(true, store.observe(localBook).first())
        assertEquals(true, store.observe(onlineNovel).first())
    }

    /** Khóa nguồn không đọc được loại vẫn bị coi là cần đồng ý. */
    @Test
    fun unknownSourceKeyIsTreatedAsRequiringConsent() {
        assertFalse(testConsentStore().isEnabled("UNKNOWN:book_1"))
    }
}

/**
 * Tạo [BookBibleConsentStore] trên SharedPreferences giả trong bộ nhớ.
 *
 * @param prefs SharedPreferences giả dùng chung giữa các lần tạo để mô phỏng khởi động lại.
 * @param enabled Các nguồn bật sẵn.
 * @return Store dùng cho test.
 */
internal fun testConsentStore(
    prefs: SharedPreferences = InMemorySharedPreferences(),
    vararg enabled: BookBibleSource
): BookBibleConsentStore {
    val context = mock<Context>()
    whenever(context.getSharedPreferences(any(), any())).thenReturn(prefs)
    return BookBibleConsentStore(context).apply { enabled.forEach { setEnabled(it, true) } }
}

/** SharedPreferences tối giản lưu trong bộ nhớ cho unit test JVM. */
internal class InMemorySharedPreferences : SharedPreferences {
    private val values = linkedMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = LinkedHashMap(values)
    override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        values[key] as? MutableSet<String> ?: defValues
    override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
    override fun contains(key: String?): Boolean = values.containsKey(key)
    override fun edit(): SharedPreferences.Editor = Editor()
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    private inner class Editor : SharedPreferences.Editor {
        private val pending = linkedMapOf<String, Any?>()
        private val removals = mutableSetOf<String>()
        private var clearAll = false

        override fun putString(key: String, value: String?) = apply { pending[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?) = apply { pending[key] = values }
        override fun putInt(key: String, value: Int) = apply { pending[key] = value }
        override fun putLong(key: String, value: Long) = apply { pending[key] = value }
        override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
        override fun remove(key: String) = apply { removals += key }
        override fun clear() = apply { clearAll = true }
        override fun commit(): Boolean {
            if (clearAll) values.clear()
            removals.forEach { values.remove(it) }
            values.putAll(pending)
            return true
        }
        override fun apply() {
            commit()
        }
    }
}
