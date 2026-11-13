package com.epubpro.feature.profile.audio

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.epubpro.core.designsystem.R
import com.epubpro.core.storage.TtsBubblePowerMode
import com.epubpro.core.reader.tts.TtsService
import com.epubpro.core.tts.TtsVoiceCatalog
import com.epubpro.domain.model.AiTtsBackend
import com.epubpro.domain.model.TtsSettings
import com.epubpro.domain.model.VieNeuQualityPreset
import kotlin.math.roundToInt

private fun Context.hasTtsBubbleNotificationPermission(): Boolean {
    return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioSettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: AudioSettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var showLanguageMenu by remember { mutableStateOf(false) }
    var showVoiceMenu by remember { mutableStateOf(false) }
    var showPowerModeMenu by remember { mutableStateOf(false) }
    var hasNotificationPermission by remember {
        mutableStateOf(context.hasTtsBubbleNotificationPermission())
    }

    fun completePendingBubbleEnable() {
        val enabledNow = viewModel.onBubbleOverlayPermissionChecked(
            isGranted = Settings.canDrawOverlays(context)
        )
        if (enabledNow) {
            TtsService.syncBubbleState(context, enabled = true)
        }
    }

    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        completePendingBubbleEnable()
    }

    fun continueBubbleEnableAfterNotificationPermission() {
        if (!viewModel.requestBubbleEnable()) return
        if (Settings.canDrawOverlays(context)) {
            completePendingBubbleEnable()
            return
        }

        val permissionIntent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )
        runCatching { overlayPermissionLauncher.launch(permissionIntent) }
            .onFailure {
                viewModel.onBubbleOverlayPermissionChecked(isGranted = false)
            }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        hasNotificationPermission = context.hasTtsBubbleNotificationPermission()
        continueBubbleEnableAfterNotificationPermission()
    }

    fun beginBubbleEnable() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission) {
            runCatching {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }.onFailure {
                continueBubbleEnableAfterNotificationPermission()
            }
        } else {
            continueBubbleEnableAfterNotificationPermission()
        }
    }

    fun syncOverlayPermissionOnResume() {
        val state = viewModel.uiState.value
        if (state.isBubbleEnablePending) {
            completePendingBubbleEnable()
        } else if (state.isBubbleEnabled && !Settings.canDrawOverlays(context)) {
            viewModel.disableBubble()
            TtsService.syncBubbleState(context, enabled = false)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasNotificationPermission = context.hasTtsBubbleNotificationPermission()
                syncOverlayPermissionOnResume()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            hasNotificationPermission = context.hasTtsBubbleNotificationPermission()
            syncOverlayPermissionOnResume()
        }
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.audio_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Thiết lập giọng đọc",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Chọn công nghệ, ngôn ngữ, giọng và cách EpubPro đọc nội dung.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text("1. Công nghệ giọng đọc", fontWeight = FontWeight.SemiBold)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        FilterChip(
                            selected = uiState.isAiVoice,
                            onClick = { viewModel.onAiVoiceToggled(true) },
                            label = { Text("Giọng AI offline") },
                            leadingIcon = { Icon(Icons.Default.AutoAwesome, null, Modifier.size(18.dp)) },
                            modifier = Modifier.weight(1f),
                            colors = FilterChipDefaults.filterChipColors()
                        )
                        FilterChip(
                            selected = !uiState.isAiVoice,
                            onClick = { viewModel.onAiVoiceToggled(false) },
                            label = { Text("Giọng hệ thống") },
                            leadingIcon = { Icon(Icons.Default.PhoneAndroid, null, Modifier.size(18.dp)) },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    if (uiState.isAiVoice) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.audio_ai_backend_label),
                            fontWeight = FontWeight.Medium,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (uiState.isVieNeuSupported) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                FilterChip(
                                    selected = uiState.selectedAiBackend == AiTtsBackend.VIENEU_NANO,
                                    onClick = { viewModel.onAiBackendSelected(AiTtsBackend.VIENEU_NANO) },
                                    label = {
                                        Column(Modifier.padding(vertical = 4.dp)) {
                                            Text(
                                                text = stringResource(R.string.audio_ai_backend_vieneu),
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            Text(
                                                text = stringResource(R.string.audio_ai_backend_vieneu_badge),
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                                FilterChip(
                                    selected = uiState.selectedAiBackend == AiTtsBackend.SHERPA_VITS,
                                    onClick = { viewModel.onAiBackendSelected(AiTtsBackend.SHERPA_VITS) },
                                    label = {
                                        Column(Modifier.padding(vertical = 4.dp)) {
                                            Text(
                                                text = stringResource(R.string.audio_ai_backend_sherpa),
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            Text(
                                                text = stringResource(R.string.audio_ai_backend_sherpa_badge),
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        } else {
                            Text(
                                text = stringResource(R.string.audio_ai_vieneu_unsupported),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    HorizontalDivider()
                    Text("2. Ngôn ngữ và giọng", fontWeight = FontWeight.SemiBold)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(modifier = Modifier.weight(0.4f)) {
                            SettingSelector(
                                text = if (uiState.language == "en") "English" else "Tiếng Việt",
                                enabled = !uiState.isAiVoice,
                                onClick = { showLanguageMenu = true }
                            )
                            DropdownMenu(
                                expanded = showLanguageMenu && !uiState.isAiVoice,
                                onDismissRequest = { showLanguageMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Tiếng Việt") },
                                    onClick = {
                                        viewModel.onLanguageChanged("vi")
                                        showLanguageMenu = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("English") },
                                    onClick = {
                                        viewModel.onLanguageChanged("en")
                                        showLanguageMenu = false
                                    }
                                )
                            }
                        }

                        Box(modifier = Modifier.weight(0.6f)) {
                            val backendAiVoices = uiState.filteredAiVoices
                            val selectedVoiceName = if (uiState.isAiVoice) {
                                backendAiVoices.firstOrNull { it.id == uiState.selectedVoiceId }?.name
                                    ?: if (uiState.selectedAiBackend == AiTtsBackend.VIENEU_NANO) {
                                        stringResource(R.string.audio_vieneu_voice_fallback)
                                    } else {
                                        stringResource(R.string.audio_sherpa_voice_fallback)
                                    }
                            } else {
                                uiState.systemVoices.firstOrNull { it.id == uiState.selectedVoiceId }?.name
                                    ?: stringResource(R.string.tts_setup_system_default_voice)
                            }
                            SettingSelector(
                                text = selectedVoiceName,
                                enabled = uiState.isAiVoice || uiState.isSystemTtsReady,
                                onClick = { showVoiceMenu = true }
                            )
                            DropdownMenu(
                                expanded = showVoiceMenu,
                                onDismissRequest = { showVoiceMenu = false }
                            ) {
                                if (uiState.isAiVoice) {
                                    backendAiVoices.forEach { voice ->
                                        val statusText = if (voice.backend == AiTtsBackend.VIENEU_NANO) {
                                            val readyTag = if (voice.isDownloaded) {
                                                stringResource(R.string.audio_vieneu_voice_downloaded)
                                            } else {
                                                stringResource(R.string.audio_vieneu_voice_not_downloaded)
                                            }
                                            if (voice.description.isNotBlank()) "${voice.description} · $readyTag" else readyTag
                                        } else {
                                            val stateLabel = if (voice.isDownloaded) {
                                                stringResource(R.string.tts_setup_downloaded)
                                            } else {
                                                stringResource(R.string.audio_vieneu_voice_not_downloaded)
                                            }
                                            "${voice.size} · $stateLabel"
                                        }
                                        DropdownMenuItem(
                                            text = {
                                                Column {
                                                    Text(voice.name)
                                                    Text(
                                                        text = statusText,
                                                        fontSize = 12.sp,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                            },
                                            leadingIcon = if (uiState.selectedVoiceId == voice.id) {
                                                { Icon(Icons.Default.Check, contentDescription = null) }
                                            } else null,
                                            onClick = {
                                                viewModel.onVoiceSelected(voice.id)
                                                showVoiceMenu = false
                                            }
                                        )
                                    }
                                } else {
                                    DropdownMenuItem(
                                        text = { Text("Mặc định hệ thống") },
                                        onClick = {
                                            viewModel.onVoiceSelected(null)
                                            showVoiceMenu = false
                                        }
                                    )
                                    uiState.systemVoices.forEach { voice ->
                                        DropdownMenuItem(
                                            text = { Text(voice.name) },
                                            leadingIcon = if (uiState.selectedVoiceId == voice.id) {
                                                { Icon(Icons.Default.Check, contentDescription = null) }
                                            } else null,
                                            onClick = {
                                                viewModel.onVoiceSelected(voice.id)
                                                showVoiceMenu = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (uiState.isAiVoice) {
                        if (uiState.selectedAiBackend == AiTtsBackend.VIENEU_NANO) {
                            VieNeuDownloadSection(
                                uiState = uiState,
                                onDownload = viewModel::downloadVieNeuPackage,
                                onCancel = viewModel::cancelVieNeuDownload,
                                onClear = viewModel::clearVieNeuModel,
                            )
                        } else {
                            AiDownloadSection(uiState = uiState, onDownload = viewModel::downloadCurrentVoice)
                        }
                    }

                    HorizontalDivider()
                    Text("3. Điều chỉnh và nghe thử", fontWeight = FontWeight.SemiBold)
                    SettingSlider(
                        label = stringResource(R.string.audio_speech_rate),
                        valueLabel = String.format("%.1fx", uiState.speechSpeed),
                        value = uiState.speechSpeed,
                        range = 0.5f..2.0f,
                        steps = 15,
                        onValueChange = viewModel::onSpeedChanged
                    )

                    if (!uiState.isAiVoice) {
                        SettingSlider(
                            label = stringResource(R.string.audio_speech_pitch),
                            valueLabel = String.format("%.1f", uiState.speechPitch),
                            value = uiState.speechPitch,
                            range = 0.5f..1.5f,
                            steps = 10,
                            onValueChange = viewModel::onPitchChanged
                        )
                    } else if (uiState.selectedAiBackend == AiTtsBackend.VIENEU_NANO) {
                        VieNeuTuningCard(
                            uiState = uiState,
                            onPresetSelected = viewModel::onVieNeuPresetSelected,
                            onStepsChanged = viewModel::onVieNeuStepsChanged,
                            onCfgChanged = viewModel::onVieNeuCfgChanged,
                            onSwayChanged = viewModel::onVieNeuSwayChanged,
                            onThreadsSelected = viewModel::onVieNeuThreadsSelected,
                            onResetDefaults = viewModel::resetVieNeuDefaults,
                        )
                    }

                    val vieNeuSampleText = stringResource(R.string.audio_vieneu_preview_sample)
                    val canPlayAiPreview = if (uiState.selectedAiBackend == AiTtsBackend.VIENEU_NANO) {
                        uiState.selectedVoiceId != null && uiState.isVieNeuSupported && uiState.isVieNeuModelReady
                    } else {
                        uiState.selectedVoiceId != null && uiState.isModelDownloaded
                    }
                    OutlinedButton(
                        onClick = {
                            when {
                                uiState.isPlaying -> viewModel.stopActivePreview()
                                uiState.isAiVoice -> {
                                    if (uiState.selectedAiBackend == AiTtsBackend.VIENEU_NANO) {
                                        viewModel.testVoice(vieNeuSampleText)
                                    } else {
                                        viewModel.testVoice()
                                    }
                                }
                                else -> viewModel.testSystemVoice()
                            }
                        },
                        enabled = uiState.isPlaying || if (uiState.isAiVoice) canPlayAiPreview else uiState.isSystemTtsReady,
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        if (uiState.isPlaying) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Icon(Icons.Default.Stop, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.tts_action_stop))
                        } else {
                            Icon(Icons.Default.VolumeUp, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.audio_test_play))
                        }
                    }
                }
            }

            AudioBubbleSettingsCard(
                isEnabled = uiState.isBubbleEnabled,
                isEnablePending = uiState.isBubbleEnablePending,
                showNotificationWarning = uiState.isBubbleEnabled && !hasNotificationPermission,
                onEnabledChange = { shouldEnable ->
                    if (shouldEnable) {
                        beginBubbleEnable()
                    } else {
                        viewModel.disableBubble()
                        TtsService.syncBubbleState(context, enabled = false)
                    }
                }
            )

            BubblePowerModeCard(
                mode = uiState.bubblePowerMode,
                expanded = showPowerModeMenu,
                onExpandedChange = { showPowerModeMenu = it },
                onModeSelected = viewModel::setBubblePowerMode
            )

            Button(
                onClick = onNavigateBack,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(stringResource(R.string.audio_save_settings), fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun AudioBubbleSettingsCard(
    isEnabled: Boolean,
    isEnablePending: Boolean,
    showNotificationWarning: Boolean,
    onEnabledChange: (Boolean) -> Unit
) {
    val title = stringResource(R.string.audio_bubble_settings_title)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = title,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = stringResource(R.string.audio_bubble_settings_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = isEnabled || isEnablePending,
                    onCheckedChange = onEnabledChange,
                    modifier = Modifier.semantics { contentDescription = title }
                )
            }

            if (isEnablePending) {
                Text(
                    text = stringResource(R.string.audio_bubble_permission_pending),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            if (showNotificationWarning) {
                HorizontalDivider()
                Text(
                    text = stringResource(R.string.audio_bubble_notification_permission_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun BubblePowerModeCard(
    mode: TtsBubblePowerMode,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onModeSelected: (TtsBubblePowerMode) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(stringResource(R.string.audio_bubble_power_mode_title), fontWeight = FontWeight.SemiBold)
            Text(
                text = if (mode == TtsBubblePowerMode.ALWAYS_ON) {
                    stringResource(R.string.audio_bubble_power_mode_always_on_desc)
                } else {
                    stringResource(R.string.audio_bubble_power_mode_saver_desc)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Box {
                SettingSelector(
                    text = if (mode == TtsBubblePowerMode.ALWAYS_ON) {
                        stringResource(R.string.audio_bubble_power_mode_always_on)
                    } else {
                        stringResource(R.string.audio_bubble_power_mode_saver)
                    },
                    enabled = true,
                    onClick = { onExpandedChange(true) }
                )
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { onExpandedChange(false) }
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.audio_bubble_power_mode_always_on)) },
                        onClick = {
                            onModeSelected(TtsBubblePowerMode.ALWAYS_ON)
                            onExpandedChange(false)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.audio_bubble_power_mode_saver)) },
                        onClick = {
                            onModeSelected(TtsBubblePowerMode.BATTERY_SAVER)
                            onExpandedChange(false)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingSelector(text: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text, maxLines = 1, modifier = Modifier.weight(1f))
            if (enabled) Icon(Icons.Default.KeyboardArrowDown, contentDescription = null)
        }
    }
}

@Composable
private fun AiDownloadSection(uiState: AudioSettingsUiState, onDownload: () -> Unit) {
    val selected = uiState.aiVoices.firstOrNull { it.id == uiState.selectedVoiceId }
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                enabled = selected != null && !selected.isDownloaded && !uiState.isDownloading,
                onClick = onDownload
            ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (selected?.isDownloaded == true) Icons.Default.Check else Icons.Default.CloudDownload,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = when {
                            selected == null -> "Chọn một giọng AI để tải"
                            selected.isDownloaded -> "${selected.name} đã sẵn sàng"
                            uiState.isDownloading -> "Đang tải ${selected.name}..."
                            else -> "Tải ${selected.name}"
                        },
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = selected?.let { "${it.size} · sử dụng ngoại tuyến" }
                            ?: "Không có model mặc định",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (uiState.isDownloading) {
                LinearProgressIndicator(
                    progress = { uiState.downloadProgress },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            uiState.downloadError?.let { error ->
                Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun SettingSlider(
    label: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit
) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label)
            Text(valueLabel, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            steps = steps
        )
    }
}

/**
 * Thẻ quản lý tải xuống, tạm dừng và xoá gói tài nguyên dùng chung của VieNeu-TTS v3-Nano (~345 MB).
 *
 * @param uiState Trạng thái hiện tại của màn hình cài đặt âm thanh.
 * @param onDownload Callback bắt đầu hoặc tiếp tục tải gói model VieNeu.
 * @param onCancel Callback tạm dừng tải xuống để lần sau resume tiếp.
 * @param onClear Callback xoá bộ file model VieNeu khỏi máy.
 */
@Composable
private fun VieNeuDownloadSection(
    uiState: AudioSettingsUiState,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onClear: () -> Unit,
) {
    val statusText = when {
        !uiState.isVieNeuSupported -> stringResource(R.string.audio_ai_vieneu_unsupported)
        uiState.isVieNeuDownloading -> stringResource(
            R.string.audio_vieneu_package_downloading,
            uiState.vieNeuCurrentFile,
            uiState.vieNeuDownloadedMb,
            uiState.vieNeuTotalMb,
        )
        uiState.isVieNeuModelReady -> stringResource(
            R.string.audio_vieneu_package_ready,
            uiState.vieNeuTotalMb,
        )
        else -> stringResource(
            R.string.audio_vieneu_package_missing,
            TtsVoiceCatalog.VIENEU_SHARED_PACKAGE_SIZE,
        )
    }

    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (uiState.isVieNeuModelReady) Icons.Default.Check else Icons.Default.CloudDownload,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.audio_vieneu_package_title),
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = statusText,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (uiState.isVieNeuDownloading) {
                LinearProgressIndicator(
                    progress = { uiState.vieNeuDownloadProgress },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (uiState.isVieNeuSupported) {
                when {
                    uiState.isVieNeuDownloading -> {
                        OutlinedButton(
                            onClick = onCancel,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.audio_vieneu_package_cancel_btn))
                        }
                    }
                    uiState.isVieNeuModelReady -> {
                        OutlinedButton(
                            onClick = onClear,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.audio_vieneu_package_delete_btn))
                        }
                    }
                    else -> {
                        Button(
                            onClick = onDownload,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                stringResource(
                                    R.string.audio_vieneu_package_download_btn,
                                    TtsVoiceCatalog.VIENEU_SHARED_PACKAGE_SIZE,
                                )
                            )
                        }
                    }
                }
            }

            uiState.vieNeuDownloadError?.let { error ->
                Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
        }
    }
}

/**
 * Bảng tinh chỉnh preset và tham số tổng hợp giọng nói VieNeu-TTS v3-Nano (`steps`, `cfg`, `sway`, `threads`).
 *
 * @param uiState Trạng thái chứa cấu hình VieNeu hiện hành.
 * @param onPresetSelected Callback khi người dùng chọn preset chất lượng.
 * @param onStepsChanged Callback khi người dùng thay đổi số bước Euler.
 * @param onCfgChanged Callback khi người dùng thay đổi hệ số CFG.
 * @param onSwayChanged Callback khi người dùng thay đổi tham số Sway.
 * @param onThreadsSelected Callback khi người dùng chọn số luồng CPU (`2` hoặc `4`).
 * @param onResetDefaults Callback khôi phục thông số tối ưu mặc định (`FAST`).
 */
@Composable
private fun VieNeuTuningCard(
    uiState: AudioSettingsUiState,
    onPresetSelected: (VieNeuQualityPreset) -> Unit,
    onStepsChanged: (Int) -> Unit,
    onCfgChanged: (Float) -> Unit,
    onSwayChanged: (Float) -> Unit,
    onThreadsSelected: (Int) -> Unit,
    onResetDefaults: () -> Unit,
) {
    val presetItems = listOf(
        VieNeuQualityPreset.FAST to R.string.audio_vieneu_preset_fast,
        VieNeuQualityPreset.BALANCED to R.string.audio_vieneu_preset_balanced,
        VieNeuQualityPreset.TURBO to R.string.audio_vieneu_preset_turbo,
        VieNeuQualityPreset.CUSTOM to R.string.audio_vieneu_preset_custom,
    )

    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.audio_vieneu_config_title),
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = stringResource(R.string.audio_vieneu_config_subtitle),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                presetItems.chunked(2).forEach { rowItems ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        rowItems.forEach { (preset, labelRes) ->
                            FilterChip(
                                selected = uiState.vieNeuPreset == preset,
                                onClick = { onPresetSelected(preset) },
                                label = { Text(stringResource(labelRes), maxLines = 1) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            SettingSlider(
                label = stringResource(R.string.audio_vieneu_steps_label),
                valueLabel = stringResource(R.string.audio_vieneu_steps_value, uiState.vieNeuSteps),
                value = uiState.vieNeuSteps.toFloat(),
                range = TtsSettings.MIN_VIENEU_STEPS.toFloat()..TtsSettings.MAX_VIENEU_STEPS.toFloat(),
                steps = (TtsSettings.MAX_VIENEU_STEPS - TtsSettings.MIN_VIENEU_STEPS) - 1,
                onValueChange = { onStepsChanged(it.roundToInt()) }
            )

            SettingSlider(
                label = stringResource(R.string.audio_vieneu_cfg_label),
                valueLabel = String.format("%.1f", uiState.vieNeuCfg),
                value = uiState.vieNeuCfg,
                range = TtsSettings.MIN_VIENEU_CFG..TtsSettings.MAX_VIENEU_CFG,
                steps = 49,
                onValueChange = onCfgChanged
            )

            SettingSlider(
                label = stringResource(R.string.audio_vieneu_sway_label),
                valueLabel = String.format("%.1f", uiState.vieNeuSway),
                value = uiState.vieNeuSway,
                range = TtsSettings.MIN_VIENEU_SWAY..TtsSettings.MAX_VIENEU_SWAY,
                steps = 9,
                onValueChange = onSwayChanged
            )

            Text(
                text = stringResource(R.string.audio_vieneu_threads_label),
                fontWeight = FontWeight.Medium,
                style = MaterialTheme.typography.bodyMedium
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                listOf(2, 4).forEach { threadCount ->
                    FilterChip(
                        selected = uiState.vieNeuThreads == threadCount,
                        onClick = { onThreadsSelected(threadCount) },
                        label = { Text(stringResource(R.string.audio_vieneu_threads_value, threadCount)) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            TextButton(
                onClick = onResetDefaults,
                modifier = Modifier.align(Alignment.End)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.audio_vieneu_reset_default))
            }
        }
    }
}
