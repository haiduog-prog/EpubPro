package com.epubpro.core.reader.engine

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.File
import java.nio.file.Files

class EpubEngineIntegrationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var mockContext: Context
    private lateinit var epubEngine: EpubEngine

    @Before
    fun setUp() {
        mockContext = mock(Context::class.java)
        val tempFilesDir = Files.createTempDirectory("epub_test_files").toFile().apply {
            deleteOnExit()
        }
        `when`(mockContext.filesDir).thenReturn(tempFilesDir)
        epubEngine = EpubEngine(mockContext)
    }

    @Test
    fun `parseEpubMetadata extracts title, author and total chapters correctly`() = runBlocking {
        val epubFile = File(tempFolder.root, "standard_book.epub")
        EpubTestArchiveFactory.createStandardEpub(
            targetFile = epubFile,
            title = "Hoàng Tử Bé",
            author = "Antoine de Saint-Exupéry",
            chapters = listOf(
                "Chương 1" to "<p>Nội dung chương 1</p>",
                "Chương 2" to "<p>Nội dung chương 2</p>",
                "Chương 3" to "<p>Nội dung chương 3</p>"
            )
        )

        val book = epubEngine.parseEpubMetadata(epubFile)
        assertEquals("Hoàng Tử Bé", book.title)
        assertEquals("Antoine de Saint-Exupéry", book.author)
        assertEquals(3, book.totalChapters)
        assertEquals(epubFile.absolutePath, book.filePath)
    }

    @Test
    fun `extractChapterHeaders extracts clean chapter titles without watermarks`() = runBlocking {
        val epubFile = File(tempFolder.root, "watermark_book.epub")
        EpubTestArchiveFactory.createStandardEpub(
            targetFile = epubFile,
            title = "Truyện Kiếm Hiệp",
            author = "Kim Dung",
            chapters = listOf(
                "Chương 1 - Mở Đầu - Created with truyenfull.vn" to "<p>Văn bản...</p>",
                "Chương 2 - Biến Cố - Converted by mkbyme" to "<p>Văn bản...</p>"
            )
        )

        val headers = epubEngine.extractChapterHeaders(epubFile)
        assertEquals(2, headers.size)
        assertEquals("Chương 1 - Mở Đầu", headers[0].title)
        assertEquals("Chương 2 - Biến Cố", headers[1].title)
    }

    @Test
    fun `loadChapterHtml loads and normalizes chapter content`() = runBlocking {
        val epubFile = File(tempFolder.root, "content_book.epub")
        EpubTestArchiveFactory.createStandardEpub(
            targetFile = epubFile,
            title = "Test Sách",
            author = "Tác Giả",
            chapters = listOf(
                "Chương 1" to "Dòng một<br/>Dòng hai<br/>Dòng ba"
            )
        )

        val html = epubEngine.loadChapterHtml(epubFile, "OEBPS/text/chapter_1.xhtml")
        assertTrue(html.contains("<p>Dòng một</p>"))
        assertTrue(html.contains("<p>Dòng hai</p>"))
        assertTrue(html.contains("<p>Dòng ba</p>"))
    }

    @Test
    fun `legacy epub without container_xml falls back to natural numeric sort`() = runBlocking {
        val epubFile = File(tempFolder.root, "legacy_book.epub")
        EpubTestArchiveFactory.createLegacyEpubWithoutContainerXml(
            targetFile = epubFile,
            title = "Sách Cũ",
            chapters = listOf(
                "chap10.xhtml" to "<p>Chương Mười</p>",
                "chap2.xhtml" to "<p>Chương Hai</p>",
                "chap1.xhtml" to "<p>Chương Một</p>"
            )
        )

        val headers = epubEngine.extractChapterHeaders(epubFile)
        assertEquals(3, headers.size)
        // Natural numeric sort should order: chap1 -> chap2 -> chap10
        assertEquals("chap1.xhtml", headers[0].entryName)
        assertEquals("chap2.xhtml", headers[1].entryName)
        assertEquals("chap10.xhtml", headers[2].entryName)
    }
}
