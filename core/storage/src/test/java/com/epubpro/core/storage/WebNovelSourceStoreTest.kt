package com.epubpro.core.storage

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/** Kiểm tra việc ghi nhận và dọn các bản EPUB cũ sau khi nối chương truyện web. */
class WebNovelSourceStoreTest {

    private val store = WebNovelSourceStore(
        mock<Context>().also { whenever(it.getSharedPreferences(any(), any())).thenReturn(InMemoryPreferences()) }
    )

    /** Bản đang được database tham chiếu không bị xóa; các bản cũ khác bị xóa đúng một lần. */
    @Test
    fun purgeSkipsCurrentFileAndForgetsDeletedOnes() {
        store.retireFile("book", "/books/a.epub")
        store.retireFile("book", "/books/b.epub")
        val deleted = mutableListOf<String>()

        store.purgeRetiredFiles("book", currentPath = "/books/b.epub") { deleted += it; true }
        store.purgeRetiredFiles("book", currentPath = "/books/c.epub") { deleted += it; true }
        store.purgeRetiredFiles("book", currentPath = "/books/c.epub") { deleted += it; true }

        assertEquals(listOf("/books/a.epub", "/books/b.epub"), deleted)
    }

    /** File xóa thất bại được giữ lại để thử ở lần dọn sau; danh sách tách riêng theo sách. */
    @Test
    fun purgeKeepsFailedDeletionsAndSeparatesBooks() {
        store.retireFile("book", "/books/a.epub")
        store.retireFile("other", "/books/x.epub")
        val attempts = mutableListOf<String>()

        store.purgeRetiredFiles("book", currentPath = null) { attempts += it; false }
        store.purgeRetiredFiles("book", currentPath = null) { attempts += it; true }

        assertEquals(listOf("/books/a.epub", "/books/a.epub"), attempts)
        val otherAttempts = mutableListOf<String>()
        store.purgeRetiredFiles("other", currentPath = null) { otherAttempts += it; true }
        assertEquals(listOf("/books/x.epub"), otherAttempts)
    }

    /** Xóa nguồn của sách cũng bỏ danh sách file chờ dọn. */
    @Test
    fun clearForgetsRetiredFiles() {
        store.retireFile("book", "/books/a.epub")

        store.clear("book")
        val attempts = mutableListOf<String>()
        store.purgeRetiredFiles("book", currentPath = null) { attempts += it; true }

        assertTrue(attempts.isEmpty())
    }
}

/** SharedPreferences lưu trong bộ nhớ, đủ cho các thao tác chuỗi mà store sử dụng. */
internal class InMemoryPreferences : SharedPreferences {
    private val values = mutableMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = values.toMutableMap()
    override fun getString(key: String, defValue: String?): String? = values[key] as? String ?: defValue
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? = defValues
    override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue
    override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue
    override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
    override fun contains(key: String): Boolean = key in values
    override fun edit(): SharedPreferences.Editor = Editor()
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    private inner class Editor : SharedPreferences.Editor {
        private val changes = mutableMapOf<String, Any?>()
        private val removals = mutableSetOf<String>()

        override fun putString(key: String, value: String?) = also { changes[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?) = also { changes[key] = values }
        override fun putInt(key: String, value: Int) = also { changes[key] = value }
        override fun putLong(key: String, value: Long) = also { changes[key] = value }
        override fun putFloat(key: String, value: Float) = also { changes[key] = value }
        override fun putBoolean(key: String, value: Boolean) = also { changes[key] = value }
        override fun remove(key: String) = also { removals += key }
        override fun clear() = also { removals += values.keys }
        override fun commit(): Boolean {
            removals.forEach(values::remove)
            values.putAll(changes)
            return true
        }
        override fun apply() {
            commit()
        }
    }
}
