package com.epubpro.core.storage.bookbible

import android.content.Context
import com.epubpro.core.database.dao.BookDao
import com.epubpro.core.database.dao.BookBibleDao
import com.epubpro.core.database.dao.BookBibleProgressEntry
import com.epubpro.core.database.entity.BookBibleEditionEntity
import com.epubpro.core.storage.EpubStorageManager
import com.epubpro.core.storage.network.*
import com.epubpro.domain.model.*
import com.google.gson.Gson
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

class BookBibleRepositoryImplTest {

    private lateinit var mockContext: Context
    private lateinit var mockApiService: BookBibleApiService
    private lateinit var mockOnlineNovelApiService: OnlineNovelApiService
    private lateinit var mockBookDao: BookDao
    private lateinit var mockBookBibleDao: BookBibleDao
    private lateinit var mockPayloadStore: BookBiblePayloadStore
    private lateinit var mockFingerprintGenerator: BookBibleFingerprintGenerator
    private lateinit var mockStorageManager: EpubStorageManager
    private val gson = Gson()

    private lateinit var repository: BookBibleRepositoryImpl

    @Before
    fun setUp() {
        mockContext = mock()
        mockApiService = mock()
        mockOnlineNovelApiService = mock()
        mockBookDao = mock()
        mockBookBibleDao = mock()
        mockPayloadStore = mock()
        mockFingerprintGenerator = mock()
        mockStorageManager = mock()

        repository = BookBibleRepositoryImpl(
            context = mockContext,
            apiService = mockApiService,
            onlineNovelApiService = mockOnlineNovelApiService,
            bookDao = mockBookDao,
            bookBibleDao = mockBookBibleDao,
            payloadStore = mockPayloadStore,
            fingerprintGenerator = mockFingerprintGenerator,
            storageManager = mockStorageManager,
            gson = gson,
            // Sách test đã được người dùng bật Book Bible.
            consentStore = testConsentStore(
                enabled = arrayOf(BookBibleSource(BookBibleSourceType.LOCAL_EPUB, "book_1"))
            )
        )
    }

