package com.epubpro.core.webnovel

import java.net.URI
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Phát hiện liên kết chương trước/sau/mục lục bằng heuristic: `rel`, chữ trên nút, id/class và số thứ tự trong URL.
 *
 * Chỉ nhận liên kết http(s) cùng host với trang hiện tại để tránh nhảy sang quảng cáo hoặc site khác.
 */
object ChapterLinkDetector {
    private const val MIN_SCORE = 40
    private const val MAX_LINK_TEXT_LENGTH = 30

    private val nextTexts = listOf(
        "chương sau", "chuong sau", "chương tiếp", "chuong tiep", "tiếp theo", "tiep theo", "trang sau",
        "next chapter", "next page", "next", "下一章", "下一页", "下一頁", "下一节", "下一節", "下章", "下页",
        "다음화", "다음 화", "다음", "次の話", "次へ"
    )
    private val previousTexts = listOf(
        "chương trước", "chuong truoc", "trang trước", "trang truoc", "previous chapter", "prev chapter",
        "previous", "prev", "上一章", "上一页", "上一頁", "上一节", "上一節", "上章", "上页",
        "이전화", "이전 화", "이전", "前の話", "前へ"
    )
    private val tocTexts = listOf(
        "mục lục", "muc luc", "danh sách chương", "danh sach chuong", "table of contents", "chapter list",
        "index", "目录", "目錄", "章节目录", "章節目錄", "목차", "目次"
    )
    private val nextSymbols = setOf("»", "›", "→", ">", ">>", "»»")
    private val previousSymbols = setOf("«", "‹", "←", "<", "<<", "««")

    /**
     * Phát hiện liên kết điều hướng chương trong tài liệu.
     *
     * @param document Tài liệu HTML gốc (chưa xóa nav/footer vì nút chuyển chương thường nằm ở đó).
     * @param pageUrl URL tuyệt đối của trang hiện tại.
     * @return Các liên kết tìm được; trường null khi không đủ tin cậy.
     */
    fun detect(document: Document, pageUrl: String): ChapterLinks {
        val pageUri = runCatching { URI(pageUrl) }.getOrNull() ?: return ChapterLinks()
        val candidates = mutableListOf<LinkCandidate>()

        document.select("link[rel][href]").forEach { link ->
            val url = normalize(link.absUrl("href"), pageUri) ?: return@forEach
            val rel = link.attr("rel").lowercase()
            candidates += LinkCandidate(
                url = url,
                nextScore = if ("next" in rel) 100 else 0,
                previousScore = if ("prev" in rel) 100 else 0,
                tocScore = if ("contents" in rel || "index" in rel) 60 else 0
            )
        }
        document.select("a[href]").forEach { anchor ->
            val url = normalize(anchor.absUrl("href"), pageUri) ?: return@forEach
            candidates += scoreAnchor(anchor, url, pageUri)
        }

        val next = candidates.filter { it.nextScore >= MIN_SCORE }.maxByOrNull { it.nextScore }?.url
        val previous = candidates.filter { it.previousScore >= MIN_SCORE && it.url != next }
            .maxByOrNull { it.previousScore }?.url
        val toc = candidates.filter { it.tocScore >= MIN_SCORE && it.url != next && it.url != previous }
            .maxByOrNull { it.tocScore }?.url
        return ChapterLinks(next = next, previous = previous, tableOfContents = toc)
    }

    /**
     * Chấm điểm một thẻ `<a>` cho từng vai trò điều hướng.
     *
     * @param anchor Thẻ liên kết.
     * @param url URL tuyệt đối đã chuẩn hóa.
     * @param pageUri URI trang hiện tại.
     * @return Ứng viên kèm điểm.
     */
    private fun scoreAnchor(anchor: Element, url: String, pageUri: URI): LinkCandidate {
        val text = normalizeText(anchor.text().ifBlank { anchor.attr("aria-label").ifBlank { anchor.attr("title") } })
        val rel = anchor.attr("rel").lowercase()
        val idClass = (anchor.id() + " " + anchor.className()).lowercase()
        val shortText = text.length <= MAX_LINK_TEXT_LENGTH

        var next = 0
        var previous = 0
        var toc = 0
        if ("next" in rel) next += 100
        if ("prev" in rel) previous += 100
        if (shortText && matchesAny(text, nextTexts)) next += 60
        if (shortText && matchesAny(text, previousTexts)) previous += 60
        if (shortText && matchesAny(text, tocTexts)) toc += 60
        if (text in nextSymbols) next += 45
        if (text in previousSymbols) previous += 45
        if (Regex("\\bnext\\b|next[-_]|[-_]next").containsMatchIn(idClass)) next += 30
        if (Regex("\\bprev\\b|prev[-_]|[-_]prev|previous").containsMatchIn(idClass)) previous += 30
        if (Regex("toc|catalog|chapter[-_]?list|index").containsMatchIn(idClass)) toc += 20

        when (compareSequentialUrls(pageUri.toString(), url)) {
            1 -> next += 40
            -1 -> previous += 40
        }
        return LinkCandidate(url, next, previous, toc)
    }

