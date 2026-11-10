package com.epubpro.core.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Kiểm thử dựng đoạn văn và chương từ OCR trang sách giấy. */
class ScannedPageTextAssemblerTest {

    /** Dòng xuống dòng được nối; dòng thụt đầu mở đoạn mới; số trang ở chân trang bị bỏ. */
    @Test
    fun joinsWrappedLinesAndSplitsOnIndent() {
        val page = listOf(
            line("Trời đã sang thu, lá vàng rơi", 0, left = INDENT),
            line("khắp các con đường nhỏ quanh", 1),
            line("làng.", 2, right = 0.40f),
            line("Anh bước ra khỏi nhà và nhìn", 3, left = INDENT),
            line("lên bầu trời xanh ngắt, lòng", 4),
            line("nhẹ tênh.", 5, right = 0.45f),
            OcrTextBlock("12", 0.48f, 0.93f, 0.52f, 0.96f)
        )

        val chapters = ScannedPageTextAssembler.assemble(listOf(page))

        assertEquals(1, chapters.size)
        assertNull(chapters[0].title)
        assertEquals(
            listOf(
                "Trời đã sang thu, lá vàng rơi khắp các con đường nhỏ quanh làng.",
                "Anh bước ra khỏi nhà và nhìn lên bầu trời xanh ngắt, lòng nhẹ tênh."
            ),
            chapters[0].paragraphs
        )
    }

    /** Đoạn văn kéo dài qua trang sau khi dòng cuối trang trước còn chạm lề phải. */
    @Test
    fun continuesParagraphAcrossPages() {
        val first = listOf(
            line("Họ đi suốt buổi chiều, kể cho", 0, left = INDENT),
            line("nhau nghe chuyện cũ, chuyện mới", 1),
            line("đến khi trời ngả màu tím, rồi", 2)
        )
        val second = listOf(
            line("cả hai cùng cười.", 0, right = 0.45f),
            line("Đêm xuống rất nhanh trên những", 1, left = INDENT),
            line("ngọn đồi phía tây của thung lũng.", 2, right = 0.80f)
        )

        val chapters = ScannedPageTextAssembler.assemble(listOf(first, second))

        assertEquals(
            listOf(
                "Họ đi suốt buổi chiều, kể cho nhau nghe chuyện cũ, chuyện mới đến khi trời ngả màu tím, rồi cả hai cùng cười.",
                "Đêm xuống rất nhanh trên những ngọn đồi phía tây của thung lũng."
            ),
            chapters.single().paragraphs
        )
    }

    /** Header in lặp ở đầu nhiều trang (kèm số trang khác nhau) bị bỏ. */
    @Test
    fun removesRunningHeaders() {
        val pages = (1..3).map { number ->
            listOf(
                OcrTextBlock("TÊN SÁCH  ${10 + number}", 0.35f, 0.04f, 0.65f, 0.07f),
                line("Nội dung trang số $number", 0, left = INDENT),
                line("của cuốn sách.", 1, right = 0.50f)
            )
        }

        val paragraphs = ScannedPageTextAssembler.assemble(pages).flatMap { it.paragraphs }

        assertEquals(3, paragraphs.size)
        assertTrue(paragraphs.none { it.contains("TÊN SÁCH") })
    }

    /** Header bị OCR đọc lệch dấu giữa các trang vẫn bị bỏ và không cắt đôi đoạn văn qua trang. */
    @Test
    fun removesRunningHeadersReadWithDifferentDiacritics() {
        val first = listOf(
            line("Nó cười toe toét, khoe cái giỏ", 0, left = INDENT),
            line("tre mới đan, bảo rằng hôm nay", 1)
        )
        val second = listOf(
            OcrTextBlock("TRUYỆN NGẮN QUÊ NHÀ", 0.35f, 0.04f, 0.65f, 0.07f),
            line("phải bắt được một con cá lóc.", 0, right = 0.60f),
            line("Đêm xuống rất nhanh trên những", 1, left = INDENT),
            line("ngọn đồi phía tây của thung", 2),
            line("lũng.", 3, right = 0.25f)
        )
        val third = listOf(
            OcrTextBlock("TRUYỆN NGÂN QUÊ NHÀ", 0.35f, 0.04f, 0.65f, 0.07f),
            line("Sáng ra, sương phủ trắng cả mặt", 0, left = INDENT),
            line("sông như một tấm khăn voan mỏng", 1),
            line("manh.", 2, right = 0.25f)
        )

        val paragraphs = ScannedPageTextAssembler.assemble(listOf(first, second, third)).single().paragraphs

        assertEquals(
            listOf(
                "Nó cười toe toét, khoe cái giỏ tre mới đan, bảo rằng hôm nay phải bắt được một con cá lóc.",
                "Đêm xuống rất nhanh trên những ngọn đồi phía tây của thung lũng.",
                "Sáng ra, sương phủ trắng cả mặt sông như một tấm khăn voan mỏng manh."
            ),
            paragraphs
        )
    }

