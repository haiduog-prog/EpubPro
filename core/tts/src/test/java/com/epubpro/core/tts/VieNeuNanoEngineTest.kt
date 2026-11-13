package com.epubpro.core.tts

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class VieNeuNanoEngineTest {

    @Test
    fun `loadPresets parses bundled asset with all 11 voices`() {
        val assetFile = locateAssetFile()
        val catalog = VieNeuNanoEngine.loadPresets(assetFile.readText(Charsets.UTF_8))

        assertEquals("Minh Quân", catalog.defaultVoiceName)
        assertEquals(11, catalog.voices.size)
        val minhQuan = catalog.voices.first { it.name == "Minh Quân" }
        assertEquals(192, minhQuan.speakerEmbedding.size)
        assertEquals(50, minhQuan.style.size)
        assertEquals(256, minhQuan.style[0].size)
        assertEquals(50 * 256, minhQuan.flatStyle.size)
    }

    @Test
    fun `encodePhones expands emotion tags wraps bos eos and skips oov`() {
        val oovChars = mutableListOf<Char>()
        val ids = VieNeuNanoEngine.encodePhones(
            phonemes = "a<|emotion_1|>?b",
            vocab = mapOf("a" to 10, "b" to 11, "\uE001" to 99),
            emotionMap = mapOf("<|emotion_1|>" to "\uE001"),
            bosId = 1L,
            eosId = 2L,
            onOov = { oovChars += it },
        )

        assertArrayEquals(longArrayOf(1L, 10L, 99L, 11L, 2L), ids)
        assertEquals(listOf('?'), oovChars)
    }

    @Test
    fun `readNpzFloatArrays reads little endian float32 arrays accurately`() {
        val tempFile = File.createTempFile("vieneu-const", ".npz").apply { deleteOnExit() }
        val expected = floatArrayOf(0.5f, -1.25f, 3.75f, 4.0f)
        ZipOutputStream(tempFile.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("null_spk.npy"))
            zip.write(buildNpyV1Float32(intArrayOf(4), expected))
            zip.closeEntry()
        }

        val parsed = VieNeuNanoEngine.readNpzFloatArrays(tempFile)
        val entry = parsed["null_spk.npy"]!!
        assertArrayEquals(intArrayOf(4), entry.shape)
        assertArrayEquals(expected, entry.data, 1e-6f)
    }

    @Test
    fun `trimAndFade trims excess silence and preserves core signal`() {
        val sr = 1_000
        val wav = FloatArray(1_000) { idx ->
            if (idx in 300 until 700) 0.4f else 0f
        }

        val trimmed = VieNeuNanoEngine.trimAndFade(wav, sampleRate = sr)

        assertTrue(trimmed.size < wav.size)
        assertTrue(trimmed.size >= 400)
        assertEquals(0f, trimmed.first(), 1e-5f)
        assertEquals(0f, trimmed.last(), 1e-5f)
    }

    @Test
    fun `computePausePadSamples returns expected samples per gap`() {
        val prev = FloatArray(24_000) { 0.5f }
        val next = FloatArray(24_000) { 0.5f }

        assertEquals(0, VieNeuNanoEngine.computePausePadSamples(prev, next, ChunkGap.FIRST))
        assertEquals(7_200, VieNeuNanoEngine.computePausePadSamples(prev, next, ChunkGap.MINOR))
        assertEquals(12_000, VieNeuNanoEngine.computePausePadSamples(prev, next, ChunkGap.SENTENCE))
        assertEquals(16_800, VieNeuNanoEngine.computePausePadSamples(prev, next, ChunkGap.PARA))
    }

    private fun buildNpyV1Float32(shape: IntArray, data: FloatArray): ByteArray {
        val shapeStr = if (shape.size == 1) "${shape[0]}," else shape.joinToString(", ")
        var header = "{'descr': '<f4', 'fortran_order': False, 'shape': ($shapeStr), }"
        val preamble = 10
        while ((preamble + header.length + 1) % 16 != 0) {
            header += " "
        }
        header += "\n"
        val headerBytes = header.toByteArray(Charsets.ISO_8859_1)
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x93.toByte(), 'N'.code.toByte(), 'U'.code.toByte(), 'M'.code.toByte(), 'P'.code.toByte(), 'Y'.code.toByte()))
        out.write(byteArrayOf(1, 0))
        out.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(headerBytes.size.toShort()).array())
        out.write(headerBytes)
        val dataBuf = ByteBuffer.allocate(data.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        data.forEach { dataBuf.putFloat(it) }
        out.write(dataBuf.array())
        return out.toByteArray()
    }

    private fun locateAssetFile(): File {
        val candidates = listOf(
            File("src/main/assets/voices_v3_nano.json"),
            File("core/tts/src/main/assets/voices_v3_nano.json"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("Không tìm thấy voices_v3_nano.json từ working dir ${File(".").absolutePath}")
    }
}