    /**
     * Kiểm tra chữ trên liên kết có khớp một trong các cụm từ khóa.
     *
     * Cụm Latin phải khớp cả từ để "index" không khớp "reindex"; cụm CJK chỉ cần chứa.
     *
     * @param text Chữ đã chuẩn hóa.
     * @param phrases Danh sách cụm từ khóa.
     * @return true nếu khớp.
     */
    private fun matchesAny(text: String, phrases: List<String>): Boolean = phrases.any { phrase ->
        if (phrase.any { it.code > 0x2E7F }) {
            text.contains(phrase)
        } else {
            Regex("(^|[^\\p{L}])${Regex.escape(phrase)}($|[^\\p{L}])").containsMatchIn(text)
        }
    }

    /**
     * So sánh hai URL chỉ khác nhau ở số cuối cùng trong đường dẫn.
     *
     * @param current URL trang hiện tại.
     * @param candidate URL ứng viên.
     * @return 1 nếu ứng viên là số kế tiếp, -1 nếu là số liền trước, 0 nếu không liên quan.
     */
    internal fun compareSequentialUrls(current: String, candidate: String): Int {
        val pattern = Regex("(\\d+)(?!.*\\d)")
        val currentMatch = pattern.find(current) ?: return 0
        val candidateMatch = pattern.find(candidate) ?: return 0
        val currentPrefix = current.substring(0, currentMatch.range.first)
        val candidatePrefix = candidate.substring(0, candidateMatch.range.first)
        val currentSuffix = current.substring(currentMatch.range.last + 1)
        val candidateSuffix = candidate.substring(candidateMatch.range.last + 1)
        if (currentPrefix != candidatePrefix || currentSuffix != candidateSuffix) return 0
        val currentNumber = currentMatch.value.toLongOrNull() ?: return 0
        val candidateNumber = candidateMatch.value.toLongOrNull() ?: return 0
        return when (candidateNumber - currentNumber) {
            1L -> 1
            -1L -> -1
            else -> 0
        }
    }

    /**
     * Chuẩn hóa URL ứng viên: bỏ fragment, chỉ nhận http(s) cùng host và khác trang hiện tại.
     *
     * @param raw URL tuyệt đối do jsoup tính.
     * @param pageUri URI trang hiện tại.
     * @return URL đã chuẩn hóa hoặc null nếu bị loại.
     */
    private fun normalize(raw: String, pageUri: URI): String? {
        if (raw.isBlank()) return null
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        if (!uri.host.equals(pageUri.host, ignoreCase = true)) return null
        // Cắt fragment bằng chuỗi thay vì dựng lại URI để không đổi cách mã hóa của query.
        val withoutFragment = raw.substringBefore('#')
        val pageWithoutFragment = pageUri.toString().substringBefore('#')
        return withoutFragment.takeIf { it != pageWithoutFragment }
    }

    /**
     * Chuẩn hóa chữ trên liên kết: chữ thường, gộp khoảng trắng.
     *
     * @param value Chữ gốc.
     * @return Chữ đã chuẩn hóa.
     */
    private fun normalizeText(value: String): String =
        value.replace(' ', ' ').trim().replace(Regex("\\s+"), " ").lowercase()

    /**
     * Ứng viên liên kết kèm điểm cho từng vai trò.
     */
    private data class LinkCandidate(
        val url: String,
        val nextScore: Int,
        val previousScore: Int,
        val tocScore: Int
    )
}