    /** Tiêu đề "CHƯƠNG 1" căn giữa kèm tiêu đề phụ tách chương; phần trước đó là chương không tên. */
    @Test
    fun splitsChaptersOnHeadingsWithSubtitle() {
        val preface = listOf(
            line("Cuốn sách này dành tặng mẹ.", 0, left = INDENT, right = 0.60f)
        )
        val chapterPage = listOf(
            line("CHƯƠNG 1", 0, left = 0.42f, right = 0.58f),
            line("MỞ ĐẦU", 2, left = 0.44f, right = 0.56f),
            line("Ngày ấy, làng tôi còn nghèo lắm,", 4, left = INDENT),
            line("nhà nào cũng lợp mái tranh và", 5),
            line("đêm nào cũng nghe tiếng ếch kêu.", 6, right = 0.70f),
            line("Mẹ tôi dậy từ khi trời còn tối", 7, left = INDENT),
            line("để thổi cơm cho cả nhà.", 8, right = 0.55f)
        )

        val chapters = ScannedPageTextAssembler.assemble(listOf(preface, chapterPage))

        assertEquals(2, chapters.size)
        assertNull(chapters[0].title)
        assertEquals(listOf("Cuốn sách này dành tặng mẹ."), chapters[0].paragraphs)
        assertEquals("CHƯƠNG 1: MỞ ĐẦU", chapters[1].title)
        assertEquals(
            listOf(
                "Ngày ấy, làng tôi còn nghèo lắm, nhà nào cũng lợp mái tranh và đêm nào cũng nghe tiếng ếch kêu.",
                "Mẹ tôi dậy từ khi trời còn tối để thổi cơm cho cả nhà."
            ),
            chapters[1].paragraphs
        )
    }

    /** Các dòng mục lục giống tiêu đề nhưng không có nội dung được giữ như đoạn văn, không tạo chương rỗng. */
    @Test
    fun keepsTableOfContentsAsParagraphs() {
        val toc = listOf(
            line("MỤC LỤC", 0, left = 0.42f, right = 0.58f),
            line("Chương 1: Mở đầu ............ 7", 2),
            line("Chương 2: Hành trình ........ 15", 3)
        )
        val body = listOf(
            line("Chương 1", 0, right = 0.25f),
            line("Mọi chuyện bắt đầu vào một sáng", 1, left = INDENT),
            line("mùa đông.", 2, right = 0.30f)
        )

        val chapters = ScannedPageTextAssembler.assemble(listOf(toc, body))

        assertEquals(2, chapters.size)
        assertEquals(
            listOf("MỤC LỤC", "Chương 1: Mở đầu ............ 7", "Chương 2: Hành trình ........ 15"),
            chapters[0].paragraphs
        )
        assertEquals("Chương 1", chapters[1].title)
        assertEquals(listOf("Mọi chuyện bắt đầu vào một sáng mùa đông."), chapters[1].paragraphs)
    }

    /** Lời thoại mở bằng gạch ngang tách đoạn dù không thụt đầu dòng. */
    @Test
    fun splitsDialogueLines() {
        val page = listOf(
            line("Cô gái nhìn anh rồi hỏi nhỏ, giọng run run:", 0),
            line("- Anh có về không?", 1, right = 0.45f),
            line("- Có.", 2, right = 0.20f)
        )

        val paragraphs = ScannedPageTextAssembler.assemble(listOf(page)).single().paragraphs

        assertEquals(
            listOf("Cô gái nhìn anh rồi hỏi nhỏ, giọng run run:", "- Anh có về không?", "- Có."),
            paragraphs
        )
    }

    /** Từ bị ngắt bằng gạch nối cuối dòng được nối lại; chữ Việt tổ hợp được chuẩn hóa NFC. */
    @Test
    fun joinsHyphenatedWordsAndNormalizesUnicode() {
        val page = listOf(
            line("Tiếng Việt and infor-", 0, left = INDENT),
            line("mation retrieval.", 1, right = 0.50f)
        )

        val paragraph = ScannedPageTextAssembler.assemble(listOf(page)).single().paragraphs.single()

        assertEquals("Tiếng Việt and information retrieval.", paragraph)
    }

    /** Trang trắng hoặc chỉ có số trang thì không tạo chương nào. */
    @Test
    fun returnsEmptyWhenNoText() {
        val pages = listOf(emptyList(), listOf(OcrTextBlock("15", 0.48f, 0.93f, 0.52f, 0.96f)))

        assertTrue(ScannedPageTextAssembler.assemble(pages).isEmpty())
    }

    /**
     * Tạo một dòng chữ ở hàng thứ [row] của khối chữ.
     *
     * @param text Nội dung dòng.
     * @param row Chỉ số hàng tính từ đầu khối chữ.
     * @param left Lề trái chuẩn hóa.
     * @param right Lề phải chuẩn hóa.
     * @return Dòng OCR giả lập.
     */
    private fun line(text: String, row: Int, left: Float = BODY_LEFT, right: Float = BODY_RIGHT): OcrTextBlock {
        val top = START_Y + row * PITCH
        return OcrTextBlock(text, left, top, right, top + LINE_HEIGHT)
    }

    private companion object {
        const val BODY_LEFT = 0.10f
        const val BODY_RIGHT = 0.90f
        const val INDENT = 0.14f
        const val START_Y = 0.15f
        const val PITCH = 0.04f
        const val LINE_HEIGHT = 0.03f
    }
}
