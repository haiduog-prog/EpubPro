package com.epubpro.core.tts

import com.epubpro.domain.model.AiTtsBackend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsVoiceCatalogTest {

    @Test
    fun `voice ids are unique and model files are mapped`() {
        val voices = TtsVoiceCatalog.aiVoices

        assertEquals(voices.size, voices.map { it.id }.distinct().size)
        assertTrue(voices.all { it.onnxFileName.endsWith(".onnx") })
        assertEquals("ngocngan3701.onnx", TtsVoiceCatalog.find("ngoc_ngan")?.onnxFileName)
    }

    @Test
    fun `AI catalog only exposes supported language`() {
        assertEquals(25, TtsVoiceCatalog.forLanguage("vi").size)
        assertTrue(TtsVoiceCatalog.forLanguage("en").isEmpty())
    }

    @Test
    fun `missing or blank selection has no fallback`() {
        assertNull(TtsVoiceCatalog.find(null))
        assertNull(TtsVoiceCatalog.find(""))
        assertNull(TtsVoiceCatalog.find("unknown"))
    }

    @Test
    fun `vieneu catalog exposes 11 unique voices without colliding with sherpa ids`() {
        val vieNeu = TtsVoiceCatalog.vieNeuVoices
        val sherpaIds = TtsVoiceCatalog.aiVoices.map { it.id }.toSet()

        assertEquals(11, vieNeu.size)
        assertEquals(vieNeu.size, vieNeu.map { it.id }.distinct().size)
        assertTrue(vieNeu.all { it.id.startsWith("vieneu:") })
        assertTrue(vieNeu.none { it.id in sherpaIds })
        assertEquals(11, TtsVoiceCatalog.vieNeuForLanguage("vi").size)
        assertTrue(TtsVoiceCatalog.vieNeuForLanguage("en").isEmpty())
    }

    @Test
    fun `backend resolution distinguishes sherpa and vieneu presets cleanly`() {
        assertEquals(AiTtsBackend.SHERPA_VITS, TtsVoiceCatalog.findBackend("manh_dung"))
        assertEquals(AiTtsBackend.VIENEU_NANO, TtsVoiceCatalog.findBackend("vieneu:Mạnh Dũng"))
        assertNotNull(TtsVoiceCatalog.findVieNeu("Minh Quân"))
        assertNotNull(TtsVoiceCatalog.findVieNeu(TtsVoiceCatalog.DEFAULT_VIENEU_VOICE_ID))
        assertFalse(TtsVoiceCatalog.isVieNeuVoice("manh_dung"))
        assertTrue(TtsVoiceCatalog.isVieNeuVoice("vieneu:Minh Quân"))
        assertNull(TtsVoiceCatalog.findBackend("non_existent_id"))
    }
}
