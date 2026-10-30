package com.epubpro.core.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

/** Kiểm thử gom dòng OCR thành bóng thoại và thứ tự đọc. */
class BubbleGrouperTest {

    /** Hai dòng sát nhau gộp một bóng; bóng ở xa tách riêng; manga đọc bóng bên phải trước. */
    @Test
    fun groupsNearbyLinesAndOrdersRightToLeft() {
        val blocks = listOf(
            OcrTextBlock("你好", 0.10f, 0.10f, 0.30f, 0.14f),
            OcrTextBlock("世界", 0.10f, 0.15f, 0.30f, 0.19f),
            OcrTextBlock("再见", 0.70f, 0.11f, 0.90f, 0.15f)
        )

        val bubbles = BubbleGrouper.group(blocks, rightToLeft = true, joinWithSpace = false)

        assertEquals(listOf("再见", "你好世界"), bubbles.map { it.text })
        assertEquals(0.10f, bubbles[1].top, 0.0001f)
        assertEquals(0.19f, bubbles[1].bottom, 0.0001f)
    }

    /** Chữ Latin nối dòng bằng dấu cách và đọc trái sang phải; khối rỗng bị bỏ. */
    @Test
    fun joinsLatinLinesWithSpaces() {
        val blocks = listOf(
            OcrTextBlock("HELLO", 0.10f, 0.10f, 0.30f, 0.13f),
            OcrTextBlock("THERE", 0.10f, 0.135f, 0.30f, 0.165f),
            OcrTextBlock("  ", 0.5f, 0.5f, 0.6f, 0.6f)
        )

        val bubbles = BubbleGrouper.group(blocks, rightToLeft = false, joinWithSpace = true)

        assertEquals(listOf("HELLO THERE"), bubbles.map { it.text })
    }
}
