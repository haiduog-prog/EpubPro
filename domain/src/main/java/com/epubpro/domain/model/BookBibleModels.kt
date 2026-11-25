package com.epubpro.domain.model

/**
 * Loại nguồn sách hỗ trợ Book Bible.
 */
enum class BookBibleSourceType {
    /** Sách EPUB lưu trữ cục bộ trên thiết bị */
    LOCAL_EPUB,

    /** Truyện online đọc từ máy chủ backend */
    ONLINE_NOVEL
}

/**
 * Định danh nguồn sách trong hệ thống Book Bible.
 *
 * @property type Loại nguồn sách ([BookBibleSourceType]).
 * @property sourceId Mã định danh nguồn (Book ID hoặc Novel ID).
 */
data class BookBibleSource(
    val type: BookBibleSourceType,
    val sourceId: String
) {
    /**
     * Khóa định danh duy nhất của nguồn để lưu trữ trong database.
     */
    val uniqueKey: String get() = "${type.name}:$sourceId"

    /**
     * `true` nếu nguồn này cần người dùng bật Book Bible trước khi gửi nội dung chương lên máy chủ.
     * Chỉ sách EPUB cục bộ (file cá nhân của người dùng) cần đồng ý; truyện online vốn đã được tải từ chính
     * máy chủ backend nên không phát sinh thêm dữ liệu cá nhân.
     */
    val requiresSubmissionConsent: Boolean get() = type == BookBibleSourceType.LOCAL_EPUB
}

/**
 * Thông tin chương nguồn của sách với chỉ số 1-based phục vụ trích xuất và phân tích Book Bible.
 *
 * @property chapterNumber Thứ tự chương (1-based index).
 * @property chapterTitle Tiêu đề chương.
 * @property totalChapters Tổng số chương của sách/truyện.
 */
data class BookBibleChapter(
    val chapterNumber: Int,
    val chapterTitle: String,
    val totalChapters: Int
)

/**
 * Tóm tắt tiến trình Book Bible của một truyện để hiển thị ở màn hình cấp ứng dụng.
 *
 * @property source Nguồn truyện tương ứng.
 * @property title Tên truyện.
 * @property author Tác giả truyện.
 * @property totalChapters Tổng số chương của truyện.
 * @property latestChapterNumber Chương lớn nhất đã có submission hoặc snapshot.
 * @property snapshotStatus Trạng thái snapshot mới nhất, nếu đã có snapshot.
 * @property submissionState Trạng thái submission mới nhất, nếu chưa có snapshot.
 * @property updatedAt Thời điểm cập nhật tiến trình gần nhất theo mili-giây.
 * @property backendBookId Mã sách Book Bible dùng cho thao tác duyệt, nếu backend đã cung cấp.
 * @property eventCount Tổng số sự kiện tiến trình trên backend.
 * @property pendingEventCount Số sự kiện đang chờ duyệt trên backend.
 */
data class BookBibleProgressSummary(
    val source: BookBibleSource,
    val title: String,
    val author: String,
    val totalChapters: Int,
    val latestChapterNumber: Int,
    val snapshotStatus: SnapshotStatus? = null,
    val submissionState: SubmissionState? = null,
    val updatedAt: Long = System.currentTimeMillis(),
    val backendBookId: String? = null,
    val eventCount: Int = 0,
    val pendingEventCount: Int = 0
)

/**
 * Tóm tắt một cuốn sách có dữ liệu tiến trình trên backend để đưa vào hàng đợi duyệt.
 *
 * @property bookId Mã sách trên backend.
 * @property title Tên sách.
 * @property author Tác giả.
 * @property language Ngôn ngữ của sách.
 * @property revision Revision hiện tại của Book Bible.
 * @property editionCount Số ấn bản đã đăng ký.
 * @property eventCount Tổng số sự kiện đã trích xuất.
 * @property pendingEventCount Số sự kiện đang chờ người dùng duyệt.
 */
data class BookBibleReviewBook(
    val bookId: String,
    val title: String,
    val author: String = "",
    val language: String = "vi",
    val revision: Int = 0,
    val editionCount: Int = 0,
    val eventCount: Int = 0,
    val pendingEventCount: Int = 0
)

