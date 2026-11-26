package com.epubpro.core.tts

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * So khớp [VieNeuTurboTokenizer] với thư viện `tokenizers` Python trên dữ liệu mẫu
 * (`vieneu_turbo_tokenizer_golden.json`, sinh từ SDK `vieneu` 3.8.5 và `tokenizer.json` chính thức).
 */
class VieNeuTurboTokenizerTest {

    private val tokenizer = VieNeuTurboTokenizer.fromJson(resource("vieneu_turbo_tokenizer.json"))

    @Test
    fun `token ids match the reference tokenizer for every golden case`() {
        val cases = JSONObject(resource("vieneu_turbo_tokenizer_golden.json")).getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            if (case.isNull("ids")) continue
            val phonemes = case.getString("phonemes")
            val expected = case.getJSONArray("ids").let { arr -> IntArray(arr.length()) { arr.getInt(it) } }
            assertArrayEquals("Lệch token id ở case $i: $phonemes", expected, tokenizer.encode(phonemes))
        }
    }

    @Test
    fun `emotion tokens are recognized as single special tokens`() {
        assertArrayEquals(intArrayOf(9), tokenizer.encode("<|emotion_1|>"))
    }

    @Test
    fun `byte level mapping keeps printable ascii and shifts control bytes`() {
        assertEquals("a", VieNeuTurboTokenizer.toByteLevel("a"))
        assertEquals("Ġ", VieNeuTurboTokenizer.toByteLevel(" "))
        assertEquals("Ċ", VieNeuTurboTokenizer.toByteLevel("\n"))
    }

    /**
     * Đọc file tài nguyên test dạng UTF-8.
     *
     * @param name Tên file trong `src/test/resources`.
     * @return Nội dung file.
     */
    private fun resource(name: String): String =
        requireNotNull(javaClass.classLoader?.getResourceAsStream(name)) { "Thiếu resource $name" }
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
}