    @Test
    fun testRefreshSnapshotParsesNestedProfileAndMetadata() = runBlocking {
        val source = BookBibleSource(BookBibleSourceType.LOCAL_EPUB, "book_1")
        val editionEntity = BookBibleEditionEntity(
            localSourceKey = source.uniqueKey,
            backendBookId = "real_backend_book_id",
            backendEditionId = "backend_edition_123",
            title = "Cổ Chân Nhân",
            author = "Cổ Chân Nhân",
            chapterCount = 100
        )
        whenever(mockBookBibleDao.getEditionByLocalSourceKey(source.uniqueKey)).thenReturn(editionEntity)

        val characterJson = """
            {
                "character_id": "c1",
                "original_name": "Tang San",
                "attributes": {
                    "profile": {
                        "vi_name": "Đường Tam",
                        "role": "protagonist",
                        "voice_notes": "Lạnh lùng, điềm tĩnh",
                        "aliases": ["Thiên Thủ Đấu La"]
                    },
                    "cultivation_realm": "Hồn Tôn",
                    "Hoàng Kim Long Thể": [null],
                    "Kim Long Trảo": null,
                    "Hồn Lực": "Cấp ba",
                    "exam_score": "50 điểm (sau 7 quan)",
                    "address_terms": [
                        {
                            "with": "Thẩm Dập",
                            "self": "chúng em/em",
                            "other": "Thẩm lão sư",
                            "context": "học viên với giáo viên hướng dẫn sát hạch"
                        },
                        {
                            "with": "Thẩm lão sư",
                            "self": "chúng em",
                            "other": "Thẩm lão sư",
                            "context": "Hỏi Thẩm Dập về quy tắc thi ăn và xin điểm tuyệt đối"
                        }
                    ]
                },
                "pets": [
                    {
                        "name": "Tiểu Hắc",
                        "species": "U Minh Miêu",
                        "realm": "Bách Niên Hồn Thú",
                        "status": "Khế ước linh hồn"
                    }
                ]
            }
        """.trimIndent()
        val charDto = gson.fromJson(characterJson, CharacterProfileDto::class.java)

        val apiResponse = CharacterSnapshotResponseDto(
            bookId = "real_backend_book_id",
            editionId = "backend_edition_123",
            requestedChapter = 5,
            canonicalChapter = 5,
            bookRevision = 3,
            projectionRevision = 4,
            projectionStatus = "ready",
            snapshotStatus = "complete",
            completeThroughChapter = 5,
            pendingChapters = emptyList(),
            coverage = CoverageDto(
                processedRanges = listOf(listOf(1, 5)),
                missingRanges = emptyList()
            ),
            characters = listOf(charDto)
        )
        whenever(mockApiService.getSnapshot("backend_edition_123", 5)).thenReturn(apiResponse)
        whenever(mockBookBibleDao.getTotalCacheByteSize()).thenReturn(1024L)

        val result = repository.refreshSnapshot(source, 5)

        assertTrue(result.isSuccess)
        val snapshot = result.getOrNull()
        assertNotNull(snapshot)

        // Kiểm tra đúng backendBookId (không gán nhầm editionId vào bookId)
        assertEquals("real_backend_book_id", snapshot!!.bookId)
        assertEquals("backend_edition_123", snapshot.editionId)
        assertEquals(3, snapshot.bookRevision)
        assertEquals(4, snapshot.projectionRevision)
        assertEquals("ready", snapshot.projectionStatus)
        assertEquals(5, snapshot.completeThroughChapter)

        // Kiểm tra parse nested attributes.profile, pets, addressTerms và làm sạch extraAttributes
        assertEquals(1, snapshot.characters.size)
        val character = snapshot.characters[0]
        assertEquals("c1", character.id)
        assertEquals("Đường Tam", character.name)
        assertEquals("Tang San", character.originalName)
        assertEquals("protagonist", character.role)
        assertEquals("Lạnh lùng, điềm tĩnh", character.voiceNotes)
        assertTrue(character.aliases.contains("Thiên Thủ Đấu La"))
        assertEquals("Hồn Tôn", character.cultivationRealm)
        assertEquals(1, character.pets.size)
        assertEquals("Tiểu Hắc", character.pets[0].name)
        assertEquals("U Minh Miêu", character.pets[0].species)
        assertEquals("Bách Niên Hồn Thú", character.pets[0].realm)
        assertEquals("Khế ước linh hồn", character.pets[0].status)

        // Kiểm tra parse và GỘP nhóm chống trùng lặp Xưng hô & Giao tiếp (Address Terms)
        assertEquals(1, character.addressTerms.size)
        assertEquals("Thẩm Dập", character.addressTerms[0].targetName)
        assertEquals("chúng em / em", character.addressTerms[0].selfTerm)
        assertEquals("Thẩm lão sư", character.addressTerms[0].otherTerm)
        assertEquals(2, character.addressTerms[0].contexts.size)
        assertTrue(character.addressTerms[0].contexts.contains("học viên với giáo viên hướng dẫn sát hạch"))
        assertTrue(character.addressTerms[0].contexts.contains("Hỏi Thẩm Dập về quy tắc thi ăn và xin điểm tuyệt đối"))

        // Kiểm tra lọc sạch extraAttributes: không còn [null], null và dịch exam_score sang tiếng Việt "Điểm sát hạch"
        assertEquals(2, character.extraAttributes.size)
        val attrMap = character.extraAttributes.associate { it.label to it.value }
        assertEquals("Cấp ba", attrMap["Hồn Lực"])
        assertEquals("50 điểm (sau 7 quan)", attrMap["Điểm sát hạch"])
    }

