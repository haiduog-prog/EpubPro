package com.epubpro.app.shortcut

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/**
 * Kiểm thử việc đọc `bookId` từ Intent lối tắt của [BookEntryContract] và ánh xạ ID lối tắt.
 */
class BookEntryContractTest {

    /**
     * Tạo Intent giả với action và extra `bookId` cho trước.
     *
     * @param action Action của Intent.
     * @param bookId Giá trị extra `bookId`, có thể null.
     * @return Intent giả.
     */
    private fun intentOf(action: String?, bookId: String?): Intent {
        val intent = mock(Intent::class.java)
        `when`(intent.action).thenReturn(action)
        `when`(intent.getStringExtra(BookEntryContract.EXTRA_BOOK_ID)).thenReturn(bookId)
        return intent
    }

    /** Intent đúng action và có `bookId` trả về ID sách. */
    @Test
    fun parseBookIdReturnsIdForValidIntent() {
        val intent = intentOf(BookEntryContract.ACTION_OPEN_BOOK_ENTRY, "book-1")

        assertEquals("book-1", BookEntryContract.parseBookId(intent))
    }

    /** Intent null hoặc sai action bị bỏ qua. */
    @Test
    fun parseBookIdReturnsNullForWrongActionOrNullIntent() {
        assertNull(BookEntryContract.parseBookId(null))
        assertNull(BookEntryContract.parseBookId(intentOf(Intent.ACTION_VIEW, "book-1")))
    }

    /** Thiếu extra hoặc extra rỗng bị bỏ qua. */
    @Test
    fun parseBookIdReturnsNullForMissingOrBlankId() {
        assertNull(BookEntryContract.parseBookId(intentOf(BookEntryContract.ACTION_OPEN_BOOK_ENTRY, null)))
        assertNull(BookEntryContract.parseBookId(intentOf(BookEntryContract.ACTION_OPEN_BOOK_ENTRY, "  ")))
    }

    /** Extra hỏng ném khi unparcel không làm crash, chỉ trả null. */
    @Test
    fun parseBookIdReturnsNullWhenExtraThrows() {
        val intent = mock(Intent::class.java)
        `when`(intent.action).thenReturn(BookEntryContract.ACTION_OPEN_BOOK_ENTRY)
        `when`(intent.getStringExtra(BookEntryContract.EXTRA_BOOK_ID))
            .thenThrow(RuntimeException("BadParcelableException"))

        assertNull(BookEntryContract.parseBookId(intent))
    }

    /** Chữ viết tắt lấy chữ đầu của tối đa hai từ, giữ dấu tiếng Việt và bỏ qua dấu câu. */
    @Test
    fun titleInitialsUsesFirstLettersOfTwoWords() {
        assertEquals("ĐP", BookShortcutManager.titleInitials("Đấu Phá Thương Khung"))
        assertEquals("NT", BookShortcutManager.titleInitials("  Ngự   Thú Bắt Đầu"))
        assertEquals("TL", BookShortcutManager.titleInitials("[Truyện] (Lẻ)"))
        assertEquals("A", BookShortcutManager.titleInitials("a"))
        assertEquals("", BookShortcutManager.titleInitials(" -- "))
    }

    /** ID lối tắt chuyển qua lại với ID sách mà không mất dữ liệu, kể cả ID truyện online có tiền tố. */
    @Test
    fun shortcutIdRoundTripsBookId() {
        listOf("book-1", "ONLINE_COMIC_abc", "a_b_c").forEach { bookId ->
            val shortcutId = BookShortcutManager.shortcutIdOf(bookId)
            assertEquals(bookId, BookShortcutManager.bookIdOf(shortcutId))
        }
    }
}
