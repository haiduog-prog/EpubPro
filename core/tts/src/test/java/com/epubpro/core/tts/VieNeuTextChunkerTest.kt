package com.epubpro.core.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VieNeuTextChunkerTest {

    /**
     * Fake [TextG2p] thuần JVM giúp test tách đoạn/câu và emotion tags mà không cần nạp JNI.
     */
    private class FakeTextG2p : TextG2p {
        val phonemizedInputs = mutableListOf<String>()

        override fun normalize(text: String, puncNorm: Boolean): String {
            val cleaned = text.trim()
            return if (puncNorm) puncNorm(cleaned) else cleaned
        }

        override fun phonemize(text: String, puncNorm: Boolean): String {
            phonemizedInputs += text
            val base = text.trim()
            return if (puncNorm) "ipa($base)" else "ipa_raw($base)"
        }

        override fun puncNorm(text: String): String {
            val t = text.trim()
            if (t.isEmpty()) return ""
            return if (t.last() in ".!?,;:…") t else "$t."
        }

        override fun close() = Unit
    }

    @Test
    fun `blank input yields no chunks`() {
        val chunker = VieNeuTextChunker(FakeTextG2p())

        assertTrue(chunker.prepareChunks("   \n\n  ").isEmpty())
    }

    @Test
    fun `paragraph boundary assigns PARA gap and sentence boundary assigns SENTENCE gap`() {
        val g2p = FakeTextG2p()
        val chunker = VieNeuTextChunker(g2p)
        val input = "Đoạn một có câu dài đủ hai mươi ký tự.\n\nĐoạn hai cũng đủ dài để không bị gộp lại."

        val prepared = chunker.prepareChunks(input, maxChars = 120, minChunkChars = 20)

        assertEquals(2, prepared.size)
        assertEquals(ChunkGap.FIRST, prepared[0].gapBefore)
        assertEquals(ChunkGap.PARA, prepared[1].gapBefore)
    }

    @Test
    fun `emotion tags are normalized outside g2p and reattached in phonemes`() {
        val g2p = FakeTextG2p()
        val chunker = VieNeuTextChunker(g2p)

        val prepared = chunker.prepareChunks(
            "Xin chào bạn đọc sách [cười] hôm nay trời rất đẹp luôn nhé.",
            maxChars = 140,
            minChunkChars = 10,
        )

        assertEquals(1, prepared.size)
        assertTrue(prepared[0].text.contains("<|emotion_1|>"))
        assertTrue(prepared[0].phonemes.contains("<|emotion_1|>"))
        assertTrue(g2p.phonemizedInputs.none { "<|emotion_" in it })
    }

    @Test
    fun `short sentences under minChunkChars merge within same paragraph`() {
        val g2p = FakeTextG2p()
        val chunker = VieNeuTextChunker(g2p)

        val prepared = chunker.prepareChunks(
            "Chào bạn. Đây là câu dài thứ hai để test gộp chunk ngắn trong cùng một đoạn.",
            maxChars = 140,
            minChunkChars = 20,
        )

        assertEquals(1, prepared.size)
        assertTrue(prepared[0].text.contains("Chào bạn."))
    }

    @Test
    fun `long sentence splits under maxChars without losing words`() {
        val g2p = FakeTextG2p()
        val chunker = VieNeuTextChunker(g2p)
        val sentence = (1..20).joinToString(" ") { "từ$it" } + "."

        val prepared = chunker.prepareChunks(sentence, maxChars = 45, minChunkChars = 10)

        assertTrue(prepared.size >= 2)
        assertTrue(prepared.all { it.text.length <= 65 })
    }
}