    /**
     * Kiểm tra repository chuyển đúng bản ghi tổng hợp từ Room thành tiến trình domain cho màn hình cấp ứng dụng.
     */
    @Test
    fun testObserveProgressSummariesMapsDaoEntry() = runBlocking {
        val entry = BookBibleProgressEntry(
            localSourceKey = "ONLINE_NOVEL:novel_1",
            title = "Truyện thử nghiệm",
            author = "Tác giả",
            chapterCount = 120,
            latestSnapshotChapter = 18,
            latestSubmissionChapter = 20,
            snapshotStatus = "PARTIAL",
            submissionState = "PROCESSING",
            latestSnapshotUpdatedAt = 18L,
            latestSubmissionUpdatedAt = 20L,
            updatedAt = 42L
        )
        whenever(mockBookBibleDao.observeProgressEntries()).thenReturn(flowOf(listOf(entry)))

        val summary = repository.observeProgressSummaries().first().single()

        assertEquals(BookBibleSourceType.ONLINE_NOVEL, summary.source.type)
        assertEquals("novel_1", summary.source.sourceId)
        assertEquals(20, summary.latestChapterNumber)
        assertNull(summary.snapshotStatus)
        assertTrue(summary.submissionState is SubmissionState.Processing)
    }

    @Test
    fun testRefreshSnapshotParsesLegacyFlatAttributesFallback() = runBlocking {
        val source = BookBibleSource(BookBibleSourceType.ONLINE_NOVEL, "novel_1")
        val editionEntity = BookBibleEditionEntity(
            localSourceKey = source.uniqueKey,
            backendBookId = "online_book_id",
            backendEditionId = "online_edition_id",
            title = "Phàm Nhân Tu Tiên",
            author = "Vong Ngữ",
            chapterCount = 200
        )
        whenever(mockBookBibleDao.getEditionByLocalSourceKey(source.uniqueKey)).thenReturn(editionEntity)

        val characterJson = """
            {
                "character_id": "c2",
                "name": "Hàn Lập",
                "originalName": "Han Li",
                "attributes": {
                    "cultivation_realm": "Trúc Cơ kỳ",
                    "role": "main",
                    "voice_notes": "Cẩn trọng, ít nói"
                }
            }
        """.trimIndent()
        val charDto = gson.fromJson(characterJson, CharacterProfileDto::class.java)

        val apiResponse = CharacterSnapshotResponseDto(
            bookId = "online_book_id",
            editionId = "online_edition_id",
            requestedChapter = 10,
            canonicalChapter = 10,
            characters = listOf(charDto)
        )
        whenever(mockApiService.getSnapshot("online_edition_id", 10)).thenReturn(apiResponse)
        whenever(mockBookBibleDao.getTotalCacheByteSize()).thenReturn(1024L)

        val result = repository.refreshSnapshot(source, 10)

        assertTrue(result.isSuccess)
        val snapshot = result.getOrNull()!!
        val character = snapshot.characters[0]
        assertEquals("Hàn Lập", character.name)
        assertEquals("main", character.role)
        assertEquals("Cẩn trọng, ít nói", character.voiceNotes)
        assertEquals("Trúc Cơ kỳ", character.cultivationRealm)
    }

    @Test
    fun testCheckSubmissionStatus() = runBlocking {
        whenever(mockApiService.getSubmissionStatus("sub_completed")).thenReturn(
            ChapterSubmissionResponseDto(status = "completed")
        )
        whenever(mockApiService.getSubmissionStatus("sub_processing")).thenReturn(
            ChapterSubmissionResponseDto(status = "processing")
        )
        whenever(mockApiService.getSubmissionStatus("sub_failed")).thenReturn(
            ChapterSubmissionResponseDto(status = "failed", errorMessage = "Quá tải model LLM")
        )

        val res1 = repository.checkSubmissionStatus("sub_completed")
        assertEquals(SubmissionState.Completed, res1.getOrNull())

        val res2 = repository.checkSubmissionStatus("sub_processing")
        assertEquals(SubmissionState.Processing, res2.getOrNull())

        val res3 = repository.checkSubmissionStatus("sub_failed")
        assertTrue(res3.getOrNull() is SubmissionState.PermanentFailure)
        assertEquals("Quá tải model LLM", (res3.getOrNull() as SubmissionState.PermanentFailure).message)
    }

