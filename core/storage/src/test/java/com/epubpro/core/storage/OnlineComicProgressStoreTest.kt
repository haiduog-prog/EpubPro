package com.epubpro.core.storage

import android.content.Context
import android.content.SharedPreferences
import com.epubpro.domain.model.ComicReadingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.lang.reflect.Proxy

/**
 * Unit test cho [OnlineComicProgressStore].
 *
 * Kiểm tra việc định danh khóa truyện, phân biệt các nguồn/truyện khác nhau,
 * lưu và khôi phục tiến độ đọc mà không gây xung đột dữ liệu.
 */
class OnlineComicProgressStoreTest {

    private val chapter1OfMangaA = "https://nettruyen.gg/truyen-tranh/chi-ta-nhin-thay-huong-dan/chuong-1"
    private val chapter2OfMangaA = "https://nettruyen.gg/truyen-tranh/chi-ta-nhin-thay-huong-dan/chuong-2"
    private val chapter1OfMangaB = "https://nettruyen.gg/truyen-tranh/phuong-thuc-bao-thu/chuong-1"
    private val chapter1OfOtherHost = "https://othermanga.com/truyen-tranh/chi-ta-nhin-thay-huong-dan/chuong-1"

    /**
     * Tạo Fake [SharedPreferences] in-memory bằng dynamic proxy để kiểm thử logic lưu trữ.
     */
    private fun createFakePrefs(): SharedPreferences {
        val map = HashMap<String, Any>()
        val editorMap = HashMap<String, Any>()

        val editorProxy = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java)
        ) { proxy, method, args ->
            when (method.name) {
                "putString" -> {
                    editorMap[args[0] as String] = args[1]
                    proxy
                }
                "putInt" -> {
                    editorMap[args[0] as String] = args[1]
                    proxy
                }
                "putLong" -> {
                    editorMap[args[0] as String] = args[1]
                    proxy
                }
                "apply", "commit" -> {
                    map.putAll(editorMap)
                    true
                }
                "clear" -> {
                    editorMap.clear()
                    proxy
                }
                else -> proxy
            }
        } as SharedPreferences.Editor

        return Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getString" -> map[args[0]] as? String ?: args[1]
                "getInt" -> map[args[0]] as? Int ?: args[1]
                "getLong" -> map[args[0]] as? Long ?: args[1]
                "edit" -> editorProxy
                else -> null
            }
        } as SharedPreferences
    }

    /**
     * Tạo instance [OnlineComicProgressStore] với mock Context trả về fake SharedPreferences.
     */
    private fun createStoreWithFakePrefs(fakePrefs: SharedPreferences): OnlineComicProgressStore {
        val mockContext = mock<Context>()
        whenever(mockContext.getSharedPreferences(any(), any())).thenReturn(fakePrefs)
        return OnlineComicProgressStore(mockContext)
    }

    @Test
    fun computeComicKey_sameMangaDifferentChapters_producesSameKey() {
        val store = createStoreWithFakePrefs(createFakePrefs())
        val key1 = store.computeComicKey(chapter1OfMangaA)
        val key2 = store.computeComicKey(chapter2OfMangaA)

        assertEquals("Hai chương của cùng một bộ truyện phải sinh ra cùng một mã comicKey", key1, key2)
    }

    @Test
    fun computeComicKey_differentMangas_producesDifferentKeys() {
        val store = createStoreWithFakePrefs(createFakePrefs())
        val keyA = store.computeComicKey(chapter1OfMangaA)
        val keyB = store.computeComicKey(chapter1OfMangaB)

        assertNotEquals("Hai bộ truyện khác nhau phải có mã comicKey khác nhau", keyA, keyB)
    }

    @Test
    fun computeComicKey_differentHosts_producesDifferentKeys() {
        val store = createStoreWithFakePrefs(createFakePrefs())
        val keyHost1 = store.computeComicKey(chapter1OfMangaA)
        val keyHost2 = store.computeComicKey(chapter1OfOtherHost)

        assertNotEquals("Cùng tên truyện nhưng ở hai host nguồn khác nhau phải có mã comicKey khác nhau", keyHost1, keyHost2)
    }

    @Test
    fun saveAndGetProgress_savesCorrectDetails() {
        val store = createStoreWithFakePrefs(createFakePrefs())
        store.saveProgress(chapter1OfMangaA, pageIndex = 15, mode = ComicReadingMode.WEBTOON)

        val progress = store.getProgress(chapter1OfMangaA)
        assertNotNull("Tiến độ đã lưu không được null", progress)
        assertEquals("URL chương phải khớp", chapter1OfMangaA, progress?.chapterUrl)
        assertEquals("Chỉ số trang phải khớp", 15, progress?.pageIndex)
        assertEquals("Chế độ đọc phải khớp", ComicReadingMode.WEBTOON, progress?.readingMode)
        assertTrue("Thời gian đọc phải lớn hơn 0", (progress?.lastRead ?: 0L) > 0L)
    }

    @Test
    fun saveProgress_differentMangasDoNotClobberEachOther() {
        val store = createStoreWithFakePrefs(createFakePrefs())

        store.saveProgress(chapter1OfMangaA, pageIndex = 42, mode = ComicReadingMode.LEFT_TO_RIGHT)
        store.saveProgress(chapter1OfMangaB, pageIndex = 7, mode = ComicReadingMode.WEBTOON)

        val progressA = store.getProgress(chapter2OfMangaA)
        val progressB = store.getProgress(chapter1OfMangaB)

        assertEquals("Tiến độ của truyện A không bị ghi đè", 42, progressA?.pageIndex)
        assertEquals(ComicReadingMode.LEFT_TO_RIGHT, progressA?.readingMode)

        assertEquals("Tiến độ của truyện B được lưu riêng biệt", 7, progressB?.pageIndex)
        assertEquals(ComicReadingMode.WEBTOON, progressB?.readingMode)
    }

    @Test
    fun getProgress_returnsNullWhenNoProgressSaved() {
        val store = createStoreWithFakePrefs(createFakePrefs())
        val progress = store.getProgress("https://nettruyen.gg/truyen-tranh/chua-tung-doc/chuong-1")
        assertNull("Khi chưa có tiến độ lưu phải trả về null", progress)
    }
}
