package com.epubpro.core.comic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Kiểm thử thứ tự tự nhiên của tên trang truyện. */
class NaturalOrderComparatorTest {

    /** Cụm số được so theo giá trị, chữ không phân biệt hoa thường. */
    @Test
    fun sortsNumbersByValue() {
        val sorted = listOf("page10.jpg", "Page2.jpg", "page1.jpg", "page02.jpg", "cover.jpg")
            .sortedWith(NaturalOrderComparator)
        assertEquals(listOf("cover.jpg", "page1.jpg", "Page2.jpg", "page02.jpg", "page10.jpg"), sorted)
    }

    /** Số rất dài không bị tràn khi so sánh. */
    @Test
    fun handlesLongDigitRuns() {
        assertTrue(NaturalOrderComparator.compare("99999999999999999999.jpg", "100000000000000000000.jpg") < 0)
    }

    /** Hai chuỗi chỉ khác hoa thường vẫn có thứ tự ổn định, không bằng nhau. */
    @Test
    fun keepsStableOrderForCaseOnlyDifferences() {
        assertTrue(NaturalOrderComparator.compare("A.jpg", "a.jpg") != 0)
    }
}
