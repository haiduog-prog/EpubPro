package com.epubpro.core.webnovel

import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.jsoup.Jsoup
import org.jsoup.parser.Parser

/**
 * Metadata của EPUB được tạo từ truyện web.
 *
 * @property title Tên truyện.
 * @property author Tác giả, rỗng nếu không rõ.
 * @property language Mã ngôn ngữ BCP 47 (ví dụ `vi`, `zh`).
 * @property sourceUrl URL nguồn (ghi vào `dc:source`).
 * @property identifier ID xuất bản; mặc định là UUID ngẫu nhiên.
 */
data class EpubMetadata(
    val title: String,
    val author: String = "",
    val language: String = "vi",
    val sourceUrl: String? = null,
    val identifier: String = "urn:uuid:${UUID.randomUUID()}"
)

/**
 * Ghi EPUB 3 tối giản (kèm `toc.ncx` cho Reader cũ) từ danh sách chương văn bản thuần.
 *
 * File được ghi vào file tạm rồi đổi tên, nên không bao giờ để lại EPUB dở dang ở đường dẫn đích.
 */
object EpubWriter {

    /**
     * Ghi EPUB ra file.
     *
     * @param target File EPUB đích.
     * @param metadata Metadata xuất bản.
     * @param chapters Các chương theo thứ tự; phải có ít nhất một chương.
     * @return File đích đã ghi.
     * @throws IllegalArgumentException Nếu danh sách chương rỗng.
     * @throws java.io.IOException Nếu không ghi được file.
     */
    fun write(target: File, metadata: EpubMetadata, chapters: List<WebNovelChapter>): File {
        require(chapters.isNotEmpty()) { "EPUB phải có ít nhất một chương" }
        val temporary = File(target.parentFile, "${target.name}.part")
        try {
            ZipOutputStream(FileOutputStream(temporary)).use { zip ->
                writeMimetype(zip)
                zip.putText("META-INF/container.xml", containerXml())
                zip.putText("OEBPS/style.css", STYLESHEET)
                chapters.forEachIndexed { index, chapter ->
                    zip.putText("OEBPS/${chapterPath(index)}", chapterXhtml(chapter, metadata.language))
                }
                zip.putText("OEBPS/nav.xhtml", navXhtml(metadata, chapters))
                zip.putText("OEBPS/toc.ncx", tocNcx(metadata, chapters))
                zip.putText("OEBPS/content.opf", contentOpf(metadata, chapters))
            }
            if (target.exists() && !target.delete()) throw java.io.IOException("Không thay được EPUB cũ")
            if (!temporary.renameTo(target)) throw java.io.IOException("Không lưu được EPUB")
            return target
        } finally {
            temporary.delete()
        }
    }

    /**
     * Đọc lại các chương từ EPUB do chính [write] tạo ra, để nối thêm chương mới rồi ghi lại.
     *
     * Chỉ dùng cho EPUB truyện web của ứng dụng (cấu trúc `OEBPS/text/chapter_XXXX.xhtml` với `<h2>` + `<p>`).
     *
     * @param source File EPUB đã tạo.
     * @return Các chương theo thứ tự.
     * @throws IllegalStateException Nếu file không có chương đúng cấu trúc.
     */
    fun readChapters(source: File): List<WebNovelChapter> = ZipFile(source).use { zip ->
        val entries = zip.entries().asSequence()
            .filter { it.name.startsWith(CHAPTER_DIRECTORY) && it.name.endsWith(".xhtml") }
            .sortedWith(compareBy({ it.name.length }, { it.name }))
            .toList()
        check(entries.isNotEmpty()) { "EPUB không có chương truyện web" }
        entries.map { entry ->
            val document = zip.getInputStream(entry).use { Jsoup.parse(it, "UTF-8", "", Parser.xmlParser()) }
            WebNovelChapter(
                title = document.selectFirst("h2")?.text().orEmpty(),
                paragraphs = document.select("p").map { it.text() }
            )
        }
    }

    /**
     * Ghi entry `mimetype` không nén, đứng đầu archive theo chuẩn OCF.
     *
     * @param zip Luồng ZIP đích.
     */
    private fun writeMimetype(zip: ZipOutputStream) {
        val bytes = "application/epub+zip".toByteArray(Charsets.US_ASCII)
        val entry = ZipEntry("mimetype").apply {
            method = ZipEntry.STORED
            size = bytes.size.toLong()
            compressedSize = bytes.size.toLong()
            crc = CRC32().apply { update(bytes) }.value
        }
        zip.putNextEntry(entry)
        zip.write(bytes)
        zip.closeEntry()
    }

