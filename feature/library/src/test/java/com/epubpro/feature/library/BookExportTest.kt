package com.epubpro.feature.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Kiểm tra định dạng và tên file gợi ý khi xuất sách ra ngoài app. */
class BookExportTest {

    /** Chỉ file EPUB/CBZ cục bộ được xuất; sách lưu bằng URL (truyện tranh online) thì không. */
    @Test
    fun detectsExportFormatFromLocalFile() {
        assertEquals(BookExportFormat.EPUB, bookExportFormat("/data/user/0/app/files/books/a.EPUB"))
        assertEquals(BookExportFormat.CBZ, bookExportFormat("/data/user/0/app/files/books/a.cbz"))
        assertNull(bookExportFormat("https://nettruyen.gg/truyen-tranh/abc/chuong-1"))
        assertNull(bookExportFormat("/data/user/0/app/files/books/a"))
    }

    /** Tên file bỏ ký tự không hợp lệ, gộp khoảng trắng và có tên dự phòng khi rỗng. */
    @Test
    fun sanitizesFileName() {
        assertEquals(
            "Đấu Phá Thương Khung Quyển 1 2.epub",
            bookExportFileName("Đấu Phá: Thương Khung / Quyển 1*2?", BookExportFormat.EPUB)
        )
        assertEquals("book.cbz", bookExportFileName(" ..<>|.. ", BookExportFormat.CBZ))
    }

    /** Tên quá dài bị cắt để không vượt giới hạn tên file của hệ thống. */
    @Test
    fun limitsFileNameLength() {
        val name = bookExportFileName("a".repeat(300), BookExportFormat.EPUB)

        assertEquals(100 + ".epub".length, name.length)
    }
}
