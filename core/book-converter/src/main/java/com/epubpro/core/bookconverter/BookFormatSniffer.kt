package com.epubpro.core.bookconverter

import com.epubpro.core.comic.ComicArchiveEntries
import com.epubpro.domain.model.BookSourceFormat
import java.io.File
import java.io.FileInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Nhận diện các định dạng ebook được phép chuyển đổi cục bộ.
 *
 * Tên file chỉ là gợi ý; libmobi tiếp tục kiểm tra header và cấu trúc thực tế.
 */
@Singleton
class BookFormatSniffer @Inject constructor() {
    /**
     * Xác định định dạng nguồn từ phần mở rộng hoặc header Palm Database.
     *
     * @param file File nguồn cần nhận diện.
     * @return Định dạng được hỗ trợ hoặc null nếu không thuộc phạm vi converter.
     */
    fun sniff(file: File): BookSourceFormat? = when (file.extension.lowercase()) {
        "epub" -> BookSourceFormat.EPUB
        "prc" -> BookSourceFormat.PRC
        "mobi" -> BookSourceFormat.MOBI
        "azw3" -> BookSourceFormat.AZW3
        "cbz" -> BookSourceFormat.CBZ
        else -> detectPalmDatabaseFormat(file) ?: detectZipFormat(file)
    }

    /**
     * Nhận diện file ZIP không có phần mở rộng tin cậy là EPUB hay truyện tranh CBZ.
     *
     * EPUB được ưu tiên vì EPUB cũng chứa ảnh; ZIP chỉ gồm ảnh (không có HTML/OPF) được coi là CBZ.
     *
     * @param file File nguồn cần kiểm tra.
     * @return [BookSourceFormat.EPUB], [BookSourceFormat.CBZ] hoặc null nếu không nhận diện được.
     */
    private fun detectZipFormat(file: File): BookSourceFormat? =
        detectEpubFormat(file) ?: detectComicFormat(file)

    /**
     * Nhận diện archive ảnh truyện tranh dựa trên danh sách entry.
     *
     * @param file File nguồn cần kiểm tra.
     * @return [BookSourceFormat.CBZ] nếu ZIP chỉ chứa trang ảnh, ngược lại null.
     */
    private fun detectComicFormat(file: File): BookSourceFormat? {
        if (!file.isFile || file.length() < 22L) return null
        return runCatching {
            java.util.zip.ZipFile(file).use { zip ->
                if (ComicArchiveEntries.looksLikeComicArchive(zip)) BookSourceFormat.CBZ else null
            }
        }.getOrNull()
    }

    /**
     * Nhận diện file EPUB dựa trên cấu trúc file nén ZIP và các entry đặc trưng (mimetype hoặc container.xml).
     *
     * Phương thức này phục vụ trường hợp file EPUB được mở từ ứng dụng bên ngoài bị gán tên tạm hoặc thiếu extension.
     *
     * @param file File nguồn cần kiểm tra cấu trúc.
     * @return [BookSourceFormat.EPUB] nếu là file EPUB hợp lệ, ngược lại trả về null.
     */
    private fun detectEpubFormat(file: File): BookSourceFormat? {
        if (!file.isFile || file.length() < 58L) return null
        return runCatching {
            java.util.zip.ZipFile(file).use { zip ->
                val hasMimetype = zip.getEntry("mimetype") != null
                val hasContainer = zip.getEntry("META-INF/container.xml") != null
                if (hasMimetype || hasContainer) BookSourceFormat.EPUB else null
            }
        }.getOrNull()
    }

    /**
     * Kiểm tra nhanh file có header Palm Database ebook hợp lý hay không.
     *
     * @param file File nguồn.
     * @return true khi header có thể là MOBI hoặc PalmDOC/PRC.
     */
    fun hasPalmDatabaseHeader(file: File): Boolean {
        return detectPalmDatabaseFormat(file) != null
    }

    /**
     * Nhận diện định dạng Palm Database dựa trên cặp trường type/creator trong header.
     *
     * @param file File nguồn cần kiểm tra.
     * @return Định dạng tương ứng hoặc null nếu header không phải PDB ebook hỗ trợ.
     */
    private fun detectPalmDatabaseFormat(file: File): BookSourceFormat? {
        if (!file.isFile || file.length() < 78L) return null
        return runCatching {
            FileInputStream(file).use { input ->
                val header = ByteArray(78)
                var offset = 0
                while (offset < header.size) {
                    val read = input.read(header, offset, header.size - offset)
                    if (read <= 0) return@use null
                    offset += read
                }
                when {
                    header.copyOfRange(60, 68).contentEquals("BOOKMOBI".toByteArray(Charsets.US_ASCII)) ->
                        BookSourceFormat.MOBI
                    header.copyOfRange(60, 68).contentEquals("TEXtREAd".toByteArray(Charsets.US_ASCII)) ->
                        BookSourceFormat.PRC
                    else -> null
                }
            }
        }.getOrNull()
    }
}
