package com.epubpro.core.reader.engine

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class EpubPackageStructureParserTest {

    @Test
    fun `resolveRelativePath correctly normalizes paths with dots`() {
        assertEquals("OEBPS/Text/chap1.xhtml", EpubPackageStructureParser.resolveRelativePath("OEBPS/Text", "chap1.xhtml"))
        assertEquals("OEBPS/Text/chap1.xhtml", EpubPackageStructureParser.resolveRelativePath("OEBPS/toc", "../Text/chap1.xhtml"))
        assertEquals("Text/chap1.xhtml", EpubPackageStructureParser.resolveRelativePath("", "Text/chap1.xhtml"))
        assertEquals("chap1.xhtml", EpubPackageStructureParser.resolveRelativePath("OEBPS", "/chap1.xhtml"))
        assertEquals("OEBPS/chap1.xhtml", EpubPackageStructureParser.resolveRelativePath("OEBPS", "/../OEBPS/./chap1.xhtml"))
    }

    @Test
    fun `parseStructure safely decodes plus sign and invalid percent in hrefs and matches directory boundary`() {
        val tempFile = File.createTempFile("sample_special_href", ".epub")
        try {
            ZipOutputStream(FileOutputStream(tempFile)).use { zos ->
                zos.putNextEntry(ZipEntry("META-INF/container.xml"))
                zos.write("""
                    <?xml version="1.0"?>
                    <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                        <rootfiles>
                            <rootfile full-path="OEBPS/package.opf" media-type="application/oebps-package+xml"/>
                        </rootfiles>
                    </container>
                """.trimIndent().toByteArray(Charsets.UTF_8))
                zos.closeEntry()

                zos.putNextEntry(ZipEntry("OEBPS/package.opf"))
                zos.write("""
                    <?xml version="1.0" encoding="utf-8"?>
                    <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                        <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                            <dc:title>Special Href Book</dc:title>
                        </metadata>
                        <manifest>
                            <item id="c_other" href="text/11.xhtml" media-type="application/xhtml+xml"/>
                            <item id="c1" href="1.xhtml?ver=1#top" media-type="application/xhtml+xml"/>
                            <item id="c2" href="c++_100%25.xhtml" media-type="application/xhtml+xml"/>
                        </manifest>
                        <spine>
                            <itemref idref="c1"/>
                            <itemref idref="c2"/>
                        </spine>
                    </package>
                """.trimIndent().toByteArray(Charsets.UTF_8))
                zos.closeEntry()

                zos.putNextEntry(ZipEntry("OEBPS/text/11.xhtml"))
                zos.write("<html><body><p>Wrong match if endsWith without slash</p></body></html>".toByteArray(Charsets.UTF_8))
                zos.closeEntry()

                zos.putNextEntry(ZipEntry("OEBPS/sub/1.xhtml"))
                zos.write("<html><body><p>Correct boundary match</p></body></html>".toByteArray(Charsets.UTF_8))
                zos.closeEntry()

                zos.putNextEntry(ZipEntry("OEBPS/c++_100%.xhtml"))
                zos.write("<html><body><p>Plus sign preserved</p></body></html>".toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }

            ZipFile(tempFile).use { zip ->
                val structure = EpubPackageStructureParser.parseStructure(zip)
                assertEquals(2, structure.orderedEntries.size)
                assertEquals("OEBPS/sub/1.xhtml", structure.orderedEntries[0].name)
                assertEquals("OEBPS/c++_100%.xhtml", structure.orderedEntries[1].name)
            }
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `naturalOrderComparator fallback handles numbers larger than Int_MAX_VALUE`() {
        val tempFile = File.createTempFile("sample_big_numbers", ".epub")
        try {
            ZipOutputStream(FileOutputStream(tempFile)).use { zos ->
                zos.putNextEntry(ZipEntry("chap_99999999999999999999.xhtml"))
                zos.write("<html><body><p>Big 2</p></body></html>".toByteArray(Charsets.UTF_8))
                zos.closeEntry()

                zos.putNextEntry(ZipEntry("chap_10000000000000000000.xhtml"))
                zos.write("<html><body><p>Big 1</p></body></html>".toByteArray(Charsets.UTF_8))
                zos.closeEntry()

                zos.putNextEntry(ZipEntry("chap_2.xhtml"))
                zos.write("<html><body><p>Small</p></body></html>".toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }

            ZipFile(tempFile).use { zip ->
                val structure = EpubPackageStructureParser.parseStructure(zip)
                assertEquals(
                    listOf(
                        "chap_2.xhtml",
                        "chap_10000000000000000000.xhtml",
                        "chap_99999999999999999999.xhtml"
                    ),
                    structure.orderedEntries.map { it.name }
                )
            }
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `parseStructure extracts titles from EPUB3 Navigation Document`() {
        val tempZip = createSampleEpub3WithNav()
        try {
            ZipFile(tempZip).use { zip ->
                val structure = EpubPackageStructureParser.parseStructure(zip)
                assertEquals("Test EPUB 3 Book", structure.title)
                assertEquals("Test Author", structure.author)
                assertEquals(2, structure.orderedEntries.size)
                assertEquals("Chương 1: Khởi đầu", structure.chapterTitles["OEBPS/text/c1.xhtml"])
                assertEquals("Chương 2: Kết thúc", structure.chapterTitles["OEBPS/text/c2.xhtml"])
            }
        } finally {
            tempZip.delete()
        }
    }

    @Test
    fun `parseStructure extracts titles from EPUB2 NCX Document`() {
        val tempZip = createSampleEpub2WithNcx()
        try {
            ZipFile(tempZip).use { zip ->
                val structure = EpubPackageStructureParser.parseStructure(zip)
                assertEquals("Test EPUB 2 Book", structure.title)
                assertEquals("Test Author 2", structure.author)
                assertEquals(2, structure.orderedEntries.size)
                assertEquals("Hồi 1", structure.chapterTitles["OEBPS/c1.xhtml"])
                assertEquals("Hồi 2", structure.chapterTitles["OEBPS/c2.xhtml"])
            }
        } finally {
            tempZip.delete()
        }
    }

    private fun createSampleEpub3WithNav(): File {
        val tempFile = File.createTempFile("sample_epub3", ".epub")
        ZipOutputStream(FileOutputStream(tempFile)).use { zos ->
            // 1. container.xml
            zos.putNextEntry(ZipEntry("META-INF/container.xml"))
            zos.write("""
                <?xml version="1.0"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                    <rootfiles>
                        <rootfile full-path="OEBPS/package.opf" media-type="application/oebps-package+xml"/>
                    </rootfiles>
                </container>
            """.trimIndent().toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            // 2. package.opf
            zos.putNextEntry(ZipEntry("OEBPS/package.opf"))
            zos.write("""
                <?xml version="1.0" encoding="utf-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="pub-id">
                    <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                        <dc:title>Test EPUB 3 Book</dc:title>
                        <dc:creator>Test Author</dc:creator>
                    </metadata>
                    <manifest>
                        <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                        <item id="c1" href="text/c1.xhtml" media-type="application/xhtml+xml"/>
                        <item id="c2" href="text/c2.xhtml" media-type="application/xhtml+xml"/>
                    </manifest>
                    <spine>
                        <itemref idref="c1"/>
                        <itemref idref="c2"/>
                    </spine>
                </package>
            """.trimIndent().toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            // 3. nav.xhtml
            zos.putNextEntry(ZipEntry("OEBPS/nav.xhtml"))
            zos.write("""
                <?xml version="1.0" encoding="utf-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
                <body>
                    <nav epub:type="toc">
                        <ol>
                            <li><a href="text/c1.xhtml">Chương 1: Khởi đầu</a></li>
                            <li><a href="text/c2.xhtml#section1">Chương 2: Kết thúc</a></li>
                        </ol>
                    </nav>
                </body>
                </html>
            """.trimIndent().toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            // 4. c1.xhtml
            zos.putNextEntry(ZipEntry("OEBPS/text/c1.xhtml"))
            zos.write("<html><body><p>Chap 1 content</p><p>Line 2</p><p>Line 3</p></body></html>".toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            // 5. c2.xhtml
            zos.putNextEntry(ZipEntry("OEBPS/text/c2.xhtml"))
            zos.write("<html><body><p>Chap 2 content</p><p>Line 2</p><p>Line 3</p></body></html>".toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        return tempFile
    }

    private fun createSampleEpub2WithNcx(): File {
        val tempFile = File.createTempFile("sample_epub2", ".epub")
        ZipOutputStream(FileOutputStream(tempFile)).use { zos ->
            // 1. container.xml
            zos.putNextEntry(ZipEntry("META-INF/container.xml"))
            zos.write("""
                <?xml version="1.0"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                    <rootfiles>
                        <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                    </rootfiles>
                </container>
            """.trimIndent().toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            // 2. content.opf
            zos.putNextEntry(ZipEntry("OEBPS/content.opf"))
            zos.write("""
                <?xml version="1.0" encoding="utf-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="2.0">
                    <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                        <dc:title>Test EPUB 2 Book</dc:title>
                        <dc:creator>Test Author 2</dc:creator>
                    </metadata>
                    <manifest>
                        <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                        <item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/>
                        <item id="c2" href="c2.xhtml" media-type="application/xhtml+xml"/>
                    </manifest>
                    <spine toc="ncx">
                        <itemref idref="c1"/>
                        <itemref idref="c2"/>
                    </spine>
                </package>
            """.trimIndent().toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            // 3. toc.ncx
            zos.putNextEntry(ZipEntry("OEBPS/toc.ncx"))
            zos.write("""
                <?xml version="1.0" encoding="utf-8"?>
                <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
                    <navMap>
                        <navPoint id="np-1" playOrder="1">
                            <navLabel><text>Hồi 1</text></navLabel>
                            <content src="c1.xhtml"/>
                        </navPoint>
                        <navPoint id="np-2" playOrder="2">
                            <navLabel><text>Hồi 2</text></navLabel>
                            <content src="c2.xhtml#part2"/>
                        </navPoint>
                    </navMap>
                </ncx>
            """.trimIndent().toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            // 4. c1.xhtml
            zos.putNextEntry(ZipEntry("OEBPS/c1.xhtml"))
            zos.write("<html><body><p>Chap 1</p><p>Line 2</p><p>Line 3</p></body></html>".toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            // 5. c2.xhtml
            zos.putNextEntry(ZipEntry("OEBPS/c2.xhtml"))
            zos.write("<html><body><p>Chap 2</p><p>Line 2</p><p>Line 3</p></body></html>".toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        return tempFile
    }
}
