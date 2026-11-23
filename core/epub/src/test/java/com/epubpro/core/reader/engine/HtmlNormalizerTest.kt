package com.epubpro.core.reader.engine

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlNormalizerTest {

    @Test
    fun `already well structured html with 3 or more p tags is unchanged`() {
        val input = "<div><p>Đoạn 1</p><p>Đoạn 2</p><p>Đoạn 3</p></div>"
        val output = HtmlNormalizer.normalize(input)
        assertEquals(input, output)
    }

    @Test
    fun `raw text with line breaks is converted to p tags`() {
        val input = "Dòng một của cuốn sách\nDòng hai của cuốn sách\nDòng ba của cuốn sách"
        val output = HtmlNormalizer.normalize(input)
        val doc = Jsoup.parse(output)
        val pTags = doc.body().select("p")
        assertTrue("Should have created 3 p tags", pTags.size >= 3)
        assertEquals("Dòng một của cuốn sách", pTags[0].text())
        assertEquals("Dòng hai của cuốn sách", pTags[1].text())
        assertEquals("Dòng ba của cuốn sách", pTags[2].text())
    }

    @Test
    fun `br separated text is wrapped into p tags`() {
        val input = "<div>Đoạn A<br/>Đoạn B<br/><br/>Đoạn C</div>"
        val output = HtmlNormalizer.normalize(input)
        val doc = Jsoup.parse(output)
        val pTags = doc.body().select("p")
        assertEquals(3, pTags.size)
        assertEquals("Đoạn A", pTags[0].text())
        assertEquals("Đoạn B", pTags[1].text())
        assertEquals("Đoạn C", pTags[2].text())
    }

    @Test
    fun `lists and tables are preserved and not wrapped in p tags`() {
        val input = """
            <div>
                Tiêu đề danh sách
                <ul>
                    <li>Mục 1</li>
                    <li>Mục 2</li>
                </ul>
                <table border="1">
                    <tr><td>Ô 1</td><td>Ô 2</td></tr>
                </table>
                Kết luận sau bảng
            </div>
        """.trimIndent()

        val output = HtmlNormalizer.normalize(input)
        val doc = Jsoup.parse(output)

        // Ensure <ul> is NOT inside any <p>
        val ulsInP = doc.select("p > ul")
        assertTrue("ul should not be wrapped inside p", ulsInP.isEmpty())

        // Ensure <table> is NOT inside any <p>
        val tablesInP = doc.select("p > table")
        assertTrue("table should not be wrapped inside p", tablesInP.isEmpty())

        // Text nodes before/after should be wrapped in <p>
        val pTags = doc.body().select("p")
        assertTrue(pTags.any { it.text().contains("Tiêu đề danh sách") })
        assertTrue(pTags.any { it.text().contains("Kết luận sau bảng") })
    }

    @Test
    fun `preformatted block pre is not broken into p`() {
        val input = """
            <div>
                Đoạn mở đầu
                <pre>fun main() {\n    println("Hello")\n}</pre>
                Đoạn kết thúc
            </div>
        """.trimIndent()

        val output = HtmlNormalizer.normalize(input)
        val doc = Jsoup.parse(output)

        val preInP = doc.select("p > pre")
        assertTrue("pre should not be wrapped inside p", preInP.isEmpty())
        assertEquals(1, doc.select("pre").size)
    }

    @Test
    fun `multiline html tag attributes and pre content are not corrupted by br insertion`() {
        val input = """
            <html>
            <head>
                <title>Chương 1</title>
            </head>
            <body>
                <img
                    src="cover.jpg"
                    alt="Ảnh bìa" />
                Đoạn 1
                Đoạn 2
                <pre>line 1
            line 2</pre>
            </body>
            </html>
        """.trimIndent()

        val output = HtmlNormalizer.normalize(input)
        val doc = Jsoup.parse(output)

        val img = doc.selectFirst("img")
        assertEquals("cover.jpg", img?.attr("src"))
        assertEquals("Ảnh bìa", img?.attr("alt"))
        assertTrue("pre should not contain br tags", doc.select("pre br").isEmpty())
        assertTrue("head should not contain br tags", doc.head().select("br").isEmpty())
        val pTags = doc.body().select("p")
        assertTrue(pTags.any { it.text() == "Đoạn 1" })
        assertTrue(pTags.any { it.text() == "Đoạn 2" })
    }
}
