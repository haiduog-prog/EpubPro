package com.epubpro.core.comic

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Kiểm tra đóng gói ảnh truyện tranh tải từ web thành CBZ và nối thêm chương. */
class ComicCbzWriterTest {

    private val dir: File = Files.createTempDirectory("cbz").toFile()

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    /** Nối chương mới giữ nguyên trang cũ đứng trước, thứ tự đọc đúng và chỉ có một ComicInfo.xml. */
    @Test
    fun appendKeepsOldPagesFirstAndSingleComicInfo() {
        val first = File(dir, "first.cbz")
        ComicCbzWriter.write(first, null, listOf(chapter("Chương 1", 2)), 1, "Truyện A", "https://a.com/c1")
        val second = File(dir, "second.cbz")
        ComicCbzWriter.write(second, first, listOf(chapter("Chương 2: Mở đầu?", 1)), 2, "Truyện A", "https://a.com/c1")

        ZipFile(second).use { zip ->
            assertEquals(
                listOf("0001 Chương 1/0001.jpg", "0001 Chương 1/0002.jpg", "0002 Chương 2 Mở đầu/0001.jpg"),
                ComicArchiveEntries.listPages(zip)
            )
            assertEquals(1, zip.entries().asSequence().count { it.name == "ComicInfo.xml" })
        }
        ComicArchive.open(second).use { archive ->
            assertEquals(3, archive.pageCount)
            assertEquals("Truyện A", archive.info?.title)
        }
    }

    /** Tên thư mục bỏ ký tự không hợp lệ và không bắt đầu bằng dấu chấm (sẽ bị coi là thư mục ẩn). */
    @Test
    fun chapterFolderNameIsSafe() {
        assertEquals("0012 Chap 12 a b", ComicCbzWriter.chapterFolderName(12, "Chap 12 / a:b"))
        assertEquals("0003 hidden", ComicCbzWriter.chapterFolderName(3, "..hidden"))
        assertEquals("0004", ComicCbzWriter.chapterFolderName(4, "  "))
    }

    /** Nhận diện định dạng ảnh theo byte đầu; trang HTML lỗi thì trả null. */
    @Test
    fun detectsImageExtension() {
        assertEquals("jpg", ComicCbzWriter.imageExtension(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0)))
        assertEquals("png", ComicCbzWriter.imageExtension(byteArrayOf(0x89.toByte()) + "PNG\r\n".toByteArray()))
        assertEquals("webp", ComicCbzWriter.imageExtension("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray()))
        assertEquals("gif", ComicCbzWriter.imageExtension("GIF89a".toByteArray()))
        assertNull(ComicCbzWriter.imageExtension("<!DOCTYPE html><html>".toByteArray()))
        assertTrue(ComicCbzWriter.imageExtension(ByteArray(0)) == null)
    }

    /**
     * Tạo chương giả với các file ảnh JPEG tối thiểu.
     *
     * @param title Tên chương.
     * @param pages Số trang.
     * @return Chương đã tải về thư mục tạm.
     */
    private fun chapter(title: String, pages: Int): DownloadedComicChapter {
        val chapterDir = File(dir, title.hashCode().toString()).apply { mkdirs() }
        val files = (1..pages).map { index ->
            File(chapterDir, "%04d.jpg".format(index)).apply {
                writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), index.toByte()))
            }
        }
        return DownloadedComicChapter(title, files)
    }
}
