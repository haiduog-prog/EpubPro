package com.epubpro.core.storage

import android.content.Context
import android.net.Uri
import com.epubpro.core.bookconverter.MobiEpubConverter
import com.epubpro.core.comic.ComicReadLimits
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EpubStorageManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val booksDir = File(context.filesDir, "books").apply { if (!exists()) mkdirs() }
    private val coversDir = File(context.filesDir, "covers").apply { if (!exists()) mkdirs() }
    private val aiCacheDir = File(context.filesDir, "ai_chapters").apply { if (!exists()) mkdirs() }

    /**
     * Copy EPUB from Uri (SAF) into local internal app storage for secure offline access
     */
    fun importEpubFromUri(uri: Uri, originalFileName: String?): File {
        val fileId = UUID.randomUUID().toString()
        val extension = safeExtension(originalFileName, "epub")
        val targetFile = File(booksDir, "$fileId.$extension")

        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(targetFile).use { output ->
                input.copyTo(output)
            }
        } ?: throw IllegalStateException("Could not open InputStream for URI: $uri")

        return targetFile
    }

    /**
     * Sao lưu file PRC/MOBI/AZW3 vào storage nội bộ với giới hạn 100 MiB.
     *
     * File nguồn được giữ lại trong lúc Worker chuyển đổi để có thể retry sau process death.
     *
     * @param uri URI nguồn từ Storage Access Framework.
     * @param originalFileName Tên gốc dùng để giữ phần mở rộng phục vụ nhận diện định dạng.
     * @return File nguồn đã sao lưu trong thư mục books.
     * @throws IllegalStateException Nếu URI không đọc được hoặc vượt giới hạn dung lượng.
     */
    fun importLocalBookSource(uri: Uri, originalFileName: String?): File {
        val fileId = UUID.randomUUID().toString()
        val extension = safeExtension(originalFileName, "")
            .ifBlank { safeExtension(Uri.decode(uri.lastPathSegment), "") }
            .ifBlank {
                when (context.contentResolver.getType(uri)?.lowercase()?.substringBefore(';')?.trim()) {
                    "application/epub+zip",
                    "application/epub",
                    "application/x-zip-compressed-epub" -> "epub"
                    "application/x-mobipocket-ebook" -> "mobi"
                    "application/vnd.amazon.mobi8-ebook" -> "azw3"
                    "application/x-palm-database" -> "prc"
                    "application/vnd.comicbook+zip", "application/x-cbz" -> "cbz"
                    "application/zip", "application/x-zip-compressed" -> "zip"
                    else -> "bin"
                }
            }
        val targetFile = File(booksDir, "$fileId.$extension")
        // Archive truyện tranh thường lớn hơn nhiều so với ebook chữ nên dùng giới hạn riêng.
        val maxBytes = if (extension == "cbz" || extension == "zip") {
            ComicReadLimits.MAX_ARCHIVE_BYTES
        } else {
            MobiEpubConverter.MAX_INPUT_BYTES
        }
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(targetFile).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        total += read
                        if (total > maxBytes) throw BookFileTooLargeException(maxBytes)
                        output.write(buffer, 0, read)
                    }
                    output.fd.sync()
                }
            } ?: throw IllegalStateException("Không thể mở file ebook từ URI: $uri")
            return targetFile
        } catch (error: Throwable) {
            targetFile.delete()
            throw error
        }
    }

    /**
     * Tạo đường dẫn EPUB mới trong storage nội bộ.
     *
     * @return File đích chưa có dữ liệu, dùng cho commit atomically sau chuyển đổi.
     */
    fun createConvertedEpubFile(): File = File(booksDir, "${UUID.randomUUID()}.epub")

    /**
     * Tạo đường dẫn CBZ mới trong storage nội bộ cho truyện tranh nhập từ thiết bị.
     *
     * @return File đích chưa có dữ liệu; tên file cũng là ID sách sau khi commit.
     */
    fun createComicFile(): File = File(booksDir, "${UUID.randomUUID()}.cbz")

    /**
     * Lấy đường dẫn ảnh bìa nội bộ cho truyện tranh.
     *
     * @param bookFile File CBZ đã nằm trong thư viện.
     * @return File JPEG đích trong thư mục covers.
     */
    fun comicCoverFile(bookFile: File): File = File(coversDir, "${bookFile.nameWithoutExtension}_cover.jpg")

    /**
     * Save downloaded EPUB stream directly into internal app storage
     */
    fun importDownloadedEpub(inputStream: InputStream, originalFileName: String?): File {
        val fileId = UUID.randomUUID().toString()
        val extension = safeExtension(originalFileName, "epub")
        val targetFile = File(booksDir, "$fileId.$extension")

        inputStream.use { input ->
            FileOutputStream(targetFile).use { output ->
                input.copyTo(output)
            }
        }
        return targetFile
    }

    /**
     * Trả về cặp file tạm và file hoàn tất dành riêng cho một truyện online.
     *
     * @param novelId Định danh ổn định của truyện online.
     * @return Cặp đường dẫn deterministic để WorkManager có thể tiếp tục sau retry hoặc process death.
     */
    fun getOnlineDownloadFiles(novelId: String): OnlineDownloadFiles {
        val key = stableFileKey(novelId)
        return OnlineDownloadFiles(
            temporary = File(booksDir, "online_$key.epub.part"),
            completed = File(booksDir, "online_$key.epub")
        )
    }

    /**
     * Ghi nối tiếp dữ liệu EPUB vào file tạm với giới hạn dung lượng và callback tiến độ.
     *
     * @param inputStream Luồng dữ liệu HTTP cần ghi.
     * @param targetFile File tạm đích.
     * @param append True nếu tiếp tục ghi sau số byte đã có.
     * @param initialBytes Số byte đã có trước khi ghi.
     * @param totalBytes Tổng số byte dự kiến, có thể null nếu server không cung cấp.
     * @param onProgress Callback nhận số byte đã ghi và tổng byte dự kiến.
     * @return Tổng số byte hiện có trong file sau khi ghi.
     * @throws IllegalStateException Nếu file vượt quá giới hạn tải tối đa.
     */
    suspend fun appendOnlineDownload(
        inputStream: InputStream,
        targetFile: File,
        append: Boolean,
        initialBytes: Long,
        totalBytes: Long?,
        onProgress: suspend (downloadedBytes: Long, totalBytes: Long?) -> Unit
    ): Long {
        targetFile.parentFile?.mkdirs()
        var downloadedBytes = if (append) initialBytes else 0L
        inputStream.use { input ->
            FileOutputStream(targetFile, append).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    downloadedBytes += read
                    check(downloadedBytes <= MAX_ONLINE_DOWNLOAD_BYTES) {
                        "File EPUB tải xuống vượt quá kích thước tối đa cho phép"
                    }
                    output.write(buffer, 0, read)
                    onProgress(downloadedBytes, totalBytes)
                }
                output.fd.sync()
            }
        }
        return downloadedBytes
    }

    /**
     * Đổi file tạm thành file EPUB hoàn tất bằng rename cùng thư mục đích.
     *
     * @param files Cặp file tạm và file hoàn tất của truyện.
     * @return File EPUB hoàn tất.
     * @throws IllegalStateException Nếu không thể đổi tên file atomically.
     */
    fun promoteOnlineDownload(files: OnlineDownloadFiles): File {
        check(files.temporary.isFile) { "Không tìm thấy file tải tạm" }

        if (files.completed.isFile) {
            val previousFile = File(files.completed.parentFile, files.completed.name + ".previous")
            previousFile.delete()
            check(files.completed.renameTo(previousFile)) {
                "Không thể chuẩn bị file EPUB cũ để cập nhật"
            }
            if (!files.temporary.renameTo(files.completed)) {
                previousFile.renameTo(files.completed)
                error("Không thể hoàn tất file EPUB tải xuống")
            }
            previousFile.delete()
        } else {
            check(files.temporary.renameTo(files.completed)) {
                "Không thể hoàn tất file EPUB tải xuống"
            }
        }

        check(files.completed.isFile) { "Không tìm thấy file EPUB đã hoàn tất" }
        return files.completed
    }

    /**
     * Xóa riêng file tạm trước khi bắt đầu một lần cập nhật EPUB mới.
     *
     * File EPUB đang có trong thư viện được giữ nguyên cho tới khi file mới tải xong.
     *
     * @param novelId Định danh ổn định của truyện online.
     */
    fun clearOnlineDownloadTemporary(novelId: String) {
        getOnlineDownloadFiles(novelId).temporary.delete()
    }

    /**
     * Xóa dữ liệu tải tạm và file hoàn tất của một truyện online.
     *
     * @param novelId Định danh ổn định của truyện online.
     */
    fun deleteOnlineDownloadFiles(novelId: String) {
        val files = getOnlineDownloadFiles(novelId)
        files.temporary.delete()
        files.completed.delete()
    }

    fun saveCoverImage(bookId: String, inputStream: InputStream): String {
        val coverFile = File(coversDir, "$bookId.jpg")
        FileOutputStream(coverFile).use { output ->
            inputStream.copyTo(output)
        }
        return coverFile.absolutePath
    }

    /**
     * Xóa cover nội bộ của sách nếu đường dẫn thuộc thư mục covers của ứng dụng.
     *
     * @param coverPath Đường dẫn cover có thể là file nội bộ hoặc URL từ nguồn online.
     */
    fun deleteCoverFile(coverPath: String?) {
        val path = coverPath?.takeIf { it.isNotBlank() } ?: return
        if (path.startsWith("http://", ignoreCase = true) || path.startsWith("https://", ignoreCase = true)) {
            return
        }
        try {
            val coversRoot = coversDir.canonicalFile
            val coverFile = File(path).canonicalFile
            if (coverFile.parentFile == coversRoot) {
                coverFile.delete()
            }
        } catch (_: Exception) {
            // Cleanup không được làm gián đoạn việc xóa bản ghi sách.
        }
    }

    fun getBookFile(filePath: String): File {
        return File(filePath)
    }

    fun deleteBookFile(filePath: String) {
        val file = File(filePath)
        if (file.exists()) {
            file.delete()
        }
    }

    fun saveAiChapter(bookId: String, chapterIndex: Int, html: String): String {
        val file = aiChapterFile(bookId, chapterIndex)
        writeAtomically(file, html)
        return file.absolutePath
    }

    fun readAiChapter(filePath: String?): String? {
        if (filePath.isNullOrBlank()) return null
        return runCatching {
            val file = File(filePath).canonicalFile
            val root = aiCacheDir.canonicalFile
            require(file.path.startsWith(root.path + File.separator))
            file.takeIf { it.isFile }?.readText(Charsets.UTF_8)
        }.getOrNull()
    }

    fun saveAiProgress(bookId: String, chapterIndex: Int, json: String) {
        writeAtomically(aiProgressFile(bookId, chapterIndex), json)
    }

    fun readAiProgress(bookId: String, chapterIndex: Int): String? =
        aiProgressFile(bookId, chapterIndex)
            .takeIf { it.isFile }
            ?.readText(Charsets.UTF_8)

    fun deleteAiProgress(bookId: String, chapterIndex: Int) {
        aiProgressFile(bookId, chapterIndex).delete()
    }

    fun deleteAiChapterCache(bookId: String, chapterIndex: Int) {
        aiChapterFile(bookId, chapterIndex).delete()
        aiProgressFile(bookId, chapterIndex).delete()
    }

    fun deleteAiBookCache(bookId: String) {
        aiBookDirectory(bookId).deleteRecursively()
    }

    private fun stableFileKey(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
        .take(32)

    /**
     * Chuẩn hóa phần mở rộng do provider bên ngoài cung cấp trước khi đưa vào tên file.
     *
     * @param originalFileName Tên file chưa tin cậy từ URI.
     * @param fallback Phần mở rộng dùng khi tên không hợp lệ.
     * @return Phần mở rộng chỉ gồm ký tự an toàn.
     */
    private fun safeExtension(originalFileName: String?, fallback: String): String =
        originalFileName
            ?.substringAfterLast('.', "")
            ?.lowercase()
            ?.takeIf {
                originalFileName.contains('.') && it.matches(Regex("[a-z0-9]{1,8}"))
            }
            ?: fallback

    private fun aiChapterFile(bookId: String, chapterIndex: Int): File =
        File(aiBookDirectory(bookId), "chapter_$chapterIndex.html")

    private fun aiProgressFile(bookId: String, chapterIndex: Int): File =
        File(aiBookDirectory(bookId), "chapter_$chapterIndex.progress.json")

    private fun aiBookDirectory(bookId: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(bookId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(24)
        return File(aiCacheDir, digest).apply { if (!exists()) mkdirs() }
    }

    private fun writeAtomically(target: File, content: String) {
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, "${target.name}.tmp")
        temporary.writeText(content, Charsets.UTF_8)
        if (target.exists() && !target.delete()) {
            temporary.delete()
            error("Không thể cập nhật cache AI")
        }
        if (!temporary.renameTo(target)) {
            temporary.delete()
            error("Không thể lưu cache AI")
        }
    }

    companion object {
        /** Giới hạn kích thước một file EPUB tải online để tránh làm đầy internal storage. */
        const val MAX_ONLINE_DOWNLOAD_BYTES: Long = 500L * 1024 * 1024
    }
}

/**
 * Các đường dẫn bền vững cho một tác vụ tải EPUB online.
 *
 * @property temporary File đang được ghi nối tiếp.
 * @property completed File đã tải xong và có thể đưa vào thư viện.
 */
data class OnlineDownloadFiles(
    val temporary: File,
    val completed: File
)
