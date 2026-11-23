package com.epubpro.core.storage

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Kho lưu trữ snapshot khôi phục chương đọc tức thì (Reader Resume Snapshot Store) trên bộ nhớ trong.
 *
 * Cho phép khôi phục tức thì chương sách đang đọc dở khi người dùng mở lại sách hoặc mở lại ứng dụng,
 * loại bỏ việc phải quét lại toàn bộ file EPUB hoặc chạy lại HTML normalizer/sanitizer trên đường dẫn hiển thị đầu tiên.
 *
 * @param context Context ứng dụng Android.
 */
@Singleton
class ReaderResumeSnapshotStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val snapshotBaseDir: File by lazy {
        File(context.filesDir, "reader_snapshots").apply {
            if (!exists()) mkdirs()
        }
    }

    /**
     * Tải snapshot chương đọc của cuốn sách nếu thông tin tệp và thông tin chương khớp chính xác.
     *
     * @param bookId Mã định danh cuốn sách.
     * @param expectedChapterIndex Chỉ số chương cần khôi phục.
     * @param expectedEntryName Tên entry tệp chương mong muốn.
     * @param canonicalPath Đường dẫn chuẩn hóa của tệp EPUB.
     * @param fileLength Kích thước tệp EPUB tính theo byte.
     * @param lastModified Thời điểm chỉnh sửa tệp EPUB gần nhất.
     * @return Đối tượng [ReaderResumeSnapshot] nếu snapshot hợp lệ và toàn vẹn, ngược lại trả về `null`.
     */
    fun loadSnapshot(
        bookId: String,
        expectedChapterIndex: Int,
        expectedEntryName: String,
        canonicalPath: String,
        fileLength: Long,
        lastModified: Long
    ): ReaderResumeSnapshot? {
        val bookDir = getBookDir(bookId)
        val metaFile = File(bookDir, META_FILE_NAME)
        if (!metaFile.isFile) return null

        return runCatching {
            val root = JSONObject(metaFile.readText(Charsets.UTF_8))
            val cachedChapterIndex = root.getInt("chapterIndex")
            val cachedEntryName = root.getString("entryName")
            val cachedPath = root.getString("canonicalPath")
            val cachedLength = root.getLong("fileLength")
            val cachedLastModified = root.getLong("lastModified")
            val cachedSchemaVersion = root.getInt("cacheSchemaVersion")
            val cachedNormalizerVersion = root.getInt("normalizerVersion")
            val cachedSanitizerVersion = root.getInt("sanitizerVersion")
            val cachedSourceHash = root.getString("sourceHash")
            val cachedSanitizedHash = root.getString("sanitizedHash")
            val normalizedFileName = root.getString("normalizedFile")
            val sanitizedFileName = root.getString("sanitizedFile")
            val cachedTimestamp = root.optLong("timestamp", System.currentTimeMillis())

            if (cachedChapterIndex != expectedChapterIndex ||
                cachedEntryName != expectedEntryName ||
                cachedPath != canonicalPath ||
                cachedLength != fileLength ||
                cachedLastModified != lastModified ||
                cachedSchemaVersion != ReaderResumeSnapshot.CURRENT_CACHE_SCHEMA_VERSION ||
                cachedNormalizerVersion != ReaderResumeSnapshot.CURRENT_NORMALIZER_VERSION ||
                cachedSanitizerVersion != ReaderResumeSnapshot.CURRENT_SANITIZER_VERSION ||
                !isSafeSnapshotFileName(normalizedFileName, "normalized_") ||
                !isSafeSnapshotFileName(sanitizedFileName, "sanitized_")
            ) {
                deleteSnapshot(bookId)
                return null
            }

            val normalizedFile = File(bookDir, normalizedFileName)
            val sanitizedFile = File(bookDir, sanitizedFileName)
            if (!normalizedFile.isFile || !sanitizedFile.isFile) {
                deleteSnapshot(bookId)
                return null
            }

            val normalizedHtml = normalizedFile.readText(Charsets.UTF_8)
            val sanitizedHtml = sanitizedFile.readText(Charsets.UTF_8)
            if (ReaderResumeSnapshot.computeContentHash(normalizedHtml) != cachedSourceHash ||
                ReaderResumeSnapshot.computeContentHash(sanitizedHtml) != cachedSanitizedHash
            ) {
                deleteSnapshot(bookId)
                return null
            }

            ReaderResumeSnapshot(
                bookId = bookId,
                chapterIndex = cachedChapterIndex,
                entryName = cachedEntryName,
                canonicalPath = cachedPath,
                fileLength = cachedLength,
                lastModified = cachedLastModified,
                cacheSchemaVersion = cachedSchemaVersion,
                normalizerVersion = cachedNormalizerVersion,
                sanitizerVersion = cachedSanitizerVersion,
                sourceHash = cachedSourceHash,
                normalizedHtml = normalizedHtml,
                sanitizedHtml = sanitizedHtml,
                timestamp = cachedTimestamp
            )
        }.getOrElse {
            deleteSnapshot(bookId)
            null
        }
    }

    /**
     * Lưu snapshot chương đọc vào bộ nhớ trong dưới dạng tệp nguyên tử.
     *
     * @param snapshot Đối tượng [ReaderResumeSnapshot] chứa dữ liệu chương cần lưu.
     */
    fun saveSnapshot(snapshot: ReaderResumeSnapshot) {
        runCatching {
            val bookDir = getBookDir(snapshot.bookId)
            bookDir.mkdirs()

            val sanitizedHash = ReaderResumeSnapshot.computeContentHash(snapshot.sanitizedHtml)
            val normalizedFileName = "normalized_${snapshot.sourceHash}.html"
            val sanitizedFileName = "sanitized_${sanitizedHash}.html"
            val metaJson = JSONObject().apply {
                put("bookId", snapshot.bookId)
                put("chapterIndex", snapshot.chapterIndex)
                put("entryName", snapshot.entryName)
                put("canonicalPath", snapshot.canonicalPath)
                put("fileLength", snapshot.fileLength)
                put("lastModified", snapshot.lastModified)
                put("cacheSchemaVersion", snapshot.cacheSchemaVersion)
                put("normalizerVersion", snapshot.normalizerVersion)
                put("sanitizerVersion", snapshot.sanitizerVersion)
                put("sourceHash", snapshot.sourceHash)
                put("sanitizedHash", sanitizedHash)
                put("normalizedFile", normalizedFileName)
                put("sanitizedFile", sanitizedFileName)
                put("timestamp", snapshot.timestamp)
            }

            // Ghi content theo tên hash trước, metadata pointer cuối cùng để snapshot cũ vẫn đọc được khi bị kill giữa chừng.
            writeAtomically(File(bookDir, normalizedFileName), snapshot.normalizedHtml)
            writeAtomically(File(bookDir, sanitizedFileName), snapshot.sanitizedHtml)
            writeAtomically(File(bookDir, META_FILE_NAME), metaJson.toString())
            pruneObsoleteSnapshotFiles(
                bookDir = bookDir,
                keepNormalizedName = normalizedFileName,
                keepSanitizedName = sanitizedFileName
            )
        }
    }

    /**
     * Dọn các file HTML snapshot của chương cũ trong cùng thư mục sách để tránh phình bộ nhớ lưu trữ.
     *
     * @param bookDir Thư mục chứa snapshot của sách.
     * @param keepNormalizedName Tên file normalized HTML hiện tại cần giữ.
     * @param keepSanitizedName Tên file sanitized HTML hiện tại cần giữ.
     */
    private fun pruneObsoleteSnapshotFiles(
        bookDir: File,
        keepNormalizedName: String,
        keepSanitizedName: String
    ) {
        bookDir.listFiles()?.forEach { child ->
            val name = child.name
            val isStaleHtml = (name.startsWith("normalized_") || name.startsWith("sanitized_")) &&
                name != keepNormalizedName &&
                name != keepSanitizedName
            val isStaleTmp = name.endsWith(".tmp")
            if (isStaleHtml || isStaleTmp) {
                runCatching { child.delete() }
            }
        }
    }

    /**
     * Xóa snapshot chương đọc của một cuốn sách khi sách bị xóa khỏi thư viện.
     *
     * @param bookId Mã định danh cuốn sách.
     */
    fun deleteSnapshot(bookId: String) {
        val bookDir = getBookDir(bookId)
        if (bookDir.exists()) {
            bookDir.deleteRecursively()
        }
    }

    /**
     * Xóa toàn bộ snapshot của tất cả sách trong ứng dụng.
     */
    fun clearAll() {
        if (snapshotBaseDir.exists()) {
            snapshotBaseDir.deleteRecursively()
            snapshotBaseDir.mkdirs()
        }
    }

    private fun getBookDir(bookId: String): File {
        val safeKey = ReaderResumeSnapshot.computeContentHash(bookId)
        return File(snapshotBaseDir, safeKey)
    }

    /**
     * Kiểm tra tên file snapshot chỉ chứa hash SHA-256 do ứng dụng sinh ra, không cho phép thoát khỏi thư mục cache.
     *
     * @param fileName Tên file lấy từ metadata snapshot.
     * @param prefix Tiền tố bắt buộc của file normalized hoặc sanitized.
     * @return `true` nếu tên file hợp lệ và an toàn.
     */
    private fun isSafeSnapshotFileName(fileName: String, prefix: String): Boolean {
        return fileName.matches("${prefix}[a-f0-9]{64}\\.html".toRegex())
    }

    private fun writeAtomically(target: File, content: String) {
        val parentDir = target.parentFile?.apply { mkdirs() }
        val tempFile = File.createTempFile("${target.name}.", ".tmp", parentDir)
        tempFile.writeText(content, Charsets.UTF_8)
        try {
            Files.move(
                tempFile.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (e: Exception) {
            try {
                Files.move(
                    tempFile.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (fallbackEx: Exception) {
                tempFile.delete()
                throw fallbackEx
            }
        }
    }

    companion object {
        private const val META_FILE_NAME = "meta.json"
    }
}
