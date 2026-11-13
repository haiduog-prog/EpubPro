package com.vieneu.tts.sample.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vieneu.tts.sample.R
import com.vieneu.tts.sample.tts.ChunkGap

/**
 * Màn hình chính cho phép tải tài nguyên, chọn giọng mẫu, cấu hình tham số và phát thử VieNeu-TTS.
 *
 * @param state Trạng thái giao diện hiện tại.
 * @param onStartDownload Callback bắt đầu tải model và `sea_g2p.bin`.
 * @param onCancelDownload Callback huỷ quá trình tải xuống.
 * @param onLoadEngine Callback nạp ONNX + `sea-g2p` vào RAM.
 * @param onUnloadEngine Callback giải phóng engine khỏi RAM.
 * @param onSelectVoice Callback chọn giọng mẫu preset.
 * @param onInputTextChanged Callback cập nhật nội dung văn bản thử nghiệm.
 * @param onApplySampleText Callback chèn nhanh văn bản mẫu theo resource ID.
 * @param onSpeedChanged Callback chỉnh hệ số tốc độ đọc.
 * @param onSelectQualityPreset Callback chọn chế độ bước Euler / CFG / Sway.
 * @param onSelectThreads Callback chọn số luồng CPU ONNX.
 * @param onSpeak Callback bắt đầu tổng hợp và phát audio.
 * @param onStopSpeaking Callback dừng phát ngay lập tức.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VieNeuSampleScreen(
    state: VieNeuSampleUiState,
    onStartDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onLoadEngine: () -> Unit,
    onUnloadEngine: () -> Unit,
    onSelectVoice: (String) -> Unit,
    onInputTextChanged: (String) -> Unit,
    onApplySampleText: (Int) -> Unit,
    onSpeedChanged: (Float) -> Unit,
    onSelectQualityPreset: (QualityPreset) -> Unit,
    onSelectThreads: (Int) -> Unit,
    onSpeak: () -> Unit,
    onStopSpeaking: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = stringResource(R.string.app_subtitle),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
        bottomBar = {
            PlaybackBottomBar(
                state = state,
                onSpeak = onSpeak,
                onStopSpeaking = onStopSpeaking,
            )
        },
    ) { innerPadding ->
        val activeError = state.validationError?.let { stringResource(it.messageRes) } ?: state.runtimeError
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (activeError != null) {
                item(key = "error_banner") {
                    ErrorBannerCard(message = activeError)
                }
            }

            item(key = "resource_card") {
                ResourcesSectionCard(
                    state = state,
                    onStartDownload = onStartDownload,
                    onCancelDownload = onCancelDownload,
                    onLoadEngine = onLoadEngine,
                    onUnloadEngine = onUnloadEngine,
                )
            }

            item(key = "voice_card") {
                VoiceSelectorCard(
                    voices = state.voices,
                    selectedVoiceName = state.selectedVoiceName,
                    onSelectVoice = onSelectVoice,
                )
            }

            item(key = "input_card") {
                InputTextCard(
                    inputText = state.inputText,
                    onInputTextChanged = onInputTextChanged,
                    onApplySampleText = onApplySampleText,
                )
            }

            item(key = "settings_card") {
                SettingsCard(
                    speed = state.speed,
                    qualityPreset = state.qualityPreset,
                    selectedThreads = state.selectedThreads,
                    loadedThreads = state.loadedThreads,
                    isEngineLoaded = state.isEngineLoaded,
                    onSpeedChanged = onSpeedChanged,
                    onSelectQualityPreset = onSelectQualityPreset,
                    onSelectThreads = onSelectThreads,
                )
            }

            if (state.chunkLogs.isNotEmpty()) {
                item(key = "telemetry_header") {
                    Text(
                        text = stringResource(R.string.label_phoneme_preview),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }

                items(
                    items = state.chunkLogs,
                    key = { "chunk_${it.index}" },
                ) { chunk ->
                    ChunkTelemetryCard(chunk = chunk)
                }
            }
        }
    }
}

/**
 * Thẻ hiển thị banner báo lỗi kiểm tra đầu vào hoặc lỗi runtime.
 *
 * @param message Thông điệp lỗi cần hiển thị.
 */
