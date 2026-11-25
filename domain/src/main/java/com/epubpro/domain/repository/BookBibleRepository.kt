package com.epubpro.domain.repository

import com.epubpro.domain.model.BookBibleProgressSummary
import com.epubpro.domain.model.BookBibleReviewBook
import com.epubpro.domain.model.BookBibleReviewEvent
import com.epubpro.domain.model.BookBibleReviewEventEdit
import com.epubpro.domain.model.BookBibleSnapshot
import com.epubpro.domain.model.BookBibleSource
import com.epubpro.domain.model.CharacterTimeline
import com.epubpro.domain.model.SubmissionState
import kotlinx.coroutines.flow.Flow

/**
 * Interface Repository quản lý toàn bộ tương tác nghiệp vụ cho hệ thống Book Bible:
 * lập lịch gửi chương nguồn, quan sát hồ sơ nhân vật snapshot từ cache/mạng, và tra cứu timeline chống spoiler.
 */
interface BookBibleRepository {

    /**
     * Đưa một chương sách vừa được mở vào hàng đợi gửi dữ liệu lên backend để phân tích.
     * Thao tác này là bất đồng bộ và có tính idempotent: nếu chương đã được gửi hoặc cùng mã băm nguồn thì không tạo job trùng.
     *
     * @param source Nguồn sách ([BookBibleSource]).
     * @param chapterNumber Thứ tự chương (1-based index).
     * @param chapterTitle Tiêu đề chương.
     * @param totalChapters Tổng số chương của sách.
     * @param sourceContent Nội dung văn bản thô (plain text) của chương nguồn.
     * @param bookTitle Tên sách.
     * @param author Tên tác giả.
     * @return [Result] trả về thành công nếu đã ghi payload và lên lịch WorkManager thành công, hoặc thành công mà
     *         không làm gì nếu người dùng chưa bật Book Bible cho nguồn này (xem [observeSubmissionEnabled]).
     */
    suspend fun enqueueChapterSubmission(
        source: BookBibleSource,
        chapterNumber: Int,
        chapterTitle: String,
        totalChapters: Int,
        sourceContent: String,
        bookTitle: String,
        author: String
    ): Result<Unit>

    /**
     * Quan sát việc người dùng có cho phép gửi nội dung chương của nguồn này lên máy chủ Book Bible hay không.
     * Nguồn không cần đồng ý ([BookBibleSource.requiresSubmissionConsent] = `false`) luôn phát `true`; sách EPUB
     * cục bộ mặc định `false` cho tới khi người dùng bật.
     *
     * @param source Nguồn sách.
     * @return [Flow] phát trạng thái cho phép hiện tại và mỗi lần thay đổi.
     */
    fun observeSubmissionEnabled(source: BookBibleSource): Flow<Boolean>

    /**
     * Bật hoặc tắt việc gửi nội dung chương của một nguồn lên máy chủ Book Bible. Khi tắt, mọi yêu cầu gửi đang
     * chờ của nguồn sẽ bị bỏ (và xóa nội dung tạm) thay vì được gửi đi. Không có tác dụng với nguồn không cần
     * đồng ý.
     *
     * @param source Nguồn sách.
     * @param enabled `true` để cho phép gửi, `false` để dừng.
     */
    suspend fun setSubmissionEnabled(source: BookBibleSource, enabled: Boolean)

    /**
     * Quan sát luồng dữ liệu Snapshot của Book Bible tại một mốc chương xác định.
     * Ưu tiên phát ra dữ liệu có sẵn từ Room Database Cache trước (Cache-first).
     *
     * @param source Nguồn sách.
     * @param chapterNumber Mốc chương yêu cầu (1-based).
     * @return [Flow] phát ra [BookBibleSnapshot] hoặc `null` nếu chưa có dữ liệu trong cache.
     */
    fun observeSnapshot(
        source: BookBibleSource,
        chapterNumber: Int
    ): Flow<BookBibleSnapshot?>

    /**
     * Quan sát danh sách các truyện đã được đăng ký hoặc có dữ liệu Book Bible trong bộ nhớ cục bộ.
     *
     * @return [Flow] phát ra danh sách tóm tắt tiến trình, sắp xếp theo lần cập nhật gần nhất.
     */
    fun observeProgressSummaries(): Flow<List<BookBibleProgressSummary>>

    /**
     * Lấy danh sách sách có dữ liệu Book Bible từ backend để hiển thị hàng đợi duyệt.
     *
     * @return [Result] chứa danh sách sách và số lượng sự kiện đang chờ duyệt.
     */
    suspend fun getReviewBooks(): Result<List<BookBibleReviewBook>>

