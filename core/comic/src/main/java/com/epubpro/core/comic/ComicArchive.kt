package com.epubpro.core.comic

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Giới hạn an toàn khi đọc archive truyện tranh do người dùng nhập.
 */
object ComicReadLimits {
    /** Dung lượng tối đa của một file CBZ được nhập vào thư viện. */
    const val MAX_ARCHIVE_BYTES: Long = 1024L * 1024L * 1024L

    /** Dung lượng giải nén tối đa của một trang ảnh, chặn zip bomb. */
    const val MAX_PAGE_BYTES: Long = 64L * 1024L * 1024L

    /** Dung lượng tối đa của `ComicInfo.xml`. */
    const val MAX_COMIC_INFO_BYTES: Long = 256L * 1024L

    /** Số trang tối đa được nhận trong một archive. */
    const val MAX_PAGES: Int = 10_000
}

/**
 * Lỗi khi archive không đọc được hoặc không chứa trang ảnh hợp lệ.
 *
 * @param message Mô tả kỹ thuật phục vụ chẩn đoán, không hiển thị trực tiếp cho người dùng.
 * @param cause Lỗi gốc nếu có.
 */
class ComicArchiveException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/**
 * Quy tắc nhận diện entry trang ảnh trong archive truyện tranh.
 */
object ComicArchiveEntries {
    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "avif", "heic", "heif")
    private const val COMIC_INFO_NAME = "comicinfo.xml"

    /**
     * Kiểm tra tên entry có phải trang ảnh hiển thị được không.
     *
     * Bỏ qua thư mục, metadata macOS (`__MACOSX/`, `._*`) và mọi file/thư mục ẩn.
     *
     * @param name Đường dẫn entry trong ZIP.
     * @return true nếu entry là ảnh trang truyện.
     */
    fun isPageName(name: String): Boolean {
        if (name.endsWith('/')) return false
        val segments = name.replace('\\', '/').split('/')
        if (segments.any { it.startsWith('.') || it.equals("__MACOSX", ignoreCase = true) }) return false
        val extension = segments.last().substringAfterLast('.', "").lowercase()
        return extension in IMAGE_EXTENSIONS
    }

    /**
     * Liệt kê các trang ảnh theo thứ tự đọc tự nhiên.
     *
     * @param zip Archive đã mở.
     * @return Tên entry đã sắp xếp; rỗng nếu không có ảnh.
     */
    fun listPages(zip: ZipFile): List<String> =
        zip.entries().asSequence()
            .filter { !it.isDirectory && isPageName(it.name) }
            .map(ZipEntry::getName)
            .sortedWith(NaturalOrderComparator)
            .toList()

    /**
     * Tìm `ComicInfo.xml`, ưu tiên file ở thư mục gốc.
     *
     * @param zip Archive đã mở.
     * @return Entry metadata hoặc null nếu không có.
     */
    fun findComicInfo(zip: ZipFile): ZipEntry? =
        zip.entries().asSequence()
            .filter { !it.isDirectory && it.name.substringAfterLast('/').lowercase() == COMIC_INFO_NAME }
            .minByOrNull { it.name.count { char -> char == '/' } }

    /**
     * Nhận diện ZIP chỉ chứa ảnh là truyện tranh (dùng khi file không có phần mở rộng `.cbz`).
     *
     * EPUB cũng là ZIP chứa ảnh nên mọi archive có tài liệu HTML/OPF đều bị loại.
     *
     * @param zip Archive đã mở.
     * @return true nếu archive có ít nhất một trang ảnh và không chứa tài liệu chữ.
     */
    fun looksLikeComicArchive(zip: ZipFile): Boolean {
        var hasPage = false
        for (entry in zip.entries()) {
            if (entry.isDirectory) continue
            val extension = entry.name.substringAfterLast('.', "").lowercase()
            if (extension in setOf("opf", "xhtml", "html", "htm", "ncx")) return false
            if (!hasPage && isPageName(entry.name)) hasPage = true
        }
        return hasPage
    }
}

/**
 * Phiên đọc một archive truyện tranh, giữ [ZipFile] mở để đọc trang ngẫu nhiên.
 *
 * Phải gọi [close] khi rời màn hình đọc. Việc đọc entry từ nhiều luồng được [ZipFile] đồng bộ nội bộ.
 *
 * @property file File CBZ nguồn.
 * @property pages Tên entry các trang theo thứ tự đọc.
 * @property info Metadata `ComicInfo.xml`, null nếu archive không có.
 */