/**
 * Sự kiện tiến trình nhân vật đang được người dùng xem xét.
 *
 * @property eventId Mã sự kiện.
 * @property bookId Mã sách chứa sự kiện.
 * @property characterId Mã định danh nhân vật.
 * @property characterOriginalName Tên gốc của nhân vật.
 * @property canonicalChapter Chương canonical nơi sự kiện xuất hiện.
 * @property category Nhóm thông tin của sự kiện.
 * @property attributeKey Khóa thuộc tính bị thay đổi.
 * @property operation Phép thay đổi, ví dụ set, add hoặc remove.
 * @property valueJson Giá trị gốc ở dạng JSON để hỗ trợ chỉnh sửa chính xác.
 * @property displayValue Giá trị đã được backend định dạng để hiển thị, nếu có.
 * @property certainty Mức độ chắc chắn của sự kiện.
 * @property status Trạng thái duyệt hiện tại.
 * @property evidence Bằng chứng văn bản đi kèm.
 * @property confidence Độ tin cậy do bộ trích xuất cung cấp.
 * @property sourceGroupId Nhóm nguồn sinh ra sự kiện.
 * @property sourceSubmissionId Submission sinh ra sự kiện.
 * @property createdAt Thời điểm tạo sự kiện ở backend.
 */
data class BookBibleReviewEvent(
    val eventId: String,
    val bookId: String,
    val characterId: String,
    val characterOriginalName: String,
    val canonicalChapter: Int,
    val category: String,
    val attributeKey: String,
    val operation: String,
    val valueJson: String? = null,
    val displayValue: String? = null,
    val certainty: String? = "observed",
    val status: String = "pending",
    val evidence: String? = null,
    val confidence: Double? = null,
    val sourceGroupId: String? = null,
    val sourceSubmissionId: String? = null,
    val createdAt: String? = null
)

/**
 * Dữ liệu người dùng gửi khi sửa hoặc duyệt một sự kiện tiến trình.
 *
 * @property valueJson Giá trị mới ở dạng JSON hoặc chuỗi thông thường.
 * @property evidence Bằng chứng mới, có thể để trống để xóa.
 * @property confidence Độ tin cậy mới, nếu người dùng có thay đổi.
 */
data class BookBibleReviewEventEdit(
    val valueJson: String? = null,
    val evidence: String? = null,
    val confidence: Double? = null
)

/**
 * Trạng thái dữ liệu của bản Snapshot Book Bible.
 */
enum class SnapshotStatus {
    /** Dữ liệu phân tích hoàn chỉnh tất cả các chương từ đầu đến chương hiện tại */
    COMPLETE,

    /** Dữ liệu chỉ bao phủ một phần các chương do người dùng nhảy cóc chương */
    PARTIAL,

    /** Backend đang trong tiến trình phân tích dữ liệu */
    PROCESSING,

    /** Chưa có nhân vật nào xuất hiện hoặc chưa phân tích được dữ liệu */
    EMPTY,

    /** Quá trình phân tích hoặc tải snapshot thất bại */
    FAILED
}

/**
 * Đoạn khoảng chương liên tục (inclusive: từ start đến end).
 *
 * @property start Chương bắt đầu (1-based).
 * @property end Chương kết thúc (1-based).
 */
data class ChapterRange(
    val start: Int,
    val end: Int
)

/**
 * Độ bao phủ các chương đã được phân tích và các chương còn thiếu.
 *
 * @property processedRanges Danh sách các khoảng chương đã được AI xử lý.
 * @property missingRanges Danh sách các khoảng chương còn thiếu trước mốc hiện tại.
 */
data class SnapshotCoverage(
    val processedRanges: List<ChapterRange> = emptyList(),
    val missingRanges: List<ChapterRange> = emptyList()
)

/**
 * Mối quan hệ giữa nhân vật với các nhân vật khác trong tác phẩm.
 *
 * @property targetName Tên nhân vật liên quan.
 * @property relationType Loại quan hệ (ví dụ: "Sư phụ", "Đồng môn", "Kẻ thù", "Đạo lữ").
 * @property description Chi tiết thêm về mối quan hệ.
 */
