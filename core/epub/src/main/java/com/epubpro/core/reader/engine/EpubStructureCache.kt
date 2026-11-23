package com.epubpro.core.reader.engine

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Quản lý lưu trữ bền vững (persistent cache) trên bộ nhớ trong (internal storage)
 * cho cấu trúc danh sách chương ([EpubChapterHeader]) của các tệp sách EPUB.
 *
 * Giúp ứng dụng đọc ngay danh sách chương mà không cần mở và giải nén tệp Zip khi mở lại sách.
 * Sử dụng dấu vân tay [EpubCacheFingerprint] để đảm bảo dữ liệu cache luôn đồng bộ với tệp EPUB gốc.
 *
 * @param context Context của ứng dụng Android dùng để truy cập thư mục filesDir.
 */
@Singleton
class EpubStructureCache @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val cacheDir: File by lazy {
        File(context.filesDir, "epub_structure_cache").apply {
            if (!exists()) mkdirs()
        }
    }

    /**
     * Đọc danh sách tiêu đề chương đã được lưu cache nếu dấu vân tay của tệp khớp chính xác.
     *
     * @param fingerprint Dấu vân tay định danh của tệp EPUB hiện tại.
     * @return Danh sách [EpubChapterHeader] nếu cache hợp lệ, hoặc `null` nếu cache không tồn tại,
     *         không khớp vân tay hoặc dữ liệu bị lỗi.
     */
    fun readHeaders(fingerprint: EpubCacheFingerprint): List<EpubChapterHeader>? {
        val cacheFile = getCacheFile(fingerprint.cacheKey)
        if (!cacheFile.isFile) return null

        return runCatching {
            val jsonContent = cacheFile.readText(Charsets.UTF_8)
            val root = JSONObject(jsonContent)

            // Kiểm tra tính hợp lệ của dấu vân tay
            val cachedPath = root.optString("canonicalPath")
            val cachedLength = root.optLong("fileLength", -1L)
            val cachedLastModified = root.optLong("lastModified", -1L)
            val cachedSchemaVersion = root.optInt("cacheSchemaVersion", -1)
            val cachedParserVersion = root.optInt("headerParserVersion", -1)

            if (cachedPath != fingerprint.canonicalPath ||
                cachedLength != fingerprint.fileLength ||
                cachedLastModified != fingerprint.lastModified ||
                cachedSchemaVersion != fingerprint.cacheSchemaVersion ||
                cachedParserVersion != fingerprint.headerParserVersion
            ) {
                // Vân tay không khớp (tệp đã bị sửa đổi hoặc phiên bản parser thay đổi)
                cacheFile.delete()
                return null
            }

            val headersArray = root.getJSONArray("headers")
            val headers = ArrayList<EpubChapterHeader>(headersArray.length())
            for (i in 0 until headersArray.length()) {
                val item = headersArray.getJSONObject(i)
                headers.add(
                    EpubChapterHeader(
                        index = item.getInt("index"),
                        title = item.getString("title"),
                        entryName = item.getString("entryName")
                    )
                )
            }
            headers.takeIf { it.isNotEmpty() }
        }.getOrElse {
            // Tệp JSON hỏng, xóa an toàn và fallback
            cacheFile.delete()
            null
        }
    }

    /**
     * Lưu danh sách chương vào tệp cache dưới định dạng JSON một cách nguyên tử (atomic write).
     *
     * @param fingerprint Dấu vân tay của tệp EPUB tương ứng.
     * @param headers Danh sách [EpubChapterHeader] cần lưu trữ.
     */
    fun saveHeaders(fingerprint: EpubCacheFingerprint, headers: List<EpubChapterHeader>) {
        if (headers.isEmpty()) return

        runCatching {
            val root = JSONObject().apply {
                put("canonicalPath", fingerprint.canonicalPath)
                put("fileLength", fingerprint.fileLength)
                put("lastModified", fingerprint.lastModified)
                put("cacheSchemaVersion", fingerprint.cacheSchemaVersion)
                put("headerParserVersion", fingerprint.headerParserVersion)

                val headersArray = JSONArray()
                for (header in headers) {
                    val item = JSONObject().apply {
                        put("index", header.index)
                        put("title", header.title)
                        put("entryName", header.entryName)
                    }
                    headersArray.put(item)
                }
                put("headers", headersArray)
            }

            val cacheFile = getCacheFile(fingerprint.cacheKey)
            writeAtomically(cacheFile, root.toString())
        }
    }

    /**
     * Xóa tệp cache cấu trúc của một cuốn sách khi sách bị xóa khỏi thư viện.
     *
     * @param canonicalPath Đường dẫn chuẩn hóa của tệp sách.
     */
    fun deleteCache(canonicalPath: String) {
        val canonical = runCatching { File(canonicalPath).canonicalPath }.getOrDefault(canonicalPath)
        val cacheKey = EpubCacheFingerprint.computeSha256(canonical)
        val cacheFile = getCacheFile(cacheKey)
        if (cacheFile.exists()) {
            cacheFile.delete()
        }
    }

    /**
     * Xóa toàn bộ dữ liệu cache cấu trúc chương của tất cả sách trong bộ nhớ.
     */
    fun clearAll() {
        if (cacheDir.exists()) {
            cacheDir.deleteRecursively()
            cacheDir.mkdirs()
        }
    }

    private fun getCacheFile(cacheKey: String): File {
        return File(cacheDir, "$cacheKey.json")
    }

    private fun writeAtomically(target: File, content: String) {
        val parentDir = target.parentFile?.apply { mkdirs() }
        val tempFile = File.createTempFile("${target.name}.", ".tmp", parentDir)
        try {
            tempFile.writeText(content, Charsets.UTF_8)
            try {
                java.nio.file.Files.move(
                    tempFile.toPath(),
                    target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE
                )
            } catch (e: Exception) {
                java.nio.file.Files.move(
                    tempFile.toPath(),
                    target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING
                )
            }
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }
}