@Composable
private fun ErrorBannerCard(message: String) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = stringResource(R.string.error_banner_prefix, message),
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(12.dp),
        )
    }
}

/**
 * Thẻ quản lý tải model, trạng thái từ điển `sea-g2p` và nạp/giải phóng engine.
 *
 * @param state Trạng thái UI hiện tại.
 * @param onStartDownload Callback bắt đầu tải tài nguyên.
 * @param onCancelDownload Callback huỷ tải tài nguyên.
 * @param onLoadEngine Callback nạp engine ONNX + G2P.
 * @param onUnloadEngine Callback giải phóng engine khỏi RAM.
 */
@Composable
private fun ResourcesSectionCard(
    state: VieNeuSampleUiState,
    onStartDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onLoadEngine: () -> Unit,
    onUnloadEngine: () -> Unit,
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = if (state.isEngineLoaded) Icons.Outlined.CheckCircle else Icons.Outlined.Memory,
                    contentDescription = null,
                    tint = if (state.isEngineLoaded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.section_resources),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            val inventory = state.inventory
            when {
                state.isDownloading -> {
                    val progress = state.downloadProgress
                    if (progress != null) {
                        val pct = (progress.fraction * 100f).toInt()
                        Text(
                            text = stringResource(R.string.status_downloading, progress.currentFile, pct),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        LinearProgressIndicator(
                            progress = { progress.fraction },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            text = stringResource(
                                R.string.status_downloading_detail,
                                formatMbString(progress.downloadedBytes),
                                formatMbString(progress.totalBytes),
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    OutlinedButton(
                        onClick = onCancelDownload,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.btn_cancel_download))
                    }
                }

                state.isLoadingEngine -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                        Text(
                            text = stringResource(R.string.status_loading_engine),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                state.isEngineLoaded -> {
                    Text(
                        text = stringResource(
                            R.string.status_engine_loaded,
                            state.engineLoadMs ?: 0L,
                            state.seaG2pAbiVersion ?: 1,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    OutlinedButton(
                        onClick = onUnloadEngine,
                        enabled = !state.isSynthesizing,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.btn_unload_engine))
                    }
                }

                inventory != null && !inventory.isReady -> {
                    Text(
                        text = stringResource(
                            R.string.status_missing_files,
                            inventory.presentCount,
                            inventory.totalCount,
                            formatMbString(inventory.missingBytes),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(
                        onClick = onStartDownload,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.CloudDownload,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.btn_download_models))
                    }
                }

                inventory != null -> {
                    Text(
                        text = stringResource(
                            R.string.status_ready_to_load,
                            formatMbString(inventory.existingBytes),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(
                        onClick = onLoadEngine,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.btn_load_engine))
                    }
                }
            }

            if (inventory != null) {
                Text(
                    text = stringResource(R.string.label_storage_dir, inventory.modelDir.absolutePath),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Thẻ cho phép chọn một trong 11 giọng mẫu preset kèm mô tả vùng miền.
 *
 * @param voices Danh sách giọng mẫu khả dụng.
 * @param selectedVoiceName Tên giọng đang chọn.
 * @param onSelectVoice Callback khi người dùng chọn giọng khác.
 */
@Composable
private fun VoiceSelectorCard(
    voices: List<VoiceOptionUi>,
    selectedVoiceName: String,
    onSelectVoice: (String) -> Unit,
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.RecordVoiceOver,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(R.string.section_voices, voices.size),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(
                    items = voices,
                    key = { it.name },
                ) { voice ->
                    val selected = voice.name == selectedVoiceName
                    FilterChip(
                        selected = selected,
                        onClick = { onSelectVoice(voice.name) },
                        modifier = Modifier.heightIn(min = 48.dp),
                        label = {
                            Column {
                                Text(
                                    text = voice.name,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                )
                                val genderLabel = stringResource(
                                    if (voice.isFemale) R.string.voice_gender_female else R.string.voice_gender_male,
                                )
                                val suffix = if (voice.isDefault) {
                                    "$genderLabel • ${stringResource(R.string.voice_default_badge)}"
                                } else {
                                    genderLabel
                                }
                                Text(
                                    text = suffix,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        },
                    )
                }
            }

            val current = voices.firstOrNull { it.name == selectedVoiceName }
            if (current != null && current.description.isNotBlank()) {
                Text(
                    text = stringResource(R.string.voice_selected_info, current.name, current.description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Thẻ nhập liệu văn bản tiếng Việt / song ngữ và các chip nạp nhanh câu mẫu.
 *
 * @param inputText Văn bản hiện tại trong ô nhập liệu.
 * @param onInputTextChanged Callback cập nhật văn bản.
 * @param onApplySampleText Callback nạp văn bản mẫu theo resource ID.
 */
@Composable
private fun InputTextCard(
    inputText: String,
    onInputTextChanged: (String) -> Unit,
    onApplySampleText: (Int) -> Unit,
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.section_input),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item(key = "sample_news") {
                    AssistChip(
                        onClick = { onApplySampleText(R.string.sample_text_news) },
                        label = { Text(stringResource(R.string.sample_chip_news)) },
                    )
                }
                item(key = "sample_para") {
                    AssistChip(
                        onClick = { onApplySampleText(R.string.sample_text_paragraph) },
                        label = { Text(stringResource(R.string.sample_chip_paragraph)) },
                    )
                }
                item(key = "sample_emotion") {
                    AssistChip(
                        onClick = { onApplySampleText(R.string.sample_text_emotion) },
                        label = { Text(stringResource(R.string.sample_chip_emotion)) },
                    )
                }
                item(key = "sample_bi") {
                    AssistChip(
                        onClick = { onApplySampleText(R.string.sample_text_bilingual) },
                        label = { Text(stringResource(R.string.sample_chip_bilingual)) },
                    )
                }
            }

            OutlinedTextField(
                value = inputText,
                onValueChange = onInputTextChanged,
                label = { Text(stringResource(R.string.input_label)) },
                minLines = 3,
                maxLines = 7,
                trailingIcon = {
                    if (inputText.isNotEmpty()) {
                        IconButton(onClick = { onInputTextChanged("") }) {
                            Icon(
                                imageVector = Icons.Outlined.Clear,
                                contentDescription = stringResource(R.string.btn_clear_text),
                            )
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Thẻ cấu hình tốc độ đọc, chế độ bước Euler/CFG và số luồng CPU ONNX Runtime.
 *
 * @param speed Tốc độ đọc hiện tại.
 * @param qualityPreset Chế độ bước tổng hợp đang chọn.
 * @param selectedThreads Số luồng người dùng đang chọn.
 * @param loadedThreads Số luồng hiện đang nạp trong engine.
 * @param isEngineLoaded Cho biết engine đã được nạp hay chưa.
 * @param onSpeedChanged Callback chỉnh tốc độ đọc.
 * @param onSelectQualityPreset Callback đổi chế độ chất lượng.
 * @param onSelectThreads Callback đổi số luồng CPU.
 */
@Composable
private fun SettingsCard(
    speed: Float,
    qualityPreset: QualityPreset,
    selectedThreads: Int,
    loadedThreads: Int,
    isEngineLoaded: Boolean,
    onSpeedChanged: (Float) -> Unit,
    onSelectQualityPreset: (QualityPreset) -> Unit,
    onSelectThreads: (Int) -> Unit,
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.section_settings),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            Text(
                text = stringResource(R.string.label_speed, speed),
                style = MaterialTheme.typography.bodyMedium,
            )
            Slider(
                value = speed,
                onValueChange = onSpeedChanged,
                valueRange = 0.6f..1.8f,
                steps = 11,
            )

            Text(
                text = stringResource(R.string.label_quality_preset),
                style = MaterialTheme.typography.bodyMedium,
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(
                    items = QualityPreset.entries,
                    key = { it.name },
                ) { preset ->
                    FilterChip(
                        selected = qualityPreset == preset,
                        onClick = { onSelectQualityPreset(preset) },
                        label = { Text(stringResource(preset.labelRes)) },
                    )
                }
            }

            Text(
                text = stringResource(R.string.label_threads),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(2, 4).forEach { count ->
                    FilterChip(
                        selected = selectedThreads == count,
                        onClick = { onSelectThreads(count) },
                        label = { Text(stringResource(R.string.threads_option, count)) },
                    )
                }
            }
            if (isEngineLoaded && selectedThreads != loadedThreads) {
                Text(
                    text = stringResource(R.string.hint_reload_threads),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }
    }
}

/**
 * Thẻ hiển thị kết quả phân chunk, chuỗi IPA từ `sea-g2p` và thông số RTF từng chunk.
 *
 * @param chunk Dữ liệu đo đạc và IPA của một chunk.
 */
@Composable
private fun ChunkTelemetryCard(chunk: ChunkTelemetryUi) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.outlinedCardColors(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val gapText = stringResource(gapLabelRes(chunk.gapBefore))
            Text(
                text = stringResource(R.string.phoneme_chunk_header, chunk.index, gapText, chunk.text),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = stringResource(R.string.phoneme_line, chunk.phonemes),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val ms = chunk.synthMs
            val sec = chunk.audioSec
            val rtf = chunk.rtf
            if (ms != null && sec != null && rtf != null) {
                Text(
                    text = stringResource(R.string.phoneme_timing, ms, sec, rtf),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/**
 * Thanh điều khiển cố định phía dưới gồm trạng thái phát, TTFA/RTF và nút Đọc/Dừng.
 *
 * @param state Trạng thái UI hiện tại.
 * @param onSpeak Callback bắt đầu đọc thử.
 * @param onStopSpeaking Callback dừng phát âm thanh.
 */
@Composable
private fun PlaybackBottomBar(
    state: VieNeuSampleUiState,
    onSpeak: () -> Unit,
    onStopSpeaking: () -> Unit,
) {
    Surface(
        tonalElevation = 4.dp,
        shadowElevation = 6.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            val statusText = when (state.playbackPhase) {
                PlaybackPhase.IDLE -> stringResource(R.string.playback_idle)
                PlaybackPhase.PREPARING -> stringResource(R.string.playback_preparing)
                PlaybackPhase.SYNTHESIZING -> stringResource(
                    R.string.playback_synthesizing,
                    state.currentChunkIndex,
                    state.totalChunks.coerceAtLeast(1),
                )
                PlaybackPhase.DONE -> stringResource(
                    R.string.playback_done,
                    state.totalChunks,
                    state.totalAudioSeconds,
                )
                PlaybackPhase.STOPPED -> stringResource(R.string.playback_stopped)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (state.ttfaMs != null || state.totalRtf != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.ttfaMs?.let { ttfa ->
                            Text(
                                text = stringResource(R.string.metric_ttfa, ttfa),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        state.totalRtf?.let { rtf ->
                            Text(
                                text = stringResource(R.string.metric_rtf, rtf),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = onSpeak,
                    enabled = state.isEngineLoaded && !state.isLoadingEngine && !state.isDownloading,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = null,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.btn_speak))
                }

                OutlinedButton(
                    onClick = onStopSpeaking,
                    enabled = state.isSynthesizing,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                    modifier = Modifier.height(48.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Stop,
                        contentDescription = null,
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.btn_stop))
                }
            }
        }
    }
}

/**
 * Định dạng kích thước byte sang chuỗi megabyte hiển thị trên UI từ `strings.xml`.
 *
 * @param bytes Kích thước theo byte.
 * @return Chuỗi dung lượng tính bằng MB.
 */
@Composable
private fun formatMbString(bytes: Long): String {
    val mb = bytes.toFloat() / (1024f * 1024f)
    return stringResource(R.string.unit_mb, mb)
}

/**
 * Ánh xạ loại khoảng nghỉ trước chunk sang mã tài nguyên chuỗi tiếng Việt tương ứng.
 *
 * @param gap Loại ranh giới chunk.
 * @return Mã tài nguyên `@StringRes` mô tả loại ranh giới.
 */
@StringRes
private fun gapLabelRes(gap: ChunkGap): Int = when (gap) {
    ChunkGap.FIRST -> R.string.gap_first
    ChunkGap.PARA -> R.string.gap_para
    ChunkGap.SENTENCE -> R.string.gap_sentence
    ChunkGap.MINOR -> R.string.gap_minor
}
