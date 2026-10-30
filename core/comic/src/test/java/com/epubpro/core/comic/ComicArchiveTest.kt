package com.epubpro.core.comic

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Kiểm thử đọc archive CBZ: sắp trang, lọc entry rác, ComicInfo và lỗi archive. */
class ComicArchiveTest {
    private val createdFiles = mutableListOf<File>()

    /** Dọn các file ZIP tạm tạo trong test. */
    @After
    fun tearDown() {
        createdFiles.forEach(File::delete)
    }

    /** Trang được sắp theo thứ tự tự nhiên, kể cả trong thư mục lồng, và bỏ qua metadata macOS/file ẩn. */
    @Test
    fun listsPagesInNaturalOrderAndSkipsJunkEntries() {
        val file = createZip(
            "Chap 10/1.jpg" to byteArrayOf(10),
            "Chap 2/10.jpg" to byteArrayOf(3),
            "Chap 2/2.jpg" to byteArrayOf(2),
            "Chap 2/1.PNG" to byteArrayOf(1),
            "__MACOSX/Chap 2/._1.PNG" to byteArrayOf(0),
            "Chap 2/.hidden.jpg" to byteArrayOf(0),
            "Chap 2/notes.txt" to byteArrayOf(0)
        )

        ComicArchive.open(file).use { archive ->
            assertEquals(
                listOf("Chap 2/1.PNG", "Chap 2/2.jpg", "Chap 2/10.jpg", "Chap 10/1.jpg"),
                archive.pages
            )
            assertArrayEquals(byteArrayOf(3), archive.readPageBytes(2))
            assertNull(archive.info)
        }
    }

    /** ComicInfo ở thư mục gốc được đọc, gồm cờ đọc phải sang trái. */
    @Test
    fun readsComicInfoMetadata() {
        val xml = """
            <?xml version="1.0"?>
            <ComicInfo>
              <Series>One &amp; Two</Series>
              <Number>3</Number>
              <Writer>Tác giả</Writer>
              <Manga>YesAndRightToLeft</Manga>
            </ComicInfo>
        """.trimIndent()
        val file = createZip("001.jpg" to byteArrayOf(1), "ComicInfo.xml" to xml.toByteArray())

        ComicArchive.open(file).use { archive ->
            val info = archive.info
            assertEquals("One & Two", info?.series)
            assertEquals("3", info?.number)
            assertTrue(info?.isRightToLeft == true)
            assertEquals("One & Two 3", info.resolveTitle("fallback"))
            assertEquals("Tác giả", info.resolveAuthor())
        }
    }

    /** Archive không có ảnh hoặc không phải ZIP bị từ chối bằng ComicArchiveException. */
    @Test
    fun rejectsArchiveWithoutPagesOrInvalidZip() {
        val textOnly = createZip("readme.txt" to byteArrayOf(1))
        val notZip = Files.createTempFile("comic", ".cbz").toFile().also {
            it.writeText("not a zip")
            createdFiles += it
        }

        assertThrowsArchive { ComicArchive.open(textOnly) }
        assertThrowsArchive { ComicArchive.open(notZip) }
    }

    /** ZIP chỉ chứa ảnh được nhận là truyện tranh, còn ZIP có tài liệu EPUB thì không. */
    @Test
    fun detectsComicArchiveButNotEpub() {
        val comic = createZip("a/01.webp" to byteArrayOf(1))
        val epub = createZip("OEBPS/cover.jpg" to byteArrayOf(1), "OEBPS/content.opf" to byteArrayOf(1))

        ZipFile(comic).use { assertTrue(ComicArchiveEntries.looksLikeComicArchive(it)) }
        ZipFile(epub).use { assertFalse(ComicArchiveEntries.looksLikeComicArchive(it)) }
    }

    /** Đọc trang sau khi archive đã đóng (rời màn hình giữa chừng) ném ComicArchiveException, không ném lỗi lạ. */
    @Test
    fun readingClosedArchiveThrowsArchiveException() {
        val file = createZip("001.jpg" to byteArrayOf(1))
        val archive = ComicArchive.open(file)
        archive.close()

        assertThrowsArchive { archive.readPageBytes(0) }
    }

    /** Tiêu đề rơi về tên file khi không có metadata. */
    @Test
    fun resolvesTitleFallback() {
        val missing: ComicInfo? = null
        assertEquals("file", missing.resolveTitle("file"))
        assertEquals("Title", ComicInfo(title = "Title", series = "S").resolveTitle("file"))
        assertEquals("S", ComicInfo(series = "S").resolveTitle("file"))
        assertEquals("", missing.resolveAuthor())
    }

    /**
     * Tạo file ZIP tạm với các entry cho trước.
     *
     * @param entries Cặp tên entry và nội dung.
     * @return File ZIP đã ghi.
     */
    private fun createZip(vararg entries: Pair<String, ByteArray>): File {
        val file = Files.createTempFile("comic", ".cbz").toFile()
        createdFiles += file
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return file
    }

    /**
     * Khẳng định khối lệnh ném [ComicArchiveException].
     *
     * @param block Khối lệnh cần kiểm tra.
     */
    private fun assertThrowsArchive(block: () -> Unit) {
        try {
            block()
        } catch (_: ComicArchiveException) {
            return
        }
        throw AssertionError("Expected ComicArchiveException")
    }
}
