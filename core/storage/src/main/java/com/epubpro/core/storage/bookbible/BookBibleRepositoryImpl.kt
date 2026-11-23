package com.epubpro.core.storage.bookbible

import android.content.Context
import androidx.work.*
import com.epubpro.core.database.dao.BookDao
import com.epubpro.core.database.dao.BookBibleDao
import com.epubpro.core.database.entity.BookBibleEditionEntity
import com.epubpro.core.database.entity.BookBibleSnapshotEntity
import com.epubpro.core.database.entity.BookBibleSubmissionEntity
import com.epubpro.core.database.entity.BookBibleTimelineEntity
import com.epubpro.core.storage.EpubStorageManager
import com.epubpro.core.storage.network.*
import com.epubpro.domain.model.*
import com.epubpro.domain.repository.BookBibleRepository
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Payload chứa siêu dữ liệu mở rộng được lưu trữ an toàn trong trường `coverageJson` của Snapshot.
 */
private data class SnapshotMetadataPayload(
    val coverage: CoverageDto? = null,
    val completeThroughChapter: Int? = null,
    val pendingChapters: List<Int>? = null,
    val projectionStatus: String? = null,
    val bookRevision: Int = 0,
    val projectionRevision: Int = 0,
    val backendBookId: String? = null
)

/**
 * Triển khai interface [BookBibleRepository], quản lý điều phối giữa Room Database, WorkManager, PayloadStore và Retrofit API.
 */
