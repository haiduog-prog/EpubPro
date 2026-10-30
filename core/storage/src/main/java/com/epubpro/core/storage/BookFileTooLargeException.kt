package com.epubpro.core.storage

/**
 * File sách người dùng nhập vượt giới hạn dung lượng của định dạng tương ứng.
 *
 * Mang theo giới hạn thực tế để UI hiển thị đúng con số (100 MiB cho ebook chữ, 1 GiB cho truyện tranh),
 * thay vì so khớp chuỗi thông báo kỹ thuật.
 *
 * @property limitBytes Giới hạn dung lượng đã bị vượt.
 */
class BookFileTooLargeException(val limitBytes: Long) :
    IllegalStateException("File ebook cục bộ vượt quá giới hạn ${limitBytes / BYTES_PER_MIB} MiB") {

    /** Giới hạn tính theo MiB để định dạng thông báo. */
    val limitMiB: Long get() = limitBytes / BYTES_PER_MIB

    private companion object {
        const val BYTES_PER_MIB = 1024L * 1024L
    }
}
