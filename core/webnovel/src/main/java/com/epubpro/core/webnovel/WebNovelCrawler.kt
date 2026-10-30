package com.epubpro.core.webnovel

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/**
 * Lỗi khi tải một trang thất bại.
 *
 * @property statusCode Mã HTTP nếu có (ví dụ 429), null nếu lỗi mạng.
 * @param message Mô tả kỹ thuật phục vụ chẩn đoán.
 */
class WebFetchException(val statusCode: Int?, message: String) : java.io.IOException(message)

/**
 * Kết quả một lần thu thập.
 *
 * @property chapters Các chương đã tải theo thứ tự.
 * @property novelTitle Tên truyện đoán được từ chương đầu tiên.
 * @property stoppedReason Lý do dừng.
 * @property lastChapterUrl URL của chương cuối cùng tải thành công; dùng làm mốc để "tải thêm chương" sau này.
 * @property failedUrl URL trang không tải hoặc bóc tách được, null nếu không gặp lỗi.
 * @property author Tác giả truyện nếu trang chương cung cấp.
 */
data class CrawlResult(
    val chapters: List<WebNovelChapter>,
    val novelTitle: String?,
    val stoppedReason: CrawlStopReason,
    val lastChapterUrl: String? = null,
    val failedUrl: String? = null,
    val author: String? = null
)

/** Lý do dừng thu thập. */
enum class CrawlStopReason {
    /** Đã đủ số chương yêu cầu. */
    LIMIT_REACHED,

    /** Không còn liên kết chương sau. */
    NO_NEXT_LINK,

    /** Liên kết chương sau quay về trang đã tải (vòng lặp). */
    LOOP_DETECTED,

    /** Trang không bóc tách được nội dung. */
    EXTRACTION_FAILED,

    /** Trang tải lỗi sau khi đã thử lại. */
    FETCH_FAILED
}

/**
 * Thu thập lần lượt các chương bằng cách đi theo liên kết "chương sau".
 *
 * Tải tuần tự với khoảng nghỉ giữa các trang và lùi thời gian khi máy chủ trả `429`/`503` để không gây tải
 * cho site nguồn. Lớp không phụ thuộc HTTP client cụ thể nên kiểm thử được bằng hàm [fetch] giả.
 *
 * @param fetch Hàm tải HTML của một URL; ném [WebFetchException] khi lỗi.
 * @param politeDelayMs Khoảng nghỉ giữa hai lần tải.
 * @param maxRetries Số lần thử lại cho lỗi tạm thời.
 */
class WebNovelCrawler(
    private val fetch: suspend (url: String) -> String,
    private val politeDelayMs: Long = 800L,
    private val maxRetries: Int = 3
) {

    /**
     * Tải tối đa [maxChapters] chương bắt đầu từ [startUrl].
     *
     * @param startUrl URL chương đầu tiên.
     * @param maxChapters Số chương tối đa.
     * @param onProgress Callback sau mỗi chương: (số chương đã tải, tiêu đề chương vừa tải).
     * @return Kết quả thu thập; có thể ít chương hơn yêu cầu.
     */
    suspend fun crawl(
        startUrl: String,
        maxChapters: Int,
        onProgress: suspend (count: Int, chapterTitle: String) -> Unit = { _, _ -> }
    ): CrawlResult {
        val chapters = mutableListOf<WebNovelChapter>()
        val visited = mutableSetOf<String>()
        var novelTitle: String? = null
        var author: String? = null
        var lastChapterUrl: String? = null
        var url: String? = startUrl
        var reason = CrawlStopReason.NO_NEXT_LINK

        while (url != null) {
            currentCoroutineContext().ensureActive()
            if (chapters.size >= maxChapters) {
                reason = CrawlStopReason.LIMIT_REACHED
                break
            }
            if (!visited.add(url.substringBefore('#'))) {
                reason = CrawlStopReason.LOOP_DETECTED
                break
            }
            val html = fetchWithRetry(url) ?: run {
                reason = CrawlStopReason.FETCH_FAILED
                null
            } ?: break
            val article = ArticleExtractor.extract(html, url) ?: run {
                reason = CrawlStopReason.EXTRACTION_FAILED
                null
            } ?: break
            if (novelTitle == null) novelTitle = article.novelTitle
            if (author == null) author = article.author
            chapters += WebNovelChapter(article.title, article.paragraphs, url)
            lastChapterUrl = url
            onProgress(chapters.size, article.title)
            url = article.links.next
            if (url != null && chapters.size < maxChapters) delay(politeDelayMs)
        }
        return CrawlResult(chapters, novelTitle, reason, lastChapterUrl,
            url.takeIf { reason == CrawlStopReason.FETCH_FAILED || reason == CrawlStopReason.EXTRACTION_FAILED }, author)
    }

    /**
     * Tìm URL chương kế tiếp của một chương đã tải trước đó (truyện đang ra có thể đã có chương mới).
     *
     * @param chapterUrl URL chương cuối đã tải.
     * @return URL chương sau, hoặc null nếu chưa có chương mới.
     * @throws WebFetchException Nếu không tải hoặc bóc tách được trang chương cuối.
     */
    suspend fun findNextChapterUrl(chapterUrl: String): String? {
        val html = fetchWithRetry(chapterUrl) ?: throw WebFetchException(null, "Cannot fetch last chapter")
        val article = ArticleExtractor.extract(html, chapterUrl)
            ?: throw WebFetchException(null, "Cannot extract last chapter")
        return article.links.next
    }

    /**
     * Tải một trang, thử lại với thời gian chờ tăng dần cho lỗi mạng, `429` và `5xx`.
     *
     * @param url URL cần tải.
     * @return HTML, hoặc null nếu vẫn lỗi sau khi thử lại hoặc gặp lỗi vĩnh viễn (`4xx` khác 429).
     */
    private suspend fun fetchWithRetry(url: String): String? {
        var attempt = 0
        while (true) {
            try {
                return fetch(url)
            } catch (error: WebFetchException) {
                val status = error.statusCode
                val transient = status == null || status == 429 || status >= 500
                if (!transient || attempt >= maxRetries) return null
                attempt++
                delay(politeDelayMs * (1L shl attempt) * 2)
            }
        }
    }
}
