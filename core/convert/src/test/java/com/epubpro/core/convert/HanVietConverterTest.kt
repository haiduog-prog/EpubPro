package com.epubpro.core.convert

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Kiểm thử từ điển nhị phân, longest-match theo tầng ưu tiên, dấu câu và convert HTML. */
class HanVietConverterTest {
    private val phienAm = PhraseDictionary.fromEntries(
        listOf("我" to "ngã", "是" to "thị", "林" to "lâm", "动" to "động", "说" to "thuyết", "你" to "nhĩ", "好" to "hảo", "第" to "đệ", "章" to "chương")
    )
    private val vietPhrase = PhraseDictionary.fromEntries(
        listOf("我" to "ta", "是" to "là", "你好" to "xin chào", "说" to "nói", "林动说" to "SAI_DO_UU_TIEN_NAME")
    )

    /** Cụm dài nhất thắng; Name thắng VietPhrase khi cùng độ dài; viết hoa đầu câu và sau dấu kết câu. */
    @Test
    fun convertsWithLongestMatchAndPriority() {
        val names = PhraseDictionary.fromEntries(listOf("林动" to "Lâm Động", "林动说" to "Lâm Động bảo"))
        val converter = HanVietConverter(listOf(names, vietPhrase, phienAm))

        assertEquals("Lâm Động bảo: “Xin chào!” Ta là Lâm Động.", converter.convert("林动说：“你好！”我是林动。").text)
    }

    /** Chữ không có trong từ điển dùng âm Hán-Việt; chữ số đứng riêng có khoảng cách. */
    @Test
    fun fallsBackToPhienAmAndSpacesDigits() {
        val converter = HanVietConverter(listOf(vietPhrase, phienAm))
        assertEquals("Đệ 1 chương", converter.convert("第1章").text)
    }

    /** Ký tự không có trong mọi tầng được giữ nguyên. */
    @Test
    fun keepsUnknownCharacters() {
        val converter = HanVietConverter(listOf(phienAm))
        assertEquals("Ngã 龘", converter.convert("我龘").text)
    }

    /** Builder khử trùng giữ dòng sau cùng, đọc định dạng QuickTranslator và ghi/nạp lại qua file nhị phân. */
    @Test
    fun builderParsesQuickTranslatorAndRoundTripsFile() {
        val builder = DictionaryBuilder()
        builder.addQuickTranslatorLines(
            "﻿# chú thích\n你好=xin chào/chào\n你好=chào bạn\nbad line\n世界=thế giới\n".reader().buffered()
        )
        val dictionary = builder.build()
        assertEquals(2, dictionary.size)
        assertEquals("chào bạn", dictionary.get("你好"))

        val file = Files.createTempFile("dict", ".bin").toFile()
        try {
            dictionary.writeTo(file)
            val loaded = PhraseDictionary.load(file)
            assertEquals(2, PhraseDictionary.readEntryCount(file))
            assertEquals("thế giới", loaded.get("世界"))
            assertEquals(PhraseMatch(2, "chào bạn"), loaded.longestMatch("你好啊", 0))
            assertNull(loaded.get("不存在"))
        } finally {
            file.delete()
            File(file.parentFile, "${file.name}.tmp").delete()
        }
    }

    /** Dòng quá dài (ví dụ file nhị phân chọn nhầm) bị bỏ qua mà không ảnh hưởng các dòng hợp lệ. */
    @Test
    fun builderSkipsOverlongLines() {
        val builder = DictionaryBuilder()
        val overlong = "长=" + "x".repeat(DictionaryBuilder.MAX_LINE_CHARS + 10)
        builder.addQuickTranslatorLines("你好=xin chào\r\n$overlong\n世界=thế giới".reader())

        val dictionary = builder.build()

        assertEquals(2, dictionary.size)
        assertEquals("xin chào", dictionary.get("你好"))
        assertEquals("thế giới", dictionary.get("世界"))
        assertNull(dictionary.get("长"))
    }

    /** HTML giữ nguyên thẻ/thuộc tính, chỉ đổi chữ; mỗi đoạn mới viết hoa từ đầu. */
    @Test
    fun convertsHtmlTextNodesOnly() {
        val converter = HanVietConverter(listOf(vietPhrase, phienAm))
        val html = "<html><head></head><body><p class=\"x\">我是<b>你</b>。</p><p>你好</p><script>var a='我';</script></body></html>"

        val result = HtmlConverter.convert(html, converter)

        assertTrue(result, result.contains("<p class=\"x\">Ta là<b> nhĩ</b>.</p>"))
        assertTrue(result, result.contains("<p>Xin chào</p>"))
        assertTrue(result.contains("var a='我';"))
    }

    /** Nhận diện tiếng Trung, loại tiếng Nhật và tiếng Việt. */
    @Test
    fun detectsChineseText() {
        assertTrue(ChineseTextDetector.isLikelyChinese("林动说：“你好！”我是林动。"))
        assertFalse(ChineseTextDetector.isLikelyChinese("これは日本語の文章です。漢字もある。"))
        assertFalse(ChineseTextDetector.isLikelyChinese("Đây là tiếng Việt."))
    }
}
