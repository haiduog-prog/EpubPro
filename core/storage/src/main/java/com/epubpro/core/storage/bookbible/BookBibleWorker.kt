package com.epubpro.core.storage.bookbible

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.epubpro.core.database.dao.BookBibleDao
import com.epubpro.core.database.entity.BookBibleEditionEntity
import com.epubpro.core.storage.ServerPreferencesManager
import com.epubpro.core.storage.network.*
import com.google.gson.Gson
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import retrofit2.HttpException
import java.io.IOException

/**
 * Background Worker quản lý quy trình gửi chương nguồn lên backend với tính năng tự động retry, phân loại lỗi và xóa payload an toàn.
 */
@HiltWorker
class BookBibleWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val apiService: BookBibleApiService,
    private val bookBibleDao: BookBibleDao,
    private val payloadStore: BookBiblePayloadStore,
    private val serverPreferencesManager: ServerPreferencesManager,
    private val gson: Gson
) : CoroutineWorker(appContext, workerParams) {

    /**
     * Thực thi tác vụ gửi chương nguồn lên máy chủ backend trong luồng nền.
     */
    override suspend fun doWork(): Result {
        val submissionDbId = inputData.getString(KEY_SUBMISSION_ID) ?: return Result.failure()
        val localSourceKey = inputData.getString(KEY_LOCAL_SOURCE_KEY) ?: return Result.failure()
        val chapterNumber = inputData.getInt(KEY_CHAPTER_NUMBER, -1)
        if (chapterNumber <= 0) return Result.failure()

        val sourceHash = inputData.getString(KEY_SOURCE_HASH) ?: return Result.failure()
        val bookTitle = inputData.getString(KEY_BOOK_TITLE) ?: ""
        val author = inputData.getString(KEY_AUTHOR) ?: ""
        val totalChapters = inputData.getInt(KEY_TOTAL_CHAPTERS, chapterNumber)
        val chapterTitle = inputData.getString(KEY_CHAPTER_TITLE)

        val submission = bookBibleDao.getSubmissionById(submissionDbId) ?: return Result.failure()
        val payloadPath = submission.payloadPath
        if (payloadPath.isNullOrBlank()) {
            bookBibleDao.updateSubmissionState(
                id = submissionDbId,
                state = "PERMANENT_FAILURE",
                submissionId = null,
                errorCode = 400,
                errorMessage = "Không tìm thấy đường dẫn file payload nguồn."
            )
            return Result.failure()
        }

        val payloadText = payloadStore.readPayload(payloadPath)
        if (payloadText == null) {
            bookBibleDao.updateSubmissionState(
                id = submissionDbId,
                state = "PERMANENT_FAILURE",
                submissionId = null,
                errorCode = 404,
                errorMessage = "File payload nguồn không tồn tại trên thiết bị."
            )
            return Result.failure()
        }

        return try {
            // Bước 1: Khớp hoặc khởi tạo Edition ID trên backend nếu chưa có trong Room
            var edition = bookBibleDao.getEditionByLocalSourceKey(localSourceKey)
            if (edition == null) {
                val bookRes = apiService.resolveBook(
                    body = BookResolutionRequestDto(
                        metadata = BookMetadataDto(
                            title = bookTitle,
                            author = author,
                            language = "vi"
                        ),
                        createIfMissing = true
                    )
                )
                val bookId = when {
                    !bookRes.bookId.isNullOrBlank() -> bookRes.bookId
                    !bookRes.candidates.isNullOrEmpty() -> {
                        bookRes.candidates.maxByOrNull { it.score }?.bookId ?: bookRes.candidates.first().bookId
                    }
                    else -> null
                } ?: throw IllegalStateException("Không nhận diện được book_id từ server.")

                val editionRes = apiService.resolveOrCreateEdition(
                    bookId = bookId,
                    body = CreateEditionRequestDto(
                        metadata = BookMetadataDto(
                            title = bookTitle,
                            author = author,
                            language = "vi"
                        ),
                        chapterCount = totalChapters
                    )
                )
                val newEdition = BookBibleEditionEntity(
                    localSourceKey = localSourceKey,
                    backendBookId = bookId,
                    backendEditionId = editionRes.editionId,
                    mappingRevision = editionRes.mappingRevision,
                    title = bookTitle,
                    author = author,
                    chapterCount = totalChapters
                )
                bookBibleDao.insertEdition(newEdition)
                edition = newEdition
            }

            // Bước 2: Sinh Idempotency-Key xác định từ editionId + chapterNumber + sourceHash
            val idempotencyKey = payloadStore.computeSha256("${edition.backendEditionId}:$chapterNumber:$sourceHash")

            // Cập nhật trạng thái đang gửi
            bookBibleDao.updateSubmissionState(
                id = submissionDbId,
                state = "SUBMITTING",
                submissionId = submission.submissionId,
                errorCode = null,
                errorMessage = null
            )

            // Bước 3: Gửi request submit chapter lên backend với cấu hình LLM nếu có
            val response = apiService.submitChapter(
                editionId = edition.backendEditionId,
                chapterNumber = chapterNumber,
                idempotencyKey = idempotencyKey,
                apiKey = serverPreferencesManager.getLlmApiKey(),
                model = serverPreferencesManager.getLlmModel(),
                body = ChapterSubmissionRequestDto(
                    localChapterIndex = chapterNumber,
                    inputType = "chapter_text",
                    content = payloadText,
                    contentFingerprint = sourceHash,
                    sourceLabel = chapterTitle
                )
            )

            // Bước 4: Thành công (HTTP 200/202) -> cập nhật trạng thái theo response.status và xóa file payload tạm
            val targetState = when (response.status?.lowercase(java.util.Locale.ROOT)) {
                "completed" -> "COMPLETED"
                "processing", "reviewing" -> "PROCESSING"
                else -> "ACCEPTED"
            }

            bookBibleDao.updateSubmissionState(
                id = submissionDbId,
                state = targetState,
                submissionId = response.submissionId.ifBlank { submission.submissionId },
                errorCode = null,
                errorMessage = null
            )
            payloadStore.deletePayload(payloadPath)

            Result.success()
        } catch (e: HttpException) {
            val code = e.code()
            val errorBody = runCatching { e.response()?.errorBody()?.string() }.getOrNull()

            // 409 Conflict: Đã được submit trước đó trên backend -> xem như thành công
            if (code == 409) {
                bookBibleDao.updateSubmissionState(
                    id = submissionDbId,
                    state = "ACCEPTED",
                    submissionId = "duplicate_accepted",
                    errorCode = 409,
                    errorMessage = null
                )
                payloadStore.deletePayload(payloadPath)
                return Result.success()
            }

            // 401 Unauthorized / 403 Forbidden: Lỗi Client Token -> Không xóa payload, chuyển trạng thái RETRYABLE_FAILURE để người dùng chỉnh sửa key rồi thử lại
            if (code == 401 || code == 403) {
                bookBibleDao.updateSubmissionState(
                    id = submissionDbId,
                    state = "RETRYABLE_FAILURE",
                    submissionId = submission.submissionId,
                    errorCode = code,
                    errorMessage = "Lỗi xác thực (HTTP $code). Vui lòng cấu hình lại Client Key trong Cài đặt và thử lại."
                )
                return Result.failure()
            }

            // Lỗi mạng hoặc server tạm thời (408, 429, 5xx) -> Thử lại có giãn cách (Retry)
            if (code == 408 || code == 429 || code >= 500) {
                bookBibleDao.incrementSubmissionAttempts(submissionDbId)
                bookBibleDao.updateSubmissionState(
                    id = submissionDbId,
                    state = "RETRYABLE_FAILURE",
                    submissionId = submission.submissionId,
                    errorCode = code,
                    errorMessage = "Lỗi máy chủ tạm thời (HTTP $code). Đang xếp lịch thử lại."
                )
                Result.retry()
            } else {
                // Lỗi client vĩnh viễn (400, 413 Payload Too Large, 422,...) -> Không thử lại
                bookBibleDao.updateSubmissionState(
                    id = submissionDbId,
                    state = "PERMANENT_FAILURE",
                    submissionId = null,
                    errorCode = code,
                    errorMessage = "Lỗi gửi dữ liệu (HTTP $code): ${errorBody ?: e.message()}"
                )
                payloadStore.deletePayload(payloadPath)
                Result.failure()
            }
        } catch (e: IOException) {
            // Mất kết nối mạng hoặc timeout I/O -> Retry
            bookBibleDao.incrementSubmissionAttempts(submissionDbId)
            bookBibleDao.updateSubmissionState(
                id = submissionDbId,
                state = "RETRYABLE_FAILURE",
                submissionId = submission.submissionId,
                errorCode = null,
                errorMessage = "Không có kết nối mạng. Sẽ tự động gửi lại khi có mạng."
            )
            Result.retry()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            bookBibleDao.updateSubmissionState(
                id = submissionDbId,
                state = "PERMANENT_FAILURE",
                submissionId = null,
                errorCode = null,
                errorMessage = e.message ?: "Lỗi không xác định khi xử lý chương nguồn."
            )
            payloadStore.deletePayload(payloadPath)
            Result.failure()
        }
    }

    companion object {
        const val KEY_SUBMISSION_ID = "submission_id"
        const val KEY_LOCAL_SOURCE_KEY = "local_source_key"
        const val KEY_CHAPTER_NUMBER = "chapter_number"
        const val KEY_SOURCE_HASH = "source_hash"
        const val KEY_BOOK_TITLE = "book_title"
        const val KEY_AUTHOR = "author"
        const val KEY_TOTAL_CHAPTERS = "total_chapters"
        const val KEY_CHAPTER_TITLE = "chapter_title"
    }
}