data class CharacterRelationship(
    val targetName: String,
    val relationType: String,
    val description: String? = null
)

/**
 * Thuộc tính mở rộng dạng key-value hiển thị an toàn.
 *
 * @property key Khóa định danh thuộc tính.
 * @property label Nhãn hiển thị tiếng Việt.
 * @property value Giá trị hiển thị tiếng Việt.
 */
data class ExtraAttribute(
    val key: String,
    val label: String,
    val value: String
)

/**
 * Dấu vân tay định danh cấu trúc và nội dung thực tế của sách phục vụ nhận diện Book Bible.
 *
 * @property file Mã băm SHA-256 nội dung bytes thực tế của tệp sách.
 * @property edition Chuỗi định danh ổn định của ấn bản.
 * @property structure Mã băm SHA-256 cấu trúc mục lục/spine chuẩn hóa.
 * @property sampledChapters Danh sách mã băm nội dung các chương mẫu.
 */
data class BookFingerprints(
    val file: String,
    val edition: String,
    val structure: String,
    val sampledChapters: List<String> = emptyList()
)

/**
 * Quy cách xưng hô và cách giao tiếp của nhân vật với các nhân vật khác.
 *
 * @property targetName Tên đối tượng xưng hô (ví dụ: "Thẩm Dập", "Tiểu Vũ").
 * @property selfTerm Cách nhân vật tự xưng (ví dụ: "em / chúng em", "huynh", "ta").
 * @property otherTerm Cách nhân vật gọi đối phương (ví dụ: "Thẩm lão sư", "muội", "sư phụ").
 * @property context Ngữ cảnh sử dụng cách xưng hô (ví dụ: "học viên với giáo viên hướng dẫn sát hạch").
 * @property contexts Danh sách các ngữ cảnh sử dụng xưng hô đã được gộp nhóm chống trùng lặp.
 */
data class CharacterAddressTerm(
    val targetName: String,
    val selfTerm: String? = null,
    val otherTerm: String? = null,
    val context: String? = null,
    val contexts: List<String> = emptyList()
)

/**
 * Thông tin linh thú / thú cưng (Pet) đi kèm với nhân vật.
 *
 * @property name Tên của thú cưng (ví dụ: "Tiểu Hắc").
 * @property species Chủng loại linh thú (ví dụ: "U Minh Miêu", "Cửu Vĩ Thiên Hồ").
 * @property realm Cảnh giới / Tu vi của thú cưng (ví dụ: "Bách Niên Hồn Thú", "Cấp 4").
 * @property status Trạng thái / Loại khế ước (ví dụ: "Khế ước linh hồn", "Nuôi dưỡng", "Đồng hành").
 */
data class CharacterPet(
    val name: String,
    val species: String? = null,
    val realm: String? = null,
    val status: String? = null
)

/**
 * Hồ sơ chi tiết của một nhân vật được giới hạn chống spoiler tại chương hiện tại.
 *
 * @property id Mã định danh duy nhất của nhân vật trên backend.
 * @property name Tên nhân vật (đã chuẩn hóa hoặc tên gốc).
 * @property originalName Tên gốc tiếng Trung/Anh nếu có.
 * @property role Vai trò nhân vật (ví dụ: "Nhân vật chính", "Phản diện", "Nhân vật phụ").
 * @property voiceNotes Ghi chú tính cách, âm điệu giọng nói (phục vụ hiển thị và TTS).
 * @property aliases Danh sách biệt danh, tôn hiệu, bí danh khác của nhân vật.
 * @property changedInCurrentChapter Cờ đánh dấu nhân vật có sự kiện/tiến triển mới trong chương hiện tại.
 * @property lastChangedChapter Chương gần nhất nhân vật có cập nhật trạng thái/thuộc tính.
 * @property cultivationRealm Cảnh giới tu vi / cấp bậc hiện tại.
 * @property techniques Danh sách công pháp / võ học tu luyện.
 * @property skills Danh sách kỹ năng / năng lực đặc biệt.
 * @property items Danh sách trang bị / pháp bảo / vật phẩm sở hữu.
 * @property pets Danh sách linh thú / thú cưng thuộc sở hữu hoặc đồng hành.
 * @property addressTerms Danh sách quy cách xưng hô và giao tiếp với các nhân vật khác.
 * @property relationships Danh sách các mối quan hệ xã hội.
 * @property affiliations Danh sách các bang phái / thế lực / tông môn trực thuộc.
 * @property titles Danh sách các danh hiệu / xưng hiệu / bí danh.
 * @property extraAttributes Danh sách các thuộc tính tùy biến khác.
 */