    @Test
    fun testTimelineEventMappingHandlesNullValueWithAttributeKeyAndAddressTerms() = runBlocking {
        val source = BookBibleSource(BookBibleSourceType.LOCAL_EPUB, "book_1")
        val editionEntity = BookBibleEditionEntity(
            localSourceKey = source.uniqueKey,
            backendBookId = "real_backend_book_id",
            backendEditionId = "backend_edition_123",
            title = "Đấu La Đại Lục 3",
            author = "Đường Gia Tam Thiếu",
            chapterCount = 300
        )
        whenever(mockBookBibleDao.getEditionByLocalSourceKey(source.uniqueKey)).thenReturn(editionEntity)
        whenever(mockBookBibleDao.getTimeline("backend_edition_123", 268, "c1")).thenReturn(null)
        whenever(mockBookBibleDao.getTotalCacheByteSize()).thenReturn(1024L)

        val eventsJson = """
            [
                {
                    "canonical_chapter": 264,
                    "category": "skill",
                    "attribute_key": "Hoàng Kim Long Thể",
                    "operation": "add",
                    "value": null,
                    "evidence": "Vừa nói, Đường Vũ Lân cấp tốc thôi động Hồn Hoàn... Hoàng Kim Long Thể?"
                },
                {
                    "canonical_chapter": 264,
                    "category": "skill",
                    "attribute_key": "Kim Long Trảo",
                    "operation": "add",
                    "value": null,
                    "evidence": "Kim Long Trảo theo đó phơi bày ra."
                },
                {
                    "canonical_chapter": 266,
                    "category": "relationship",
                    "attribute_key": "address_terms",
                    "operation": "add",
                    "value": {
                        "with": "Thẩm Dập",
                        "self": "chúng em/em",
                        "other": "Thẩm lão sư",
                        "context": "học viên với giáo viên hướng dẫn sát hạch"
                    }
                }
            ]
        """.trimIndent()
        val eventDtos = gson.fromJson(eventsJson, Array<CharacterEventDto>::class.java).toList()

        whenever(mockApiService.getCharacterTimeline("backend_edition_123", 268, "c1")).thenReturn(eventDtos)

        val result = repository.getCharacterTimeline(source, "c1", 268)
        assertTrue(result.isSuccess)
        val timeline = result.getOrNull()!!
        assertEquals(3, timeline.events.size)

        // Kiểm tra không bị chữ "null" mà format thành "Lĩnh ngộ / Xuất hiện: Hoàng Kim Long Thể"
        assertEquals("Lĩnh ngộ / Xuất hiện: Hoàng Kim Long Thể", timeline.events[0].displayValue)
        assertEquals("Lĩnh ngộ / Xuất hiện: Kim Long Trảo", timeline.events[1].displayValue)

        // Kiểm tra không bị raw JSON mà format thành "Xưng hô với Thẩm Dập (Tự xưng: chúng em/em • Gọi: Thẩm lão sư)"
        assertEquals("Xưng hô với Thẩm Dập (Tự xưng: chúng em/em • Gọi: Thẩm lão sư)", timeline.events[2].displayValue)
    }