    /**
     * Ghi một entry văn bản UTF-8.
     *
     * @receiver Luồng ZIP đích.
     * @param name Đường dẫn entry.
     * @param content Nội dung.
     */
    private fun ZipOutputStream.putText(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    /**
     * Đường dẫn tương đối (từ OEBPS) của file chương.
     *
     * @param index Chỉ số chương 0-based.
     * @return Đường dẫn dạng `text/chapter_0001.xhtml`.
     */
    private fun chapterPath(index: Int): String = "text/chapter_%04d.xhtml".format(Locale.US, index + 1)

    private const val CHAPTER_DIRECTORY = "OEBPS/text/chapter_"

    /** @return Nội dung `META-INF/container.xml`. */
    private fun containerXml(): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
          <rootfiles>
            <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
          </rootfiles>
        </container>
    """.trimIndent()

    /**
     * Tạo XHTML cho một chương.
     *
     * @param chapter Chương cần ghi.
     * @param language Mã ngôn ngữ.
     * @return Tài liệu XHTML.
     */
    private fun chapterXhtml(chapter: WebNovelChapter, language: String): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<!DOCTYPE html>\n")
        append("<html xmlns=\"http://www.w3.org/1999/xhtml\" xml:lang=\"${escape(language)}\" lang=\"${escape(language)}\">\n")
        append("<head><title>${escape(chapter.title)}</title><link rel=\"stylesheet\" type=\"text/css\" href=\"../style.css\"/></head>\n")
        append("<body>\n<h2>${escape(chapter.title)}</h2>\n")
        chapter.paragraphs.forEach { append("<p>").append(escape(it)).append("</p>\n") }
        append("</body>\n</html>\n")
    }

    /**
     * Tạo tài liệu điều hướng EPUB 3.
     *
     * @param metadata Metadata xuất bản.
     * @param chapters Các chương.
     * @return Nội dung `nav.xhtml`.
     */
    private fun navXhtml(metadata: EpubMetadata, chapters: List<WebNovelChapter>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!DOCTYPE html>\n")
        append("<html xmlns=\"http://www.w3.org/1999/xhtml\" xmlns:epub=\"http://www.idpf.org/2007/ops\">\n")
        append("<head><title>${escape(metadata.title)}</title></head>\n<body>\n")
        append("<nav epub:type=\"toc\" id=\"toc\"><ol>\n")
        chapters.forEachIndexed { index, chapter ->
            append("<li><a href=\"${chapterPath(index)}\">${escape(chapter.title)}</a></li>\n")
        }
        append("</ol></nav>\n</body>\n</html>\n")
    }

    /**
     * Tạo mục lục NCX cho Reader chỉ hỗ trợ EPUB 2.
     *
     * @param metadata Metadata xuất bản.
     * @param chapters Các chương.
     * @return Nội dung `toc.ncx`.
     */
    private fun tocNcx(metadata: EpubMetadata, chapters: List<WebNovelChapter>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<ncx xmlns=\"http://www.daisy.org/z3986/2005/ncx/\" version=\"2005-1\">\n")
        append("<head><meta name=\"dtb:uid\" content=\"${escape(metadata.identifier)}\"/></head>\n")
        append("<docTitle><text>${escape(metadata.title)}</text></docTitle>\n<navMap>\n")
        chapters.forEachIndexed { index, chapter ->
            append("<navPoint id=\"nav${index + 1}\" playOrder=\"${index + 1}\">")
            append("<navLabel><text>${escape(chapter.title)}</text></navLabel>")
            append("<content src=\"${chapterPath(index)}\"/></navPoint>\n")
        }
        append("</navMap>\n</ncx>\n")
    }

    /**
     * Tạo package document.
     *
     * @param metadata Metadata xuất bản.
     * @param chapters Các chương.
     * @return Nội dung `content.opf`.
     */
    private fun contentOpf(metadata: EpubMetadata, chapters: List<WebNovelChapter>): String = buildString {
        val modified = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date())
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\" unique-identifier=\"bookid\">\n")
        append("<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">\n")
        append("<dc:identifier id=\"bookid\">${escape(metadata.identifier)}</dc:identifier>\n")
        append("<dc:title>${escape(metadata.title)}</dc:title>\n")
        if (metadata.author.isNotBlank()) append("<dc:creator>${escape(metadata.author)}</dc:creator>\n")
        append("<dc:language>${escape(metadata.language)}</dc:language>\n")
        metadata.sourceUrl?.let { append("<dc:source>${escape(it)}</dc:source>\n") }
        append("<meta property=\"dcterms:modified\">$modified</meta>\n")
        append("</metadata>\n<manifest>\n")
        append("<item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>\n")
        append("<item id=\"ncx\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\"/>\n")
        append("<item id=\"css\" href=\"style.css\" media-type=\"text/css\"/>\n")
        chapters.indices.forEach { index ->
            append("<item id=\"c${index + 1}\" href=\"${chapterPath(index)}\" media-type=\"application/xhtml+xml\"/>\n")
        }
        append("</manifest>\n<spine toc=\"ncx\">\n")
        chapters.indices.forEach { index -> append("<itemref idref=\"c${index + 1}\"/>\n") }
        append("</spine>\n</package>\n")
    }

    /**
     * Escape ký tự đặc biệt XML và loại ký tự điều khiển không hợp lệ trong XML 1.0.
     *
     * @param value Chuỗi gốc.
     * @return Chuỗi an toàn cho XML.
     */
    internal fun escape(value: String): String = buildString(value.length) {
        value.forEach { char ->
            when {
                char == '&' -> append("&amp;")
                char == '<' -> append("&lt;")
                char == '>' -> append("&gt;")
                char == '"' -> append("&quot;")
                char == '\'' -> append("&apos;")
                char < ' ' && char != '\t' && char != '\n' && char != '\r' -> Unit
                char == '￾' || char == '￿' -> Unit
                else -> append(char)
            }
        }
    }

    private const val STYLESHEET = "h2 { text-align: center; margin: 1em 0; }\np { margin: 0 0 0.8em 0; text-indent: 1.5em; }\n"
}