class ComicArchive private constructor(
    val file: File,
    private val zip: ZipFile,
    val pages: List<String>,
    val info: ComicInfo?
) : Closeable {

    /** Số trang ảnh trong archive. */
    val pageCount: Int get() = pages.size

    /**
     * Đọc toàn bộ byte của một trang, có giới hạn dung lượng.
     *
     * @param index Chỉ số trang 0-based.
     * @return Nội dung ảnh nén (JPEG/PNG/WebP...).
     * @throws ComicArchiveException Nếu chỉ số sai, entry mất hoặc vượt giới hạn.
     */
    fun readPageBytes(index: Int): ByteArray {
        val name = pages.getOrNull(index) ?: throw ComicArchiveException("Trang $index nằm ngoài phạm vi")
        try {
            val entry = zip.getEntry(name) ?: throw ComicArchiveException("Không tìm thấy entry trang $index")
            if (entry.size > ComicReadLimits.MAX_PAGE_BYTES) throw ComicArchiveException("Trang $index vượt giới hạn dung lượng")
            return zip.getInputStream(entry).use { readBounded(it, ComicReadLimits.MAX_PAGE_BYTES) }
        } catch (error: ComicArchiveException) {
            throw error
        } catch (error: Exception) {
            // ZipFile đã đóng (rời màn hình khi đang đọc) hoặc entry hỏng: gói về một kiểu lỗi duy nhất.
            throw ComicArchiveException("Không đọc được trang $index", error)
        }
    }

    /** Đóng archive và giải phóng file descriptor. */
    override fun close() {
        zip.close()
    }

    companion object {
        /**
         * Mở archive, liệt kê trang và đọc `ComicInfo.xml`.
         *
         * @param file File CBZ/ZIP.
         * @return Phiên đọc đã sẵn sàng; caller chịu trách nhiệm đóng.
         * @throws ComicArchiveException Nếu file không phải ZIP hợp lệ, không có trang hoặc vượt giới hạn.
         */
        fun open(file: File): ComicArchive {
            if (!file.isFile) throw ComicArchiveException("Không tìm thấy file truyện tranh")
            if (file.length() > ComicReadLimits.MAX_ARCHIVE_BYTES) {
                throw ComicArchiveException("File truyện tranh vượt giới hạn dung lượng")
            }
            val zip = try {
                ZipFile(file)
            } catch (error: Exception) {
                throw ComicArchiveException("File truyện tranh không phải ZIP hợp lệ", error)
            }
            try {
                val pages = ComicArchiveEntries.listPages(zip)
                if (pages.isEmpty()) throw ComicArchiveException("Archive không chứa trang ảnh")
                if (pages.size > ComicReadLimits.MAX_PAGES) throw ComicArchiveException("Archive có quá nhiều trang")
                val info = ComicArchiveEntries.findComicInfo(zip)
                    ?.takeIf { it.size <= ComicReadLimits.MAX_COMIC_INFO_BYTES }
                    ?.let { entry ->
                        runCatching {
                            val bytes = zip.getInputStream(entry).use { readBounded(it, ComicReadLimits.MAX_COMIC_INFO_BYTES) }
                            ComicInfoParser.parse(bytes.toString(Charsets.UTF_8))
                        }.getOrNull()
                    }
                return ComicArchive(file, zip, pages, info)
            } catch (error: Throwable) {
                zip.close()
                throw error
            }
        }

        /**
         * Đọc luồng vào bộ nhớ và dừng khi vượt giới hạn, không tin vào kích thước khai báo trong ZIP.
         *
         * @param input Luồng nguồn.
         * @param limit Số byte tối đa.
         * @return Nội dung đã đọc.
         * @throws ComicArchiveException Nếu nội dung vượt [limit].
         */
        private fun readBounded(input: InputStream, limit: Long): ByteArray {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                total += read
                if (total > limit) throw ComicArchiveException("Entry vượt giới hạn dung lượng")
                output.write(buffer, 0, read)
            }
            return output.toByteArray()
        }
    }
}

/**
 * Tạo tên hiển thị cho truyện từ metadata, rơi về tên file khi thiếu thông tin.
 *
 * @param fallback Tên dùng khi không có `Title`/`Series` (thường là tên file gốc không đuôi).
 * @return Tiêu đề lưu vào thư viện.
 */
fun ComicInfo?.resolveTitle(fallback: String): String {
    val info = this ?: return fallback
    info.title?.let { return it }
    val series = info.series ?: return fallback
    return info.number?.let { "$series $it" } ?: series
}

/**
 * Lấy tên tác giả ưu tiên người viết kịch bản, sau đó là họa sĩ.
 *
 * @return Tên tác giả hoặc chuỗi rỗng khi metadata không có.
 */
fun ComicInfo?.resolveAuthor(): String = this?.writer ?: this?.penciller ?: ""
