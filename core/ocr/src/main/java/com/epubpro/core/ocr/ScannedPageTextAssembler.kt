package com.epubpro.core.ocr

import java.text.Normalizer
import kotlin.math.abs

/**
 * Một chương văn bản dựng lại từ các trang sách đã quét.
 *
 * @property title Tiêu đề chương nhận diện được; null với phần mở đầu trước chương đầu tiên
 * hoặc khi sách không có tiêu đề chương.
 * @property paragraphs Các đoạn văn theo thứ tự đọc.
 */
data class ScannedChapter(
    val title: String?,
    val paragraphs: List<String>
)

/**
 * Dựng lại đoạn văn và chương từ kết quả OCR theo dòng của từng trang sách giấy.
 *
 * Bỏ số trang và header/footer lặp lại, ghép các dòng bị xuống dòng thành đoạn văn (dựa vào
 * thụt đầu dòng, khoảng cách dòng, dòng ngắn kết thúc bằng dấu câu, gạch đầu dòng hội thoại),
 * nối đoạn văn bị cắt giữa hai trang và tách chương theo dòng tiêu đề kiểu "Chương 1".
 *
 * Chỉ hỗ trợ bố cục một cột chữ ngang. Không phụ thuộc Android để unit test được.
 */
object ScannedPageTextAssembler {

    /**
     * Dựng các chương từ các trang đã OCR.
     *
     * @param pages Các dòng chữ của từng trang theo thứ tự trang, tọa độ chuẩn hóa 0..1.
     * @return Các chương có nội dung; rỗng nếu không trang nào có chữ.
     */
    fun assemble(pages: List<List<OcrTextBlock>>): List<ScannedChapter> {
        val rows = removeRunningHeaders(pages.map { page -> removePageNumbers(mergeRows(normalize(page))) })
        return buildChapters(buildParagraphs(rows))
    }

    /** Đoạn văn trung gian; [standalone] khi đoạn là một dòng riêng (căn giữa hoặc cách xa đoạn khác). */
    private class Paragraph(var text: String, var lineCount: Int, val standalone: Boolean)

    /**
     * Chuẩn hóa Unicode NFC, gộp khoảng trắng và bỏ dòng rỗng.
     *
     * @param page Các dòng OCR của trang.
     * @return Các dòng đã làm sạch.
     */
    private fun normalize(page: List<OcrTextBlock>): List<OcrTextBlock> = page.mapNotNull { line ->
        val text = Normalizer.normalize(line.text, Normalizer.Form.NFC).replace(WHITESPACE, " ").trim()
        if (text.isEmpty()) null else line.copy(text = text)
    }

    /**
     * Sắp xếp dòng từ trên xuống và gộp các mảnh ML Kit tách ra trên cùng một hàng chữ.
     *
     * @param page Các dòng của trang.
     * @return Các hàng chữ theo thứ tự đọc.
     */
    private fun mergeRows(page: List<OcrTextBlock>): List<OcrTextBlock> {
        val rows = mutableListOf<MutableList<OcrTextBlock>>()
        page.sortedBy { (it.top + it.bottom) / 2 }.forEach { line ->
            val row = rows.lastOrNull()
            val reference = row?.maxByOrNull { it.height }
            val overlap = if (reference == null) 0f else
                minOf(reference.bottom, line.bottom) - maxOf(reference.top, line.top)
            if (row != null && reference != null && overlap > minOf(reference.height, line.height) * ROW_OVERLAP_RATIO) {
                row += line
            } else {
                rows += mutableListOf(line)
            }
        }
        return rows.map { row ->
            val ordered = row.sortedBy { it.left }
            OcrTextBlock(
                text = ordered.joinToString(" ") { it.text },
                left = ordered.minOf { it.left },
                top = ordered.minOf { it.top },
                right = ordered.maxOf { it.right },
                bottom = ordered.maxOf { it.bottom }
            )
        }
    }

    /**
     * Bỏ dòng chỉ chứa số trang ở mép trên/dưới trang.
     *
     * @param rows Các hàng chữ của trang.
     * @return Các hàng còn lại.
     */
    private fun removePageNumbers(rows: List<OcrTextBlock>): List<OcrTextBlock> = rows.filterNot { row ->
        (row.top < EDGE_ZONE || row.bottom > 1f - EDGE_ZONE) && PAGE_NUMBER.matches(row.text)
    }

