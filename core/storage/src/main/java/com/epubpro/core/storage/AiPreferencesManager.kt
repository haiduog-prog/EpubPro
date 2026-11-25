package com.epubpro.core.storage

import android.content.Context
import com.epubpro.domain.model.AiSettings
import com.epubpro.domain.model.DEFAULT_AI_MODEL_ID
import com.epubpro.domain.model.SUPPORTED_GEMINI_MODELS
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AiPreferencesManager @Inject constructor(
    @ApplicationContext context: Context
) {
    private val preferences =
        context.getSharedPreferences("ai_settings_prefs", Context.MODE_PRIVATE)
    // Giữ nguyên tên file và alias cũ để API key đã lưu từ các phiên bản trước vẫn giải mã được.
    private val credentialStore = KeystoreSecretStore(
        context = context,
        fileName = AI_CREDENTIAL_FILE_NAME,
        keyAlias = AI_CREDENTIAL_KEY_ALIAS
    )

    private val _settings = MutableStateFlow(readSettings())
    val settingsFlow: StateFlow<AiSettings> = _settings.asStateFlow()

    fun getSettings(): AiSettings = _settings.value

    @Synchronized
    fun saveConfiguration(apiKey: String?, modelId: String) {
        val supportedModel = modelId.takeIf { candidate ->
            SUPPORTED_GEMINI_MODELS.any { it.id == candidate }
        } ?: DEFAULT_AI_MODEL_ID

        if (!apiKey.isNullOrBlank()) {
            credentialStore.save(apiKey.trim())
        }
        preferences.edit().putString(KEY_MODEL_ID, supportedModel).apply()
        _settings.value = AiSettings(
            modelId = supportedModel,
            hasApiKey = credentialStore.load() != null
        )
    }

    fun getApiKey(): String? = credentialStore.load()

    @Synchronized
    fun clearApiKey() {
        credentialStore.clear()
        _settings.value = readSettings()
    }

    private fun readSettings(): AiSettings {
        val storedModel = preferences.getString(KEY_MODEL_ID, DEFAULT_AI_MODEL_ID)
            ?: DEFAULT_AI_MODEL_ID
        val modelId = storedModel.takeIf { candidate ->
            SUPPORTED_GEMINI_MODELS.any { it.id == candidate }
        } ?: DEFAULT_AI_MODEL_ID
        return AiSettings(modelId = modelId, hasApiKey = credentialStore.load() != null)
    }

    private companion object {
        const val KEY_MODEL_ID = "model_id"
        const val AI_CREDENTIAL_FILE_NAME = "ai_credentials"
        const val AI_CREDENTIAL_KEY_ALIAS = "epubpro_ai_api_key"
    }
}
