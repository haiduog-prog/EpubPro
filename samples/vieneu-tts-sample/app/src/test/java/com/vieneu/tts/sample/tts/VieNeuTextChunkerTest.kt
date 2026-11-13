package com.vieneu.tts.sample.tts

import com.vieneu.tts.sample.g2p.TextG2p
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit test cho bộ tách câu, đóng gói chunk và giữ thẻ cảm xúc [VieNeuTextChunker].
 */
class VieNeuTextChunkerTest {

    private class FakeG2p : TextG2p {
        override fun normalize(text: String, puncNorm: Boolean): String {
            val replaced = text
                .replace("13/11/2026", "ngày mười ba tháng mười một năm hai nghìn không trăm hai mươi sáu")
                .trim()
            return if (puncNorm) puncNorm(replaced) else replaced
        }

        override fun phonemize(text: String, puncNorm: Boolean): String {
            val out = normalize(text, puncNorm).replace("  ", " ").trim()
            return if (puncNorm) puncNorm(out) else out
        }

        override fun puncNorm(text: String): String {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return ""
            val words = trimmed.split(Regex("""\s+""")).size
            val last = trimmed.last()
            return when {
                last in setOf(',', '.', '!', '?') -> trimmed
                words < 5 -> "$trimmed."
                else -> "$trimmed."
            }
        }

        override fun close() = Unit
    }

    private val chunker = VieNeuTextChunker(FakeG2p())

    /**
     * Kiểm tra tách câu không cắt ngang bên trong dấu ngoặc kép trích dẫn lời thoại.
     */
    @Test
    fun `splitIntoSentences khong cat giua dau ngoac kep hoac ngoac don`() {
        val raw = "Cô khẽ hỏi: \"Rồi sao nữa? Mình phải làm đến bao giờ?\" rồi bước đi."
        val sentences = VieNeuTextChunker.splitIntoSentences(raw)
        assertEquals(1, sentences.size)
        assertEquals(raw, sentences[0])
    }

    /**
     * Kiểm tra phục hồi tách câu bình thường khi văn bản có dấu ngoặc mở nhưng không đóng.
     */
    @Test
    fun `splitIntoSentences phuc hoi khi dau ngoac bi lech`() {
        val raw = "Câu thứ nhất \"bị thiếu dấu đóng. Câu thứ hai bình thường."
        val sentences = VieNeuTextChunker.splitIntoSentences(raw)
        assertEquals(2, sentences.size)
    }

    /**
     * Kiểm tra giữ đúng ranh giới đoạn văn ([ChunkGap.PARA]) và ánh xạ thẻ cảm xúc sang `<|emotion_k|>`.
     */
    @Test
    fun `prepareChunks giu dung ranh gioi doan van va emotion tags`() {
        val text = "Đoạn một khá dài để kiểm tra cách ngắt đoạn văn bản cho chuẩn xác.\n\nXin chào [cười], rất vui được gặp bạn hôm nay!"
        val prepared = chunker.prepareChunks(text, maxChars = 140, minChunkChars = 20)

        assertEquals(2, prepared.size)
        assertEquals(ChunkGap.FIRST, prepared[0].gapBefore)
        assertEquals(ChunkGap.PARA, prepared[1].gapBefore)
        assertTrue(prepared[1].phonemes.contains("<|emotion_1|>"))
        assertFalse(prepared[1].phonemes.contains("<|emotion_1|> ,"))
        assertTrue(prepared[1].phonemes.contains("<|emotion_1|>,"))
    }

    /**
     * Kiểm tra gộp chunk quá ngắn vào chunk liền kề để tránh audio rời rạc 1-2 từ.
     */
    @Test
    fun `mergeShortChunks gop chunk qua ngan vao chunk ke`() {
        val text = "Chương một.\n\nBuổi sáng mùa thu ở vùng ngoại ô yên bình và rất trong lành."
        val (chunks, _) = chunker.normalizeToChunksWithGaps(text, maxChars = 140, minChunkChars = 20)
        assertEquals(1, chunks.size)
        assertTrue(chunks[0].startsWith("Chương một."))
    }

    /**
     * Kiểm tra `balancedCut` ưu tiên ngắt trước từ nối (`nhưng`) khi câu dài không có dấu câu phụ.
     */
    @Test
    fun `balancedCut uu tien ngat truoc tu noi trong cau dai`() {
        val text = "Hôm nay thời tiết ngoài trời mát mẻ dễ chịu nhưng chúng tôi vẫn quyết định ở nhà đọc sách suốt buổi sáng."
        val (chunks, _) = chunker.normalizeToChunksWithGaps(text, maxChars = 65, minChunkChars = 20)
        assertEquals(2, chunks.size)
        assertFalse(chunks[0].contains("nhưng"))
        assertTrue(chunks[1].startsWith("nhưng"))
    }

    /**
     * Kiểm tra `balancedCut` tránh xẻ đôi cụm từ chỉ số tiếng Việt dù điểm giữa nằm ngay giữa cụm số.
     */
    @Test
    fun `balancedCut khong xe doi cum tu so tieng Viet`() {
        val text = "Cửa hàng đón đúng hai mươi lăm khách mới."
        val (chunks, _) = chunker.normalizeToChunksWithGaps(text, maxChars = 31, minChunkChars = 10)
        assertEquals(2, chunks.size)
        assertTrue(chunks[1].startsWith("hai mươi lăm"))
    }

    /**
     * Kiểm tra `splitLongPart` giữ nguyên khối `<en>...</en>` trong một chunk duy nhất.
     */
    @Test
    fun `splitLongPart giu nguyen cum the en khong cat ngang`() {
        val text = "Ứng dụng hỗ trợ tốt cụm từ <en>deep neural speech synthesis</en> mượt mà trên Android."
        val (chunks, _) = chunker.normalizeToChunksWithGaps(text, maxChars = 60, minChunkChars = 15)
        assertTrue(chunks.size >= 2)
        assertTrue(chunks.any { "<en>deep neural speech synthesis</en>" in it })
    }
}