    /**
     * Kiểm tra cơ chế tự động gộp trùng thuộc tính (vo_hun + Võ Hồn, Đoán Tạo Sư + blacksmith_rank)
     * và tự động chuyển các trường vũ khí/học viện/công pháp vào danh mục chuyên biệt.
     */
    @Test
    fun testDeduplicationAndCategoryPromotionInCharacterProfile() = runBlocking {
        val source = BookBibleSource(BookBibleSourceType.LOCAL_EPUB, "book_1")
        val editionEntity = BookBibleEditionEntity(
            localSourceKey = source.uniqueKey,
            backendBookId = "real_backend_book_id",
            backendEditionId = "backend_edition_123",
            title = "Đấu La Đại Lục 3",
            author = "Đường Gia Tam Thiếu",
            chapterCount = 305
        )
        whenever(mockBookBibleDao.getEditionByLocalSourceKey(source.uniqueKey)).thenReturn(editionEntity)
        whenever(mockBookBibleDao.getTotalCacheByteSize()).thenReturn(1024L)

        val characterJson = """
            {
                "character_id": "char-dvl",
                "original_name": "Đường Vũ Lân",
                "attributes": {
                    "profile": {
                        "role": "Nam chính",
                        "vi_name": "Đường Vũ Lân"
                    },
                    "hon_luc_level": "Tiên Thiên Hồn Lực cấp ba",
                    "vo_hun": "Lam Ngân Thảo",
                    "Hồn Lực": "Sơ bộ cảm ứng được Hồn Lực và Lam Ngân Thảo",
                    "lớp học": "lớp Hồn Sư",
                    "Học nghề": ["Học rèn với Mang Thiên"],
                    "Đoán Tạo Sư": "Đoán Tạo Sư cấp 5 (sơ cấp)",
                    "Hồn lực": "Nhị hoàn",
                    "Võ Hồn": ["Lam Ngân Thảo"],
                    "blacksmith_rank": "Tông Tượng cấp Đoán Tạo Sư",
                    "weapon": "Linh Đoán Trầm Ngân Chuy",
                    "hoc_sinh_su_lai_khac": "Công đọc sinh Ngoại viện Sử Lai Khắc",
                    "Học viện": ["Sử Lai Khắc học viện"],
                    "Loạn Phi Phong Chuy Pháp": "49 chuy",
                    "membership": ["Sử Lai Khắc Đoán Tạo Sư Hiệp Hội"]
                }
            }
        """.trimIndent()
        val charDto = gson.fromJson(characterJson, CharacterProfileDto::class.java)

        val apiResponse = CharacterSnapshotResponseDto(
            bookId = "real_backend_book_id",
            editionId = "backend_edition_123",
            requestedChapter = 305,
            canonicalChapter = 305,
            bookRevision = 9,
            projectionRevision = 9,
            projectionStatus = "ready",
            snapshotStatus = "complete",
            completeThroughChapter = 305,
            pendingChapters = emptyList(),
            coverage = CoverageDto(processedRanges = listOf(listOf(1, 305)), missingRanges = emptyList()),
            characters = listOf(charDto)
        )
        whenever(mockApiService.getSnapshot("backend_edition_123", 305)).thenReturn(apiResponse)

        val result = repository.refreshSnapshot(source, 305)
        assertTrue(result.isSuccess)
        val character = result.getOrNull()!!.characters.first()

        // 1. Kiểm tra vũ khí được đưa vào items
        assertTrue(character.items.contains("Linh Đoán Trầm Ngân Chuy"))

        // 2. Kiểm tra học viện và membership được đưa vào affiliations
        assertTrue(character.affiliations.contains("Sử Lai Khắc học viện"))
        assertTrue(character.affiliations.contains("Sử Lai Khắc Đoán Tạo Sư Hiệp Hội"))

        // 3. Kiểm tra Loạn Phi Phong Chùy Pháp được đưa vào techniques
        assertTrue(character.techniques.any { it.contains("Loạn Phi Phong Chuy Pháp") })

        // 4. Kiểm tra extraAttributes đã được gộp sạch chống trùng:
        val attrMap = character.extraAttributes.associate { it.label to it.value }

        // Võ hồn gộp 1 dòng
        assertEquals("Lam Ngân Thảo", attrMap["Võ hồn"])

        // Đoán Tạo Sư gộp 1 dòng (ưu tiên giá trị cập nhật nhất)
        assertEquals("Tông Tượng cấp Đoán Tạo Sư", attrMap["Đoán Tạo Sư"])

        // Hồn lực gộp 1 dòng
        assertEquals("Nhị hoàn", attrMap["Hồn Lực"])

        // Các thuộc tính không còn key thô/bị trùng
        assertNull(attrMap["weapon"])
        assertNull(attrMap["Học viện"])
        assertNull(attrMap["membership"])
        assertNull(attrMap["Loạn Phi Phong Chuy Pháp"])
    }

