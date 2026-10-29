package com.epubpro.core.storage.sync

import com.epubpro.domain.sync.ManifestComparator
import com.epubpro.domain.sync.ReadingHistoryLedger
import com.epubpro.domain.sync.SyncManifest
import com.google.gson.Gson
import com.google.gson.JsonObject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.File
import java.io.FileOutputStream
import java.io.ByteArrayOutputStream
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Drive API v3 data source cho cây `EpubBackendSync`.
 *
 * Data source xác minh parent mỗi lần tìm file và không dùng filesystem path của Google Drive.
 */
@Singleton
class GoogleDriveDataSource @Inject constructor(
    private val client: OkHttpClient,
    private val gson: Gson,
    private val authManager: GoogleDriveAuthManager,
    private val stateStore: SyncStateStore,
    private val manifestCodec: ManifestJsonCodec,
    private val readingHistoryLedgerCodec: ReadingHistoryLedgerCodec,
    @ApplicationContext private val context: android.content.Context
) {
    /**
     * Đọc manifest và các file hiện có trong folder sync.
     *
     * @return Snapshot remote mới nhất.
     * @throws DriveSyncException Khi chưa có quyền hoặc API trả lỗi.
     */
    suspend fun readSnapshot(): DriveSyncSnapshot {
        val rootId = ensureRootFolder()
        val manifestFile = findChild(rootId, MANIFEST_NAME, MIME_JSON)
            ?: throw DriveSyncException("Chưa có manifest trên Google Drive")
        val manifestJson = downloadText(manifestFile.id ?: throw DriveSyncException("Manifest Drive thiếu file ID"))
        val manifest = manifestCodec.decode(manifestJson)
        val files = linkedMapOf<String, DriveFileRef>()
        walkFolder(rootId, "", files)
        return DriveSyncSnapshot(manifest, files)
    }

    /**
     * Đọc các ledger tiến độ riêng của từng thiết bị trong thư mục lịch sử đọc.
     *
     * @return Các ledger hợp lệ cùng ID Drive được xác minh theo tên file.
     * @throws DriveSyncException Nếu file không khớp chủ sở hữu hoặc không đọc được.
     */
    suspend fun readReadingHistoryLedgers(): List<ReadingHistoryLedger> = withContext(Dispatchers.IO) {
        val rootId = ensureRootFolder()
        val folderId = ensureFolder(rootId, READING_HISTORY_FOLDER)
        listChildren(folderId)
            .filter { it.mimeType == MIME_JSON && it.name.startsWith(READING_HISTORY_FILE_PREFIX) }
            .map { file ->
                val fileId = file.id ?: throw DriveSyncException("Ledger Drive thiếu file ID")
                val ledger = try {
                    readingHistoryLedgerCodec.decode(downloadText(fileId, MAX_READING_HISTORY_LEDGER_BYTES))
                } catch (error: IllegalArgumentException) {
                    throw DriveSyncException(error.message ?: "Ledger Drive không hợp lệ", cause = error)
                }
                if (file.name != readingHistoryFileName(ledger.deviceId)) {
                    throw DriveSyncException("Tên ledger Drive không khớp device ID")
                }
                ledger
            }
    }

    /**
     * Ghi ledger đã gộp vào file riêng của thiết bị hiện tại.
     *
     * @param ledger Ledger chứa hợp dữ liệu đã được merge.
     * @throws DriveSyncException Nếu Drive không tạo hoặc cập nhật được file.
     */
    suspend fun writeReadingHistoryLedger(ledger: ReadingHistoryLedger) = withContext(Dispatchers.IO) {
        val rootId = ensureRootFolder()
        val folderId = ensureFolder(rootId, READING_HISTORY_FOLDER)
        val name = readingHistoryFileName(ledger.deviceId)
        val existing = findChild(folderId, name, MIME_JSON)
        val metadata = JsonObject().apply {
            addProperty("name", name)
            addProperty("mimeType", MIME_JSON)
            add("parents", gson.toJsonTree(listOf(folderId)))
        }
        val temporary = File.createTempFile("reading-history-", ".json", context.cacheDir)
        try {
            temporary.writeText(readingHistoryLedgerCodec.encode(ledger), Charsets.UTF_8)
            resumableUpload(existing?.id, metadata.toString(), temporary) { _, _ -> }
        } finally {
            temporary.delete()
        }
    }

    /**
     * Upload file theo resumable upload, tạo thư mục con nếu cần.
     *
     * @param key Relative key dạng POSIX.
     * @param source File local đã snapshot.
     * @param onProgress Callback bytes đã gửi và tổng bytes.
     */
    suspend fun uploadFile(key: String, source: File, onProgress: suspend (Long, Long) -> Unit) {
        require(ManifestComparator.isAllowedStorageKey(key) || key == DATABASE_KEY) { "Key Drive không hợp lệ" }
        val rootId = ensureRootFolder()
        val parentId = ensureParentForKey(rootId, key)
        val name = key.substringAfterLast('/')
        val existing = findChild(parentId, name, null)
        val existingId = existing?.id
        if (existingId != null && !getFile(existingId)?.parents.orEmpty().contains(parentId)) {
            throw DriveSyncException("Drive file không còn thuộc đúng folder sync")
        }
        val metadata = JsonObject().apply {
            addProperty("name", name)
            add("parents", gson.toJsonTree(listOf(parentId)))
        }
        resumableUpload(existingId, metadata.toString(), source, onProgress)
    }

    /**
     * Upload manifest sau cùng để backend chỉ thấy trạng thái hoàn chỉnh best-effort.
     *
     * @param manifest Manifest cần ghi.
     */
    suspend fun uploadManifest(manifest: SyncManifest) {
        val rootId = ensureRootFolder()
        val existing = findChild(rootId, MANIFEST_NAME, MIME_JSON)
        val metadata = JsonObject().apply {
            addProperty("name", MANIFEST_NAME)
            addProperty("mimeType", MIME_JSON)
            add("parents", gson.toJsonTree(listOf(rootId)))
        }
        val temporary = File(context.cacheDir, "manifest.json.syncing")
        temporary.writeText(manifestCodec.encode(manifest), Charsets.UTF_8)
        try {
            resumableUpload(existing?.id, metadata.toString(), temporary) { _, _ -> }
        } finally {
            temporary.delete()
        }
    }

    /**
     * Download file remote vào file tạm do caller cung cấp.
     *
     * @param ref File remote đã được xác minh parent.
     * @param target File tạm đích.
     * @param onProgress Callback bytes đã nhận và tổng bytes nếu biết.
     */
    suspend fun downloadFile(ref: DriveFileRef, target: File, onProgress: suspend (Long, Long?) -> Unit) {
        val token = accessToken()
        val request = Request.Builder()
            .url(apiUrl("drive/v3/files/${ref.id}", mapOf("alt" to "media")))
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        executeWithRetry(request).use { response ->
            val body = response.body ?: throw DriveSyncException("Google Drive trả về body rỗng")
            val total = body.contentLength().takeIf { it >= 0L }
            target.parentFile?.mkdirs()
            FileOutputStream(target).use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var completed = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        completed += read
                        onProgress(completed, total)
                    }
                }
                output.fd.sync()
            }
        }
    }

    /**
     * Xóa file sau khi đã xác minh file thuộc đúng folder sync.
     *
     * @param ref File remote cần xóa.
     */
    suspend fun deleteFile(ref: DriveFileRef) {
        val current = getFile(ref.id)
        if (current == null || !current.parents.orEmpty().contains(ref.parentId)) {
            throw DriveSyncException("Drive file không còn thuộc đúng folder sync")
        }
        val token = accessToken()
        val request = Request.Builder()
            .url(apiUrl("drive/v3/files/${ref.id}", emptyMap()))
            .header("Authorization", "Bearer $token")
            .delete()
            .build()
        executeWithRetry(request).close()
    }

    private suspend fun ensureRootFolder(): String {
        val configured = stateStore.read().driveFolderId
        if (!configured.isNullOrBlank()) {
            val file = getFile(configured)
            if (file != null && file.mimeType == MIME_FOLDER && file.parents.orEmpty().contains(ROOT_PARENT)) return configured
        }
        val existing = findChild(ROOT_PARENT, ROOT_NAME, MIME_FOLDER)
        val rootId = existing?.id ?: createFolder(ROOT_PARENT, ROOT_NAME)
        stateStore.saveDriveFolderId(rootId)
        ensureFolder(rootId, DATABASE_FOLDER)
        ensureFolder(rootId, STORAGE_FOLDER)
        val storageId = ensureFolder(rootId, STORAGE_FOLDER)
        ensureFolder(storageId, NOVELS_FOLDER)
        ensureFolder(storageId, UPLOADS_FOLDER)
        return rootId
    }

    private suspend fun ensureParentForKey(rootId: String, key: String): String {
        val segments = key.split('/')
        val first = segments.first()
        var parent = when {
            key == DATABASE_KEY -> ensureFolder(rootId, DATABASE_FOLDER)
            first == "novels" || first == "uploads" -> {
                val storageId = ensureFolder(rootId, STORAGE_FOLDER)
                ensureFolder(storageId, first)
            }
            else -> error("Key Drive không hợp lệ")
        }
        val directorySegments = segments.drop(1).dropLast(1)
        for (segment in directorySegments) parent = ensureFolder(parent, segment)
        return parent
    }

    private suspend fun walkFolder(parentId: String, prefix: String, output: MutableMap<String, DriveFileRef>) {
        listChildren(parentId).forEach { file ->
            if (file.mimeType == MIME_FOLDER) {
                val childPrefix = if (prefix.isBlank()) file.name else "$prefix/${file.name}"
                walkFolder(file.id ?: return@forEach, childPrefix, output)
            } else {
                val key = when {
                    prefix == DATABASE_FOLDER && file.name == DATABASE_FILE -> DATABASE_KEY
                    prefix == "$STORAGE_FOLDER/$NOVELS_FOLDER" -> "novels/${file.name}"
                    prefix.startsWith("$STORAGE_FOLDER/$NOVELS_FOLDER/") ->
                        "novels/${prefix.removePrefix("$STORAGE_FOLDER/$NOVELS_FOLDER/")}/${file.name}"
                    prefix == "$STORAGE_FOLDER/$UPLOADS_FOLDER" -> "uploads/${file.name}"
                    prefix.startsWith("$STORAGE_FOLDER/$UPLOADS_FOLDER/") ->
                        "uploads/${prefix.removePrefix("$STORAGE_FOLDER/$UPLOADS_FOLDER/")}/${file.name}"
                    else -> null
                }
                if (key != null && ManifestComparator.isAllowedStorageKey(key)) {
                    output[key] = DriveFileRef(file.id ?: return@forEach, key, parentId)
                }
            }
        }
    }

    private suspend fun ensureFolder(parentId: String, name: String): String =
        findChild(parentId, name, MIME_FOLDER)?.id ?: createFolder(parentId, name)

    private suspend fun createFolder(parentId: String, name: String): String {
        val token = accessToken()
        val json = JsonObject().apply {
            addProperty("name", name)
            addProperty("mimeType", MIME_FOLDER)
            add("parents", gson.toJsonTree(listOf(parentId)))
        }
        val request = Request.Builder()
            .url(apiUrl("drive/v3/files", emptyMap()))
            .header("Authorization", "Bearer $token")
            .post(json.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return executeWithRetry(request).use { response ->
            gson.fromJson(response.body?.string(), DriveFileDto::class.java)?.id
                ?: throw DriveSyncException("Google Drive không tạo được folder")
        }
    }

    private suspend fun findChild(parentId: String, name: String, mimeType: String?): DriveFileDto? {
        val filters = mutableListOf("'$parentId' in parents", "name = '${escapeQuery(name)}'", "trashed = false")
        if (mimeType != null) filters += "mimeType = '$mimeType'"
        return listFiles(filters.joinToString(" and ")).firstOrNull()
    }

    private suspend fun listChildren(parentId: String): List<DriveFileDto> =
        listFiles("'$parentId' in parents and trashed = false")

    private suspend fun listFiles(query: String): List<DriveFileDto> {
        val token = accessToken()
        val result = mutableListOf<DriveFileDto>()
        var pageToken: String? = null
        do {
            val params = mutableMapOf(
                "q" to query,
                "spaces" to "drive",
                "pageSize" to "1000",
                "fields" to "nextPageToken,files(id,name,mimeType,parents,size)"
            )
            pageToken?.let { params["pageToken"] = it }
            val request = Request.Builder()
                .url(apiUrl("drive/v3/files", params))
                .header("Authorization", "Bearer $token")
                .get()
                .build()
            val page = executeWithRetry(request).use { response ->
                gson.fromJson(response.body?.string(), DriveListDto::class.java)
                    ?: throw DriveSyncException("Google Drive trả về danh sách rỗng")
            }
            result += page.files.orEmpty()
            pageToken = page.nextPageToken
        } while (pageToken != null)
        return result
    }

    private suspend fun getFile(fileId: String): DriveFileDto? {
        val token = accessToken()
        val request = Request.Builder()
            .url(apiUrl("drive/v3/files/$fileId", mapOf("fields" to "id,name,mimeType,parents,size")))
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        return try {
            executeWithRetry(request).use { response -> gson.fromJson(response.body?.string(), DriveFileDto::class.java) }
        } catch (error: DriveSyncException) {
            if (error.message?.contains("404") == true) null else throw error
        }
    }

    /**
     * Tải file JSON dưới giới hạn byte tùy chọn để tránh ledger quá lớn chiếm bộ nhớ.
     *
     * @param fileId ID file Drive.
     * @param maxBytes Giới hạn byte, null nếu caller dùng contract không giới hạn riêng.
     * @return Nội dung UTF-8 của file.
     */
    private suspend fun downloadText(fileId: String, maxBytes: Long? = null): String {
        val token = accessToken()
        val request = Request.Builder()
            .url(apiUrl("drive/v3/files/$fileId", mapOf("alt" to "media")))
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        return executeWithRetry(request).use { response ->
            val body = response.body ?: throw DriveSyncException("Manifest Drive rỗng")
            val contentLength = body.contentLength()
            if (maxBytes != null && contentLength > maxBytes) {
                throw DriveSyncException("Ledger Drive vượt quá giới hạn dung lượng")
            }
            val output = ByteArrayOutputStream()
            body.byteStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var totalBytes = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    totalBytes += read
                    if (maxBytes != null && totalBytes > maxBytes) {
                        throw DriveSyncException("Ledger Drive vượt quá giới hạn dung lượng")
                    }
                    output.write(buffer, 0, read)
                }
            }
            output.toString(Charsets.UTF_8.name())
        }
    }

    private suspend fun resumableUpload(
        fileId: String?,
        metadata: String,
        source: File,
        onProgress: suspend (Long, Long) -> Unit
    ) {
        val total = source.length()
        val token = accessToken()
        val path = if (fileId == null) "drive/v3/files" else "drive/v3/files/$fileId"
        val method = if (fileId == null) "POST" else "PATCH"
        val sessionRequest = Request.Builder()
            .url(apiUrl(path, mapOf("uploadType" to "resumable")))
            .header("Authorization", "Bearer $token")
            .header("X-Upload-Content-Type", "application/octet-stream")
            .header("X-Upload-Content-Length", total.toString())
            .method(method, metadata.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val sessionUrl = executeWithRetry(sessionRequest).use { response ->
            response.header("Location") ?: throw DriveSyncException("Google Drive không tạo resumable session")
        }
        source.inputStream().use { input ->
            val buffer = ByteArray(CHUNK_SIZE)
            var offset = 0L
            while (offset < total) {
                val expected = minOf(CHUNK_SIZE.toLong(), total - offset).toInt()
                var readTotal = 0
                while (readTotal < expected) {
                    val read = input.read(buffer, readTotal, expected - readTotal)
                    if (read <= 0) break
                    readTotal += read
                }
                check(readTotal == expected) { "Không thể đọc đủ file để upload" }
                val end = offset + readTotal - 1
                val request = Request.Builder()
                    .url(sessionUrl)
                    .header("Authorization", "Bearer $token")
                    .header("Content-Length", readTotal.toString())
                    .header("Content-Range", "bytes $offset-$end/$total")
                    .put(buffer.copyOf(readTotal).toRequestBody(BINARY_MEDIA_TYPE))
                    .build()
                val complete = executeWithRetry(request).use { response -> response.code in 200..299 }
                offset += readTotal
                onProgress(offset, total)
                if (complete && offset < total) throw DriveSyncException("Google Drive hoàn tất upload sớm")
            }
        }
    }

    private suspend fun accessToken(): String =
        authManager.getAccessToken() ?: throw DriveSyncException("Cần đăng nhập và cấp quyền Google Drive", requiresAuth = true)

    private suspend fun executeWithRetry(request: Request): okhttp3.Response = withContext(Dispatchers.IO) {
        var attempt = 0
        var result: okhttp3.Response? = null
        while (result == null) {
            val response = client.newCall(request).execute()
            if (response.isSuccessful || response.code == 308) {
                result = response
                continue
            }
            val authFailure = response.code == 401 || response.code == 403
            val retryable = response.code == 429 || response.code >= 500
            val retryAfter = response.header("Retry-After")?.toLongOrNull()
            val code = response.code
            response.close()
            if (authFailure) throw DriveSyncException("Google Drive y�u �u x�c t~�c l�i ($code)", requiresAuth = true)
            if (!retryable || attempt >= MAX_RETRIES) throw DriveSyncException("Google Drive tr� l�i HTTP $code")
            delay((retryAfter?.times(1_000L) ?: (1L shl attempt) * 1_000L).coerceAtMost(30_000L))
            attempt++
        }
        result ?: error("Google Drive request kh�ng c� response")
    }

    private fun apiUrl(path: String, params: Map<String, String>): String {
        val builder = "https://www.googleapis.com/$path".toHttpUrl().newBuilder()
        params.forEach { (key, value) -> builder.addQueryParameter(key, value) }
        return builder.build().toString()
    }

    private fun escapeQuery(value: String): String = value.replace("\\", "\\\\").replace("'", "\\'")

    /**
     * Tạo tên file ledger xác định từ UUID thiết bị đã được lưu cục bộ.
     *
     * @param deviceId UUID thiết bị sở hữu ledger.
     * @return Tên file JSON không chứa đường dẫn tùy ý.
     */
    private fun readingHistoryFileName(deviceId: String): String = "$READING_HISTORY_FILE_PREFIX$deviceId.json"

    private data class DriveListDto(val nextPageToken: String? = null, val files: List<DriveFileDto>? = null)
    private data class DriveFileDto(
        val id: String? = null,
        val name: String = "",
        val mimeType: String? = null,
        val parents: List<String>? = null,
        val size: Long? = null
    )

    companion object {
        private const val ROOT_PARENT = "root"
        private const val ROOT_NAME = "EpubBackendSync"
        private const val DATABASE_FOLDER = "database"
        private const val DATABASE_FILE = "local_db.sqlite3"
        private const val DATABASE_KEY = "database/$DATABASE_FILE"
        private const val STORAGE_FOLDER = "storage"
        private const val NOVELS_FOLDER = "novels"
        private const val UPLOADS_FOLDER = "uploads"
        private const val READING_HISTORY_FOLDER = "reading-history"
        private const val READING_HISTORY_FILE_PREFIX = "progress-"
        private const val MAX_READING_HISTORY_LEDGER_BYTES = 8L * 1024L * 1024L
        private const val MANIFEST_NAME = "manifest.json"
        private const val MIME_FOLDER = "application/vnd.google-apps.folder"
        private const val MIME_JSON = "application/json"
        private const val CHUNK_SIZE = 8 * 1024 * 1024
        private const val MAX_RETRIES = 4
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val BINARY_MEDIA_TYPE = "application/octet-stream".toMediaType()
    }
}
