package com.epubpro.core.ocr

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** Kiểm thử phần thuần Kotlin của VietOCR: bảng ký tự, tiền xử lý ảnh dòng và chọn chữ cho dòng. */
class VietOcrTest {

    /** Id ký tự lệch 4; token đặc biệt bị bỏ và dừng ở token kết thúc. */
    @Test
    fun decodesIdsAndStopsAtEos() {
        val vocab = VietOcrVocab("aàb ")

        assertEquals(8, vocab.size)
        assertEquals("àb a", vocab.decode(listOf(VietOcrVocab.SOS, 5, 6, 7, 4, VietOcrVocab.EOS, 4)))
        assertEquals("a", vocab.decode(listOf(0, 3, 4, 99)))
    }

    /** Bảng ký tự đóng gói khớp model: 229 ký tự, đủ chữ có dấu, kết thúc bằng dấu cách. */
    @Test
    fun bundledVocabMatchesModel() {
        val chars = File("src/main/assets/vietocr/vietocr_vocab.txt").readText(Charsets.UTF_8)

        assertEquals(229, chars.length)
        assertEquals(233, VietOcrVocab(chars).size)
        assertEquals("aAàÀ", chars.take(4))
        assertEquals(true, "ỹ" in chars && "Đ" in chars && "ự" in chars)
        assertEquals(' ', chars.last())
    }

    /** Độ rộng giữ tỉ lệ, làm tròn lên bội số 10 và kẹp trong 32..512 như bản Python. */
    @Test
    fun computesTargetWidthLikeTraining() {
        assertEquals(320, VietOcrPreprocessor.targetWidth(600, 60))
        assertEquals(170, VietOcrPreprocessor.targetWidth(1000, 190))
        assertEquals(32, VietOcrPreprocessor.targetWidth(20, 60))
        assertEquals(512, VietOcrPreprocessor.targetWidth(3000, 60))
    }

    /** Co 2 lần lấy trung bình từng ô 2x2, chuẩn hóa 0..1, kênh R-G-B tách riêng theo CHW. */
    @Test
    fun averagesBoxesIntoChwTensor() {
        val width = 64
        val height = 64
        val argb = IntArray(width * height) { i ->
            val x = i % width
            // Cột chẵn đỏ thuần, cột lẻ đen: sau khi co mỗi điểm R = 127.5, G = B = 0.
            if (x % 2 == 0) 0xFFFF0000.toInt() else 0xFF000000.toInt()
        }

        val tensor = VietOcrPreprocessor.toInputTensor(argb, width, height, outWidth = 32)

        assertEquals(3 * 32 * 32, tensor.size)
        val plane = 32 * 32
        assertArrayEquals(FloatArray(plane) { 0.5f }, tensor.copyOfRange(0, plane), 1e-6f)
        assertArrayEquals(FloatArray(plane * 2), tensor.copyOfRange(plane, 3 * plane), 1e-6f)
    }

    /** Phóng to (dòng thấp hơn 32 px) vẫn ra đúng kích thước và giữ màu nền. */
    @Test
    fun upscalesShortLines() {
        val argb = IntArray(10 * 8) { 0xFF336699.toInt() }

        val tensor = VietOcrPreprocessor.toInputTensor(argb, width = 10, height = 8, outWidth = 40)

        assertEquals(3 * 32 * 40, tensor.size)
        assertEquals(0x33 / 255f, tensor[0], 1e-6f)
        assertEquals(0x66 / 255f, tensor[32 * 40], 1e-6f)
        assertEquals(0x99 / 255f, tensor.last(), 1e-6f)
    }

    /** Dùng chữ VietOCR khi độ dài hợp lý; rỗng, lỗi hoặc lệch hẳn thì giữ chữ ML Kit. */
    @Test
    fun choosesVietOcrTextOnlyWhenPlausible() {
        assertEquals("Năm ấy nước lên sớm", chooseLineText("Năm ây nuóc lên sóm", " Năm ấy nước lên sớm "))
        assertEquals("Năm ây nuóc", chooseLineText("Năm ây nuóc", null))
        assertEquals("Năm ây nuóc", chooseLineText("Năm ây nuóc", "  "))
        assertEquals("Năm ây nuóc lên sóm hon moi năm", chooseLineText("Năm ây nuóc lên sóm hon moi năm", "Năm"))
        assertEquals("12", chooseLineText("12", "12"))
        assertEquals("ab", chooseLineText("ab", "một dòng dài bất thường không liên quan"))
    }

    /** Từ VietOCR tự thêm ở mép dòng bị bỏ khi từ kế bên khớp mép của ML Kit (so bỏ dấu, lệch ≤ 1 ký tự). */
    @Test
    fun dropsWordsAddedAtLineEdges() {
        assertEquals(
            "nùa của những chiếc xuồng nhỏ len",
            chooseLineText("mua của nhung chiếc xuông nhỏ len", "xã nùa của những chiếc xuồng nhỏ len này")
        )
        assertEquals(
            "mười tuổi, mùa nước nổi là",
            chooseLineText("muời tuối, mùa nước nối là", "mười tuổi, mùa nước nổi là 1970")
        )
        assertEquals("Đêm đó tôi nằm nghe", chooseLineText("Dêm đó tôi nẳm nghe", "Đêm đó tôi nằm nghe"))
        // Không cắt khi ML Kit cũng có từ đó hoặc khi từ mép khớp.
        assertEquals("- Dạ, con cũng thấy", chooseLineText("- Da, con cũng thây", "- Dạ, con cũng thấy"))
        assertEquals("ông nói khẽ thêm", chooseLineText("ông nói khê thêm", "ông nói khẽ thêm"))
        assertEquals("ông nói khẽ", chooseLineText("ông nói khê", "ông nói khẽ thêm"))
    }

    /** Gạch đầu lời thoại ML Kit thấy mà VietOCR bỏ sót được thêm lại; đã có thì giữ nguyên. */
    @Test
    fun restoresDialogueDash() {
        assertEquals("– Năm nay nước to đấy,", chooseLineText("– Năm nay nuớc to đây,", "Năm nay nước to đấy,"))
        assertEquals("- Dạ, con cũng thấy", chooseLineText("- Da, con cũng thây", "- Dạ, con cũng thấy"))
        assertEquals("Năm nay nước to", chooseLineText("Năm nay nuớc to", "Năm nay nước to"))
    }
}