data class CharacterProfile(
    val id: String,
    val name: String,
    val originalName: String? = null,
    val role: String? = null,
    val isMain: Boolean = false,
    val voiceNotes: String? = null,
    val aliases: List<String> = emptyList(),
    val changedInCurrentChapter: Boolean = false,
    val lastChangedChapter: Int = 1,
    val cultivationRealm: String? = null,
    val techniques: List<String> = emptyList(),
    val skills: List<String> = emptyList(),
    val items: List<String> = emptyList(),
    val pets: List<CharacterPet> = emptyList(),
    val addressTerms: List<CharacterAddressTerm> = emptyList(),
    val relationships: List<CharacterRelationship> = emptyList(),
    val affiliations: List<String> = emptyList(),
    val titles: List<String> = emptyList(),
    val extraAttributes: List<ExtraAttribute> = emptyList()
) {
    /**
     * Kiểm tra nhân vật có phải là Nhân vật chính (Protagonist / Main Character) hay không.
     */
    val isProtagonist: Boolean
        get() {
            if (isMain) return true
            val r = role?.trim()?.lowercase(java.util.Locale.ROOT) ?: return false
            return r in listOf(
                "protagonist", "main", "lead", "mc", "nhân vật chính", "nhan vat chinh",
                "nam chính", "nam chinh", "nữ chính", "nu chinh", "chính", "nam chủ", "nam chu", "nữ chủ", "nu chu",
                "main character", "lead character", "central character", "hero", "heroine",
                "chủ giác", "chu giac", "nam chủ giác", "nam chu giac", "nữ chủ giác", "nu chu giac",
                "主角", "男主角", "女主角", "男主", "女主"
            ) || r.startsWith("nam chính") || r.startsWith("nữ chính") || r.startsWith("nhân vật chính")
                || r.startsWith("nam chủ") || r.startsWith("nữ chủ")
                || r.startsWith("main character") || r.startsWith("protagonist")
                || r.contains("nhân vật chính") || r.contains("nhan vat chinh")
                || r.contains("nam chính") || r.contains("nam chinh")
                || r.contains("nữ chính") || r.contains("nu chinh")
                || r.contains("nam chủ") || r.contains("nam chu")
                || r.contains("nữ chủ") || r.contains("nu chu")
                || r.contains("main character") || r.contains("protagonist")
                || r.contains("主角") || r.contains("男主") || r.contains("女主")
        }

    /**
     * Kiểm tra nhân vật có phải là Phản diện (Antagonist / Villain) hay không.
     */
    val isAntagonist: Boolean
        get() {
            val r = role?.trim()?.lowercase(java.util.Locale.ROOT) ?: return false
            return r in listOf(
                "antagonist", "villain", "phản diện", "phan dien", "kẻ thù", "ke thu",
                "phản phái", "phan phai", "反派", "敌人", "反角"
            ) || r.startsWith("phản diện") || r.startsWith("kẻ thù") || r.startsWith("phản phái")
                || r.startsWith("antagonist") || r.startsWith("villain")
                || r.contains("phản diện") || r.contains("phan dien")
                || r.contains("kẻ thù") || r.contains("ke thu")
                || r.contains("phản phái") || r.contains("phan phai")
                || r.contains("反派")
        }
}

