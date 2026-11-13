package com.epubpro.feature.reader.tts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.epubpro.core.designsystem.R
import com.epubpro.domain.model.AiTtsBackend
import com.epubpro.domain.model.TtsSettings
import com.epubpro.domain.model.TtsVoice
import com.epubpro.domain.model.VieNeuQualityPreset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TtsSetupBottomSheet(
    currentSettings: TtsSettings,
    onGetAvailableVoices: (isAiVoice: Boolean, language: String) -> List<TtsVoice>,
    onDismiss: () -> Unit,
    onPreviewVoice: (settings: TtsSettings) -> Unit,
    onStartListening: (newSettings: TtsSettings) -> Unit
) {
    var isAiVoice by remember { mutableStateOf(currentSettings.isAiVoice) }
    var speed by remember { mutableStateOf(currentSettings.speed) }
    var pitch by remember { mutableStateOf(if (currentSettings.isAiVoice) 1.0f else currentSettings.pitch) }
    var language by remember { mutableStateOf(if (currentSettings.isAiVoice) "vi" else currentSettings.language) }
    var selectedVoiceId by remember { mutableStateOf(currentSettings.voiceId) }
    var vieNeuPreset by remember { mutableStateOf(currentSettings.vieNeuPreset) }
    var selectedBackend by remember {
        mutableStateOf(
            if (!currentSettings.isAiVoice || TtsSettings.isVieNeuVoiceId(currentSettings.voiceId) || currentSettings.voiceId == null) {
                AiTtsBackend.VIENEU_NANO
            } else {
                AiTtsBackend.SHERPA_VITS
            }
        )
    }
    var showLanguageMenu by remember { mutableStateOf(false) }
    var showVoiceMenu by remember { mutableStateOf(false) }

    val availableVoices = onGetAvailableVoices(isAiVoice, language)
    val hasVieNeuVoices = availableVoices.any { it.aiBackend == AiTtsBackend.VIENEU_NANO }
    val hasSherpaVoices = availableVoices.any {
        it.aiBackend == AiTtsBackend.SHERPA_VITS || it.aiBackend == null
    }

    LaunchedEffect(isAiVoice, language, availableVoices) {
        if (isAiVoice && language != "vi") language = "vi"
        if (isAiVoice && availableVoices.isNotEmpty()) {
            if (selectedVoiceId != null && availableVoices.none { it.id == selectedVoiceId }) {
                selectedVoiceId = null
            }
            val matchedVoice = availableVoices.firstOrNull { it.id == selectedVoiceId }
            if (matchedVoice != null) {
                selectedBackend = when {
                    matchedVoice.aiBackend == AiTtsBackend.VIENEU_NANO ||
                        TtsSettings.isVieNeuVoiceId(matchedVoice.id) -> AiTtsBackend.VIENEU_NANO
                    else -> AiTtsBackend.SHERPA_VITS
                }
            } else if (selectedBackend == AiTtsBackend.VIENEU_NANO && !hasVieNeuVoices && hasSherpaVoices) {
                selectedBackend = AiTtsBackend.SHERPA_VITS
            }
        } else if (!isAiVoice && selectedVoiceId != null && availableVoices.none { it.id == selectedVoiceId }) {
            selectedVoiceId = null
        }
    }

    val displayedVoices = if (isAiVoice) {
        availableVoices.filter { voice ->
            when (selectedBackend) {
                AiTtsBackend.VIENEU_NANO -> voice.aiBackend == AiTtsBackend.VIENEU_NANO
                AiTtsBackend.SHERPA_VITS -> voice.aiBackend == AiTtsBackend.SHERPA_VITS || voice.aiBackend == null
            }
        }
    } else {
        availableVoices
    }

    val selectedVoice = displayedVoices.firstOrNull { it.id == selectedVoiceId }
        ?: availableVoices.firstOrNull { it.id == selectedVoiceId }
    val canUseSelection = !isAiVoice || selectedVoice?.isDownloaded == true
    val draft = currentSettings.copy(
        isConfigured = true,
        isAiVoice = isAiVoice,
        language = if (isAiVoice) "vi" else language,
        voiceId = selectedVoiceId,
        speed = speed,
        pitch = if (isAiVoice) 1.0f else pitch
    ).withVieNeuPreset(vieNeuPreset)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Thiết lập giọng đọc", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("Chọn cách EpubPro đọc nội dung. Giọng AI cần được tải trước khi sử dụng.")

            Text("1. Công nghệ giọng đọc", fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilterChip(
                    selected = isAiVoice,
                    onClick = {
                        if (!isAiVoice) {
                            isAiVoice = true
                            language = "vi"
                            pitch = 1.0f
                            selectedVoiceId = null
                        }
                    },
                    label = { Text("Giọng AI") },
                    leadingIcon = { Icon(Icons.Default.AutoAwesome, null, Modifier.size(18.dp)) },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = !isAiVoice,
                    onClick = {
                        if (isAiVoice) {
                            isAiVoice = false
                            selectedVoiceId = null
                        }
                    },
                    label = { Text("Giọng hệ thống") },
                    leadingIcon = { Icon(Icons.Default.PhoneAndroid, null, Modifier.size(18.dp)) },
                    modifier = Modifier.weight(1f)
                )
            }

            if (isAiVoice && (hasVieNeuVoices || hasSherpaVoices)) {
                Text(
                    text = stringResource(R.string.audio_ai_backend_label),
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilterChip(
                        selected = selectedBackend == AiTtsBackend.VIENEU_NANO,
                        enabled = hasVieNeuVoices,
                        onClick = {
                            if (selectedBackend != AiTtsBackend.VIENEU_NANO) {
                                selectedBackend = AiTtsBackend.VIENEU_NANO
                                val vieNeuList = availableVoices.filter { it.aiBackend == AiTtsBackend.VIENEU_NANO }
                                selectedVoiceId = vieNeuList.firstOrNull { it.isDownloaded }?.id
                                    ?: vieNeuList.firstOrNull()?.id
                            }
                        },
                        label = { Text(stringResource(R.string.audio_ai_backend_vieneu), maxLines = 1) },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = selectedBackend == AiTtsBackend.SHERPA_VITS,
                        enabled = hasSherpaVoices,
                        onClick = {
                            if (selectedBackend != AiTtsBackend.SHERPA_VITS) {
                                selectedBackend = AiTtsBackend.SHERPA_VITS
                                val sherpaList = availableVoices.filter {
                                    it.aiBackend == AiTtsBackend.SHERPA_VITS || it.aiBackend == null
                                }
                                selectedVoiceId = sherpaList.firstOrNull { it.isDownloaded }?.id
                                    ?: sherpaList.firstOrNull()?.id
                            }
                        },
                        label = { Text(stringResource(R.string.audio_ai_backend_sherpa), maxLines = 1) },
                        modifier = Modifier.weight(1f)
                    )
                }
                if (!hasVieNeuVoices) {
                    Text(
                        text = stringResource(R.string.audio_ai_vieneu_unsupported),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            HorizontalDivider()
            Text("2. Ngôn ngữ và giọng", fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f)) {
                    SetupSelector(
                        text = if (language == "en") stringResource(R.string.audio_lang_en) else stringResource(R.string.audio_lang_vi),
                        enabled = !isAiVoice,
                        onClick = { showLanguageMenu = true }
                    )
                    DropdownMenu(
                        expanded = showLanguageMenu && !isAiVoice,
                        onDismissRequest = { showLanguageMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.audio_lang_vi)) },
                            onClick = {
                                language = "vi"
                                selectedVoiceId = null
                                showLanguageMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.audio_lang_en)) },
                            onClick = {
                                language = "en"
                                selectedVoiceId = null
                                showLanguageMenu = false
                            }
                        )
                    }
                }

                Box(Modifier.weight(1f)) {
                    SetupSelector(
                        text = selectedVoice?.name
                            ?: if (isAiVoice) {
                                stringResource(R.string.tts_setup_select_voice_prompt)
                            } else {
                                stringResource(R.string.tts_setup_system_default_voice)
                            },
                        enabled = true,
                        onClick = { showVoiceMenu = true }
                    )
                    DropdownMenu(
                        expanded = showVoiceMenu,
                        onDismissRequest = { showVoiceMenu = false }
                    ) {
                        if (!isAiVoice) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.tts_setup_system_default_voice)) },
                                onClick = {
                                    selectedVoiceId = null
                                    showVoiceMenu = false
                                }
                            )
                        }
                        displayedVoices.forEach { voice ->
                            val stateText = if (voice.isDownloaded) {
                                stringResource(R.string.tts_setup_downloaded)
                            } else {
                                stringResource(R.string.tts_setup_hint_download_in_settings)
                            }
                            val subtitleText = if (!voice.description.isNullOrBlank()) {
                                "${voice.description} · $stateText"
                            } else {
                                stateText
                            }
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(voice.name)
                                        if (isAiVoice) {
                                            Text(
                                                subtitleText,
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                },
                                leadingIcon = if (selectedVoiceId == voice.id) {
                                    { Icon(Icons.Default.Check, contentDescription = null) }
                                } else null,
                                onClick = {
                                    selectedVoiceId = voice.id
                                    showVoiceMenu = false
                                }
                            )
                        }
                    }
                }
            }

            if (isAiVoice && selectedVoice != null && !selectedVoice.isDownloaded) {
                Text(
                    text = if (selectedVoice.aiBackend == AiTtsBackend.VIENEU_NANO) {
                        stringResource(R.string.tts_setup_vieneu_missing_hint)
                    } else {
                        stringResource(R.string.tts_setup_hint_download_in_settings)
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            HorizontalDivider()
            Text("3. Điều chỉnh và nghe thử", fontWeight = FontWeight.SemiBold)
            SetupSlider(
                label = stringResource(R.string.audio_speech_rate),
                valueText = String.format("%.1fx", speed),
                value = speed,
                range = 0.5f..2.0f,
                steps = 15,
                onValueChange = { speed = it }
            )
            if (!isAiVoice) {
                SetupSlider(
                    label = stringResource(R.string.audio_speech_pitch),
                    valueText = String.format("%.1f", pitch),
                    value = pitch,
                    range = 0.5f..1.5f,
                    steps = 10,
                    onValueChange = { pitch = it }
                )
            } else if (selectedBackend == AiTtsBackend.VIENEU_NANO && hasVieNeuVoices) {
                Text(
                    text = stringResource(R.string.tts_setup_vieneu_preset_title),
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp
                )
                val showCustomChip = currentSettings.vieNeuPreset == VieNeuQualityPreset.CUSTOM ||
                    vieNeuPreset == VieNeuQualityPreset.CUSTOM
                val presetOptions = buildList {
                    add(VieNeuQualityPreset.FAST to R.string.audio_vieneu_preset_fast)
                    add(VieNeuQualityPreset.BALANCED to R.string.audio_vieneu_preset_balanced)
                    add(VieNeuQualityPreset.TURBO to R.string.audio_vieneu_preset_turbo)
                    if (showCustomChip) {
                        add(VieNeuQualityPreset.CUSTOM to R.string.audio_vieneu_preset_custom)
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    presetOptions.chunked(2).forEach { rowItems ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            rowItems.forEach { (preset, labelRes) ->
                                FilterChip(
                                    selected = vieNeuPreset == preset,
                                    onClick = { vieNeuPreset = preset },
                                    label = { Text(stringResource(labelRes), maxLines = 1) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            if (rowItems.size == 1) {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }

            OutlinedButton(
                onClick = { onPreviewVoice(draft) },
                enabled = canUseSelection,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.VolumeUp, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.audio_test_play))
            }
            Button(
                onClick = { onStartListening(draft) },
                enabled = canUseSelection,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                colors = ButtonDefaults.buttonColors()
            ) {
                Text("Bắt đầu nghe", fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SetupSelector(text: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text, maxLines = 1, modifier = Modifier.weight(1f))
            if (enabled) Icon(Icons.Default.KeyboardArrowDown, contentDescription = null)
        }
    }
}

@Composable
private fun SetupSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit
) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label)
            Text(valueText, fontWeight = FontWeight.Bold)
        }
        Slider(value, onValueChange, valueRange = range, steps = steps)
    }
}
