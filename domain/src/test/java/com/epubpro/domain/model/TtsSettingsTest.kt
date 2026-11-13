package com.epubpro.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TtsSettingsTest {

    @Test
    fun `default settings use native Vietnamese voice without selected model`() {
        val settings = TtsSettings()

        assertEquals(false, settings.isAiVoice)
        assertEquals("vi", settings.language)
        assertNull(settings.voiceId)
        assertEquals(1.0f, settings.speed)
        assertEquals(1.0f, settings.pitch)
    }

    @Test
    fun `AI settings force Vietnamese and neutral pitch without adding fallback voice`() {
        val normalized = TtsSettings(
            isAiVoice = true,
            language = "en",
            voiceId = null,
            speed = 3.0f,
            pitch = 1.5f
        ).normalizedForPlayback()

        assertEquals("vi", normalized.language)
        assertNull(normalized.voiceId)
        assertEquals(2.0f, normalized.speed)
        assertEquals(1.0f, normalized.pitch)
    }

    @Test
    fun `native settings retain supported language and clamp audio values`() {
        val normalized = TtsSettings(
            language = "en",
            voiceId = "  system-voice  ",
            speed = 0.1f,
            pitch = 2.0f
        ).normalizedForPlayback()

        assertEquals("en", normalized.language)
        assertEquals("system-voice", normalized.voiceId)
        assertEquals(0.5f, normalized.speed)
        assertEquals(1.5f, normalized.pitch)
    }

    @Test
    fun `default settings use S25 Ultra fast VieNeu preset with 4 threads`() {
        val settings = TtsSettings()

        assertEquals(VieNeuQualityPreset.FAST, settings.vieNeuPreset)
        assertEquals(8, settings.vieNeuSteps)
        assertEquals(3.0f, settings.vieNeuCfg)
        assertEquals(-1.0f, settings.vieNeuSway)
        assertEquals(4, settings.vieNeuThreads)
        assertEquals(false, settings.isVieNeuVoiceSelected)
    }

    @Test
    fun `normalizedForPlayback clamps VieNeu synthesis params and thread count`() {
        val normalized = TtsSettings(
            isAiVoice = true,
            voiceId = "vieneu:Minh Quân",
            vieNeuPreset = VieNeuQualityPreset.CUSTOM,
            vieNeuSteps = 99,
            vieNeuCfg = -2.0f,
            vieNeuSway = 1.5f,
            vieNeuThreads = 1
        ).normalizedForPlayback()

        assertEquals(true, normalized.isVieNeuVoiceSelected)
        assertEquals(24, normalized.vieNeuSteps)
        assertEquals(0.0f, normalized.vieNeuCfg)
        assertEquals(0.0f, normalized.vieNeuSway)
        assertEquals(2, normalized.vieNeuThreads)
        assertEquals(VieNeuQualityPreset.CUSTOM, normalized.vieNeuPreset)
    }

    @Test
    fun `withVieNeuPreset updates params and keeps custom values when switching to custom`() {
        val balanced = TtsSettings().withVieNeuPreset(VieNeuQualityPreset.BALANCED)
        assertEquals(VieNeuQualityPreset.BALANCED, balanced.vieNeuPreset)
        assertEquals(16, balanced.vieNeuSteps)
        assertEquals(3.0f, balanced.vieNeuCfg)
        assertEquals(0.0f, balanced.vieNeuSway)

        val turbo = balanced.withVieNeuPreset(VieNeuQualityPreset.TURBO)
        assertEquals(VieNeuQualityPreset.TURBO, turbo.vieNeuPreset)
        assertEquals(8, turbo.vieNeuSteps)
        assertEquals(0.0f, turbo.vieNeuCfg)
        assertEquals(-1.0f, turbo.vieNeuSway)

        val custom = balanced.withVieNeuPreset(VieNeuQualityPreset.CUSTOM)
        assertEquals(VieNeuQualityPreset.CUSTOM, custom.vieNeuPreset)
        assertEquals(16, custom.vieNeuSteps)
        assertEquals(VieNeuQualityPreset.CUSTOM, VieNeuQualityPreset.matchingPreset(12, 2.5f, -0.5f))
    }
}
