package com.epubpro.core.tts

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random

class VieNeuTurboSupportTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `frame cap, syllable count and cue detection match the SDK`() {
        val cases = JSONObject(resource("vieneu_turbo_tokenizer_golden.json")).getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val phonemes = case.getString("phonemes")
            assertEquals("max_frames case $i: $phonemes", case.getInt("max_frames"), VieNeuTurboText.maxExpectedFrames(phonemes))
            assertEquals("syllables case $i: $phonemes", case.getInt("syllables"), VieNeuTurboText.syllableCount(phonemes))
            assertEquals("cue_only case $i: $phonemes", case.getBoolean("cue_only"), VieNeuTurboText.isCueOnly(phonemes))
        }
    }

    @Test
    fun `greedy sampling applies repetition penalty before argmax`() {
        val sampler = VieNeuTurboSampler()
        val logits = floatArrayOf(1f, 3f, 2.8f)
        val code = sampler.sample(logits, temperature = 0f, topK = 25, topP = 0.95f, repetitionPenalty = 1.2f, history = setOf(1))
        assertEquals(2, code)
    }

    @Test
    fun `negative logits are multiplied by the penalty`() {
        val logits = floatArrayOf(-1f, -2f)
        VieNeuTurboSampler().sample(logits, temperature = 0f, topK = 0, topP = 1f, repetitionPenalty = 2f, history = setOf(0))
        assertEquals(-2f, logits[0])
    }

    @Test
    fun `top k restricts sampling to the strongest candidates`() {
        val sampler = VieNeuTurboSampler(Random(7))
        val logits = FloatArray(100) { if (it == 42 || it == 17) 10f else 0f }
        repeat(200) {
            val code = sampler.sample(logits.copyOf(), temperature = 0.8f, topK = 2, topP = 1f, repetitionPenalty = 1f, history = null)
            assertTrue(code == 42 || code == 17)
        }
    }

    @Test
    fun `top p keeps only the nucleus`() {
        val sampler = VieNeuTurboSampler(Random(3))
        val logits = floatArrayOf(10f, 0f, 0f, 0f)
        repeat(100) {
            assertEquals(0, sampler.sample(logits.copyOf(), temperature = 1f, topK = 0, topP = 0.5f, repetitionPenalty = 1f, history = null))
        }
    }

    @Test
    fun `repetition window evicts codes older than the window`() {
        val window = VieNeuTurboRepetitionWindow(2)
        window.add(5)
        window.add(6)
        window.add(5)
        assertEquals(setOf(5, 6), window.codes)
        window.add(7)
        assertEquals(setOf(5, 7), window.codes)
    }

    @Test
    fun `npz arrays are memory mapped with shape and values`() {
        val file = File(tmp.root, "heads.npz")
        writeStoredNpz(file, mapOf("a" to (intArrayOf(2, 3) to FloatArray(6) { it * 0.5f }), "eps" to (intArrayOf() to floatArrayOf(1e-6f))))
        val arrays = VieNeuTurboNpyArray.mapNpz(file)
        val a = arrays.getValue("a")
        assertEquals(listOf(2, 3), a.shape.toList())
        assertEquals(2.5f, a.buffer.get(5))
        assertEquals(1e-6f, arrays.getValue("eps").buffer.get(0))
    }

    @Test
    fun `user voice file parses into a valid preset`() {
        val voices = VieNeuTurboVoice.parsePresets(resource("voices_v3_turbo_user.json"), isUserVoice = true)
        assertEquals(1, voices.size)
        val voice = voices.single()
        assertEquals("Vie_ThuyTrang", voice.name)
        assertEquals(VieNeuTurboVoice.SPEAKER_EMBEDDING_DIM, voice.speakerEmbedding.size)
        assertTrue(voice.refCodes.isNotEmpty() && voice.refCodes.all { it.size == VieNeuTurboVoice.CODEBOOKS })
        assertTrue(voice.isUserVoice)
        assertEquals("tu_nhien", voice.style)
    }

    @Test
    fun `invalid presets are skipped and trailing pad frames trimmed`() {
        val pad = (0 until 16).joinToString(",") { if (it == 0) "455" else "1" }
        val real = (0 until 16).joinToString(",") { "2" }
        val emb = (0 until 192).joinToString(",") { "0.1" }
        val json = """{"presets": {
            "ok": {"speaker_emb": [$emb], "codes": [[$real],[$pad]]},
            "zero": {"speaker_emb": [${(0 until 192).joinToString(",") { "0" }}], "codes": [[$real]]},
            "short": {"speaker_emb": [0.1], "codes": [[$real]]}
        }}"""
        val voices = VieNeuTurboVoice.parsePresets(json, isUserVoice = false)
        assertEquals(listOf("ok"), voices.map { it.name })
        assertEquals(1, voices.single().refCodes.size)
        assertFalse(voices.single().isUserVoice)
    }

    @Test
    fun `matmul head model encodes a valid protobuf header`() {
        val bytes = VieNeuTurboMatMulModel.bytes(768)
        assertEquals(0x08, bytes[0].toInt())
        assertTrue(String(bytes, Charsets.ISO_8859_1).contains("MatMul"))
    }

    /**
     * Ghi file `.npz` không nén giống `np.savez` để kiểm tra bộ ánh xạ mmap.
     *
     * @param file File đích.
     * @param arrays Tên mảng → (shape, dữ liệu float32).
     */
    private fun writeStoredNpz(file: File, arrays: Map<String, Pair<IntArray, FloatArray>>) {
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((name, value) in arrays) {
                val (shape, data) = value
                val shapeText = when (shape.size) {
                    0 -> "()"
                    1 -> "(${shape[0]},)"
                    else -> shape.joinToString(", ", "(", ")")
                }
                var header = "{'descr': '<f4', 'fortran_order': False, 'shape': $shapeText, }"
                while ((10 + header.length + 1) % 64 != 0) header += " "
                header += "\n"
                val body = ByteBuffer.allocate(10 + header.length + data.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                body.put(0x93.toByte()); body.put("NUMPY".toByteArray()); body.put(1); body.put(0)
                body.putShort(header.length.toShort()); body.put(header.toByteArray(Charsets.ISO_8859_1))
                data.forEach { body.putFloat(it) }
                val bytes = body.array()
                val entry = ZipEntry("$name.npy").apply {
                    method = ZipEntry.STORED
                    size = bytes.size.toLong()
                    compressedSize = bytes.size.toLong()
                    crc = CRC32().apply { update(bytes) }.value
                }
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    private fun resource(name: String): String =
        requireNotNull(javaClass.classLoader?.getResourceAsStream(name)) { "Thiếu resource $name" }
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
}
