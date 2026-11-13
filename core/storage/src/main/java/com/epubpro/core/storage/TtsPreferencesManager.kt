package com.epubpro.core.storage

import android.content.Context
import android.content.SharedPreferences
import com.epubpro.domain.model.TtsSettings
import com.epubpro.domain.model.VieNeuQualityPreset
import com.epubpro.domain.model.normalizedForPlayback
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton
import androidx.core.content.edit

@Singleton
open class TtsPreferencesManager @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("tts_settings_prefs", Context.MODE_PRIVATE)

    private val _settingsFlow = kotlinx.coroutines.flow.MutableStateFlow(getSettings())
    open val settingsFlow: kotlinx.coroutines.flow.StateFlow<TtsSettings> = _settingsFlow.asStateFlow()

    open fun getSettings(): TtsSettings {
        val isConfigured = prefs.getBoolean(KEY_IS_CONFIGURED, false)
        val isAiVoice = prefs.getBoolean(KEY_IS_AI_VOICE, false)
        val language = prefs.getString(KEY_LANGUAGE, "vi") ?: "vi"
        val voiceId = prefs.getString(KEY_VOICE_ID, null)
        val speed = prefs.getFloat(KEY_SPEED, 1.0f)
        val pitch = prefs.getFloat(KEY_PITCH, 1.0f)
        val vieNeuPreset = parsePreset(prefs.getString(KEY_VIENEU_PRESET, VieNeuQualityPreset.FAST.name))
        val vieNeuSteps = prefs.getInt(KEY_VIENEU_STEPS, TtsSettings.DEFAULT_VIENEU_STEPS)
        val vieNeuCfg = prefs.getFloat(KEY_VIENEU_CFG, TtsSettings.DEFAULT_VIENEU_CFG)
        val vieNeuSway = prefs.getFloat(KEY_VIENEU_SWAY, TtsSettings.DEFAULT_VIENEU_SWAY)
        val vieNeuThreads = prefs.getInt(KEY_VIENEU_THREADS, TtsSettings.DEFAULT_VIENEU_THREADS)

        return TtsSettings(
            isConfigured = isConfigured,
            isAiVoice = isAiVoice,
            language = language,
            voiceId = voiceId,
            speed = speed,
            pitch = pitch,
            vieNeuPreset = vieNeuPreset,
            vieNeuSteps = vieNeuSteps,
            vieNeuCfg = vieNeuCfg,
            vieNeuSway = vieNeuSway,
            vieNeuThreads = vieNeuThreads
        ).normalizedForPlayback()
    }

    open fun saveSettings(settings: TtsSettings) {
        val normalizedSettings = settings.normalizedForPlayback()
        prefs.edit {
            putBoolean(KEY_IS_CONFIGURED, normalizedSettings.isConfigured)
                .putBoolean(KEY_IS_AI_VOICE, normalizedSettings.isAiVoice)
                .putString(KEY_LANGUAGE, normalizedSettings.language)
                .putString(KEY_VOICE_ID, normalizedSettings.voiceId)
                .putFloat(KEY_SPEED, normalizedSettings.speed)
                .putFloat(KEY_PITCH, normalizedSettings.pitch)
                .putString(KEY_VIENEU_PRESET, normalizedSettings.vieNeuPreset.name)
                .putInt(KEY_VIENEU_STEPS, normalizedSettings.vieNeuSteps)
                .putFloat(KEY_VIENEU_CFG, normalizedSettings.vieNeuCfg)
                .putFloat(KEY_VIENEU_SWAY, normalizedSettings.vieNeuSway)
                .putInt(KEY_VIENEU_THREADS, normalizedSettings.vieNeuThreads)
        }
        _settingsFlow.value = normalizedSettings
    }

    fun saveLastTtsChunkIndex(bookId: String, chapterIndex: Int, chunkIndex: Int) {
        prefs.edit { putInt("tts_chunk_${bookId}_$chapterIndex", chunkIndex) }
    }

    fun getLastTtsChunkIndex(bookId: String, chapterIndex: Int): Int {
        return prefs.getInt("tts_chunk_${bookId}_$chapterIndex", 0)
    }

    /**
     * Chuyển chuỗi tên preset đã lưu sang [VieNeuQualityPreset] an toàn khi gặp dữ liệu cũ/lạ.
     *
     * @param raw Tên enum đã lưu trong SharedPreferences.
     * @return Giá trị [VieNeuQualityPreset] hợp lệ hoặc mặc định [VieNeuQualityPreset.FAST].
     */
    private fun parsePreset(raw: String?): VieNeuQualityPreset =
        raw?.let { name -> runCatching { VieNeuQualityPreset.valueOf(name) }.getOrNull() }
            ?: VieNeuQualityPreset.FAST

    companion object {
        private const val KEY_IS_CONFIGURED = "is_configured"
        private const val KEY_IS_AI_VOICE = "is_ai_voice"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_VOICE_ID = "voice_id"
        private const val KEY_SPEED = "speed"
        private const val KEY_PITCH = "pitch"
        private const val KEY_VIENEU_PRESET = "vieneu_preset"
        private const val KEY_VIENEU_STEPS = "vieneu_steps"
        private const val KEY_VIENEU_CFG = "vieneu_cfg"
        private const val KEY_VIENEU_SWAY = "vieneu_sway"
        private const val KEY_VIENEU_THREADS = "vieneu_threads"
    }
}