/**
 * Bản Snapshot Book Bible đại diện cho toàn bộ hồ sơ nhân vật tại một mốc chương xác định.
 *
 * @property bookId Mã backend của cuốn sách.
 * @property editionId Mã backend của phiên bản sách (edition).
 * @property requestedChapter Mốc chương người dùng yêu cầu xem (1-based).
 * @property canonicalChapter Mốc chương chuẩn mà backend trả về (không bao giờ vượt quá requestedChapter).
 * @property status Trạng thái của snapshot ([SnapshotStatus]).
 * @property coverage Độ bao phủ của các chương đã phân tích ([SnapshotCoverage]).
 * @property revision Số phiên bản sửa đổi dữ liệu (dùng để kiểm tra cache mới nhất).
 * @property bookRevision Số phiên bản cập nhật của đầu sách.
 * @property projectionRevision Số phiên bản chiếu dữ liệu tại mốc chương.
 * @property projectionStatus Trạng thái sẵn sàng của projection ("ready", "stale", "pending").
 * @property completeThroughChapter Mốc chương cao nhất đã được phân tích hoàn chỉnh liên tục.
 * @property pendingChapters Danh sách các chương đang chờ phân tích.
 * @property updatedAt Thời điểm cập nhật cuối cùng tính theo mili-giây.
 * @property characters Danh sách toàn bộ hồ sơ nhân vật xuất hiện đến chương này.
 */
data class BookBibleSnapshot(
    val bookId: String,
    val editionId: String,
    val requestedChapter: Int,
    val canonicalChapter: Int,
    val status: SnapshotStatus,
    val coverage: SnapshotCoverage = SnapshotCoverage(),
    val revision: Long = 1L,
    val bookRevision: Int = 0,
    val projectionRevision: Int = 0,
    val projectionStatus: String = "ready",
    val completeThroughChapter: Int? = null,
    val pendingChapters: List<Int> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis(),
    val characters: List<CharacterProfile> = emptyList()
)

/**
 * Một sự kiện diễn tiến của nhân vật trong dòng thời gian.
 *
 * @property chapter Thứ tự chương diễn ra sự kiện.
 * @property category Phân loại sự kiện (ví dụ: "realm", "skill", "item", "relationship", "faction").
 * @property operation Loại tác động (ví dụ: "set", "add", "remove", "increase", "decrease", "link", "unlink", "correct").
 * @property displayValue Chuỗi hiển thị nội dung sự kiện bằng tiếng Việt.
 * @property certainty Mức độ tin cậy của thông tin ("observed", "stated", "rumor", "inferred", "contradicted").
 * @property evidence Đoạn văn bản trích dẫn làm chứng cứ trong nguyên tác (nếu có).
 * @property confidence Độ tin cậy của mô hình AI trích xuất (chỉ ghi log, không hiển thị trên UI).
 */
data class CharacterTimelineEvent(
    val chapter: Int,
    val category: String,
    val operation: String,
    val displayValue: String,
    val certainty: String? = "observed",
    val evidence: String? = null,
    val confidence: Double? = null
)

/**
 * Dòng thời gian tiến trình của một nhân vật cụ thể đến mốc chương hiện tại.
 *
 * @property characterId Mã nhân vật.
 * @property characterName Tên nhân vật.
 * @property events Danh sách các sự kiện được sắp xếp theo trình tự chương.
 */
data class CharacterTimeline(
    val characterId: String,
    val characterName: String,
    val events: List<CharacterTimelineEvent> = emptyList()
)

/**
 * Trạng thái gửi nội dung chương lên máy chủ backend để phân tích.
 */
sealed class SubmissionState {
    /** Đang chờ mạng để gửi */
    object Pending : SubmissionState()

    /** Đang trong tiến trình truyền dữ liệu lên backend */
    object Submitting : SubmissionState()

    /** Backend đã tiếp nhận yêu cầu phân tích thành công (HTTP 200/202/409) */
    object Accepted : SubmissionState()

    /** Backend đang phân tích AI */
    object Processing : SubmissionState()

    /** Backend đã phân tích xong hoàn tất */
    object Completed : SubmissionState()

    /** Lỗi mạng hoặc server tạm thời có thể tự động thử lại (HTTP 408, 429, 5xx, mất mạng) */
    data class RetryableFailure(val message: String) : SubmissionState()

    /** Lỗi vĩnh viễn không thể retry (HTTP 400, 413 Payload Too Large, lỗi dữ liệu nguồn) */
    data class PermanentFailure(val message: String) : SubmissionState()
}
