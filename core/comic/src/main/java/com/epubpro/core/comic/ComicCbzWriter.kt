package com.epubpro.core.comic

import java.io.File
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Một chương truyện tranh đã tải ảnh về thư mục tạm, chờ đóng gói vào CBZ.
 *
 * @property title Tên chương dùng đặt tên thư mục trong archive.
 * @property pages File ảnh theo thứ tự trang; tên file đã có phần mở rộng đúng định dạng ảnh.
 */
data class DownloadedComicChapter(
    val title: String,
    val pages: List<File>
)

/**
 * Đóng gói ảnh truyện tranh tải từ web thành file CBZ đọc được bằng Reader truyện tranh.
 *
 * Mỗi chương là một thư mục `0001 Tên chương/` chứa `0001.jpg`, `0002.png`...; số thứ tự đệm 0 giúp
 * [NaturalOrderComparator] giữ đúng thứ tự đọc khi nối thêm chương vào archive cũ.
 */
object ComicCbzWriter {
    private const val COMIC_INFO_ENTRY = "ComicInfo.xml"
    private const val MAX_FOLDER_TITLE_CHARS = 60
    private val INVALID_NAME_CHARS = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")

    /**
     * Ghi CBZ mới gồm toàn bộ trang của archive cũ (nếu có) rồi tới các chương vừa tải, kèm `ComicInfo.xml`.
     * Ảnh đã nén sẵn nên ghi không nén lại để tiết kiệm CPU; entry cũ được chép theo luồng, không nạp hết vào RAM.
     *
     * @param target File CBZ đích (bị ghi đè nếu đã tồn tại).
     * @param existing CBZ cũ cần giữ nguyên trang, null khi tạo truyện mới.
     * @param chapters Các chương mới theo thứ tự đọc.
     * @param startChapterNumber Số thứ tự (từ 1) của chương đầu tiên trong [chapters] trong cả bộ truyện.
     * @param title Tên bộ truyện ghi vào `ComicInfo.xml`.
     * @param sourceUrl URL nguồn ghi vào `ComicInfo.xml`, null nếu không có.
     * @throws java.io.IOException Khi đọc archive cũ hoặc ghi file đích thất bại.
     */
    fun write(
        target: File,
        existing: File?,
        chapters: List<DownloadedComicChapter>,
        startChapterNumber: Int,
        title: String,
        sourceUrl: String?
    ) {
        ZipOutputStream(target.outputStream().buffered()).use { zip ->
            zip.setLevel(Deflater.NO_COMPRESSION)
            existing?.let { ZipFile(it) }?.use { old ->
                old.entries().asSequence()
                    .filter { !it.isDirectory && !it.name.substringAfterLast('/').equals(COMIC_INFO_ENTRY, true) }
                    .forEach { entry ->
                        zip.putNextEntry(ZipEntry(entry.name))
                        old.getInputStream(entry).use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
            }
            chapters.forEachIndexed { index, chapter ->
                val folder = chapterFolderName(startChapterNumber + index, chapter.title)
                chapter.pages.forEach { page ->
                    zip.putNextEntry(ZipEntry("$folder/${page.name}"))
                    page.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            zip.putNextEntry(ZipEntry(COMIC_INFO_ENTRY))
            zip.write(comicInfoXml(title, sourceUrl).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }

    /**
     * Tạo tên thư mục chương an toàn cho mọi hệ thống file và không bị coi là thư mục ẩn.
     *
     * @param number Số thứ tự chương trong bộ (từ 1).
     * @param title Tên chương lấy từ trang nguồn.
     * @return Tên thư mục dạng `0001 Tên chương`.
     */
    fun chapterFolderName(number: Int, title: String): String {
        val cleaned = title.replace(INVALID_NAME_CHARS, " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(MAX_FOLDER_TITLE_CHARS)
            .trim()
            .trimStart('.')
        val prefix = "%04d".format(number)
        return if (cleaned.isEmpty()) prefix else "$prefix $cleaned"
    }

    /**
     * Nhận diện định dạng ảnh từ các byte đầu file, để đặt đúng phần mở rộng và loại bỏ phản hồi không phải ảnh
     * (ví dụ trang HTML báo lỗi trả về thay cho ảnh).
     *
     * @param header Các byte đầu của dữ liệu tải về (tối thiểu 12 byte để nhận diện WebP/AVIF).
     * @return Phần mở rộng (`jpg`, `png`, `webp`, `gif`, `bmp`, `avif`), hoặc null nếu không phải ảnh được hỗ trợ.
     */
    fun imageExtension(header: ByteArray): String? {
        fun at(index: Int): Int = header.getOrNull(index)?.toInt()?.and(0xFF) ?: -1
        fun ascii(from: Int, text: String): Boolean = text.indices.all { at(from + it) == text[it].code }
        return when {
            at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF -> "jpg"
            at(0) == 0x89 && ascii(1, "PNG") -> "png"
            ascii(0, "RIFF") && ascii(8, "WEBP") -> "webp"
            ascii(0, "GIF8") -> "gif"
            ascii(0, "BM") && header.size > 14 -> "bmp"
            ascii(4, "ftypavi") -> "avif"
            else -> null
        }
    }

    /**
     * Tạo nội dung `ComicInfo.xml` tối thiểu để Reader và ứng dụng khác hiển thị đúng tên truyện.
     *
     * @param title Tên bộ truyện.
     * @param sourceUrl URL nguồn, null nếu không có.
     * @return Chuỗi XML.
     */
    private fun comicInfoXml(title: String, sourceUrl: String?): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<ComicInfo>\n")
        append("  <Title>").append(escape(title)).append("</Title>\n")
        append("  <Series>").append(escape(title)).append("</Series>\n")
        sourceUrl?.let { append("  <Web>").append(escape(it)).append("</Web>\n") }
        append("</ComicInfo>\n")
    }

    /**
     * Escape ký tự đặc biệt XML.
     *
     * @param value Chuỗi gốc.
     * @return Chuỗi an toàn để đặt trong nội dung thẻ XML.
     */
    private fun escape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
