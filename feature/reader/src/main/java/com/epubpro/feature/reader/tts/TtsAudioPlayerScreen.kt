package com.epubpro.feature.reader.tts

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.epubpro.core.designsystem.R
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.epubpro.domain.model.SleepTimerOption
import com.epubpro.domain.model.TtsPlayerState
import com.epubpro.domain.model.TtsSettings

@Composable
fun TtsAudioPlayerScreen(
    bookTitle: String,
    author: String,
    playerState: TtsPlayerState,
    settings: TtsSettings,
    selectedSleepTimer: SleepTimerOption,
    onCollapse: () -> Unit,
    onPlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onOpenSetup: () -> Unit,
    onSelectSleepTimer: (SleepTimerOption) -> Unit,
    onSeekToChunk: (Int) -> Unit = {}
) {
    val primaryColor = Color(0xFFD97757)
    val lightBgColor = Color(0xFFFAF6F0)

    val isPlaying = playerState is TtsPlayerState.Playing
    val (currentChunkIndex, totalChunks, currentChunkText) = remember(playerState) {
        when (playerState) {
            is TtsPlayerState.Preparing -> Triple(playerState.currentChunkIndex, playerState.totalChunks, playerState.currentChunk.text)
            is TtsPlayerState.Playing -> Triple(playerState.currentChunkIndex, playerState.totalChunks, playerState.currentChunk.text)
            is TtsPlayerState.Paused -> Triple(playerState.currentChunkIndex, playerState.totalChunks, playerState.currentChunk.text)
            else -> Triple(0, 1, "")
        }
    }

    var currentChunkElapsedMs by remember(currentChunkIndex) { mutableLongStateOf(0L) }

    LaunchedEffect(isPlaying, currentChunkIndex) {
        if (isPlaying) {
            val startTime = System.currentTimeMillis() - currentChunkElapsedMs
            while (true) {
                currentChunkElapsedMs = System.currentTimeMillis() - startTime
                kotlinx.coroutines.delay(250)
            }
        }
    }

    val chunkDurationMs = remember(currentChunkText, settings.speed) {
        ((currentChunkText.length / 15f / settings.speed.coerceAtLeast(0.5f)) * 1000f).toLong().coerceAtLeast(2000L)
    }

    val chunkProgressFraction = (currentChunkElapsedMs.toFloat() / chunkDurationMs).coerceIn(0f, 1f)
    val progressFraction = remember(currentChunkIndex, totalChunks, chunkProgressFraction) {
        if (totalChunks <= 0) 0f
        else ((currentChunkIndex + chunkProgressFraction) / totalChunks).coerceIn(0f, 1f)
    }

    val totalSeconds = remember(totalChunks, settings.speed) {
        ((totalChunks * 5f) / settings.speed.coerceAtLeast(0.5f)).toInt()
    }
    val currentSeconds = (progressFraction * totalSeconds).toInt()

    fun formatTime(sec: Int): String {
        val m = sec / 60
        val s = sec % 60
        return String.format("%02d:%02d", m, s)
    }

    var showSleepTimerMenu by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(lightBgColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header Top Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onCollapse) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = stringResource(R.string.reader_minimize),
                        tint = Color(0xFF424242),
                        modifier = Modifier.size(32.dp)
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "ĐANG ĐỌC DỞ",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF757575),
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.weight(1f))
                Spacer(modifier = Modifier.width(32.dp))
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Book Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = bookTitle,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF212121),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = author.ifEmpty { "Tác giả EpubPro" },
                            fontSize = 13.sp,
                            color = Color.Gray
                        )
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    // Cover placeholder
                    Box(
                        modifier = Modifier
                            .width(80.dp)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFFE8D3C5)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Book,
                            contentDescription = null,
                            tint = primaryColor,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Current Playing Paragraph Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp)
                ) {
                    val currentText = when (playerState) {
                        is TtsPlayerState.Preparing -> playerState.currentChunk.text
                        is TtsPlayerState.Playing -> playerState.currentChunk.text
                        is TtsPlayerState.Paused -> playerState.currentChunk.text
                        else -> currentChunkText
                    }

                    val currentChunkInfo = when (playerState) {
                        is TtsPlayerState.Preparing -> "Đoạn ${playerState.currentChunkIndex + 1} / ${playerState.totalChunks}"
                        is TtsPlayerState.Playing -> "Đoạn ${playerState.currentChunkIndex + 1} / ${playerState.totalChunks}"
                        is TtsPlayerState.Paused -> "Đoạn ${playerState.currentChunkIndex + 1} / ${playerState.totalChunks}"
                        else -> "Đoạn 0 / 0"
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text = "“ ", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = primaryColor)
                            Text(
                                text = "Đang đọc",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = primaryColor
                            )
                        }
                        Text(text = currentChunkInfo, fontSize = 12.sp, color = Color.Gray)
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = currentText,
                        fontSize = 15.sp,
                        color = Color(0xFF333333),
                        lineHeight = 22.sp,
                        maxLines = 8,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.weight(1f))

                    // Audio Visualizer waveform animation
                    AudioVisualizer(isPlaying = playerState is TtsPlayerState.Playing)
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Progress Slider
            Column(modifier = Modifier.fillMaxWidth()) {
                Slider(
                    value = progressFraction,
                    onValueChange = { newFraction ->
                        if (totalChunks > 0) {
                            val targetIndex = (newFraction * totalChunks).toInt().coerceIn(0, totalChunks - 1)
                            onSeekToChunk(targetIndex)
                        }
                    },
                    colors = SliderDefaults.colors(
                        thumbColor = primaryColor,
                        activeTrackColor = primaryColor,
                        inactiveTrackColor = Color(0xFFE0E0E0)
                    )
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = formatTime(currentSeconds), fontSize = 12.sp, color = Color.Gray)
                    Text(text = formatTime(totalSeconds), fontSize = 12.sp, color = Color.Gray)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Playback Control Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onSkipPrevious) {
                    Icon(
                        imageVector = Icons.Default.SkipPrevious,
                        contentDescription = stringResource(R.string.tts_action_prev),
                        modifier = Modifier.size(36.dp),
                        tint = Color(0xFF212121)
                    )
                }

                val isPlaying = playerState is TtsPlayerState.Playing ||
                    playerState is TtsPlayerState.Preparing
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(primaryColor)
                        .clickable { onPlayPause() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) stringResource(R.string.tts_action_pause) else stringResource(R.string.tts_action_play),
                        tint = Color.White,
                        modifier = Modifier.size(36.dp)
                    )
                }

                IconButton(onClick = onSkipNext) {
                    Icon(
                        imageVector = Icons.Default.SkipNext,
                        contentDescription = stringResource(R.string.tts_action_next),
                        modifier = Modifier.size(36.dp),
                        tint = Color(0xFF212121)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Bottom Quick Actions Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Speed Button
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .clickable { onOpenSetup() }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(imageVector = Icons.Default.Speed, contentDescription = null, modifier = Modifier.size(18.dp), tint = Color(0xFF424242))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = String.format("%.1fx", settings.speed), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }

                // Change Voice Button
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .clickable { onOpenSetup() }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(imageVector = Icons.Default.RecordVoiceOver, contentDescription = null, modifier = Modifier.size(18.dp), tint = Color(0xFF424242))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = if (settings.isAiVoice) "Giọng AI" else "Giọng hệ thống", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }

                // Sleep Timer Button
                Box {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .clickable { showSleepTimerMenu = true }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(imageVector = Icons.Default.Timer, contentDescription = null, modifier = Modifier.size(18.dp), tint = Color(0xFF424242))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (selectedSleepTimer == SleepTimerOption.OFF) "Hẹn giờ" else selectedSleepTimer.label,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    DropdownMenu(
                        expanded = showSleepTimerMenu,
                        onDismissRequest = { showSleepTimerMenu = false }
                    ) {
                        SleepTimerOption.values().forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.label) },
                                onClick = {
                                    onSelectSleepTimer(option)
                                    showSleepTimerMenu = false
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AudioVisualizer(isPlaying: Boolean) {
    val barTargets = remember {
        FloatArray(24) { index -> 18f + ((index * 7 + 5) % 22).toFloat() }
    }
    val phaseState = if (isPlaying) {
        val infiniteTransition = rememberInfiniteTransition(label = "tts_visualizer")
        infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = (2.0 * Math.PI).toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1200, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "tts_visualizer_phase"
        )
    } else {
        null
    }

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
    ) {
        val barCount = barTargets.size
        val barWidthPx = 4.dp.toPx()
        val spacingPx = 5.dp.toPx()
        val totalWidth = barCount * barWidthPx + (barCount - 1) * spacingPx
        val startX = ((size.width - totalWidth) / 2f).coerceAtLeast(0f)
        val corner = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx(), 2.dp.toPx())
        val barColor = Color(0xFFD97757).copy(alpha = 0.7f)
        val phase = phaseState?.value ?: 0f

        for (index in 0 until barCount) {
            val heightDp = if (isPlaying) {
                val wave = (kotlin.math.sin(phase + index * 0.55f) + 1f) * 0.5f
                8f + (barTargets[index] - 8f) * wave
            } else {
                8f
            }
            val barHeightPx = heightDp.dp.toPx().coerceAtMost(size.height)
            val x = startX + index * (barWidthPx + spacingPx)
            val y = (size.height - barHeightPx) / 2f
            drawRoundRect(
                color = barColor,
                topLeft = androidx.compose.ui.geometry.Offset(x, y),
                size = androidx.compose.ui.geometry.Size(barWidthPx, barHeightPx),
                cornerRadius = corner
            )
        }
    }
}