    /**
     * Kiểm tra trường hợp attributes["profile"] trả về dưới dạng JsonArray lồng nhau
     * (gồm các JSON Object và chuỗi mô tả tích lũy qua nhiều chương) vẫn trích xuất
     * chính xác tên tiếng Việt, vai trò Nam chính (isProtagonist = true), cảnh giới và các danh hiệu.
     */
    @Test
    fun testProfileAsJsonArrayExtractsMainCharacterAndAliases() = runBlocking {
        val source = BookBibleSource(BookBibleSourceType.ONLINE_NOVEL, "van-thu-chien-than")
        val editionEntity = BookBibleEditionEntity(
            localSourceKey = source.uniqueKey,
            backendBookId = "van-thu-chien-than",
            backendEditionId = "edition-09b38f054dfb52ef715c6cbc",
            title = "Vạn Thú Chiến Thần",
            author = "Tác giả",
            chapterCount = 100
        )
        whenever(mockBookBibleDao.getEditionByLocalSourceKey(source.uniqueKey)).thenReturn(editionEntity)
        whenever(mockBookBibleDao.getTotalCacheByteSize()).thenReturn(1024L)

        val characterJson = """
            {
                "character_id": "char-9168ffd6411e0a68325a5798",
                "original_name": "杜风",
                "attributes": {
                    "profile": [
                        {
                            "role": "Nam chính",
                            "aliases": [
                                "Số 1 Đỗ Phong",
                                "Đỗ Phong",
                                "Thần Đế Đỗ Phong"
                            ],
                            "vi_name": "Đỗ Phong",
                            "voice_notes": "Điềm tĩnh, cơ trí"
                        },
                        "Đan Hoàng chi tử (kiếp trước)",
                        {
                            "aliases": [
                                "Thất Vương Tử",
                                "七王子"
                            ]
                        }
                    ],
                    "realm": "Tôi Thể Kỳ tầng sáu",
                    "profession": "Nhị tinh trận pháp sư",
                    "techniques": [
                        "Lưu quang kiếm pháp",
                        "Thăng Nguyệt Kiếm Pháp (Hồ Nguyệt Trảm)"
                    ],
                    "weapon": [
                        "Ngân Long kiếm"
                    ],
                    "equipment": [
                        "Thẻ vàng phòng đấu giá",
                        "Cánh giả bộ phi hành"
                    ]
                },
                "changed_in_current_chapter": false,
                "last_changed_chapter": 36
            }
        """.trimIndent()
        val charDto = gson.fromJson(characterJson, CharacterProfileDto::class.java)

        val apiResponse = CharacterSnapshotResponseDto(
            bookId = "van-thu-chien-than",
            editionId = "edition-09b38f054dfb52ef715c6cbc",
            requestedChapter = 52,
            canonicalChapter = 52,
            bookRevision = 1,
            projectionRevision = 1,
            projectionStatus = "ready",
            snapshotStatus = "complete",
            completeThroughChapter = 52,
            pendingChapters = emptyList(),
            coverage = CoverageDto(processedRanges = listOf(listOf(1, 52)), missingRanges = emptyList()),
            characters = listOf(charDto)
        )
        whenever(mockApiService.getSnapshot("edition-09b38f054dfb52ef715c6cbc", 52)).thenReturn(apiResponse)

        val result = repository.refreshSnapshot(source, 52)
        assertTrue(result.isSuccess)
        val character = result.getOrNull()!!.characters.first()

        // 1. Kiểm tra tên tiếng Việt được trích xuất từ object con trong JsonArray
        assertEquals("Đỗ Phong", character.name)
        assertEquals("杜风", character.originalName)

        // 2. Kiểm tra vai trò và nhận diện Nhân vật chính
        assertEquals("Nam chính", character.role)
        assertTrue(character.isProtagonist)

        // 3. Kiểm tra cảnh giới từ trường realm
        assertEquals("Tôi Thể Kỳ tầng sáu", character.cultivationRealm)

        // 4. Kiểm tra bí danh được gộp từ các object con và chuỗi string trong mảng profile
        assertTrue(character.titles.contains("Số 1 Đỗ Phong"))
        assertTrue(character.titles.contains("Thần Đế Đỗ Phong"))
        assertTrue(character.titles.contains("Đan Hoàng chi tử (kiếp trước)"))
        assertTrue(character.titles.contains("Thất Vương Tử"))
        assertTrue(character.titles.contains("七王子"))

        // 5. Kiểm tra vũ khí và trang bị được đưa vào items
        assertTrue(character.items.contains("Ngân Long kiếm"))
        assertTrue(character.items.contains("Thẻ vàng phòng đấu giá"))
        assertTrue(character.items.contains("Cánh giả bộ phi hành"))

        // 6. Kiểm tra công pháp
        assertTrue(character.techniques.contains("Lưu quang kiếm pháp"))
    }