    /**
     * Lấy các sự kiện tiến trình theo sách và trạng thái duyệt.
     *
     * @param bookId Mã sách trên backend.
     * @param status Trạng thái cần lọc, mặc định là `pending`.
     * @param canonicalChapter Có thể giới hạn theo chương canonical.
     * @return [Result] chứa danh sách sự kiện cần hiển thị.
     */
    suspend fun getReviewEvents(
        bookId: String,
        status: String = "pending",
        canonicalChapter: Int? = null
    ): Result<List<BookBibleReviewEvent>>

    /**
     * Duyệt một sự kiện và có thể cập nhật bằng chứng hoặc giá trị đi kèm.
     *
     * @param eventId Mã sự kiện cần duyệt.
     * @param edit Dữ liệu chỉnh sửa gửi kèm request.
     * @return [Result] chứa sự kiện sau khi duyệt.
     */
    suspend fun approveReviewEvent(
        eventId: String,
        edit: BookBibleReviewEventEdit = BookBibleReviewEventEdit()
    ): Result<BookBibleReviewEvent>

    /**
     * Cập nhật giá trị, bằng chứng và độ tin cậy của một sự kiện.
     *
     * @param eventId Mã sự kiện cần cập nhật.
     * @param edit Dữ liệu mới của sự kiện.
     * @return [Result] chứa sự kiện sau khi cập nhật.
     */
    suspend fun updateReviewEvent(
        eventId: String,
        edit: BookBibleReviewEventEdit
    ): Result<BookBibleReviewEvent>

    /**
     * Từ chối một sự kiện tiến trình.
     *
     * @param eventId Mã sự kiện cần từ chối.
     * @return [Result] chứa sự kiện sau khi từ chối.
     */
    suspend fun rejectReviewEvent(eventId: String): Result<BookBibleReviewEvent>

    /**
     * Duyệt toàn bộ sự kiện đang chờ của một cuốn sách.
     *
     * @param bookId Mã sách trên backend.
     * @param canonicalChapter Có thể giới hạn thao tác theo chương canonical.
     * @return [Result] chứa danh sách sự kiện đã được backend xử lý.
     */
    suspend fun approveAllReviewEvents(
        bookId: String,
        canonicalChapter: Int? = null
    ): Result<List<BookBibleReviewEvent>>

    /**
     * Làm mới dữ liệu Snapshot từ máy chủ backend qua mạng và cập nhật giao dịch vào Room cache.
     *
     * @param source Nguồn sách.
     * @param chapterNumber Mốc chương yêu cầu (1-based).
     * @return [Result] chứa [BookBibleSnapshot] mới nhất từ backend.
     */
    suspend fun refreshSnapshot(
        source: BookBibleSource,
        chapterNumber: Int
    ): Result<BookBibleSnapshot>

    /**
     * Lấy dòng thời gian tiến trình của một nhân vật cụ thể đến mốc chương yêu cầu.
     *
     * @param source Nguồn sách.
     * @param characterId Mã định danh duy nhất của nhân vật.
     * @param chapterNumber Mốc chương giới hạn tối đa (1-based, chống spoiler).
     * @return [Result] chứa [CharacterTimeline] với các sự kiện đã được lọc đến chương này.
     */
    suspend fun getCharacterTimeline(
        source: BookBibleSource,
        characterId: String,
        chapterNumber: Int
    ): Result<CharacterTimeline>

    /**
     * Quan sát trạng thái gửi dữ liệu của một chương cụ thể.
     *
     * @param source Nguồn sách.
     * @param chapterNumber Thứ tự chương.
     * @return [Flow] phát ra [SubmissionState].
     */
    fun observeSubmissionState(
        source: BookBibleSource,
        chapterNumber: Int
    ): Flow<SubmissionState?>

    /**
     * Thử gửi lại một chương bị lỗi.
     *
     * @param source Nguồn sách.
     * @param chapterNumber Thứ tự chương.
     * @return [Result] trả về thành công nếu đã xếp lịch gửi lại.
     */
    suspend fun retrySubmission(
        source: BookBibleSource,
        chapterNumber: Int
    ): Result<Unit>

    /**
     * Tra cứu trực tiếp tiến độ xử lý của một submission từ máy chủ backend qua mã [submissionId].
     *
     * @param submissionId Mã submission nhận được từ backend sau khi submit chương.
     * @return [Result] chứa trạng thái [SubmissionState].
     */
    suspend fun checkSubmissionStatus(submissionId: String): Result<SubmissionState>

    /**
     * Xóa toàn bộ dữ liệu Book Bible (edition, snapshot, timeline, payload, submission) liên quan đến cuốn sách khi sách bị xóa khỏi thư viện.
     *
     * @param sourceId Mã sách (Book ID hoặc Novel ID).
     */
    suspend fun deleteDataForBook(sourceId: String)
}
