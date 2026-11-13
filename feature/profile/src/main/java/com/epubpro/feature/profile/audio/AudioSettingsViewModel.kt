package com.epubpro.feature.profile.audio

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.epubpro.core.designsystem.R
import com.epubpro.core.reader.tts.AndroidNativeTtsEngine
import com.epubpro.core.reader.tts.TtsEngine
import com.epubpro.core.storage.TtsBubblePowerMode
import com.epubpro.core.storage.TtsBubblePreferencesManager
import com.epubpro.core.storage.TtsPreferencesManager
import com.epubpro.core.tts.PreparedChunk
import com.epubpro.core.tts.SherpaTtsEngine
import com.epubpro.core.tts.TtsVoiceCatalog
import com.epubpro.core.tts.VieNeuDeviceSupportChecker
import com.epubpro.core.tts.VieNeuDownloadCoordinator
import com.epubpro.core.tts.VieNeuDownloadState
import com.epubpro.core.tts.VieNeuModelDownloader
import com.epubpro.core.tts.VieNeuNanoEngine
import com.epubpro.core.tts.VieNeuPcmPlayer
import com.epubpro.core.tts.VieNeuRuntimeManager
import com.epubpro.core.tts.VoiceModelDownloader
import com.epubpro.domain.model.AiTtsBackend
import com.epubpro.domain.model.TtsChunk
import com.epubpro.domain.model.TtsSettings
import com.epubpro.domain.model.TtsVoice
import com.epubpro.domain.model.VieNeuQualityPreset
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.roundToInt

/**
 * Mục hiển thị một giọng đọc AI offline trên màn hình Cài đặt âm thanh.
 *
 * @property id Mã định danh giọng đọc (`"vieneu:..."` cho VieNeu hoặc ID Sherpa).
 * @property name Tên hiển thị cho người dùng.
 * @property size Dung lượng tải ước tính của giọng hoặc gói dùng chung.
 * @property isDownloaded Trạng thái đã tải sẵn sàng để đọc ngoại tuyến.
 * @property backend Công nghệ AI sử dụng ([AiTtsBackend]).
 * @property description Mô tả vùng miền và phong cách đọc (nếu có).
 */
data class AiVoiceUiItem(
    val id: String,
    val name: String,
    val size: String,
    val isDownloaded: Boolean,
    val backend: AiTtsBackend = AiTtsBackend.SHERPA_VITS,
    val description: String = "",
)

/**
 * Trạng thái UI đầy đủ cho màn hình Cài đặt Âm thanh TTS.
 */
data class AudioSettingsUiState(
    val isAiVoice: Boolean = false,
    val selectedAiBackend: AiTtsBackend = AiTtsBackend.VIENEU_NANO,
    val isVieNeuSupported: Boolean = true,
    val language: String = "vi",
    val selectedVoiceId: String? = null,
    val aiVoices: List<AiVoiceUiItem> = emptyList(),
    val systemVoices: List<TtsVoice> = emptyList(),
    val isSystemTtsReady: Boolean = false,
    val isModelDownloaded: Boolean = false,
    val isDownloading: Boolean = false,
    val downloadProgress: Float = 0f,
    val downloadError: String? = null,
    val isVieNeuDownloading: Boolean = false,
    val isVieNeuModelReady: Boolean = false,
    val vieNeuDownloadProgress: Float = 0f,
    val vieNeuCurrentFile: String = "",
    val vieNeuDownloadedMb: Int = 0,
    val vieNeuTotalMb: Int = DEFAULT_TOTAL_MB,
    val vieNeuDownloadError: String? = null,
    val vieNeuPreset: VieNeuQualityPreset = VieNeuQualityPreset.FAST,
    val vieNeuSteps: Int = TtsSettings.DEFAULT_VIENEU_STEPS,
    val vieNeuCfg: Float = TtsSettings.DEFAULT_VIENEU_CFG,
    val vieNeuSway: Float = TtsSettings.DEFAULT_VIENEU_SWAY,
    val vieNeuThreads: Int = TtsSettings.DEFAULT_VIENEU_THREADS,
    val isPlaying: Boolean = false,
    val speechSpeed: Float = 1.0f,
    val speechPitch: Float = 1.0f,
    val isBubbleEnabled: Boolean = false,
    val isBubbleEnablePending: Boolean = false,
    val bubblePowerMode: TtsBubblePowerMode = TtsBubblePowerMode.ALWAYS_ON,
) {
    /**
     * Danh sách giọng AI thuộc riêng backend đang chọn ([selectedAiBackend]).
     */
    val filteredAiVoices: List<AiVoiceUiItem>
        get() = aiVoices.filter { it.backend == selectedAiBackend }

    private companion object {
        const val DEFAULT_TOTAL_MB = 345
    }
}

/**
 * ViewModel điều phối cài đặt giọng đọc hệ thống, Sherpa VITS, VieNeu-TTS v3-Nano và bong bóng điều khiển.
 */