    @Test
    fun testChineseAddressTermsResolvedToVietnameseNames() = runBlocking {
        val source = BookBibleSource(BookBibleSourceType.ONLINE_NOVEL, "test-book")
        val editionEntity = BookBibleEditionEntity(
            localSourceKey = source.uniqueKey,
            backendBookId = "test-book",
            backendEditionId = "edition-123",
            title = "Test Book",
            author = "Tác giả",
            chapterCount = 100
        )
        whenever(mockBookBibleDao.getEditionByLocalSourceKey(source.uniqueKey)).thenReturn(editionEntity)
        whenever(mockBookBibleDao.getTotalCacheByteSize()).thenReturn(1024L)

        val duPhongJson = """
        {
            "character_id": "char-du-phong",
            "original_name": "杜风",
            "attributes": {
                "profile": { "vi_name": "Đỗ Phong", "role": "Nam chính" },
                "address_terms": [
                    {
                        "counterpart_text": "司徒微微",
                        "counterpart_original_name": "司徒微微",
                        "self_term": "ta",
                        "other_term": "ngươi / cô nương / huynh",
                        "context": "Tư Đồ Vi Vi tiếp đón Đỗ Phong ở lầu ba"
                    },
                    {
                        "with": "陆萝",
                        "self": "Đỗ mỗ",
                        "other": "cô nương",
                        "context": "Khi đến tìm Lục La tại Túy Tiên Lâu"
                    }
                ]
            }
        }
        """.trimIndent()

        val tuDoViViJson = """
        {
            "character_id": "char-tudo-vivi",
            "original_name": "司徒微微",
            "attributes": {
                "profile": { "vi_name": "Tư Đồ Vi Vi", "role": "Chưởng quầy Thất Xảo Các" }
            }
        }
        """.trimIndent()

        val lucLaJson = """
        {
            "character_id": "char-luc-la",
            "original_name": "陆萝",
            "attributes": {
                "profile": { "vi_name": "Lục La", "role": "Bạn bè" }
            }
        }
        """.trimIndent()

        val char1 = gson.fromJson(duPhongJson, CharacterProfileDto::class.java)
        val char2 = gson.fromJson(tuDoViViJson, CharacterProfileDto::class.java)
        val char3 = gson.fromJson(lucLaJson, CharacterProfileDto::class.java)

        val apiResponse = CharacterSnapshotResponseDto(
            bookId = "test-book",
            editionId = "edition-123",
            requestedChapter = 52,
            canonicalChapter = 52,
            characters = listOf(char1, char2, char3)
        )
        whenever(mockApiService.getSnapshot("edition-123", 52)).thenReturn(apiResponse)

        val result = repository.refreshSnapshot(source, 52)
        assertTrue(result.isSuccess)
        val duPhong = result.getOrNull()!!.characters.first { it.id == "char-du-phong" }

        assertEquals(2, duPhong.addressTerms.size)
        val term1 = duPhong.addressTerms.firstOrNull { it.targetName == "Tư Đồ Vi Vi" }
        org.junit.Assert.assertNotNull(term1)
        assertEquals("ta", term1!!.selfTerm)
        assertEquals("ngươi / cô nương / huynh", term1.otherTerm)

        val term2 = duPhong.addressTerms.firstOrNull { it.targetName == "Lục La" }
        org.junit.Assert.assertNotNull(term2)
        assertEquals("Đỗ mỗ", term2!!.selfTerm)
    }