    /**
     * Bỏ header/footer chạy (tên sách, tên chương in lặp ở mép trang) xuất hiện trên nhiều trang.
     *
     * @param pages Các hàng chữ của từng trang.
     * @return Các trang sau khi bỏ dòng lặp.
     */
    private fun removeRunningHeaders(pages: List<List<OcrTextBlock>>): List<List<OcrTextBlock>> {
        val minRepeats = if (pages.size <= SMALL_BOOK_PAGES) 2 else 3
        val headerCounts = pages.mapNotNull { rows -> rows.firstOrNull()?.takeIf { it.top < EDGE_ZONE }?.let(::runningKey) }
            .groupingBy { it }.eachCount()
        val footerCounts = pages.mapNotNull { rows -> rows.lastOrNull()?.takeIf { it.bottom > 1f - EDGE_ZONE }?.let(::runningKey) }
            .groupingBy { it }.eachCount()
        return pages.map { rows ->
            var result = rows
            result.firstOrNull()?.let { first ->
                if (first.top < EDGE_ZONE && (headerCounts[runningKey(first)] ?: 0) >= minRepeats) result = result.drop(1)
            }
            result.lastOrNull()?.let { last ->
                if (last.bottom > 1f - EDGE_ZONE && (footerCounts[runningKey(last)] ?: 0) >= minRepeats) result = result.dropLast(1)
            }
            result
        }
    }

    /**
     * Khóa so sánh header/footer: bỏ chữ số (số trang in kèm), bỏ dấu (OCR hay đọc dấu của cùng một
     * header khác nhau giữa các trang) và không phân biệt hoa thường.
     *
     * @param row Hàng chữ.
     * @return Khóa đã chuẩn hóa.
     */
    private fun runningKey(row: OcrTextBlock): String =
        Normalizer.normalize(row.text.replace(DIGITS, "").replace(WHITESPACE, " ").trim().lowercase(), Normalizer.Form.NFD)
            .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
            .replace('đ', 'd')

    /**
     * Ghép hàng chữ thành đoạn văn xuyên suốt các trang.
     *
     * @param pages Các hàng chữ đã làm sạch của từng trang.
     * @return Các đoạn văn theo thứ tự đọc.
     */
    private fun buildParagraphs(pages: List<List<OcrTextBlock>>): List<Paragraph> {
        val paragraphs = mutableListOf<Paragraph>()
        // Dòng cuối trang trước có ngắn (kết thúc đoạn) hay không, để quyết định nối sang trang sau.
        var previousLineEndsParagraph = true
        pages.filter { it.isNotEmpty() }.forEach { rows ->
            val longRows = rows.filter { it.width >= rows.maxOf { row -> row.width } * LONG_LINE_RATIO }
            // Lề trái lấy phân vị thấp để các dòng thụt đầu đoạn không kéo lệch lề.
            val bodyLeft = longRows.map { it.left }.percentile(BODY_LEFT_PERCENTILE)
            val bodyRight = longRows.map { it.right }.percentile(0.5f)
            val pitch = rows.zipWithNext { a, b -> b.top - a.top }.filter { it > 0f }.percentile(0.5f)
            rows.forEachIndexed { index, row ->
                val offset = row.left - bodyLeft
                val centered = offset > CENTER_MIN_OFFSET && abs((bodyRight - row.right) - offset) < CENTER_TOLERANCE
                val indented = offset > INDENT_MIN_OFFSET
                val previous = rows.getOrNull(index - 1)
                val next = rows.getOrNull(index + 1)
                val last = paragraphs.lastOrNull()
                val gapBefore = previous != null && pitch > 0f && row.top - previous.top > pitch * PARAGRAPH_GAP_RATIO
                val gapAfter = next != null && pitch > 0f && next.top - row.top > pitch * PARAGRAPH_GAP_RATIO
                val startsDialogue = DIALOGUE_DASH.containsMatchIn(row.text) && last?.text?.endsWithTerminal() == true
                val previousEndsParagraph = if (previous == null) {
                    previousLineEndsParagraph
                } else {
                    endsParagraph(previous, bodyRight)
                }
                // Dòng "Chương N" luôn đứng riêng, kể cả khi in sát lề trái không cách dòng.
                val heading = isHeading(row.text, 1)
                val afterHeading = last != null && isHeading(last.text, last.lineCount)
                val newParagraph = last == null || last.standalone || centered || indented || gapBefore ||
                    startsDialogue || previousEndsParagraph || heading || afterHeading
                if (newParagraph) {
                    // Dòng tách biệt cả trên lẫn dưới (hoặc đầu trang rồi cách dòng) thường là tiêu đề.
                    val isolated = (gapBefore || previous == null) && gapAfter
                    paragraphs += Paragraph(row.text, 1, centered || isolated)
                } else {
                    last!!.text = joinLines(last.text, row.text)
                    last.lineCount++
                }
            }
            previousLineEndsParagraph = endsParagraph(rows.last(), bodyRight)
        }
        return paragraphs
    }