@HiltViewModel
class AudioSettingsViewModel internal constructor(
    private val voiceModelDownloader: VoiceModelDownloader,
    private val ttsEngine: SherpaTtsEngine,
    private val preferencesManager: TtsPreferencesManager,
    private val bubblePreferencesManager: TtsBubblePreferencesManager,
    private val vieNeuDownloadCoordinator: VieNeuDownloadCoordinator,
    private val vieNeuRuntimeManager: VieNeuRuntimeManager,
    private val vieNeuDeviceSupportChecker: VieNeuDeviceSupportChecker,
    nativeTtsEngineFactory: () -> TtsEngine,
    private val vieNeuPcmPlayer: VieNeuPcmPlayer,
    private val resolveString: (Int, Array<out Any>) -> String,
) : ViewModel() {

    @Inject
    constructor(
        @ApplicationContext context: Context,
        voiceModelDownloader: VoiceModelDownloader,
        ttsEngine: SherpaTtsEngine,
        preferencesManager: TtsPreferencesManager,
        bubblePreferencesManager: TtsBubblePreferencesManager,
        vieNeuDownloadCoordinator: VieNeuDownloadCoordinator,
        vieNeuRuntimeManager: VieNeuRuntimeManager,
        vieNeuDeviceSupportChecker: VieNeuDeviceSupportChecker,
    ) : this(
        voiceModelDownloader = voiceModelDownloader,
        ttsEngine = ttsEngine,
        preferencesManager = preferencesManager,
        bubblePreferencesManager = bubblePreferencesManager,
        vieNeuDownloadCoordinator = vieNeuDownloadCoordinator,
        vieNeuRuntimeManager = vieNeuRuntimeManager,
        vieNeuDeviceSupportChecker = vieNeuDeviceSupportChecker,
        nativeTtsEngineFactory = { AndroidNativeTtsEngine(context.applicationContext) },
        vieNeuPcmPlayer = VieNeuPcmPlayer(),
        resolveString = { resId, args ->
            if (args.isEmpty()) context.getString(resId) else context.getString(resId, *args)
        },
    )

    companion object {
        private const val TAG = "EpubProTTS"
        private const val BYTES_PER_MB = 1_000_000L
        private const val FALLBACK_PREVIEW_SAMPLE = "Xin chào, đây là giọng đọc thử của ứng dụng EpubPro."

        private fun logDebug(message: String) {
            try {
                Log.d(TAG, message)
            } catch (_: Throwable) {
                // Bỏ qua khi chạy pure JVM unit test không có Android Log stub
            }
        }

        private fun logError(message: String, throwable: Throwable) {
            try {
                Log.e(TAG, message, throwable)
            } catch (_: Throwable) {
                // Bỏ qua khi chạy pure JVM unit test không có Android Log stub
            }
        }
    }

    private val nativeTtsEngine: TtsEngine = nativeTtsEngineFactory()
    private val initialBubblePreferences = bubblePreferencesManager.getPreferences()
    private val supportsVieNeu = vieNeuDeviceSupportChecker.isSupported()
    private var previewJob: Job? = null

    private val _uiState = MutableStateFlow(
        AudioSettingsUiState(
            isVieNeuSupported = supportsVieNeu,
            selectedAiBackend = if (supportsVieNeu) AiTtsBackend.VIENEU_NANO else AiTtsBackend.SHERPA_VITS,
            isBubbleEnabled = initialBubblePreferences.enabled,
            isBubbleEnablePending = initialBubblePreferences.pendingEnable,
            bubblePowerMode = initialBubblePreferences.powerMode,
        ),
    )
    val uiState: StateFlow<AudioSettingsUiState> = _uiState.asStateFlow()

    init {
        val initialSettings = preferencesManager.getSettings()
        val initialBackend = TtsVoiceCatalog.findBackend(initialSettings.voiceId)
        val validInitialAi = initialBackend != null &&
            (initialBackend != AiTtsBackend.VIENEU_NANO || supportsVieNeu)
        if (initialSettings.isAiVoice && !validInitialAi) {
            preferencesManager.saveSettings(
                initialSettings.copy(isAiVoice = false, voiceId = null),
            )
        }

        val effectiveInitialSettings = preferencesManager.getSettings()
        nativeTtsEngine.setLanguage(effectiveInitialSettings.language)
        nativeTtsEngine.initialize(
            onReady = {
                _uiState.value = _uiState.value.copy(isSystemTtsReady = true)
                applyNativeSettings()
                refreshSystemVoices()
            },
            onError = { error ->
                _uiState.value = _uiState.value.copy(
                    isSystemTtsReady = false,
                    downloadError = error,
                )
            },
        )

        refreshAiVoices()

        viewModelScope.launch {
            preferencesManager.settingsFlow.collect { settings ->
                val resolvedBackend = resolveBackendForVoice(
                    voiceId = settings.voiceId,
                    fallback = _uiState.value.selectedAiBackend,
                )
                val selectedVoiceId = if (settings.isAiVoice) {
                    resolveValidAiVoiceId(settings.voiceId)
                } else {
                    settings.voiceId
                }
                _uiState.value = _uiState.value.copy(
                    isAiVoice = settings.isAiVoice,
                    selectedAiBackend = resolvedBackend,
                    language = if (settings.isAiVoice) "vi" else settings.language,
                    selectedVoiceId = selectedVoiceId,
                    speechSpeed = settings.speed,
                    speechPitch = if (settings.isAiVoice) 1.0f else settings.pitch,
                    vieNeuPreset = settings.vieNeuPreset,
                    vieNeuSteps = settings.vieNeuSteps,
                    vieNeuCfg = settings.vieNeuCfg,
                    vieNeuSway = settings.vieNeuSway,
                    vieNeuThreads = settings.vieNeuThreads,
                    downloadError = null,
                )
                refreshAiVoices()
                applyNativeSettings()
                refreshSystemVoices()
            }
        }

        viewModelScope.launch {
            bubblePreferencesManager.preferencesFlow.collect { preferences ->
                _uiState.value = _uiState.value.copy(
                    isBubbleEnabled = preferences.enabled,
                    isBubbleEnablePending = preferences.pendingEnable,
                    bubblePowerMode = preferences.powerMode,
                )
            }
        }

        viewModelScope.launch {
            vieNeuDownloadCoordinator.state.collect { dlState ->
                applyVieNeuDownloadState(dlState)
            }
        }
    }

    /**
     * Đồng bộ trạng thái tải từ [VieNeuDownloadCoordinator] vào [AudioSettingsUiState].
     *
     * @param dlState Trạng thái tải hiện tại của gói VieNeu-TTS v3-Nano.
     */
    private fun applyVieNeuDownloadState(dlState: VieNeuDownloadState) {
        val totalMb = (VieNeuModelDownloader.TOTAL_EXPECTED_BYTES / BYTES_PER_MB).toInt().coerceAtLeast(1)
        when (dlState) {
            is VieNeuDownloadState.Idle -> {
                _uiState.value = _uiState.value.copy(
                    isVieNeuDownloading = false,
                    isVieNeuModelReady = dlState.inventory.isReady,
                    vieNeuDownloadProgress = if (dlState.inventory.isReady) 1f else 0f,
                    vieNeuCurrentFile = "",
                    vieNeuDownloadedMb = (dlState.inventory.existingBytes / BYTES_PER_MB).toInt(),
                    vieNeuTotalMb = totalMb,
                    vieNeuDownloadError = null,
                )
            }
            is VieNeuDownloadState.Downloading -> {
                _uiState.value = _uiState.value.copy(
                    isVieNeuDownloading = true,
                    isVieNeuModelReady = false,
                    vieNeuDownloadProgress = dlState.progress.fraction,
                    vieNeuCurrentFile = dlState.progress.currentFile,
                    vieNeuDownloadedMb = (dlState.progress.downloadedBytes / BYTES_PER_MB).toInt(),
                    vieNeuTotalMb = (dlState.progress.totalBytes / BYTES_PER_MB).toInt().coerceAtLeast(1),
                    vieNeuDownloadError = null,
                )
            }
            is VieNeuDownloadState.Ready -> {
                _uiState.value = _uiState.value.copy(
                    isVieNeuDownloading = false,
                    isVieNeuModelReady = true,
                    vieNeuDownloadProgress = 1f,
                    vieNeuCurrentFile = "",
                    vieNeuDownloadedMb = totalMb,
                    vieNeuTotalMb = totalMb,
                    vieNeuDownloadError = null,
                )
            }
            is VieNeuDownloadState.Error -> {
                _uiState.value = _uiState.value.copy(
                    isVieNeuDownloading = false,
                    isVieNeuModelReady = dlState.inventory.isReady,
                    vieNeuDownloadProgress = 0f,
                    vieNeuCurrentFile = "",
                    vieNeuDownloadedMb = (dlState.inventory.existingBytes / BYTES_PER_MB).toInt(),
                    vieNeuTotalMb = totalMb,
                    vieNeuDownloadError = dlState.message,
                )
            }
        }
        refreshAiVoices()
    }

    /**
     * Lưu toàn bộ cấu hình giọng đọc hiện tại vào [TtsPreferencesManager].
     */
    private fun saveSettings() {
        val state = _uiState.value
        val validAi = if (state.isAiVoice) resolveValidAiVoiceId(state.selectedVoiceId) != null else true
        val existing = preferencesManager.getSettings()
        preferencesManager.saveSettings(
            existing.copy(
                isConfigured = if (validAi) true else existing.isConfigured,
                isAiVoice = if (validAi) state.isAiVoice else existing.isAiVoice,
                language = when {
                    !validAi -> existing.language
                    state.isAiVoice -> "vi"
                    else -> state.language
                },
                voiceId = if (validAi) state.selectedVoiceId else existing.voiceId,
                speed = state.speechSpeed,
                pitch = if (state.isAiVoice) 1.0f else state.speechPitch,
                vieNeuPreset = state.vieNeuPreset,
                vieNeuSteps = state.vieNeuSteps,
                vieNeuCfg = state.vieNeuCfg,
                vieNeuSway = state.vieNeuSway,
                vieNeuThreads = state.vieNeuThreads,
            ),
        )
    }

    /**
     * Nạp lại trạng thái tải của toàn bộ giọng VieNeu v3-Nano và Sherpa VITS từ đĩa.
     */
    private fun refreshAiVoices() {
        val isVieNeuReady = vieNeuDownloadCoordinator.state.value.isReady || vieNeuRuntimeManager.isModelReady()
        val vieNeuVoices = if (supportsVieNeu) {
            TtsVoiceCatalog.vieNeuVoices.map { model ->
                AiVoiceUiItem(
                    id = model.id,
                    name = model.displayName,
                    size = TtsVoiceCatalog.VIENEU_SHARED_PACKAGE_SIZE,
                    isDownloaded = isVieNeuReady,
                    backend = AiTtsBackend.VIENEU_NANO,
                    description = model.description,
                )
            }
        } else {
            emptyList()
        }

        val sherpaVoices = TtsVoiceCatalog.aiVoices.map { model ->
            AiVoiceUiItem(
                id = model.id,
                name = model.displayName,
                size = model.downloadSize,
                isDownloaded = voiceModelDownloader.isModelDownloaded(model.id),
                backend = AiTtsBackend.SHERPA_VITS,
            )
        }

        val allVoices = vieNeuVoices + sherpaVoices
        val selectedVoice = allVoices.firstOrNull { it.id == _uiState.value.selectedVoiceId }
        _uiState.value = _uiState.value.copy(
            aiVoices = allVoices,
            isVieNeuModelReady = isVieNeuReady,
            isModelDownloaded = selectedVoice?.isDownloaded == true,
            downloadProgress = if (selectedVoice?.backend == AiTtsBackend.SHERPA_VITS && selectedVoice.isDownloaded) {
                1f
            } else {
                0f
            },
        )
    }

    /**
     * Lấy danh sách giọng đọc hệ thống theo ngôn ngữ hiện tại.
     */
    private fun refreshSystemVoices() {
        if (!_uiState.value.isSystemTtsReady) return
        val voices = nativeTtsEngine.getAvailableVoices(_uiState.value.language)
        _uiState.value = _uiState.value.copy(systemVoices = voices)
    }

    /**
     * Áp dụng ngôn ngữ, tốc độ, cao độ và giọng đọc cho [nativeTtsEngine].
     */
    private fun applyNativeSettings() {
        val state = _uiState.value
        if (!state.isSystemTtsReady) return
        nativeTtsEngine.setLanguage(state.language)
        nativeTtsEngine.setSpeed(state.speechSpeed)
        nativeTtsEngine.setPitch(state.speechPitch)
        nativeTtsEngine.setVoice(state.selectedVoiceId.takeUnless { state.isAiVoice })
    }

    /**
     * Chuyển đổi giữa chế độ Giọng AI offline và Giọng hệ thống.
     *
     * @param isAi `true` để dùng giọng AI offline, `false` để dùng giọng hệ thống Android.
     */
    fun onAiVoiceToggled(isAi: Boolean) {
        if (_uiState.value.isAiVoice == isAi) return
        logDebug("onAiVoiceToggled: $isAi")
        stopActivePreview()

        val nextBackend = if (supportsVieNeu) _uiState.value.selectedAiBackend else AiTtsBackend.SHERPA_VITS
        val nextVoiceId = if (isAi) {
            defaultVoiceForBackend(nextBackend)
        } else {
            null
        }

        _uiState.value = _uiState.value.copy(
            isAiVoice = isAi,
            selectedAiBackend = nextBackend,
            language = if (isAi) "vi" else _uiState.value.language,
            selectedVoiceId = nextVoiceId,
            speechPitch = if (isAi) 1.0f else _uiState.value.speechPitch,
            downloadError = null,
        )
        if (isAi) refreshAiVoices() else refreshSystemVoices()
        saveSettings()
    }

    /**
     * Chuyển đổi giữa hai backend AI là [AiTtsBackend.VIENEU_NANO] và [AiTtsBackend.SHERPA_VITS].
     *
     * @param backend Backend AI đích mà người dùng vừa chọn.
     */
    fun onAiBackendSelected(backend: AiTtsBackend) {
        if (backend == AiTtsBackend.VIENEU_NANO && !supportsVieNeu) {
            _uiState.value = _uiState.value.copy(
                vieNeuDownloadError = resolveString(R.string.audio_ai_vieneu_unsupported, emptyArray()),
            )
            return
        }
        if (_uiState.value.isAiVoice && _uiState.value.selectedAiBackend == backend) return

        stopActivePreview()
        val nextVoiceId = defaultVoiceForBackend(backend)
        _uiState.value = _uiState.value.copy(
            isAiVoice = true,
            selectedAiBackend = backend,
            language = "vi",
            selectedVoiceId = nextVoiceId,
            speechPitch = 1.0f,
            downloadError = null,
        )
        refreshAiVoices()
        saveSettings()
    }

    /**
     * Đổi ngôn ngữ đọc (chỉ áp dụng khi dùng giọng hệ thống).
     *
     * @param language Mã ngôn ngữ (`"vi"` hoặc `"en"`).
     */
    fun onLanguageChanged(language: String) {
        val supportedLanguage = if (_uiState.value.isAiVoice) "vi" else language.takeIf { it == "en" } ?: "vi"
        if (_uiState.value.language == supportedLanguage) return
        _uiState.value = _uiState.value.copy(
            language = supportedLanguage,
            selectedVoiceId = null,
            downloadError = null,
        )
        nativeTtsEngine.setLanguage(supportedLanguage)
        nativeTtsEngine.setVoice(null)
        refreshSystemVoices()
        saveSettings()
    }

    /**
     * Chọn giọng đọc cụ thể cho backend AI hiện tại hoặc giọng hệ thống.
     *
     * @param voiceId Mã định danh giọng đọc cần chọn.
     */
    fun onVoiceSelected(voiceId: String?) {
        val state = _uiState.value
        val validVoiceId = if (state.isAiVoice) {
            resolveValidAiVoiceId(voiceId)
        } else {
            voiceId?.takeIf { id -> state.systemVoices.any { it.id == id } }
        }
        logDebug("onVoiceSelected: $validVoiceId")
        stopActivePreview()
        val nextBackend = if (state.isAiVoice) {
            resolveBackendForVoice(validVoiceId, state.selectedAiBackend)
        } else {
            state.selectedAiBackend
        }
        _uiState.value = state.copy(
            selectedAiBackend = nextBackend,
            selectedVoiceId = validVoiceId,
            downloadError = null,
        )
        if (state.isAiVoice) refreshAiVoices() else applyNativeSettings()
        saveSettings()
    }

    /**
     * Cập nhật tốc độ đọc và lưu xuống bộ nhớ cài đặt.
     *
     * @param speed Tốc độ đọc trong khoảng `0.5f..2.0f`.
     */
    fun onSpeedChanged(speed: Float) {
        _uiState.value = _uiState.value.copy(speechSpeed = speed.coerceIn(0.5f, 2.0f))
        applyNativeSettings()
        saveSettings()
    }

    /**
     * Cập nhật cao độ cho giọng hệ thống (không áp dụng cho giọng AI neural).
     *
     * @param pitch Cao độ trong khoảng `0.5f..1.5f`.
     */
    fun onPitchChanged(pitch: Float) {
        if (_uiState.value.isAiVoice) return
        _uiState.value = _uiState.value.copy(speechPitch = pitch.coerceIn(0.5f, 1.5f))
        applyNativeSettings()
        saveSettings()
    }

    /**
     * Áp dụng nhanh một preset cấu hình cho VieNeu-TTS v3-Nano (`Nhanh`/`Cân bằng`/`Siêu tốc`/`Tuỳ chỉnh`).
     *
     * @param preset Mức cấu hình cần chọn.
     */
    fun onVieNeuPresetSelected(preset: VieNeuQualityPreset) {
        val current = _uiState.value
        val (steps, cfg, sway) = TtsSettings.resolveVieNeuParamsForPreset(
            preset = preset,
            currentSteps = current.vieNeuSteps,
            currentCfg = current.vieNeuCfg,
            currentSway = current.vieNeuSway,
        )
        _uiState.value = current.copy(
            vieNeuPreset = preset,
            vieNeuSteps = steps,
            vieNeuCfg = cfg,
            vieNeuSway = sway,
        )
        saveSettings()
    }

    /**
     * Cập nhật số bước Euler cho VieNeu-TTS v3-Nano và tự chuyển preset sang [VieNeuQualityPreset.CUSTOM] nếu lệch chuẩn.
     *
     * @param steps Số bước Euler (`4..24`).
     */
    fun onVieNeuStepsChanged(steps: Int) {
        val clamped = steps.coerceIn(TtsSettings.MIN_VIENEU_STEPS, TtsSettings.MAX_VIENEU_STEPS)
        val current = _uiState.value
        _uiState.value = current.copy(
            vieNeuSteps = clamped,
            vieNeuPreset = VieNeuQualityPreset.matchingPreset(clamped, current.vieNeuCfg, current.vieNeuSway),
        )
        saveSettings()
    }

    /**
     * Cập nhật hệ số Classifier-Free Guidance (CFG) cho VieNeu-TTS v3-Nano.
     *
     * @param cfg Hệ số CFG (`0.0f..5.0f`).
     */
    fun onVieNeuCfgChanged(cfg: Float) {
        val rounded = ((cfg * 10f).roundToInt() / 10f)
            .coerceIn(TtsSettings.MIN_VIENEU_CFG, TtsSettings.MAX_VIENEU_CFG)
        val current = _uiState.value
        _uiState.value = current.copy(
            vieNeuCfg = rounded,
            vieNeuPreset = VieNeuQualityPreset.matchingPreset(current.vieNeuSteps, rounded, current.vieNeuSway),
        )
        saveSettings()
    }

    /**
     * Cập nhật hệ số dịch lịch bước thời gian `sway` cho VieNeu-TTS v3-Nano.
     *
     * @param sway Giá trị `sway` trong khoảng `-1.0f..0.0f`.
     */
    fun onVieNeuSwayChanged(sway: Float) {
        val rounded = ((sway * 10f).roundToInt() / 10f)
            .coerceIn(TtsSettings.MIN_VIENEU_SWAY, TtsSettings.MAX_VIENEU_SWAY)
        val current = _uiState.value
        _uiState.value = current.copy(
            vieNeuSway = rounded,
            vieNeuPreset = VieNeuQualityPreset.matchingPreset(current.vieNeuSteps, current.vieNeuCfg, rounded),
        )
        saveSettings()
    }

    /**
     * Cập nhật số luồng CPU ONNX Runtime (`2` hoặc `4` luồng) cho VieNeu-TTS v3-Nano.
     *
     * @param threads Số luồng mong muốn (`2` hoặc `4`).
     */
    fun onVieNeuThreadsSelected(threads: Int) {
        val normalized = if (threads <= 2) 2 else 4
        _uiState.value = _uiState.value.copy(vieNeuThreads = normalized)
        saveSettings()
    }

    /**
     * Khôi phục cấu hình VieNeu-TTS v3-Nano về mức tối ưu mặc định (`FAST`: 8 bước, CFG 3.0, Sway -1.0, 4 luồng).
     */
    fun resetVieNeuDefaults() {
        _uiState.value = _uiState.value.copy(
            vieNeuPreset = VieNeuQualityPreset.FAST,
            vieNeuSteps = TtsSettings.DEFAULT_VIENEU_STEPS,
            vieNeuCfg = TtsSettings.DEFAULT_VIENEU_CFG,
            vieNeuSway = TtsSettings.DEFAULT_VIENEU_SWAY,
            vieNeuThreads = TtsSettings.DEFAULT_VIENEU_THREADS,
        )
        saveSettings()
    }

    /**
     * Bắt đầu hoặc tiếp tục tải trọn gói model dùng chung VieNeu v3-Nano (~345 MB) cho tất cả 11 giọng.
     */
    fun downloadVieNeuPackage() {
        vieNeuDownloadCoordinator.startDownload()
    }

    /**
     * Tạm dừng tải gói VieNeu v3-Nano, giữ lại các phần `.tmp` để lần sau resume tiếp.
     */
    fun cancelVieNeuDownload() {
        vieNeuDownloadCoordinator.cancelDownload()
    }

    /**
     * Xoá toàn bộ file model VieNeu v3-Nano khỏi bộ nhớ máy để giải phóng dung lượng.
     */
    fun clearVieNeuModel() {
        stopActivePreview()
        vieNeuDownloadCoordinator.clearModel()
        viewModelScope.launch {
            vieNeuRuntimeManager.unload()
        }
    }

    fun requestBubbleEnable(): Boolean {
        return bubblePreferencesManager.setPendingEnable(true)
    }

    fun onBubbleOverlayPermissionChecked(isGranted: Boolean): Boolean {
        val preferences = bubblePreferencesManager.getPreferences()
        return when {
            preferences.pendingEnable -> {
                val saved = bubblePreferencesManager.setEnabled(isGranted)
                isGranted && saved
            }
            preferences.enabled && !isGranted -> {
                bubblePreferencesManager.setEnabled(false)
                false
            }
            else -> false
        }
    }

    fun setBubblePowerMode(mode: TtsBubblePowerMode) {
        bubblePreferencesManager.setPowerMode(mode)
    }

    fun disableBubble() {
        bubblePreferencesManager.setEnabled(false)
    }

    /**
     * Tải model giọng đọc Sherpa VITS đơn lẻ đang được chọn (~60.6 MB).
     */
    fun downloadCurrentVoice() {
        val model = TtsVoiceCatalog.find(_uiState.value.selectedVoiceId)
        if (model == null) {
            _uiState.value = _uiState.value.copy(
                downloadError = resolveString(R.string.audio_sherpa_voice_fallback, emptyArray()),
            )
            return
        }
        logDebug("downloadCurrentVoice starting for '${model.id}' (${model.displayName})")
        _uiState.value = _uiState.value.copy(
            isDownloading = true,
            downloadProgress = 0f,
            downloadError = null,
        )

        viewModelScope.launch {
            try {
                val success = voiceModelDownloader.downloadModel(
                    modelName = model.id,
                    onnxUrl = TtsVoiceCatalog.modelUrl(model),
                    tokensUrl = TtsVoiceCatalog.TOKENS_URL,
                    onProgress = { progress ->
                        _uiState.value = _uiState.value.copy(downloadProgress = progress)
                    },
                )
                if (success) {
                    _uiState.value = _uiState.value.copy(isDownloading = false)
                    refreshAiVoices()
                } else {
                    _uiState.value = _uiState.value.copy(
                        isDownloading = false,
                        downloadError = resolveString(R.string.audio_vieneu_preview_error, arrayOf(model.displayName)),
                    )
                }
            } catch (error: Throwable) {
                logError("Exception during downloadCurrentVoice", error)
                _uiState.value = _uiState.value.copy(
                    isDownloading = false,
                    downloadError = resolveString(
                        R.string.audio_vieneu_preview_error,
                        arrayOf(error.localizedMessage ?: model.displayName),
                    ),
                )
            }
        }
    }

    fun checkDownloadComplete() = refreshAiVoices()

    /**
     * Dừng ngay đoạn nghe thử đang phát (nếu có) và đưa trạng thái [AudioSettingsUiState.isPlaying] về `false`.
     */
    fun stopActivePreview() {
        previewJob?.cancel()
        previewJob = null
        vieNeuPcmPlayer.pauseAndFlush()
        ttsEngine.stop()
        if (_uiState.value.isPlaying) {
            _uiState.value = _uiState.value.copy(isPlaying = false)
        }
    }

    /**
     * Nghe thử giọng AI đang chọn (tự động điều phối sang VieNeu v3-Nano hoặc Sherpa VITS).
     *
     * @param sampleText Câu mẫu cần đọc thử (mặc định đọc từ String Resource ở tầng UI).
     */
    fun testVoice(sampleText: String = FALLBACK_PREVIEW_SAMPLE) {
        val state = _uiState.value
        val voiceId = state.selectedVoiceId ?: return
        if (TtsSettings.isVieNeuVoiceId(voiceId)) {
            if (!state.isVieNeuSupported || !state.isVieNeuModelReady) return
            playVieNeuPreview(voiceId, sampleText)
            return
        }

        if (!state.isModelDownloaded) return
        stopActivePreview()
        previewJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isPlaying = true, downloadError = null)
            try {
                if (!voiceModelDownloader.isEspeakDataReady()) {
                    voiceModelDownloader.downloadEspeakNgDataIfNeeded()
                }
                ttsEngine.initialize(
                    onnxPath = voiceModelDownloader.getModelPath(voiceId),
                    tokensPath = voiceModelDownloader.getTokensPath(voiceId),
                    dataDirPath = voiceModelDownloader.getEspeakDataDir(),
                )
                ttsEngine.speak(
                    text = sampleText,
                    speed = _uiState.value.speechSpeed,
                )
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                logError("testVoice Sherpa failed", error)
                _uiState.value = _uiState.value.copy(
                    downloadError = resolveString(
                        R.string.audio_vieneu_preview_error,
                        arrayOf(error.localizedMessage ?: error.javaClass.simpleName),
                    ),
                )
            } finally {
                _uiState.value = _uiState.value.copy(isPlaying = false)
            }
        }
    }

    /**
     * Chạy nghe thử trực tiếp bằng `VieNeuRuntimeManager` theo đúng cấu hình `steps/cfg/sway/threads` đang hiển thị.
     *
     * @param voiceId Mã giọng preset VieNeu (`"vieneu:..."`).
     * @param sampleText Câu mẫu cần tổng hợp và phát qua [vieNeuPcmPlayer].
     */
    private fun playVieNeuPreview(voiceId: String, sampleText: String) {
        stopActivePreview()
        val snapshot = _uiState.value
        previewJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isPlaying = true, vieNeuDownloadError = null)
            try {
                val voice = vieNeuRuntimeManager.resolveVoice(voiceId)
                val chunks = vieNeuRuntimeManager.prepareChunks(
                    text = sampleText,
                    threads = snapshot.vieNeuThreads,
                ).filter { it.phonemes.isNotBlank() }

                if (chunks.isEmpty()) return@launch
                val wavParts = ArrayList<FloatArray>(chunks.size)
                for (chunk in chunks) {
                    if (!isActive) throw CancellationException()
                    wavParts += vieNeuRuntimeManager.synthesizeChunk(
                        phonemes = chunk.phonemes,
                        voice = voice,
                        steps = snapshot.vieNeuSteps,
                        cfg = snapshot.vieNeuCfg,
                        sway = snapshot.vieNeuSway,
                        speed = snapshot.speechSpeed,
                        threads = snapshot.vieNeuThreads,
                        isCancelled = { !isActive },
                    )
                }
                val combined = stitchPreviewAudio(chunks, wavParts)
                if (isActive && combined.isNotEmpty()) {
                    vieNeuPcmPlayer.playOneShot(combined) { !isActive }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                logError("testVoice VieNeu failed", error)
                val detail = error.localizedMessage ?: error.javaClass.simpleName
                _uiState.value = _uiState.value.copy(
                    vieNeuDownloadError = resolveString(R.string.audio_vieneu_preview_error, arrayOf(detail)),
                )
            } finally {
                _uiState.value = _uiState.value.copy(isPlaying = false)
            }
        }
    }

    /**
     * Nối các chunk âm thanh preview kèm khoảng nghỉ chuẩn theo ranh giới câu/đoạn.
     *
     * @param chunks Danh sách chunk đã chuẩn hoá với metadata ranh giới [ChunkGap].
     * @param wavParts Mảng waveform float32 tương ứng từng chunk.
     * @return Mảng waveform hợp nhất để phát một lần liên tục.
     */
    private fun stitchPreviewAudio(chunks: List<PreparedChunk>, wavParts: List<FloatArray>): FloatArray {
        if (wavParts.isEmpty()) return FloatArray(0)
        if (wavParts.size == 1) return wavParts.first()

        val pads = IntArray(wavParts.size)
        var totalSize = wavParts[0].size
        for (i in 1 until wavParts.size) {
            val pad = VieNeuNanoEngine.computePausePadSamples(
                prevWav = wavParts[i - 1],
                nextWav = wavParts[i],
                gap = chunks[i].gapBefore,
            )
            pads[i] = pad
            totalSize += pad + wavParts[i].size
        }

        val combined = FloatArray(totalSize)
        var offset = 0
        wavParts[0].copyInto(combined, destinationOffset = offset)
        offset += wavParts[0].size
        for (i in 1 until wavParts.size) {
            offset += pads[i]
            wavParts[i].copyInto(combined, destinationOffset = offset)
            offset += wavParts[i].size
        }
        return combined
    }

    fun testSystemVoice() {
        if (!_uiState.value.isSystemTtsReady) return
        applyNativeSettings()
        val text = if (_uiState.value.language == "en") {
            "Hello, this is the selected system voice for EpubPro."
        } else {
            resolveString(R.string.audio_vieneu_preview_sample, emptyArray())
        }
        nativeTtsEngine.speak(
            chunk = TtsChunk(
                id = Int.MAX_VALUE,
                paragraphIndex = 0,
                text = text,
            ),
            onChunkStart = {},
            onChunkDone = {},
        )
    }

    /**
     * Trả về giọng mặc định phù hợp khi chuyển qua backend [backend].
     *
     * @param backend Backend AI vừa được chọn.
     * @return ID giọng phù hợp hoặc giữ nguyên giọng hiện tại nếu cùng backend.
     */
    private fun defaultVoiceForBackend(backend: AiTtsBackend): String? {
        val current = _uiState.value.selectedVoiceId
        return when (backend) {
            AiTtsBackend.VIENEU_NANO -> {
                TtsVoiceCatalog.findVieNeu(current)?.id ?: TtsVoiceCatalog.DEFAULT_VIENEU_VOICE_ID
            }
            AiTtsBackend.SHERPA_VITS -> {
                TtsVoiceCatalog.find(current)?.id
                    ?: _uiState.value.aiVoices
                        .firstOrNull { it.backend == AiTtsBackend.SHERPA_VITS && it.isDownloaded }
                        ?.id
            }
        }
    }

    /**
     * Xác định [AiTtsBackend] hợp lệ tương ứng với một [voiceId] hoặc trả về [fallback].
     */
    private fun resolveBackendForVoice(voiceId: String?, fallback: AiTtsBackend): AiTtsBackend {
        val found = TtsVoiceCatalog.findBackend(voiceId) ?: return fallback
        return if (found == AiTtsBackend.VIENEU_NANO && !supportsVieNeu) {
            AiTtsBackend.SHERPA_VITS
        } else {
            found
        }
    }

    /**
     * Chuẩn hoá [voiceId] thành ID hợp lệ trong một trong hai catalog AI (có kiểm tra hỗ trợ ABI).
     */
    private fun resolveValidAiVoiceId(voiceId: String?): String? {
        TtsVoiceCatalog.findVieNeu(voiceId)?.let { info ->
            return if (supportsVieNeu) info.id else null
        }
        return TtsVoiceCatalog.find(voiceId)?.id
    }

    override fun onCleared() {
        logDebug("onCleared: releasing AudioSettings preview resources")
        previewJob?.cancel()
        vieNeuPcmPlayer.close()
        ttsEngine.release()
        nativeTtsEngine.shutdown()
        super.onCleared()
    }
}