    /**
     * Tạo repository với công tắc Book Bible ở trạng thái mặc định (chưa bật cho sách nào).
     *
     * @return Repository dùng chung mock với các test khác.
     */
    private fun repositoryWithoutConsent() = BookBibleRepositoryImpl(
        context = mockContext,
        apiService = mockApiService,
        onlineNovelApiService = mockOnlineNovelApiService,
        bookDao = mockBookDao,
        bookBibleDao = mockBookBibleDao,
        payloadStore = mockPayloadStore,
        fingerprintGenerator = mockFingerprintGenerator,
        storageManager = mockStorageManager,
        gson = gson,
        consentStore = testConsentStore()
    )

    /** Sách EPUB cục bộ chưa bật Book Bible: không ghi nội dung chương, không tạo submission. */
    @Test
    fun enqueueChapterSubmissionSkipsLocalBookWithoutConsent() = runBlocking {
        val repository = repositoryWithoutConsent()

        val result = repository.enqueueChapterSubmission(
            source = BookBibleSource(BookBibleSourceType.LOCAL_EPUB, "private_book"),
            chapterNumber = 1,
            chapterTitle = "Chương 1",
            totalChapters = 10,
            sourceContent = "Nội dung riêng tư",
            bookTitle = "Sách riêng",
            author = "Tác giả"
        )

        assertTrue(result.isSuccess)
        verify(mockPayloadStore, never()).writePayloadAtomically(any(), any())
        verify(mockBookBibleDao, never()).insertOrReplaceSubmission(any())
    }

    /** Sách EPUB cục bộ chưa bật Book Bible: không gọi máy chủ để lấy snapshot (kể cả gửi metadata). */
    @Test
    fun refreshSnapshotDoesNotCallServerWithoutConsent() = runBlocking {
        val repository = repositoryWithoutConsent()

        val result = repository.refreshSnapshot(BookBibleSource(BookBibleSourceType.LOCAL_EPUB, "private_book"), 3)

        assertTrue(result.exceptionOrNull() is BookBibleSubmissionDisabledException)
        verifyNoInteractions(mockApiService)
    }

    /** Xóa sách phải dọn file payload chỉ nguồn đó dùng, và giữ file còn được nguồn khác tham chiếu. */
    @Test
    fun deleteDataForBookRemovesOnlyUnsharedPayloadFiles() = runBlocking {
        val localKey = BookBibleSource(BookBibleSourceType.LOCAL_EPUB, "book_1").uniqueKey
        val onlineKey = BookBibleSource(BookBibleSourceType.ONLINE_NOVEL, "book_1").uniqueKey
        whenever(mockBookBibleDao.getPayloadPathsByLocalSource(localKey)).thenReturn(listOf("/p/a.txt", "/p/shared.txt"))
        whenever(mockBookBibleDao.getPayloadPathsByLocalSource(onlineKey)).thenReturn(emptyList())
        whenever(mockBookBibleDao.countSubmissionsUsingPayload("/p/a.txt")).thenReturn(0)
        whenever(mockBookBibleDao.countSubmissionsUsingPayload("/p/shared.txt")).thenReturn(1)

        repository.deleteDataForBook("book_1")

        inOrder(mockBookBibleDao, mockPayloadStore) {
            verify(mockBookBibleDao).getPayloadPathsByLocalSource(localKey)
            verify(mockBookBibleDao).deleteByLocalSourceKey(localKey)
            verify(mockPayloadStore).deletePayload("/p/a.txt")
        }
        verify(mockBookBibleDao).deleteByLocalSourceKey(onlineKey)
        verify(mockPayloadStore, never()).deletePayload("/p/shared.txt")
    }
}
