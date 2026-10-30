package com.epubpro.core.bookconverter

import com.epubpro.domain.model.BookSourceFormat
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Kiểm thử nhận diện phần mở rộng nguồn của converter. */
class BookFormatSnifferTest {
    private val sniffer = BookFormatSniffer()

    /** Kiểm tra ba phần mở rộng local được hỗ trợ không phân biệt hoa thường. */
    @Test
    fun recognizesSupportedExtensions() {
        assertEquals(BookSourceFormat.PRC, sniffer.sniff(File("book.PRC")))
        assertEquals(BookSourceFormat.MOBI, sniffer.sniff(File("book.mobi")))
        assertEquals(BookSourceFormat.AZW3, sniffer.sniff(File("book.AzW3")))
    }

    /** Kiểm tra EPUB được giữ trong pipeline, còn file không có phần mở rộng bị chặn. */
    @Test
    fun recognizesEpubAndRejectsUnknownExtensions() {
        assertEquals(BookSourceFormat.EPUB, sniffer.sniff(File("book.epub")))
        assertNull(sniffer.sniff(File("book")))
    }

    /** Kiểm tra nhận diện PalmDOC và MOBI từ header khi file không có phần mở rộng. */
    @Test
    fun recognizesPalmDatabaseHeadersWithoutExtension() {
        val mobiFile = createPalmDatabaseFixture("BOOKMOBI")
        val palmDocFile = createPalmDatabaseFixture("TEXtREAd")
        try {
            assertEquals(BookSourceFormat.MOBI, sniffer.sniff(mobiFile))
            assertEquals(BookSourceFormat.PRC, sniffer.sniff(palmDocFile))
            assertTrue(sniffer.hasPalmDatabaseHeader(mobiFile))
            assertTrue(sniffer.hasPalmDatabaseHeader(palmDocFile))
        } finally {
            mobiFile.delete()
            palmDocFile.delete()
        }
    }

    /** Kiểm tra nhận diện EPUB từ cấu trúc ZIP container khi file mang extension .bin hoặc không có extension. */
    @Test
    fun recognizesEpubZipContainerWithoutExtension() {
        val epubBinFile = createEpubZipFixture()
        try {
            assertEquals(BookSourceFormat.EPUB, sniffer.sniff(epubBinFile))
        } finally {
            epubBinFile.delete()
        }
    }

    /** Kiểm tra `.cbz` và ZIP chỉ chứa ảnh được nhận là truyện tranh CBZ. */
    @Test
    fun recognizesComicArchives() {
        assertEquals(BookSourceFormat.CBZ, sniffer.sniff(File("comic.CBZ")))
        val imageZip = Files.createTempFile("book-comic-", ".zip").toFile()
        try {
            java.util.zip.ZipOutputStream(java.io.FileOutputStream(imageZip)).use { zos ->
                zos.putNextEntry(java.util.zip.ZipEntry("001.jpg"))
                zos.write(byteArrayOf(1, 2, 3))
                zos.closeEntry()
            }
            assertEquals(BookSourceFormat.CBZ, sniffer.sniff(imageZip))
        } finally {
            imageZip.delete()
        }
    }

    /**
     * Tạo file tạm có định dạng ZIP chứa entry mimetype của EPUB để kiểm thử.
     *
     * @return File tạm mô phỏng file EPUB.
     */
    private fun createEpubZipFixture(): File {
        val file = Files.createTempFile("book-epub-", ".bin").toFile()
        java.util.zip.ZipOutputStream(java.io.FileOutputStream(file)).use { zos ->
            val entry = java.util.zip.ZipEntry("mimetype")
            zos.putNextEntry(entry)
            zos.write("application/epub+zip".toByteArray(Charsets.US_ASCII))
            zos.closeEntry()
        }
        return file
    }

    /**
     * Tạo file tạm có Palm Database header tối thiểu cho kiểm thử nhận diện.
     *
     * @param identifier Cặp type/creator 8 byte của định dạng cần mô phỏng.
     * @return File tạm chứa header kiểm thử.
     */
    private fun createPalmDatabaseFixture(identifier: String): File {
        val file = Files.createTempFile("book-format-", ".bin").toFile()
        val header = ByteArray(78)
        identifier.toByteArray(Charsets.US_ASCII).copyInto(header, destinationOffset = 60)
        file.writeBytes(header)
        return file
    }
}
