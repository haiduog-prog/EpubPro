package com.vieneu.tts.sample.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Unit test cho các hàm hậu xử lý waveform PCM của [VieNeuNanoEngine.Companion].
 */
class VieNeuAudioUtilsTest {

    /**
     * Kiểm tra [VieNeuNanoEngine.edgeSilence] nhận diện đúng số mẫu im lặng ở hai đầu waveform.
     */
    @Test
    fun `edgeSilence nhan dien dung khoang im lang dau va cuoi`() {
        val sr = 1_000
        val wav = FloatArray(500) { idx ->
            if (idx in 100 until 350) 0.25f else 0.0001f
        }
        val (lead, tail) = VieNeuNanoEngine.edgeSilence(wav, sr)
        assertEquals(100, lead)
        assertEquals(150, tail)
    }

    /**
     * Kiểm tra [VieNeuNanoEngine.edgeSilence] trả về `(size, 0)` khi toàn bộ tín hiệu im lặng.
     */
    @Test
    fun `edgeSilence tra ve toan bo do dai khi im lang hoan toan`() {
        val wav = FloatArray(480) { 0f }
        val (lead, tail) = VieNeuNanoEngine.edgeSilence(wav, sampleRate = 1_000)
        assertEquals(480, lead)
        assertEquals(0, tail)
    }

    /**
     * Kiểm tra [VieNeuNanoEngine.trimAndFade] cắt bớt khoảng lặng thừa nhưng vẫn giữ biên 40 ms và tạo fade cosine 15 ms hai đầu.
     */
    @Test
    fun `trimAndFade cat khoang lang thua va fade muot hai dau`() {
        val sr = 1_000
        val wav = FloatArray(500) { idx ->
            if (idx in 150 until 350) 0.5f else 0f
        }
        val trimmed = VieNeuNanoEngine.trimAndFade(wav, sr)
        // Giữ 40 ms (40 mẫu theo EDGE_KEEP_S = 0.04f) mỗi đầu => 40 + 200 + 40 = 280 mẫu.
        assertEquals(280, trimmed.size)
        assertEquals(0f, trimmed.first(), 1e-6f)
        assertEquals(0f, trimmed.last(), 1e-6f)
        // Điểm giữa phải giữ nguyên biên độ gốc không bị suy hao bởi fade 15 ms.
        assertTrue(abs(trimmed[trimmed.size / 2] - 0.5f) < 1e-5f)

        // Xác minh đường cong fade cosine 15 ms (15 mẫu ở 1000 Hz) thật sự làm dịu hai đầu tín hiệu.
        val activeOnly = FloatArray(100) { 0.8f }
        val faded = VieNeuNanoEngine.trimAndFade(activeOnly, sr)
        assertEquals(100, faded.size)
        assertEquals(0f, faded.first(), 1e-6f)
        assertEquals(0f, faded.last(), 1e-6f)
        assertTrue(abs(faded[7] - 0.4f) < 1e-5f)
        assertTrue(abs(faded[50] - 0.8f) < 1e-5f)
    }

    /**
     * Kiểm tra [VieNeuNanoEngine.computePausePadSamples] tính đúng số mẫu chèn cho đủ các mức [ChunkGap].
     */
    @Test
    fun `computePausePadSamples tinh dung khoang nghi theo tung loai ChunkGap`() {
        val sr = 1_000
        // Đầu và cuối mỗi chunk đều có 100 ms im lặng (-Inf dB), còn lại tín hiệu 0.4f.
        val prev = FloatArray(300) { idx -> if (idx < 200) 0.4f else 0f } // tail silence = 100 ms
        val next = FloatArray(300) { idx -> if (idx >= 100) 0.4f else 0f } // lead silence = 100 ms

        assertEquals(0, VieNeuNanoEngine.computePausePadSamples(prev, next, ChunkGap.FIRST, sr))
        // PARA = 700 ms - (100 + 100) ms = 500 ms => 500 mẫu ở 1000 Hz.
        assertEquals(500, VieNeuNanoEngine.computePausePadSamples(prev, next, ChunkGap.PARA, sr))
        // SENTENCE = 500 ms - 200 ms = 300 ms => 300 mẫu.
        assertEquals(300, VieNeuNanoEngine.computePausePadSamples(prev, next, ChunkGap.SENTENCE, sr))
        // MINOR = 300 ms - 200 ms = 100 ms => 100 mẫu.
        assertEquals(100, VieNeuNanoEngine.computePausePadSamples(prev, next, ChunkGap.MINOR, sr))

        // Nếu tổng im lặng sẵn >= ngưỡng mục tiêu thì không chèn âm.
        val longTail = FloatArray(400) { idx -> if (idx < 100) 0.4f else 0f } // tail = 300 ms
        assertEquals(0, VieNeuNanoEngine.computePausePadSamples(longTail, next, ChunkGap.MINOR, sr))
    }
}