    /**
     * Xác định một dòng có phải dòng cuối của đoạn: dòng ngắn hẳn so với lề phải và kết thúc bằng dấu câu.
     *
     * @param row Hàng chữ.
     * @param bodyRight Lề phải của khối chữ trên trang.
     * @return true khi đoạn văn kết thúc tại dòng này.
     */
    private fun endsParagraph(row: OcrTextBlock, bodyRight: Float): Boolean {
        val shortfall = bodyRight - row.right
        return (shortfall > SHORT_LINE_GAP && row.text.endsWithTerminal()) || shortfall > VERY_SHORT_LINE_GAP
    }

    /**
     * Nối hai dòng của cùng đoạn; bỏ gạch nối khi một từ bị ngắt ở cuối dòng.
     *
     * @param current Nội dung đoạn hiện tại.
     * @param next Dòng tiếp theo.
     * @return Nội dung đã nối.
     */
    private fun joinLines(current: String, next: String): String {
        val hyphenated = current.length > 1 && current.last() == '-' && current[current.length - 2].isLetter()
        return if (hyphenated && next.firstOrNull()?.isLowerCase() == true) current.dropLast(1) + next else "$current $next"
    }

    /**
     * Tách đoạn văn thành chương theo dòng tiêu đề; tiêu đề không có nội dung (ví dụ trang mục lục)
     * được giữ lại như đoạn văn thường.
     *
     * @param paragraphs Các đoạn văn của cả sách.
     * @return Các chương có nội dung.
     */
    private fun buildChapters(paragraphs: List<Paragraph>): List<ScannedChapter> {
        val raw = mutableListOf<ScannedChapter>()
        var title: String? = null
        var body = mutableListOf<String>()
        var index = 0
        while (index < paragraphs.size) {
            val paragraph = paragraphs[index]
            if (isHeading(paragraph.text, paragraph.lineCount)) {
                if (title != null || body.isNotEmpty()) raw += ScannedChapter(title, body)
                title = paragraph.text
                body = mutableListOf()
                // Tiêu đề phụ in riêng một dòng ngay dưới "Chương N".
                val subtitle = paragraphs.getOrNull(index + 1)
                if (subtitle != null && subtitle.standalone && subtitle.text.length <= MAX_HEADING_LENGTH &&
                    !subtitle.text.endsWithTerminal() && !CHAPTER_HEADING.containsMatchIn(subtitle.text)
                ) {
                    title = "$title: ${subtitle.text}"
                    index++
                }
            } else {
                body += paragraph.text
            }
            index++
        }
        if (title != null || body.isNotEmpty()) raw += ScannedChapter(title, body)

        val chapters = mutableListOf<ScannedChapter>()
        val leading = mutableListOf<String>()
        raw.forEach { chapter ->
            when {
                chapter.paragraphs.isNotEmpty() -> chapters += chapter
                chapter.title == null -> Unit
                chapters.isEmpty() -> leading += chapter.title
                else -> chapters[chapters.lastIndex] = chapters.last().let { it.copy(paragraphs = it.paragraphs + chapter.title) }
            }
        }
        if (leading.isNotEmpty()) {
            val first = chapters.firstOrNull()
            if (first != null && first.title == null) {
                chapters[0] = first.copy(paragraphs = leading + first.paragraphs)
            } else {
                chapters.add(0, ScannedChapter(null, leading))
            }
        }
        return chapters
    }