@Singleton
class BookBibleRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val apiService: BookBibleApiService,
    private val onlineNovelApiService: OnlineNovelApiService,
    private val bookDao: BookDao,
    private val bookBibleDao: BookBibleDao,
    private val payloadStore: BookBiblePayloadStore,
    private val fingerprintGenerator: BookBibleFingerprintGenerator,
    private val storageManager: EpubStorageManager,
    private val gson: Gson
) : BookBibleRepository {

    override suspend fun enqueueChapterSubmission(
        source: BookBibleSource,
        chapterNumber: Int,
        chapterTitle: String,
        totalChapters: Int,
        sourceContent: String,
        bookTitle: String,
        author: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (sourceContent.isBlank()) return@runCatching

            val localSourceKey = source.uniqueKey
            val sourceHash = payloadStore.computeSha256(sourceContent)
            val submissionId = payloadStore.computeSha256("$localSourceKey:$chapterNumber:$sourceHash")

            // Kiểm tra nếu submission đã hoàn thành hoặc đang được xử lý
            val existing = bookBibleDao.getSubmissionById(submissionId)
            if (existing != null && (existing.state == "ACCEPTED" || existing.state == "PROCESSING" || existing.state == "COMPLETED")) {
                return@runCatching
            }

            // Ghi nội dung văn bản nguồn vào file tạm
            val payloadPath = payloadStore.writePayloadAtomically(sourceContent, sourceHash)

            // Lưu bản ghi vào Room DB
            val entity = BookBibleSubmissionEntity(
                id = submissionId,
                localSourceKey = localSourceKey,
                chapterNumber = chapterNumber,
                sourceHash = sourceHash,
                payloadPath = payloadPath,
                state = "PENDING"
            )
            bookBibleDao.insertOrReplaceSubmission(entity)

            // Lập lịch gửi qua WorkManager với ràng buộc kết nối mạng
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val workData = workDataOf(
                BookBibleWorker.KEY_SUBMISSION_ID to submissionId,
                BookBibleWorker.KEY_LOCAL_SOURCE_KEY to localSourceKey,
                BookBibleWorker.KEY_CHAPTER_NUMBER to chapterNumber,
                BookBibleWorker.KEY_SOURCE_HASH to sourceHash,
                BookBibleWorker.KEY_BOOK_TITLE to bookTitle,
                BookBibleWorker.KEY_AUTHOR to author,
                BookBibleWorker.KEY_TOTAL_CHAPTERS to totalChapters,
                BookBibleWorker.KEY_CHAPTER_TITLE to chapterTitle
            )

            val workRequest = OneTimeWorkRequestBuilder<BookBibleWorker>()
                .setConstraints(constraints)
                .setInputData(workData)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "bookbible_submit_${localSourceKey}_$chapterNumber",
                ExistingWorkPolicy.KEEP,
                workRequest
            )
            Unit
        }
    }

    override fun observeSnapshot(
        source: BookBibleSource,
        chapterNumber: Int
    ): Flow<BookBibleSnapshot?> = flow {
        var lastTouchedSnapshotId: String? = null
        bookBibleDao.observeSnapshotByLocalSource(source.uniqueKey, chapterNumber)
            .distinctUntilChangedBy { entity ->
                entity?.let {
                    listOf(
                        it.id,
                        it.editionId,
                        it.canonicalChapter,
                        it.status,
                        it.coverageJson,
                        it.payloadJson,
                        it.revision,
                        it.updatedAt
                    )
                }
            }
            .collect { entity ->
                emit(
                    entity?.let {
                        if (lastTouchedSnapshotId != it.id) {
                            lastTouchedSnapshotId = it.id
                            bookBibleDao.updateSnapshotLastAccessed(it.id)
                        }
                        mapEntityToDomainSnapshot(it)
                    }
                )
            }
    }.flowOn(Dispatchers.IO)

    /**
     * Quan sát danh sách tiến trình Book Bible đã được lưu trong Room để màn hình cấp ứng dụng có thể duyệt truyện offline.
     *
     * @return [Flow] phát ra các tóm tắt tiến trình theo lần cập nhật gần nhất.
     */
    override fun observeProgressSummaries(): Flow<List<BookBibleProgressSummary>> {
        return bookBibleDao.observeProgressEntries()
            .map { entries ->
                entries.mapNotNull { entry ->
                    val sourceParts = entry.localSourceKey.split(":", limit = 2)
                    val sourceType = sourceParts.firstOrNull()?.let {
                        runCatching { BookBibleSourceType.valueOf(it) }.getOrNull()
                    } ?: return@mapNotNull null
                    val sourceId = sourceParts.getOrNull(1)?.takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null

                    val snapshotStatus = entry.snapshotStatus
                        ?.takeIf { entry.latestSnapshotUpdatedAt >= entry.latestSubmissionUpdatedAt }
                        ?.let { runCatching { SnapshotStatus.valueOf(it) }.getOrNull() }
                    val submissionState = when (entry.submissionState) {
                        "PENDING" -> SubmissionState.Pending
                        "SUBMITTING" -> SubmissionState.Submitting
                        "ACCEPTED" -> SubmissionState.Accepted
                        "PROCESSING" -> SubmissionState.Processing
                        "COMPLETED" -> SubmissionState.Completed
                        "RETRYABLE_FAILURE" -> SubmissionState.RetryableFailure("")
                        "PERMANENT_FAILURE" -> SubmissionState.PermanentFailure("")
                        else -> null
                    }

                    BookBibleProgressSummary(
                        source = BookBibleSource(sourceType, sourceId),
                        title = entry.title,
                        author = entry.author,
                        totalChapters = entry.chapterCount,
                        latestChapterNumber = maxOf(
                            entry.latestSnapshotChapter,
                            entry.latestSubmissionChapter
                        ),
                        snapshotStatus = snapshotStatus,
                        submissionState = submissionState,
                        updatedAt = entry.updatedAt
                    )
                }
            }
            .flowOn(Dispatchers.IO)
    }

    /**
     * Lấy danh sách sách có sự kiện cần duyệt từ Book Bible backend.
     *
     * @return [Result] chứa danh sách sách đã chuyển sang model domain.
     */
    override suspend fun getReviewBooks(): Result<List<BookBibleReviewBook>> = withContext(Dispatchers.IO) {
        runCatching {
            apiService.getReviewBooks().map { dto ->
                BookBibleReviewBook(
                    bookId = dto.bookId,
                    title = dto.title,
                    author = dto.author,
                    language = dto.language,
                    revision = dto.revision,
                    editionCount = dto.editionCount,
                    eventCount = dto.eventCount,
                    pendingEventCount = dto.pendingEventCount
                )
            }
        }
    }

    /**
     * Lấy các sự kiện pending của một cuốn sách để màn hình duyệt hiển thị.
     *
     * @param bookId Mã sách trên backend.
     * @param status Trạng thái cần lọc.
     * @param canonicalChapter Chương canonical cần lọc, nếu có.
     * @return [Result] chứa các sự kiện đã chuyển sang model domain.
     */
    override suspend fun getReviewEvents(
        bookId: String,
        status: String,
        canonicalChapter: Int?
    ): Result<List<BookBibleReviewEvent>> = withContext(Dispatchers.IO) {
        runCatching {
            apiService.getReviewEvents(
                status = status,
                bookId = bookId,
                canonicalChapter = canonicalChapter
            ).map(::mapDtoToReviewEvent)
        }
    }

    /**
     * Duyệt một sự kiện và gửi các chỉnh sửa tùy chọn lên backend.
     *
     * @param eventId Mã sự kiện cần duyệt.
     * @param edit Dữ liệu chỉnh sửa gửi kèm.
     * @return [Result] chứa sự kiện sau khi duyệt.
     */
    override suspend fun approveReviewEvent(
        eventId: String,
        edit: BookBibleReviewEventEdit
    ): Result<BookBibleReviewEvent> = withContext(Dispatchers.IO) {
        runCatching {
            apiService.approveReviewEvent(
                eventId = eventId,
                body = ReviewEventApproveRequestDto(
                    evidence = edit.evidence,
                    value = parseReviewValue(edit.valueJson)
                )
            ).let(::mapDtoToReviewEvent)
        }
    }

    /**
     * Cập nhật một sự kiện tiến trình trước khi người dùng duyệt lại.
     *
     * @param eventId Mã sự kiện cần cập nhật.
     * @param edit Dữ liệu mới của sự kiện.
     * @return [Result] chứa sự kiện sau khi cập nhật.
     */
    override suspend fun updateReviewEvent(
        eventId: String,
        edit: BookBibleReviewEventEdit
    ): Result<BookBibleReviewEvent> = withContext(Dispatchers.IO) {
        runCatching {
            apiService.updateReviewEvent(
                eventId = eventId,
                body = ReviewEventUpdateRequestDto(
                    value = parseReviewValue(edit.valueJson),
                    evidence = edit.evidence,
                    confidence = edit.confidence
                )
            ).let(::mapDtoToReviewEvent)
        }
    }

    /**
     * Từ chối một sự kiện tiến trình.
     *
     * @param eventId Mã sự kiện cần từ chối.
     * @return [Result] chứa sự kiện sau khi từ chối.
     */
    override suspend fun rejectReviewEvent(eventId: String): Result<BookBibleReviewEvent> = withContext(Dispatchers.IO) {
        runCatching {
            apiService.rejectReviewEvent(eventId).let(::mapDtoToReviewEvent)
        }
    }

    /**
     * Duyệt toàn bộ sự kiện pending của một cuốn sách.
     *
     * @param bookId Mã sách trên backend.
     * @param canonicalChapter Chương canonical cần giới hạn, nếu có.
     * @return [Result] chứa các sự kiện đã được xử lý.
     */
    override suspend fun approveAllReviewEvents(
        bookId: String,
        canonicalChapter: Int?
    ): Result<List<BookBibleReviewEvent>> = withContext(Dispatchers.IO) {
        runCatching {
            apiService.approveAllReviewEvents(
                ApproveAllReviewEventsRequestDto(
                    bookId = bookId,
                    canonicalChapter = canonicalChapter
                )
            ).map(::mapDtoToReviewEvent)
        }
    }

    override suspend fun refreshSnapshot(
        source: BookBibleSource,
        chapterNumber: Int
    ): Result<BookBibleSnapshot> = withContext(Dispatchers.IO) {
        runCatching {
            val edition = ensureEdition(source, chapterNumber)

            // Gọi API backend lấy CharacterSnapshotResponse
            val dto = apiService.getSnapshot(
                editionId = edition.backendEditionId,
                chapterNumber = chapterNumber
            )

            val metadataPayload = SnapshotMetadataPayload(
                coverage = dto.coverage,
                completeThroughChapter = dto.completeThroughChapter,
                pendingChapters = dto.pendingChapters,
                projectionStatus = dto.projectionStatus,
                bookRevision = dto.bookRevision,
                projectionRevision = dto.projectionRevision,
                backendBookId = dto.bookId.takeIf { it.isNotBlank() } ?: edition.backendBookId
            )
            val coverageJson = gson.toJson(metadataPayload)
            val allCharDtos = mutableListOf<CharacterProfileDto>()
            dto.mainCharacter?.let { mainDto ->
                allCharDtos.add(mainDto.copy(isMain = true))
            }
            dto.characters?.forEach { c ->
                val charId = c.characterId ?: c.id
                val name = c.name ?: c.viName
                if (allCharDtos.none { (charId != null && (it.characterId == charId || it.id == charId)) || (name != null && (it.name == name || it.viName == name)) }) {
                    allCharDtos.add(c)
                }
            }
            val payloadJson = gson.toJson(allCharDtos)
            val byteSize = (coverageJson.length + payloadJson.length).toLong()

            val snapshotEntity = BookBibleSnapshotEntity(
                id = "${edition.backendEditionId}:$chapterNumber",
                editionId = edition.backendEditionId,
                localSourceKey = source.uniqueKey,
                chapterNumber = chapterNumber,
                canonicalChapter = dto.canonicalChapter ?: dto.requestedChapter,
                status = dto.snapshotStatus?.uppercase() ?: "COMPLETE",
                coverageJson = coverageJson,
                payloadJson = payloadJson,
                revision = dto.snapshotRevision,
                byteSize = byteSize,
                updatedAt = System.currentTimeMillis(),
                lastAccessedAt = System.currentTimeMillis()
            )

            bookBibleDao.insertOrReplaceSnapshot(snapshotEntity)
            checkAndPruneCache()

            mapEntityToDomainSnapshot(snapshotEntity)
        }
    }

    override suspend fun getCharacterTimeline(
        source: BookBibleSource,
        characterId: String,
        chapterNumber: Int
    ): Result<CharacterTimeline> = withContext(Dispatchers.IO) {
        runCatching {
            val edition = ensureEdition(source, chapterNumber)

            // Kiểm tra cache trước
            val cached = bookBibleDao.getTimeline(edition.backendEditionId, chapterNumber, characterId)
            if (cached != null) {
                bookBibleDao.updateTimelineLastAccessed(cached.id)
                val type = object : TypeToken<List<CharacterEventDto>>() {}.type
                val eventDtos: List<CharacterEventDto> = gson.fromJson(cached.payloadJson, type) ?: emptyList()
                return@runCatching CharacterTimeline(
                    characterId = characterId,
                    characterName = "",
                    events = eventDtos.map { mapDtoToTimelineEvent(it) }
                )
            }

            // Gọi API backend nếu chưa có cache
            val eventDtos = apiService.getCharacterTimeline(
                editionId = edition.backendEditionId,
                chapterBoundary = chapterNumber,
                characterId = characterId
            )

            val payloadJson = gson.toJson(eventDtos)
            val timelineEntity = BookBibleTimelineEntity(
                id = "${edition.backendEditionId}:$chapterNumber:$characterId",
                editionId = edition.backendEditionId,
                localSourceKey = source.uniqueKey,
                chapterNumber = chapterNumber,
                characterId = characterId,
                payloadJson = payloadJson,
                byteSize = payloadJson.length.toLong(),
                updatedAt = System.currentTimeMillis(),
                lastAccessedAt = System.currentTimeMillis()
            )

            bookBibleDao.insertOrReplaceTimeline(timelineEntity)
            checkAndPruneCache()

            CharacterTimeline(
                characterId = characterId,
                characterName = "",
                events = eventDtos.map { mapDtoToTimelineEvent(it) }
            )
        }
    }

    override fun observeSubmissionState(
        source: BookBibleSource,
        chapterNumber: Int
    ): Flow<SubmissionState?> {
        return bookBibleDao.observeSubmission(source.uniqueKey, chapterNumber)
            .map { entity ->
                entity?.let {
                    when (it.state) {
                        "PENDING" -> SubmissionState.Pending
                        "SUBMITTING" -> SubmissionState.Submitting
                        "ACCEPTED" -> SubmissionState.Accepted
                        "PROCESSING" -> SubmissionState.Processing
                        "COMPLETED" -> SubmissionState.Completed
                        "RETRYABLE_FAILURE" -> SubmissionState.RetryableFailure(it.errorMessage ?: "Lỗi tạm thời")
                        "PERMANENT_FAILURE" -> SubmissionState.PermanentFailure(it.errorMessage ?: "Lỗi vĩnh viễn")
                        else -> null
                    }
                }
            }
            .flowOn(Dispatchers.IO)
    }

    override suspend fun checkSubmissionStatus(submissionId: String): Result<SubmissionState> = withContext(Dispatchers.IO) {
        runCatching {
            val response = apiService.getSubmissionStatus(submissionId)
            when (response.status?.lowercase(Locale.ROOT)) {
                "completed" -> SubmissionState.Completed
                "processing", "reviewing" -> SubmissionState.Processing
                "queued", "accepted" -> SubmissionState.Accepted
                "failed" -> SubmissionState.PermanentFailure(response.errorMessage ?: "Quá trình phân tích thất bại.")
                else -> SubmissionState.Processing
            }
        }
    }

    override suspend fun retrySubmission(
        source: BookBibleSource,
        chapterNumber: Int
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val localSourceKey = source.uniqueKey
            val entity = bookBibleDao.getSubmission(localSourceKey, chapterNumber)
                ?: throw IllegalStateException("Không tìm thấy thông tin submission để thử lại.")

            // Reset trạng thái về PENDING
            bookBibleDao.updateSubmissionState(
                id = entity.id,
                state = "PENDING",
                submissionId = null,
                errorCode = null,
                errorMessage = null
            )

            // Lập lịch lại Worker
            val workRequest = OneTimeWorkRequestBuilder<BookBibleWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .setInputData(
                    workDataOf(
                        BookBibleWorker.KEY_SUBMISSION_ID to entity.id,
                        BookBibleWorker.KEY_LOCAL_SOURCE_KEY to entity.localSourceKey,
                        BookBibleWorker.KEY_CHAPTER_NUMBER to entity.chapterNumber,
                        BookBibleWorker.KEY_SOURCE_HASH to entity.sourceHash,
                        BookBibleWorker.KEY_BOOK_TITLE to "",
                        BookBibleWorker.KEY_AUTHOR to "",
                        BookBibleWorker.KEY_TOTAL_CHAPTERS to 0,
                        BookBibleWorker.KEY_CHAPTER_TITLE to ""
                    )
                )
                .build()

            val uniqueWorkName = "bookbible_submit_${localSourceKey}_$chapterNumber"
            WorkManager.getInstance(context).enqueueUniqueWork(
                uniqueWorkName,
                ExistingWorkPolicy.REPLACE,
                workRequest
            )
            Unit
        }
    }

    override suspend fun deleteDataForBook(sourceId: String) = withContext(Dispatchers.IO) {
        val localKey = BookBibleSource(BookBibleSourceType.LOCAL_EPUB, sourceId).uniqueKey
        val onlineKey = BookBibleSource(BookBibleSourceType.ONLINE_NOVEL, sourceId).uniqueKey
        bookBibleDao.deleteByLocalSourceKey(localKey)
        bookBibleDao.deleteByLocalSourceKey(onlineKey)
    }

    /**
     * Đảm bảo thông tin phiên bản sách (Edition) đã được giải quyết và lưu trữ trong Room DB.
     * Nếu chưa có, tự động truy vấn thông tin sách cục bộ hoặc online và gọi API resolve/create edition.
     */
    private suspend fun ensureEdition(
        source: BookBibleSource,
        chapterNumber: Int
    ): BookBibleEditionEntity {
        val localSourceKey = source.uniqueKey
        val cachedEdition = bookBibleDao.getEditionByLocalSourceKey(localSourceKey)
        if (cachedEdition != null) return cachedEdition

        var title = ""
        var author = ""
        var totalChapters = chapterNumber
        var fingerprints = BookFingerprints(file = "", edition = "v1", structure = "")

        when (source.type) {
            BookBibleSourceType.LOCAL_EPUB -> {
                val book = bookDao.getBookById(source.sourceId)
                if (book != null) {
                    title = book.title
                    author = book.author
                    totalChapters = if (book.totalChapters > 0) book.totalChapters else chapterNumber
                    val file = storageManager.getBookFile(book.filePath)
                    if (file.isFile) {
                        fingerprints = fingerprintGenerator.generateFromEpubFile(file)
                    }
                }
            }
            BookBibleSourceType.ONLINE_NOVEL -> {
                val novelDetail = runCatching { onlineNovelApiService.getNovelDetail(source.sourceId) }.getOrNull()
                if (novelDetail != null) {
                    title = novelDetail.title
                    author = novelDetail.author
                    totalChapters = if (novelDetail.totalChapters > 0) {
                        novelDetail.totalChapters
                    } else {
                        novelDetail.chapters?.size ?: chapterNumber
                    }
                    fingerprints = fingerprintGenerator.generateForOnlineNovel(source.sourceId)
                }
            }
        }

        if (title.isNotBlank()) {
            val bookRes = apiService.resolveBook(
                body = BookResolutionRequestDto(
                    metadata = BookMetadataDto(
                        title = title,
                        author = author,
                        language = "vi"
                    ),
                    fingerprints = BookFingerprintsDto(
                        file = fingerprints.file,
                        edition = fingerprints.edition,
                        structure = fingerprints.structure,
                        sampledChapters = fingerprints.sampledChapters
                    ),
                    createIfMissing = true
                )
            )

            val bookId = when {
                !bookRes.bookId.isNullOrBlank() -> bookRes.bookId
                !bookRes.candidates.isNullOrEmpty() -> {
                    // Chọn candidate có điểm khớp cao nhất khi gặp confirmation_required
                    bookRes.candidates.maxByOrNull { it.score }?.bookId
                        ?: bookRes.candidates.first().bookId
                }
                else -> null
            } ?: throw IllegalStateException("Không nhận diện được book_id từ server cho sách $title.")

            val editionRes = apiService.resolveOrCreateEdition(
                bookId = bookId,
                body = CreateEditionRequestDto(
                    metadata = BookMetadataDto(
                        title = title,
                        author = author,
                        language = "vi"
                    ),
                    fingerprints = BookFingerprintsDto(
                        file = fingerprints.file,
                        edition = fingerprints.edition,
                        structure = fingerprints.structure,
                        sampledChapters = fingerprints.sampledChapters
                    ),
                    chapterCount = totalChapters
                )
            )
            val newEdition = BookBibleEditionEntity(
                localSourceKey = localSourceKey,
                backendBookId = bookId,
                backendEditionId = editionRes.editionId,
                mappingRevision = editionRes.mappingRevision,
                title = title,
                author = author,
                chapterCount = totalChapters
            )
            bookBibleDao.insertEdition(newEdition)
            return newEdition
        } else {
            throw IllegalStateException("Không tìm thấy thông tin sách để kết nối Book Bible.")
        }
    }

    private suspend fun checkAndPruneCache() {
        val totalSize = bookBibleDao.getTotalCacheByteSize() ?: 0L
        if (totalSize > MAX_CACHE_SIZE_BYTES) {
            bookBibleDao.pruneOldestSnapshots(5)
            bookBibleDao.pruneOldestTimelines(5)
        }
    }

    private fun mapEntityToDomainSnapshot(entity: BookBibleSnapshotEntity): BookBibleSnapshot {
        var metadataPayload: SnapshotMetadataPayload? = null
        var coverageDto: CoverageDto? = null

        // Cố gắng deserialize theo SnapshotMetadataPayload trước (mới), nếu không được fallback sang CoverageDto (cũ)
        val metaParsed = runCatching { gson.fromJson(entity.coverageJson, SnapshotMetadataPayload::class.java) }.getOrNull()
        if (metaParsed != null && (metaParsed.coverage != null || metaParsed.completeThroughChapter != null || metaParsed.projectionStatus != null || metaParsed.backendBookId != null)) {
            metadataPayload = metaParsed
            coverageDto = metaParsed.coverage
        } else {
            val coverageType = object : TypeToken<CoverageDto>() {}.type
            coverageDto = runCatching { gson.fromJson<CoverageDto>(entity.coverageJson, coverageType) }.getOrNull()
        }

        val characterListType = object : TypeToken<List<CharacterProfileDto>>() {}.type
        val characterDtos: List<CharacterProfileDto> = runCatching {
            gson.fromJson<List<CharacterProfileDto>>(entity.payloadJson, characterListType)
        }.getOrNull() ?: emptyList()

        val nameMap = buildCharacterNameMap(characterDtos)

        val characters = characterDtos.map { dto ->
            val charId = dto.characterId ?: dto.id ?: ""

            // 1. Kiểm tra object hoặc array attributes["profile"] lồng nhau (hỗ trợ cả JsonObject, JsonArray và JsonPrimitive)
            val profileElem = dto.attributes?.get("profile")
            val profileObjects = mutableListOf<com.google.gson.JsonObject>()
            val profileStrings = mutableListOf<String>()
            if (profileElem != null) {
                if (profileElem.isJsonObject) {
                    profileObjects.add(profileElem.asJsonObject)
                } else if (profileElem.isJsonArray) {
                    for (item in profileElem.asJsonArray) {
                        if (item.isJsonObject) {
                            profileObjects.add(item.asJsonObject)
                        } else if (item.isJsonPrimitive) {
                            val s = item.asStringOrJson().trim()
                            if (s.isNotBlank()) {
                                profileStrings.add(s)
                            }
                        }
                    }
                }
            }

            val name = profileObjects.firstNotNullOfOrNull { it.get("vi_name")?.asStringOrJson()?.takeIf { s -> s.isNotBlank() } }
                ?: profileObjects.firstNotNullOfOrNull { it.get("name")?.asStringOrJson()?.takeIf { s -> s.isNotBlank() } }
                ?: dto.name
                ?: dto.viName
                ?: dto.attributes?.get("name")?.asStringOrJson()
                ?: dto.attributes?.get("vi_name")?.asStringOrJson()
                ?: dto.originalName
                ?: ""

            val origName = dto.originalName
                ?: profileObjects.firstNotNullOfOrNull { it.get("original_name")?.asStringOrJson()?.takeIf { s -> s.isNotBlank() } }
                ?: dto.attributes?.get("original_name")?.asStringOrJson()

            val isMain = dto.isMain == true
                || dto.isProtagonistFlag == true
                || profileObjects.any { it.get("is_main")?.asBooleanOrNull() == true || it.get("is_protagonist")?.asBooleanOrNull() == true }
                || dto.attributes?.get("is_main")?.asBooleanOrNull() == true
                || dto.attributes?.get("is_protagonist")?.asBooleanOrNull() == true

            val role = profileObjects.firstNotNullOfOrNull { it.get("role")?.asStringOrJson()?.takeIf { s -> s.isNotBlank() } }
                ?: dto.attributes?.get("role")?.asStringOrJson()
                ?: dto.role
                ?: (if (isMain) "Nhân vật chính" else null)

            val voiceNotes = profileObjects.firstNotNullOfOrNull { it.get("voice_notes")?.asStringOrJson()?.takeIf { s -> s.isNotBlank() } }
                ?: dto.attributes?.get("voice_notes")?.asStringOrJson()

            val extractedAliases = mutableListOf<String>()
            profileObjects.forEach { obj ->
                extractedAliases.addAll(extractStringList(null, obj.get("aliases")))
            }
            extractedAliases.addAll(extractStringList(null, dto.attributes?.get("aliases")))
            extractedAliases.addAll(profileStrings)
            val aliases = extractedAliases.distinct()

            val realm = dto.cultivationRealm
                ?: dto.realm
                ?: profileObjects.firstNotNullOfOrNull { it.get("cultivation_realm")?.asStringOrJson() ?: it.get("realm")?.asStringOrJson() }
                ?: dto.attributes?.get("cultivation_realm")?.asStringOrJson()
                ?: dto.attributes?.get("realm")?.asStringOrJson()
                ?: dto.attributes?.get("cảnh_giới")?.asStringOrJson()
                ?: dto.attributes?.get("canh_gioi")?.asStringOrJson()

            val lastChanged = profileObjects.firstNotNullOfOrNull { it.get("last_changed_chapter")?.asIntOrNull() }
                ?: dto.attributes?.get("last_changed_chapter")?.asIntOrNull()
                ?: dto.lastChangedChapter
                ?: 1

            val changedInCurr = dto.changedInCurrentChapter
                || (dto.lastChangedChapter != null && dto.lastChangedChapter == entity.chapterNumber && entity.chapterNumber > 1)
                || (profileObjects.any { it.get("last_changed_chapter")?.asIntOrNull() == entity.chapterNumber } && entity.chapterNumber > 1)
                || (dto.attributes?.get("last_changed_chapter")?.asIntOrNull() == entity.chapterNumber && entity.chapterNumber > 1)

            val relationships = (dto.relationships ?: run {
                val relElem = dto.attributes?.get("relationships")
                if (relElem != null && relElem.isJsonArray) {
                    val type = object : TypeToken<List<CharacterRelationshipDto>>() {}.type
                    runCatching { gson.fromJson<List<CharacterRelationshipDto>>(relElem, type) }.getOrNull() ?: emptyList()
                } else {
                    emptyList()
                }
            }).map { rel ->
                val resolvedTarget = resolveVietnameseCharacterName(rel.targetName, rel.description, nameMap)
                CharacterRelationship(
                    targetName = resolvedTarget,
                    relationType = rel.relationType,
                    description = rel.description
                )
            }

            val standardKeys = setOf(
                "profile", "name", "vi_name", "original_name", "role", "voice_notes", "aliases",
                "cultivation_realm", "realm", "cảnh_giới", "canh_gioi", "techniques", "skills",
                "items", "pets", "relationships", "affiliations", "titles", "last_changed_chapter",
                "address_terms", "addressTerms", "xưng_hô", "xung_ho", "is_main", "is_protagonist",
                "profession", "chức_nghiệp", "nghề_nghiệp"
            )
            val absorbedKeys = mutableSetOf<String>()

            // 1. Tự động nhận diện và chuyển các trường Vũ khí / Trang bị vào danh mục items
            val extractedItems = mutableListOf<String>()
            extractedItems.addAll(extractStringList(dto.items, dto.attributes?.get("items")))
            listOf("weapon", "vũ_khí", "vu_khi", "trang_bị", "trang_bi", "equipment", "pháp_bảo", "phap_bao").forEach { key ->
                val v = dto.attributes?.get(key)
                if (v != null) {
                    absorbedKeys.add(key)
                    extractedItems.addAll(extractStringList(null, v))
                }
            }

            // 2. Tự động nhận diện và chuyển các trường Học viện / Thế lực / Hội nhóm vào danh mục affiliations
            val extractedAffiliations = mutableListOf<String>()
            extractedAffiliations.addAll(extractStringList(dto.affiliations, dto.attributes?.get("affiliations")))
            listOf("Học viện", "học_viện", "hoc_vien", "membership", "tông_môn", "tong_mon", "gia_tộc", "gia_toc", "môn_phái", "mon_phai", "tổ_chức", "to_chuc").forEach { key ->
                val v = dto.attributes?.get(key)
                if (v != null) {
                    absorbedKeys.add(key)
                    extractedAffiliations.addAll(extractStringList(null, v))
                }
            }

            // 3. Tự động nhận diện và chuyển các công pháp / kỹ năng dạng "Loạn Phi Phong Chùy Pháp" vào techniques
            val extractedTechniques = mutableListOf<String>()
            extractedTechniques.addAll(extractStringList(dto.techniques, dto.attributes?.get("techniques")))
            val techniqueSuffixes = listOf(
                "phap", "quyet", "cong", "kiem", "dao", "quyen", "chuong", "bo",
                "chuy phap", "tuyet ky", "than thong", "tam phap", "than phap", "tran", "phap tran"
            )
            dto.attributes?.forEach { (k, v) ->
                val normK = stripAccents(k.trim().lowercase(java.util.Locale.ROOT))
                if (techniqueSuffixes.any { normK.endsWith(it) } && k !in standardKeys && k !in absorbedKeys) {
                    val strVal = sanitizeAttributeValue(v)
                    if (strVal != null) {
                        absorbedKeys.add(k)
                        extractedTechniques.add(if (strVal.isNotBlank() && strVal != "true") "$k ($strVal)" else k)
                    }
                }
            }

            val allStandardKeys = standardKeys + absorbedKeys
            val extraAttributes = extractDeduplicatedExtraAttributes(dto.attributes, dto.extraAttributes, allStandardKeys)

            val allTitles = (extractStringList(dto.titles, dto.attributes?.get("titles")) + aliases).distinct()
            val pets = extractPetList(
                dto.pets
                    ?: profileObjects.firstNotNullOfOrNull { it.get("pets") }
                    ?: dto.attributes?.get("pets")
            )
            val addressTerms = extractAddressTerms(
                dto.addressTerms
                    ?: profileObjects.firstNotNullOfOrNull { it.get("address_terms") ?: it.get("addressTerms") }
                    ?: dto.attributes?.get("address_terms")
                    ?: dto.attributes?.get("addressTerms"),
                nameMap
            )

            CharacterProfile(
                id = charId,
                name = name,
                originalName = origName,
                role = role,
                isMain = isMain,
                voiceNotes = voiceNotes,
                aliases = aliases,
                changedInCurrentChapter = changedInCurr,
                lastChangedChapter = lastChanged,
                cultivationRealm = realm,
                techniques = extractedTechniques.distinct(),
                skills = extractStringList(dto.skills, dto.attributes?.get("skills")),
                items = extractedItems.distinct(),
                pets = pets,
                addressTerms = addressTerms,
                relationships = relationships,
                affiliations = extractedAffiliations.distinct(),
                titles = allTitles,
                extraAttributes = extraAttributes
            )
        }

        val processedRanges = coverageDto?.processedRanges?.mapNotNull {
            if (it.size >= 2) ChapterRange(it[0], it[1]) else null
        } ?: run {
            val completeThru = metadataPayload?.completeThroughChapter
            if (completeThru != null && completeThru > 0) {
                listOf(ChapterRange(1, completeThru))
            } else {
                emptyList()
            }
        }

        val missingRanges = coverageDto?.missingRanges?.mapNotNull {
            if (it.size >= 2) ChapterRange(it[0], it[1]) else null
        } ?: run {
            metadataPayload?.pendingChapters?.map { ChapterRange(it, it) } ?: emptyList()
        }

        val status = runCatching { SnapshotStatus.valueOf(entity.status) }.getOrDefault(SnapshotStatus.COMPLETE)

        // Sửa lỗi: Lấy đúng backendBookId (không gán nhầm editionId vào bookId)
        val resolvedBookId = metadataPayload?.backendBookId
            ?: entity.editionId

        return BookBibleSnapshot(
            bookId = resolvedBookId,
            editionId = entity.editionId,
            requestedChapter = entity.chapterNumber,
            canonicalChapter = entity.canonicalChapter,
            status = status,
            coverage = SnapshotCoverage(processedRanges, missingRanges),
            revision = entity.revision,
            bookRevision = metadataPayload?.bookRevision ?: 0,
            projectionRevision = metadataPayload?.projectionRevision ?: 0,
            projectionStatus = metadataPayload?.projectionStatus ?: "ready",
            completeThroughChapter = metadataPayload?.completeThroughChapter,
            pendingChapters = metadataPayload?.pendingChapters ?: emptyList(),
            updatedAt = entity.updatedAt,
            characters = characters
        )
    }

    private fun extractStringList(field: List<String>?, jsonElem: com.google.gson.JsonElement?): List<String> {
        if (!field.isNullOrEmpty()) return field
        if (jsonElem == null || jsonElem.isJsonNull) return emptyList()
        if (jsonElem.isJsonArray) {
            val list = mutableListOf<String>()
            val array = jsonElem.asJsonArray
            for (i in 0 until array.size()) {
                val elem = array.get(i)
                if (elem != null && !elem.isJsonNull) {
                    val str = elem.asStringOrJson().trim()
                    if (str.isNotBlank() && str != "null" && str != "None" && str != "undefined") {
                        list.add(str)
                    }
                }
            }
            return list
        } else if (jsonElem.isJsonPrimitive) {
            val str = jsonElem.asStringOrJson().trim()
            if (str.isNotBlank() && str != "null" && str != "None" && str != "undefined") {
                return listOf(str)
            }
        }
        return emptyList()
    }

    private fun extractPetList(jsonElem: com.google.gson.JsonElement?): List<CharacterPet> {
        if (jsonElem == null || !jsonElem.isJsonArray) return emptyList()
        val list = mutableListOf<CharacterPet>()
        val array = jsonElem.asJsonArray
        for (i in 0 until array.size()) {
            val elem = array.get(i) ?: continue
            if (elem.isJsonObject) {
                val obj = elem.asJsonObject
                val name = obj.get("name")?.asStringOrJson() ?: ""
                val species = obj.get("species")?.asStringOrJson()
                val realm = obj.get("realm")?.asStringOrJson()
                val status = obj.get("status")?.asStringOrJson()
                if (name.isNotBlank() || species != null) {
                    list.add(
                        CharacterPet(
                            name = name.ifBlank { species ?: "Linh thú" },
                            species = species,
                            realm = realm,
                            status = status
                        )
                    )
                }
            } else if (elem.isJsonPrimitive && elem.asJsonPrimitive.isString) {
                val str = elem.asString
                if (str.isNotBlank()) {
                    list.add(CharacterPet(name = str))
                }
            }
        }
        return list
    }

    /**
     * Xây dựng từ điển ánh xạ tên gốc (tiếng Hán hoặc định danh) sang tên hiển thị tiếng Việt chuẩn.
     *
     * @param dtos Danh sách DTO hồ sơ nhân vật trong snapshot.
     * @return Bản đồ tra cứu từ tên gốc / ID sang tên tiếng Việt.
     */
    private fun buildCharacterNameMap(dtos: List<CharacterProfileDto>): Map<String, String> {
        val map = mutableMapOf<String, String>()
        for (dto in dtos) {
            val profileElem = dto.attributes?.get("profile")
            var viName: String? = null
            var origName: String? = dto.originalName

            if (profileElem != null) {
                if (profileElem.isJsonObject) {
                    val obj = profileElem.asJsonObject
                    viName = obj.get("vi_name")?.asStringOrJson() ?: obj.get("name")?.asStringOrJson()
                    if (origName.isNullOrBlank()) {
                        origName = obj.get("original_name")?.asStringOrJson()
                    }
                } else if (profileElem.isJsonArray) {
                    for (item in profileElem.asJsonArray) {
                        if (item.isJsonObject) {
                            val obj = item.asJsonObject
                            if (viName.isNullOrBlank()) {
                                viName = obj.get("vi_name")?.asStringOrJson() ?: obj.get("name")?.asStringOrJson()
                            }
                            if (origName.isNullOrBlank()) {
                                origName = obj.get("original_name")?.asStringOrJson()
                            }
                        }
                    }
                }
            }

            if (viName.isNullOrBlank()) {
                viName = dto.viName
                    ?: dto.name
                    ?: dto.attributes?.get("vi_name")?.asStringOrJson()
                    ?: dto.attributes?.get("name")?.asStringOrJson()
            }

            val finalViName = viName?.trim()
            if (!finalViName.isNullOrBlank()) {
                dto.id?.let { map[it] = finalViName }
                dto.characterId?.let { map[it] = finalViName }
                origName?.trim()?.let { if (it.isNotBlank()) map[it] = finalViName }
                dto.name?.trim()?.let { if (it.isNotBlank()) map[it] = finalViName }
                dto.viName?.trim()?.let { if (it.isNotBlank()) map[it] = finalViName }
            }
        }
        return map
    }

    /**
     * Kiểm tra một chuỗi có chứa ký tự chữ Hán (CJK Unified Ideographs) hay không.
     *
     * @param text Chuỗi cần kiểm tra.
     * @return true nếu có ít nhất một ký tự chữ Hán.
     */
    private fun containsHanCharacters(text: String): Boolean {
        for (char in text) {
            val ub = Character.UnicodeBlock.of(char)
            if (ub == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
                ub == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
                ub == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B ||
                ub == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS ||
                ub == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
            ) {
                return true
            }
        }
        return false
    }

    /**
     * Giải quyết tên nhân vật từ tên gốc sang tên tiếng Việt chuẩn dựa trên từ điển snapshot và ngữ cảnh.
     *
     * @param rawName Tên thô lấy từ thuộc tính hoặc sự kiện.
     * @param context Ngữ cảnh diễn giải tiếng Việt nếu có.
     * @param nameMap Từ điển ánh xạ tên nhân vật đã trích xuất.
     * @return Tên tiếng Việt chuẩn hoặc tên ban đầu nếu không tìm thấy.
     */
    private fun resolveVietnameseCharacterName(
        rawName: String,
        context: String?,
        nameMap: Map<String, String>
    ): String {
        val trimmed = rawName.trim()
        if (trimmed.isBlank()) return trimmed

        // 1. Tra cứu trực tiếp từ map
        nameMap[trimmed]?.let { return it }

        // 2. Tra cứu không phân biệt hoa thường
        nameMap.entries.firstOrNull { it.key.equals(trimmed, ignoreCase = true) }?.value?.let { return it }

        // 3. Tra cứu theo mức độ tương đồng tên
        nameMap.entries.firstOrNull { isSamePerson(it.key, trimmed) }?.value?.let { return it }

        // 4. Nếu tên thô chứa chữ Hán, cố gắng tìm tên tiếng Việt xuất hiện trong ngữ cảnh context
        if (containsHanCharacters(trimmed) && !context.isNullOrBlank()) {
            val matchedName = nameMap.values.distinct()
                .filter { it.isNotBlank() && it.length >= 2 }
                .sortedByDescending { it.length }
                .firstOrNull { viName -> context.contains(viName, ignoreCase = true) }
            if (matchedName != null) {
                return matchedName
            }
        }

        return trimmed
    }

    private fun extractAddressTerms(
        jsonElem: com.google.gson.JsonElement?,
        nameMap: Map<String, String> = emptyMap()
    ): List<CharacterAddressTerm> {
        if (jsonElem == null || !jsonElem.isJsonArray) return emptyList()
        val rawList = mutableListOf<CharacterAddressTerm>()
        val array = jsonElem.asJsonArray
        for (i in 0 until array.size()) {
            val elem = array.get(i) ?: continue
            if (elem.isJsonObject) {
                val obj = elem.asJsonObject
                val rawTarget = obj.get("with")?.asStringOrJson()
                    ?: obj.get("counterpart_text")?.asStringOrJson()
                    ?: obj.get("counterpart_original_name")?.asStringOrJson()
                    ?: obj.get("target_name")?.asStringOrJson()
                    ?: ""
                val selfTerm = obj.get("self")?.asStringOrJson()
                    ?: obj.get("self_term")?.asStringOrJson()
                val otherTerm = obj.get("other")?.asStringOrJson()
                    ?: obj.get("other_term")?.asStringOrJson()
                val context = obj.get("context")?.asStringOrJson()

                val resolvedTarget = resolveVietnameseCharacterName(rawTarget, context, nameMap)

                if (resolvedTarget.isNotBlank() || !selfTerm.isNullOrBlank() || !otherTerm.isNullOrBlank()) {
                    rawList.add(
                        CharacterAddressTerm(
                            targetName = resolvedTarget.ifBlank { "Đối phương" }.trim(),
                            selfTerm = selfTerm?.trim(),
                            otherTerm = otherTerm?.trim(),
                            context = context?.trim()
                        )
                    )
                }
            } else if (elem.isJsonPrimitive && elem.asJsonPrimitive.isString) {
                val str = elem.asString.trim()
                if (str.isNotBlank()) {
                    rawList.add(CharacterAddressTerm(targetName = "Xưng hô", selfTerm = str))
                }
            }
        }

        // Nhóm và gộp thông minh theo đối tượng giao tiếp (loại bỏ lặp trùng lặp cho cùng một người)
        val groups = mutableListOf<MutableList<CharacterAddressTerm>>()
        for (item in rawList) {
            val matchedGroup = groups.firstOrNull { group ->
                group.any { existing -> isSamePerson(existing.targetName, item.targetName) }
            }
            if (matchedGroup != null) {
                matchedGroup.add(item)
            } else {
                groups.add(mutableListOf(item))
            }
        }

        return groups.map { list ->
            // Chọn tên hiển thị ưu tiên tên riêng hơn danh xưng chung (như "lão sư", "tiền bối")
            val primaryTarget = list.map { it.targetName }
                .sortedWith(
                    compareBy<String> { name ->
                        val n = name.lowercase(java.util.Locale.ROOT)
                        if (n.contains("lão sư") || n.contains("thầy") || n.contains("sư phụ") || n.contains("tiền bối")) 1 else 0
                    }.thenByDescending { it.length }
                ).firstOrNull() ?: list.first().targetName

            val allSelfTerms = list.mapNotNull { it.selfTerm }
                .flatMap { it.split("/", ",").map { s -> s.trim() } }
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString(" / ")
                .takeIf { it.isNotBlank() }

            val allOtherTerms = list.mapNotNull { it.otherTerm }
                .flatMap { it.split("/", ",").map { s -> s.trim() } }
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString(" / ")
                .takeIf { it.isNotBlank() }

            val allContexts = list.mapNotNull { it.context }.filter { it.isNotBlank() }.distinct()

            CharacterAddressTerm(
                targetName = primaryTarget,
                selfTerm = allSelfTerms,
                otherTerm = allOtherTerms,
                context = allContexts.firstOrNull(),
                contexts = allContexts
            )
        }
    }

    private val TITLE_WORDS = listOf(
        "lão sư", "lao su", "thầy", "cô", "tiền bối", "sư phụ", "huynh",
        "tỷ tỷ", "tỷ", "muội muội", "muội", "đệ đệ", "đệ", "đại sư", "trưởng lão", "tiểu thư", "công tử"
    )

    private fun isSamePerson(name1: String, name2: String): Boolean {
        val n1 = normalizeNameForMatching(name1)
        val n2 = normalizeNameForMatching(name2)
        if (n1.isEmpty() || n2.isEmpty()) return false
        if (n1 == n2) return true
        if (n1.length >= 3 && n2.length >= 3 && (n1.contains(n2) || n2.contains(n1))) {
            return true
        }
        return false
    }

    private fun normalizeNameForMatching(name: String): String {
        var result = name.lowercase(Locale.ROOT)
        for (title in TITLE_WORDS) {
            result = result.replace(title, " ")
        }
        return result.replace(Regex("\\s+"), " ").trim()
    }

    private fun sanitizeAttributeValue(elem: com.google.gson.JsonElement?): String? {
        if (elem == null || elem.isJsonNull) return null
        if (elem.isJsonArray) {
            val array = elem.asJsonArray
            if (array.size() == 0) return null
            val items = mutableListOf<String>()
            for (i in 0 until array.size()) {
                val item = array.get(i) ?: continue
                if (item.isJsonNull) continue
                val str = item.asStringOrJson().trim()
                if (str.isNotBlank() && str != "null" && str != "None" && str != "undefined") {
                    items.add(str)
                }
            }
            return if (items.isNotEmpty()) items.joinToString(", ") else null
        }
        val raw = elem.asStringOrJson().trim()
        if (raw.isBlank() || raw == "null" || raw == "[null]" || raw == "None" || raw == "undefined" || raw == "{}" || raw == "[]") {
            return null
        }
        return raw
    }

    /**
     * Thu thập và gộp nhóm các thuộc tính mở rộng (extraAttributes) chống trùng lặp ngữ nghĩa.
     *
     * @param attributes Map các thuộc tính từ DTO gốc.
     * @param extraAttributesDto Map các thuộc tính phụ trợ từ DTO.
     * @param standardKeys Tập các key đã được xử lý ở các trường thông tin chuẩn.
     * @return Danh sách [ExtraAttribute] đã được chuẩn hóa nhãn tiếng Việt và lọc sạch trùng lặp.
     */
    private fun extractDeduplicatedExtraAttributes(
        attributes: Map<String, com.google.gson.JsonElement>?,
        extraAttributesDto: Map<String, com.google.gson.JsonElement>?,
        standardKeys: Set<String>
    ): List<ExtraAttribute> {
        val rawEntries = mutableListOf<Pair<String, String>>()

        attributes?.forEach { (k, v) ->
            if (k !in standardKeys) {
                val cleanVal = sanitizeAttributeValue(v)
                if (cleanVal != null) {
                    rawEntries.add(k to cleanVal)
                }
            }
        }
        extraAttributesDto?.forEach { (k, v) ->
            if (k !in standardKeys) {
                val cleanVal = sanitizeAttributeValue(v)
                if (cleanVal != null) {
                    rawEntries.add(k to cleanVal)
                }
            }
        }

        // Gộp nhóm theo nhãn tiếng Việt chuẩn hóa (bỏ dấu, lowercase)
        val groupedByNormalizedLabel = LinkedHashMap<String, MutableList<Pair<String, String>>>()
        rawEntries.forEach { (rawKey, value) ->
            val label = formatAttributeLabel(rawKey)
            val normalizedKey = stripAccents(label.lowercase(java.util.Locale.ROOT))
            groupedByNormalizedLabel.getOrPut(normalizedKey) { mutableListOf() }.add(label to value)
        }

        val result = mutableListOf<ExtraAttribute>()
        groupedByNormalizedLabel.forEach { (_, items) ->
            if (items.size == 1) {
                val (label, value) = items.first()
                result.add(ExtraAttribute(key = label, label = label, value = value))
            } else {
                // Nhiều key trùng ngữ nghĩa (ví dụ: "vo_hun" và "Võ Hồn", hoặc "Hồn Lực" và "hon_luc_level")
                // Chọn nhãn hiển thị có dấu chuẩn nhất (ưu tiên nhãn có dấu tiếng Việt)
                val bestLabel = items.map { it.first }
                    .maxByOrNull { label -> (if (containsVietnameseDiacritics(label)) 100 else 0) + label.length }
                    ?: items.last().first

                // Nếu các giá trị trùng nhau: chỉ giữ 1 giá trị; nếu khác nhau, ưu tiên giá trị sau cùng (mới nhất)
                val distinctValues = items.map { it.second }.distinct()
                val mergedValue = if (distinctValues.size == 1) {
                    distinctValues.first()
                } else {
                    items.last().second
                }
                result.add(ExtraAttribute(key = bestLabel, label = bestLabel, value = mergedValue))
            }
        }
        return result
    }

    /**
     * Bỏ toàn bộ dấu tiếng Việt khỏi chuỗi văn bản phục vụ so khớp và tìm kiếm an toàn.
     *
     * @param str Chuỗi nguồn cần xóa dấu.
     * @return Chuỗi ASCII không dấu.
     */
    private fun stripAccents(str: String): String {
        val nfd = java.text.Normalizer.normalize(str, java.text.Normalizer.Form.NFD)
        return nfd.replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
            .replace('đ', 'd').replace('Đ', 'D')
    }

    /**
     * Kiểm tra xem chuỗi có chứa ký tự tiếng Việt có dấu hay không.
     *
     * @param str Chuỗi cần kiểm tra.
     * @return True nếu chuỗi có ít nhất một ký tự tiếng Việt có dấu.
     */
    private fun containsVietnameseDiacritics(str: String): Boolean {
        val vietnameseChars = "àáạảãâầấậẩẫăằắặẳẵèéẹẻẽêềếệểễìíịỉĩòóọỏõôồốộổỗơờớợởỡùúụủũưừứựửữỳýỵỷỹđÀÁẠẢÃÂẦẤẬẨẪĂẰẮẶẲẴÈÉẸẺẼÊỀẾỆỂỄÌÍỊỈĨÒÓỌỎÕÔỒỐỘỔỖƠỜỚỢỞỠÙÚỤỦŨƯỪỨỰỬỮỲÝỴỶỸĐ"
        return str.any { it in vietnameseChars }
    }

    /**
     * Định dạng và chuẩn hóa khóa thuộc tính thành nhãn tiếng Việt tự nhiên và đẹp mắt.
     *
     * @param key Khóa thuộc tính thô từ backend hoặc LLM.
     * @return Nhãn tiếng Việt có dấu chuẩn xác.
     */
    private fun formatAttributeLabel(key: String): String {
        val trimmed = key.trim()
        val normalized = trimmed.lowercase(java.util.Locale.ROOT).replace("-", "_")
        val stripped = stripAccents(normalized)

        val translated = ATTRIBUTE_LABEL_TRANSLATIONS[normalized]
            ?: ATTRIBUTE_LABEL_TRANSLATIONS[normalized.replace("_", " ")]
            ?: ATTRIBUTE_LABEL_TRANSLATIONS[stripped]
            ?: ATTRIBUTE_LABEL_TRANSLATIONS[stripped.replace("_", " ")]
        if (translated != null) return translated

        // Nếu key đã là tiếng Việt có dấu
        if (containsVietnameseDiacritics(trimmed)) {
            return trimmed.replace("_", " ")
                .replaceFirstChar { if (it.isLowerCase()) it.titlecase(java.util.Locale.ROOT) else it.toString() }
        }

        return trimmed.replace("_", " ")
            .split(" ")
            .filter { it.isNotBlank() }
            .joinToString(" ") { word ->
                word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(java.util.Locale.ROOT) else it.toString() }
            }
    }

    companion object {
        /** Giới hạn dung lượng mềm của toàn bộ Room cache Book Bible (50 MiB) */
        private const val MAX_CACHE_SIZE_BYTES = 50 * 1024 * 1024L

        /** Bảng dịch thuật ngữ các thuộc tính sang tiếng Việt chuẩn xác */
        private val ATTRIBUTE_LABEL_TRANSLATIONS = mapOf(
            // Sát hạch / Thi cử
            "exam_score" to "Điểm sát hạch",
            "exam score" to "Điểm sát hạch",
            "exam_scores" to "Điểm các môn sát hạch",
            "test_score" to "Điểm thi",
            "score" to "Điểm số",
            "assessment" to "Khảo hạch / Đánh giá",

            // Võ hồn / Hồn lực (Đấu La)
            "martial_soul" to "Võ hồn",
            "martial soul" to "Võ hồn",
            "martial_souls" to "Võ hồn",
            "martial_spirit" to "Võ hồn",
            "vo_hon" to "Võ hồn",
            "vo_hun" to "Võ hồn",
            "võ_hồn" to "Võ hồn",
            "võ hồn" to "Võ hồn",
            "soul_power" to "Hồn lực",
            "soul power" to "Hồn lực",
            "soul_power_level" to "Cấp bậc Hồn lực",
            "hon_luc" to "Hồn Lực",
            "hon_luc_level" to "Cấp bậc Hồn lực",
            "hồn_lực" to "Hồn Lực",
            "hồn lực" to "Hồn Lực",
            "cap_hon_luc" to "Cấp bậc Hồn lực",
            "cấp_hồn_lực" to "Cấp bậc Hồn lực",
            "soul_ring" to "Hồn hoàn",
            "soul_rings" to "Hồn hoàn",
            "hon_hoan" to "Hồn hoàn",
            "hồn_hoàn" to "Hồn hoàn",
            "soul_bone" to "Hồn cốt",
            "soul_bones" to "Hồn cốt",
            "hon_cot" to "Hồn cốt",
            "hồn_cốt" to "Hồn cốt",

            // Tu vi / Cảnh giới / Thể chất / Linh căn
            "cultivation" to "Tu vi / Cảnh giới",
            "cultivation_realm" to "Tu vi / Cảnh giới",
            "canh_gioi" to "Cảnh giới",
            "cảnh_giới" to "Cảnh giới",
            "tu_vi" to "Tu vi",
            "realm" to "Cảnh giới",
            "spiritual_root" to "Linh căn",
            "linh_can" to "Linh căn",
            "linh_căn" to "Linh căn",
            "spirit_power" to "Tinh thần lực",
            "tinh_than_luc" to "Tinh thần lực",
            "tinh thần lực" to "Tinh thần lực",
            "bloodline" to "Huyết mạch",
            "bloodline_power" to "Sức mạnh huyết mạch",
            "huyet_mach" to "Huyết mạch",
            "huyết_mạch" to "Huyết mạch",
            "physique" to "Thể chất",
            "the_chat" to "Thể chất",
            "thể_chất" to "Thể chất",
            "innate_ability" to "Thiên phú",
            "innate ability" to "Thiên phú",
            "talent" to "Thiên phú",
            "thien_phu" to "Thiên phú",
            "thiên_phú" to "Thiên phú",
            "domain" to "Lĩnh vực",
            "linh_vuc" to "Lĩnh vực",
            "lĩnh_vực" to "Lĩnh vực",

            // Học viện / Nghề nghiệp / Thân phận / Học nghề
            "class" to "Lớp học",
            "class_name" to "Lớp học",
            "lop_hoc" to "Lớp học",
            "lớp_học" to "Lớp học",
            "lớp học" to "Lớp học",
            "academy" to "Học viện",
            "school" to "Học viện",
            "hoc_vien" to "Học viện",
            "học_viện" to "Học viện",
            "học viện" to "Học viện",
            "occupation" to "Nghề nghiệp",
            "profession" to "Nghề nghiệp",
            "nghe_nghiep" to "Nghề nghiệp",
            "nghề_nghiệp" to "Nghề nghiệp",
            "hoc_nghe" to "Học nghề",
            "học_nghề" to "Học nghề",
            "học nghề" to "Học nghề",
            "blacksmith_rank" to "Đoán Tạo Sư",
            "doan_tao_su" to "Đoán Tạo Sư",
            "đoán_tạo_sư" to "Đoán Tạo Sư",
            "đoán tạo sư" to "Đoán Tạo Sư",
            "membership" to "Tổ chức / Hiệp hội",
            "hoi_vien" to "Hội viên",
            "thanh_vien" to "Thành viên",
            "hoc_sinh_su_lai_khac" to "Học sinh Sử Lai Khắc",
            "học_sinh_sử_lai_khắc" to "Học sinh Sử Lai Khắc",

            // Trang bị / Vũ khí / Công pháp
            "weapon" to "Vũ khí",
            "vu_khi" to "Vũ khí",
            "vũ_khí" to "Vũ khí",
            "vũ khí" to "Vũ khí",
            "equipment" to "Trang bị",
            "trang_bi" to "Trang bị",
            "trang_bị" to "Trang bị",
            "trang bị" to "Trang bị",
            "phap_bao" to "Pháp bảo",
            "pháp_bảo" to "Pháp bảo",
            "technique" to "Công pháp",
            "techniques" to "Công pháp",
            "cong_phap" to "Công pháp",
            "công_pháp" to "Công pháp",
            "skill" to "Kỹ năng",
            "skills" to "Kỹ năng",
            "ky_nang" to "Kỹ năng",
            "kỹ_năng" to "Kỹ năng",

            // Thông tin cá nhân & Thế lực
            "age" to "Tuổi",
            "tuoi" to "Tuổi",
            "tuổi" to "Tuổi",
            "gender" to "Giới tính",
            "gioi_tinh" to "Giới tính",
            "giới_tính" to "Giới tính",
            "status" to "Trạng thái",
            "trang_thai" to "Trạng thái",
            "trạng_thái" to "Trạng thái",
            "identity" to "Thân phận",
            "than_phan" to "Thân phận",
            "thân_phận" to "Thân phận",
            "personality" to "Tính cách",
            "tinh_cach" to "Tính cách",
            "tính_cách" to "Tính cách",
            "appearance" to "Ngoại hình",
            "ngoai_hinh" to "Ngoại hình",
            "ngoại_hình" to "Ngoại hình",
            "background" to "Bối cảnh",
            "boi_canh" to "Bối cảnh",
            "bối_cảnh" to "Bối cảnh",
            "origin" to "Xuất thân",
            "xuat_than" to "Xuất thân",
            "xuất_thân" to "Xuất thân",
            "element" to "Thuộc tính",
            "thuoc_tinh" to "Thuộc tính",
            "thuộc_tính" to "Thuộc tính",
            "alignment" to "Trận doanh",
            "tran_doanh" to "Trận doanh",
            "trận_doanh" to "Trận doanh",
            "grade" to "Phẩm cấp",
            "pham_cap" to "Phẩm cấp",
            "phẩm_cấp" to "Phẩm cấp",
            "rank" to "Đẳng cấp",
            "dang_cap" to "Đẳng cấp",
            "đẳng_cấp" to "Đẳng cấp",
            "level" to "Cấp bậc",
            "cap_bac" to "Cấp bậc",
            "cấp_bậc" to "Cấp bậc",
            "achievement" to "Thành tựu",
            "thanh_tuu" to "Thành tựu",
            "thành_tựu" to "Thành tựu",
            "location" to "Địa điểm",
            "dia_diem" to "Địa điểm",
            "địa_điểm" to "Địa điểm",
            "current_location" to "Vị trí hiện tại",
            "sect" to "Tông môn",
            "tong_mon" to "Tông môn",
            "tông_môn" to "Tông môn",
            "mon_phai" to "Môn phái",
            "môn_phái" to "Môn phái",
            "family" to "Gia tộc",
            "gia_toc" to "Gia tộc",
            "gia_tộc" to "Gia tộc",
            "organization" to "Tổ chức",
            "to_chuc" to "Tổ chức",
            "tổ_chức" to "Tổ chức"
        )
    }

    private fun mapDtoToTimelineEvent(dto: CharacterEventDto): CharacterTimelineEvent {
        val chapterNum = if (dto.canonicalChapter > 0) dto.canonicalChapter else (dto.chapter ?: 1)
        val cleanAttrKey = dto.attributeKey.trim()
        val cleanValueStr = sanitizeAttributeValue(dto.value)

        val displayVal = when {
            !dto.displayValue.isNullOrBlank() && dto.displayValue != "null" -> dto.displayValue

            // Trường hợp thú cưng (Pet)
            dto.category.equals("pet", ignoreCase = true) && dto.value != null && dto.value.isJsonObject -> {
                val obj = dto.value.asJsonObject
                val name = obj.get("name")?.asStringOrJson() ?: ""
                val species = obj.get("species")?.asStringOrJson()
                val realm = obj.get("realm")?.asStringOrJson()
                val opName = when (dto.operation.lowercase(java.util.Locale.ROOT)) {
                    "add" -> "Thu phục"
                    "set", "advance" -> "Tiến hóa / Đột phá"
                    "remove" -> "Rời đi"
                    else -> dto.operation
                }
                val details = listOfNotNull(species, realm).joinToString(" • ")
                if (details.isNotBlank()) "$opName $name ($details)" else "$opName $name"
            }

            // Trường hợp Xưng hô / Quan hệ (Address terms / Relationship)
            (dto.category.equals("relationship", ignoreCase = true) || cleanAttrKey.equals("address_terms", ignoreCase = true))
                    && dto.value != null && dto.value.isJsonObject -> {
                val obj = dto.value.asJsonObject
                val targetName = obj.get("with")?.asStringOrJson()
                    ?: obj.get("counterpart_text")?.asStringOrJson()
                    ?: obj.get("counterpart_original_name")?.asStringOrJson()
                    ?: "đối phương"
                val self = obj.get("self")?.asStringOrJson() ?: obj.get("self_term")?.asStringOrJson()
                val other = obj.get("other")?.asStringOrJson() ?: obj.get("other_term")?.asStringOrJson()
                val terms = listOfNotNull(
                    self?.let { "Tự xưng: $it" },
                    other?.let { "Gọi: $it" }
                ).joinToString(" • ")
                if (terms.isNotBlank()) "Xưng hô với $targetName ($terms)" else "Xưng hô với $targetName"
            }

            // Có giá trị value hợp lệ
            cleanValueStr != null -> {
                if (cleanAttrKey.isNotBlank() && !cleanAttrKey.equals(dto.category, ignoreCase = true) && !cleanAttrKey.equals("value", ignoreCase = true)) {
                    "${formatAttributeLabel(cleanAttrKey)}: $cleanValueStr"
                } else {
                    cleanValueStr
                }
            }

            // Value bị null nhưng có attribute_key (ví dụ: "Hoàng Kim Long Thể", "Kim Long Trảo")
            cleanAttrKey.isNotBlank() && !cleanAttrKey.equals(dto.category, ignoreCase = true) -> {
                when (dto.operation.lowercase(java.util.Locale.ROOT)) {
                    "add" -> "Lĩnh ngộ / Xuất hiện: $cleanAttrKey"
                    "set" -> cleanAttrKey
                    "remove" -> "Mất / Tiêu hao: $cleanAttrKey"
                    else -> cleanAttrKey
                }
            }

            else -> {
                val opName = formatOperationVietnamese(dto.operation)
                val catName = formatCategoryVietnamese(dto.category)
                "$opName $catName"
            }
        }

        return CharacterTimelineEvent(
            chapter = chapterNum,
            category = dto.category,
            operation = dto.operation,
            displayValue = displayVal,
            certainty = dto.certainty ?: "observed",
            evidence = dto.evidence,
            confidence = dto.confidence
        )
    }

    /**
     * Chuyển DTO sự kiện review sang model domain và giữ nguyên value JSON để có thể sửa lại.
     *
     * @param dto DTO sự kiện từ backend.
     * @return Model sự kiện dùng bởi màn hình duyệt.
     */
    private fun mapDtoToReviewEvent(dto: CharacterEventDto): BookBibleReviewEvent {
        return BookBibleReviewEvent(
            eventId = dto.eventId.orEmpty(),
            bookId = dto.bookId.orEmpty(),
            characterId = dto.characterId.orEmpty(),
            characterOriginalName = dto.characterOriginalName.orEmpty(),
            canonicalChapter = if (dto.canonicalChapter > 0) dto.canonicalChapter else (dto.chapter ?: 1),
            category = dto.category,
            attributeKey = dto.attributeKey,
            operation = dto.operation,
            valueJson = dto.value?.takeUnless { it.isJsonNull }?.toString(),
            displayValue = dto.displayValue ?: sanitizeAttributeValue(dto.value),
            certainty = dto.certainty,
            status = dto.status ?: "pending",
            evidence = dto.evidence,
            confidence = dto.confidence,
            sourceGroupId = dto.sourceGroupId,
            sourceSubmissionId = dto.sourceSubmissionId,
            createdAt = dto.createdAt
        )
    }

    /**
     * Phân tích chuỗi value do người dùng nhập thành JSON; chuỗi thường được bọc thành JSON string.
     *
     * @param valueJson Chuỗi JSON hoặc văn bản người dùng nhập.
     * @return [com.google.gson.JsonElement] phù hợp để gửi lên API, hoặc `null` nếu để trống.
     */
    private fun parseReviewValue(valueJson: String?): com.google.gson.JsonElement? {
        val value = valueJson?.trim().orEmpty()
        if (value.isBlank()) return null
        return runCatching {
            gson.fromJson(value, com.google.gson.JsonElement::class.java)
        }.getOrElse {
            com.google.gson.JsonPrimitive(value)
        }
    }

    private fun formatOperationVietnamese(op: String): String {
        return when (op.lowercase(java.util.Locale.ROOT)) {
            "add" -> "Lĩnh ngộ / Thêm"
            "set" -> "Cập nhật"
            "advance" -> "Đột phá"
            "remove" -> "Loại bỏ"
            else -> op
        }
    }

    private fun formatCategoryVietnamese(cat: String): String {
        return when (cat.lowercase(Locale.ROOT)) {
            "skill" -> "kỹ năng"
            "technique" -> "công pháp"
            "item" -> "trang bị / pháp bảo"
            "cultivation", "realm" -> "cảnh giới tu vi"
            "relationship" -> "quan hệ nhân vật"
            "pet" -> "linh thú"
            "affiliation" -> "thế lực"
            "title" -> "danh hiệu"
            else -> cat
        }
    }

    private fun com.google.gson.JsonElement.asStringOrJson(): String {
        return if (isJsonPrimitive && asJsonPrimitive.isString) {
            asString
        } else {
            toString()
        }
    }

    private fun com.google.gson.JsonElement.asIntOrNull(): Int? {
        return runCatching {
            if (isJsonPrimitive && asJsonPrimitive.isNumber) {
                asInt
            } else if (isJsonPrimitive && asJsonPrimitive.isString) {
                asString.toIntOrNull()
            } else {
                null
            }
        }.getOrNull()
    }

    /**
     * Chuyển đổi an toàn một phần tử [com.google.gson.JsonElement] sang [Boolean].
     * Hỗ trợ cả kiểu Boolean nguyên bản, chuỗi ("true", "1", "yes") và số nguyên.
     *
     * @return Giá trị Boolean hoặc null nếu không thể chuyển đổi.
     */
    private fun com.google.gson.JsonElement.asBooleanOrNull(): Boolean? {
        return runCatching {
            if (isJsonPrimitive && asJsonPrimitive.isBoolean) {
                asBoolean
            } else if (isJsonPrimitive && asJsonPrimitive.isString) {
                val s = asString.trim().lowercase(Locale.ROOT)
                if (s in listOf("true", "1", "yes")) true
                else if (s in listOf("false", "0", "no")) false
                else null
            } else if (isJsonPrimitive && asJsonPrimitive.isNumber) {
                asInt == 1
            } else {
                null
            }
        }.getOrNull()
    }
}
