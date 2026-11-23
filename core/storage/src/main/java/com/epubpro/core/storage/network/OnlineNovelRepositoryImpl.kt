package com.epubpro.core.storage.network

import android.content.Context
import com.epubpro.core.designsystem.R
import com.epubpro.core.storage.EpubStorageManager
import com.epubpro.core.storage.ServerPreferencesManager
import com.epubpro.domain.model.*
import com.epubpro.domain.repository.OnlineNovelRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Triển khai interface [OnlineNovelRepository], xử lý giao tiếp mạng qua Retrofit, bóc tách DTO sang Domain Model
 * và lưu trữ trực tiếp file EPUB vào bộ nhớ ứng dụng.
 */
@Singleton
class OnlineNovelRepositoryImpl @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val apiService: OnlineNovelApiService,
    private val storageManager: EpubStorageManager,
    private val serverPreferencesManager: ServerPreferencesManager
) : OnlineNovelRepository {

    /**
     * Lấy danh sách tóm tắt tất cả các bộ truyện online từ server.
     */
    override suspend fun getNovels(): Result<List<OnlineNovelSummary>> = withContext(Dispatchers.IO) {
        runCatchingCancellable {
            apiService.getNovels().map { dto ->
                OnlineNovelSummary(
                    novelId = dto.novelId,
                    title = dto.title,
                    originalTitle = dto.originalTitle,
                    author = dto.author,
                    genres = dto.genre ?: emptyList(),
                    coverUrl = dto.coverUrl,
                    totalChapters = dto.totalChapters,
                    translatedChapters = dto.translatedChapters,
                    status = dto.status ?: "ongoing",
                    updatedAt = dto.updatedAt
                )
            }
        }
    }

    /**
     * Lấy chi tiết bộ truyện và danh sách chương từ server.
     */
    override suspend fun getNovelDetail(novelId: String): Result<OnlineNovelDetail> = withContext(Dispatchers.IO) {
        runCatchingCancellable {
            val dto = apiService.getNovelDetail(novelId)
            OnlineNovelDetail(
                novelId = dto.novelId,
                title = dto.title,
                author = dto.author,
                description = dto.description,
                coverUrl = dto.coverUrl,
                totalChapters = dto.totalChapters,
                translatedChapters = dto.translatedChapters,
                chapters = dto.chapters?.map { chapterDto ->
                    OnlineChapterSummary(
                        chapterIndex = chapterDto.chapterIndex,
                        chapterTitle = chapterDto.chapterTitle,
                        status = chapterDto.status ?: "raw",
                        wordCount = chapterDto.wordCount,
                        translatedTextPreview = chapterDto.translatedTextPreview
                    )
                } ?: emptyList()
            )
        }
    }

    /**
     * Lấy nội dung chi tiết của một chương truyện từ server để đọc trực tuyến.
     */
    override suspend fun getChapterContent(
        novelId: String,
        chapterIndex: Int,
        version: String
    ): Result<OnlineChapterContent> = withContext(Dispatchers.IO) {
        runCatchingCancellable {
            val dto = apiService.getChapterContent(
                novelId = novelId,
                chapterIndex = chapterIndex,
                version = version
            )
            OnlineChapterContent(
                novelId = dto.novelId,
                chapterIndex = dto.chapterIndex,
                version = dto.version,
                content = dto.content
            )
        }
    }

    /**
     * Tải EPUB theo luồng, hỗ trợ tiếp tục từ file .part sau khi mạng hoặc process bị gián đoạn.
     *
     * @param novelId Mã định danh truyện trên backend.
     * @param saveFileName Tên file tương thích với contract cũ; file thực tế dùng tên deterministic theo novelId.
     * @param resumeFilePath Đường dẫn file tạm cần tiếp tục, hoặc null để dùng đường dẫn mặc định.
     * @return Flow trạng thái tải gồm byte đã ghi, tổng byte và lỗi có thể retry.
     */
    override fun downloadEpub(
        novelId: String,
        saveFileName: String,
        resumeFilePath: String?
    ): Flow<DownloadState> = flow {
        val defaultFiles = storageManager.getOnlineDownloadFiles(novelId)
        val targetFile = resumeFilePath?.let(::File) ?: defaultFiles.temporary
        var resumeOffset = targetFile.length()
        emit(DownloadState.Downloading(0, resumeOffset, null))

        try {
            var response = apiService.downloadEpub(
                novelId = novelId,
                range = resumeOffset.takeIf { it > 0 }?.let { "bytes=" + it + "-" }
            )

            if (response.code() == 416 && resumeOffset > 0) {
                response.body()?.close()
                response.errorBody()?.close()
                FileOutputStream(targetFile).use { it.channel.truncate(0) }
                resumeOffset = 0
                response = apiService.downloadEpub(novelId = novelId, range = null)
            }

            val body = response.body()
            if (!response.isSuccessful || body == null) {
                body?.close()
                response.errorBody()?.close()
                val responseCode = response.code()
                val errorMsg = when (responseCode) {
                    502, 504 -> appContext.getString(
                        R.string.online_download_error_server_busy,
                        responseCode
                    )
                    401, 403 -> appContext.getString(R.string.online_download_error_session_expired)
                    404 -> appContext.getString(R.string.online_download_error_file_not_found)
                    else -> appContext.getString(
                        R.string.online_download_fail_http,
                        responseCode,
                        response.message()
                    )
                }
                val isRetryable = responseCode == 408 ||
                    responseCode == 429 ||
                    responseCode >= 500
                emit(DownloadState.Error(message = errorMsg, isRetryable = isRetryable))
                return@flow
            }
            val append = resumeOffset > 0 && response.code() == 206
            val initialBytes = if (append) resumeOffset else 0L
            if (!append && resumeOffset > 0) {
                FileOutputStream(targetFile).use { it.channel.truncate(0) }
            }
            val bodyLength = body.contentLength().takeIf { it > 0L }
            val totalBytes = response.headers()["Content-Range"]
                ?.substringAfterLast('/')
                ?.toLongOrNull()
                ?: bodyLength?.let { initialBytes + it }

            var lastProgressPercent = -1
            var lastProgressBytes = initialBytes
            val downloadedBytes = body.use { responseBody ->
                storageManager.appendOnlineDownload(
                    inputStream = responseBody.byteStream(),
                    targetFile = targetFile,
                    append = append,
                    initialBytes = initialBytes,
                    totalBytes = totalBytes
                ) { downloaded, total ->
                    val percent = if (total != null && total > 0L) {
                        (downloaded * 100L / total).toInt().coerceIn(0, 99)
                    } else {
                        // Ước lượng tiến độ theo dung lượng nhận được khi server dùng Transfer-Encoding: chunked
                        ((downloaded / (256 * 1024L)) * 5).toInt().coerceIn(1, 90)
                    }
                    val shouldEmit = percent != lastProgressPercent ||
                        downloaded - lastProgressBytes >= PROGRESS_UPDATE_BYTES
                    if (shouldEmit) {
                        lastProgressPercent = percent
                        lastProgressBytes = downloaded
                        emit(DownloadState.Downloading(percent, downloaded, total))
                    }
                }
            }
            emit(DownloadState.Downloading(100, downloadedBytes, totalBytes))
            emit(DownloadState.Success(targetFile.absolutePath))
        } catch (error: CancellationException) {
            throw error
        } catch (error: java.net.SocketTimeoutException) {
            emit(
                DownloadState.Error(
                    message = appContext.getString(R.string.online_download_error_timeout),
                    isRetryable = true
                )
            )
        } catch (error: java.net.UnknownHostException) {
            emit(
                DownloadState.Error(
                    message = appContext.getString(R.string.online_download_error_network),
                    isRetryable = true
                )
            )
        } catch (error: IOException) {
            emit(
                DownloadState.Error(
                    message = appContext.getString(R.string.online_download_error_network),
                    isRetryable = true
                )
            )
        } catch (error: Exception) {
            emit(
                DownloadState.Error(
                    message = error.message ?: appContext.getString(R.string.online_download_error),
                    isRetryable = false
                )
            )
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Yêu cầu AI dịch một chương truyện cụ thể.
     */
    override suspend fun translateChapter(
        novelId: String,
        chapterIndex: Int,
        apiKey: String,
        provider: String,
        model: String
    ): Result<TranslateChapterResult> = withContext(Dispatchers.IO) {
        runCatchingCancellable {
            val dto = apiService.translateChapter(
                novelId = novelId,
                chapterIndex = chapterIndex,
                apiKey = apiKey,
                provider = provider,
                model = model
            )
            TranslateChapterResult(
                message = dto.message,
                chapter = dto.chapter?.let { chapterDto ->
                    OnlineChapterSummary(
                        chapterIndex = chapterDto.chapterIndex,
                        chapterTitle = chapterDto.chapterTitle,
                        status = chapterDto.status ?: "completed",
                        wordCount = chapterDto.wordCount,
                        translatedTextPreview = chapterDto.translatedTextPreview
                    )
                }
            )
        }
    }

    /**
     * Upload trực tiếp file từ điện thoại lên server backend với MIME type thực tế.
     */
    override suspend fun uploadEpub(
        filePath: String,
        isTranslated: Boolean,
        novelId: String?,
        autoScanCharacters: Boolean,
        contentType: String?,
        originalName: String?
    ): Result<ImportJobStatus> = withContext(Dispatchers.IO) {
        runCatchingCancellable {
            val file = File(filePath)
            if (!file.exists()) {
                throw IllegalArgumentException("File không tồn tại: $filePath")
            }

            val mediaType = (contentType ?: "application/octet-stream").toMediaTypeOrNull()
            val requestFile = file.asRequestBody(mediaType)
            val uploadName = originalName
                ?.substringAfterLast('/')
                ?.substringAfterLast('\\')
                ?.replace(Regex("[\\r\\n\"]"), "_")
                ?.takeIf { it.isNotBlank() }
                ?: file.name
            val filePart = MultipartBody.Part.createFormData("file", uploadName, requestFile)
            val isTranslatedBody = isTranslated.toString().toRequestBody("text/plain".toMediaTypeOrNull())
            
            val novelIdBody = novelId?.toRequestBody("text/plain".toMediaTypeOrNull())
            val autoScanBody = autoScanCharacters.toString().toRequestBody("text/plain".toMediaTypeOrNull())

            val dto = apiService.uploadEpub(filePart, isTranslatedBody, novelIdBody, autoScanBody)
            mapToImportJobStatus(dto)
        }
    }

    /**
     * Lấy trạng thái của tiến trình upload.
     */
    override suspend fun getImportJobStatus(jobId: String): Result<ImportJobStatus> = withContext(Dispatchers.IO) {
        runCatchingCancellable {
            val dto = apiService.getImportJobStatus(jobId)
            mapToImportJobStatus(dto)
        }
    }

    /**
     * Thực thi khối mạng và giữ nguyên trạng thái hủy của coroutine.
     *
     * @param block Khối suspend thực hiện request mạng.
     * @return Kết quả thành công hoặc lỗi của request.
     * @throws CancellationException Khi coroutine bị hủy.
     */
    private suspend fun <T> runCatchingCancellable(block: suspend () -> T): Result<T> {
        return try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun mapToImportJobStatus(dto: ImportJobStatusDto): ImportJobStatus {
        return ImportJobStatus(
            jobId = dto.jobId,
            novelId = dto.novelId,
            title = dto.title,
            status = dto.status,
            currentStep = dto.currentStep,
            currentChapter = dto.currentChapter,
            totalChapters = dto.totalChapters,
            progressPercentage = dto.progressPercentage,
            errorMessage = dto.errorMessage,
            createdAt = dto.createdAt,
            completedAt = dto.completedAt
        )
    }

    /**
     * Lấy luồng phát ra địa chỉ Base URL hiện tại của backend.
     */
    override fun getBaseUrl(): Flow<String> = serverPreferencesManager.baseUrlFlow

    /**
     * Cập nhật địa chỉ Base URL mới cho backend.
     */
    override suspend fun setBaseUrl(url: String) {
        withContext(Dispatchers.IO) {
            serverPreferencesManager.saveBaseUrl(url)
        }
    }

    /**
     * Kiểm tra ping kết nối tới server.
     */
    override suspend fun testServerConnection(baseUrl: String): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatchingCancellable {
            apiService.getNovelsFromBaseUrl(serverPreferencesManager.normalizeUrl(baseUrl))
            true
        }
    }

    private companion object {
        const val PROGRESS_UPDATE_BYTES = 64L * 1024L
    }
}