    /**
     * Nhận diện dòng tiêu đề chương ("Chương 1", "CHƯƠNG MỘT", "Phần II", "Chapter 3"...).
     *
     * @param text Nội dung đoạn.
     * @param lineCount Số dòng của đoạn.
     * @return true khi đoạn ngắn, một dòng và mở đầu bằng từ khóa chương kèm số thứ tự.
     */
    private fun isHeading(text: String, lineCount: Int): Boolean =
        lineCount == 1 && text.length <= MAX_HEADING_LENGTH && CHAPTER_HEADING.containsMatchIn(text)

    /** @return true khi chuỗi kết thúc bằng dấu kết thúc câu hoặc dấu đóng ngoặc kép. */
    private fun String.endsWithTerminal(): Boolean = lastOrNull()?.let { it in TERMINAL_CHARS } == true

    /**
     * Lấy phân vị của danh sách số.
     *
     * @param fraction Vị trí phân vị trong khoảng 0..1 (0.5 là trung vị).
     * @return Giá trị tại phân vị; 0 nếu danh sách rỗng.
     */
    private fun List<Float>.percentile(fraction: Float): Float {
        if (isEmpty()) return 0f
        val sorted = sorted()
        return sorted[((sorted.size - 1) * fraction).toInt()]
    }

    private val WHITESPACE = Regex("\\s+")
    private val DIGITS = Regex("\\d+")
    private val PAGE_NUMBER = Regex("^[\\s\\-–—.·•]*(\\d{1,4}|[ivxlcdmIVXLCDM]{1,7})[\\s\\-–—.·•]*$")
    private val DIALOGUE_DASH = Regex("^[\\-–—]\\s")
    private val CHAPTER_HEADING = Regex(
        "^(chương|chuong|hồi|phần|quyển|chapter|part)\\s+" +
            "(\\d+|[ivxlcdm]+|một|hai|ba|bốn|tư|năm|sáu|bảy|tám|chín|mười|thứ\\s+\\p{L}+|cuối|kết|mở đầu)" +
            "(?=$|[\\s.:,\\-–—])",
        RegexOption.IGNORE_CASE
    )
    private const val TERMINAL_CHARS = ".!?…:;\"”»’'"

    /** Tỷ lệ chồng lấn chiều cao để coi hai mảnh nằm cùng một hàng chữ. */
    private const val ROW_OVERLAP_RATIO = 0.5f
    /** Vùng mép trên/dưới trang (theo chiều cao) chứa số trang, header, footer. */
    private const val EDGE_ZONE = 0.1f
    /** Sách ít trang thì header chỉ cần lặp 2 lần để bị coi là header chạy. */
    private const val SMALL_BOOK_PAGES = 6
    /** Dòng dài hơn tỷ lệ này so với dòng dài nhất được dùng để đo lề khối chữ. */
    private const val LONG_LINE_RATIO = 0.8f
    private const val BODY_LEFT_PERCENTILE = 0.2f
    /** Độ thụt đầu dòng tối thiểu (theo chiều rộng trang) để coi là bắt đầu đoạn mới. */
    private const val INDENT_MIN_OFFSET = 0.02f
    /** Dòng lệch lề trái ít nhất chừng này và cân hai bên thì coi là căn giữa (tiêu đề). */
    private const val CENTER_MIN_OFFSET = 0.08f
    private const val CENTER_TOLERANCE = 0.04f
    /** Khoảng cách giữa hai dòng lớn hơn bội số này của khoảng cách dòng thông thường là ngắt đoạn. */
    private const val PARAGRAPH_GAP_RATIO = 1.6f
    /** Dòng hụt lề phải chừng này và có dấu câu cuối thì là dòng cuối đoạn. */
    private const val SHORT_LINE_GAP = 0.08f
    /** Dòng hụt lề phải nhiều thế này thì là dòng cuối đoạn kể cả khi OCR mất dấu câu. */
    private const val VERY_SHORT_LINE_GAP = 0.35f
    private const val MAX_HEADING_LENGTH = 80
}
