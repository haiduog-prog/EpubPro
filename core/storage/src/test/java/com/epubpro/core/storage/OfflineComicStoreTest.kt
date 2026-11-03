package com.epubpro.core.storage

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/** Kiểm tra lưu nguồn cuốn CBZ tải từ truyện tranh online. */
class OfflineComicStoreTest {

    private val store = OfflineComicStore(
        mock<Context>().also { whenever(it.getSharedPreferences(any(), any())).thenReturn(InMemoryPreferences()) }
    )

    /** Lưu nguồn thì tìm lại được sách theo khóa truyện và chương cuối đúng thứ tự. */
    @Test
    fun saveAndFindByComicKey() {
        store.save("book1", OfflineComicSource("key", listOf("https://a.com/c1", "https://a.com/c2")))

        assertEquals("book1", store.findBookId("key"))
        assertEquals("https://a.com/c2", store.get("book1")?.lastChapterUrl)
    }

    /** Khóa truyện trỏ tới cuốn tải gần nhất; xóa cuốn cũ không làm mất liên kết của cuốn mới. */
    @Test
    fun clearOnlyRemovesOwnKeyMapping() {
        store.save("old", OfflineComicSource("key", listOf("https://a.com/c1")))
        store.save("new", OfflineComicSource("key", listOf("https://a.com/c9")))

        store.clear("old")

        assertNull(store.get("old"))
        assertEquals("new", store.findBookId("key"))
        store.clear("new")
        assertNull(store.findBookId("key"))
    }
}
