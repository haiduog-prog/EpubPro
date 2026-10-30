package com.epubpro.feature.reader

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material.icons.filled.BrightnessLow
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NavigateBefore
import androidx.compose.material.icons.filled.NavigateNext
import androidx.compose.material.icons.filled.Nightlight
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import android.widget.Toast
import androidx.compose.material3.Slider
import com.epubpro.feature.reader.filter.ReplaceTextBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import com.epubpro.core.designsystem.R
import com.epubpro.core.reader.tts.TtsService
import com.epubpro.domain.model.EXTRA_DIM_THRESHOLD
import com.epubpro.domain.model.MAX_PAGE_TURN_SPEED_MS
import com.epubpro.domain.model.MIN_PAGE_TURN_SPEED_MS
import com.epubpro.domain.model.PAGE_TURN_SPEED_PRESETS_MS
import com.epubpro.domain.model.ReaderSettings
import com.epubpro.domain.model.ReaderThemeMode
import com.epubpro.domain.model.TtsPlayerState
import com.epubpro.domain.model.calculateBrightnessOutput
import com.epubpro.feature.reader.brightness.BrightnessEdgeSensor
import com.epubpro.feature.reader.brightness.BrightnessHud
import com.epubpro.feature.reader.brightness.BrightnessWindowEffect
import com.epubpro.feature.reader.brightness.ExtraDimOverlay
import com.epubpro.feature.reader.brightness.findActivity
import com.epubpro.feature.reader.tts.TtsAudioPlayerScreen
import com.epubpro.feature.reader.tts.TtsMiniPlayerBar
import com.epubpro.feature.reader.tts.TtsSetupBottomSheet
import com.epubpro.feature.reader.webview.EpubProWebView
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    onNavigateBack: () -> Unit,
    onOpenBookBible: (bookId: String, chapterNumber: Int) -> Unit = { _, _ -> },
    onOpenWebChapter: (String) -> Unit = {},
    viewModel: ReaderViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var showTocDrawer by remember { mutableStateOf(false) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var showReaderOverflowMenu by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val hostView = LocalView.current
    val lifecycleOwner = context as? LifecycleOwner
    var ttsService by remember { mutableStateOf<TtsService?>(null) }
    val window = (context as? android.app.Activity)?.window

    val currentBrightness = uiState.settings.brightness
    var draftBrightness by remember(currentBrightness) { mutableFloatStateOf(currentBrightness) }
    var showBrightnessHud by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    var hudHideJob by remember { mutableStateOf<Job?>(null) }

    val brightnessOutput = remember(draftBrightness) { calculateBrightnessOutput(draftBrightness) }
    BrightnessWindowEffect(
        activity = context.findActivity(),
        hardwareBrightness = brightnessOutput.hardwareBrightness
    )

    DisposableEffect(lifecycleOwner) {
        if (lifecycleOwner == null) {
            onDispose { }
        } else {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP) {
                    viewModel.flushPendingProgress()
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
                viewModel.flushPendingProgress()
            }
        }
    }

    DisposableEffect(window, uiState.settings.keepScreenOn) {
        if (window != null) {
            if (uiState.settings.keepScreenOn) {
                window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        onDispose {
            window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    DisposableEffect(context) {
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val binder = service as? TtsService.TtsBinder
                ttsService = binder?.getService()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                ttsService = null
            }
        }
        val intent = Intent(context, TtsService::class.java)
        context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        onDispose {
            try {
                context.unbindService(connection)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    val (readerBgColor, readerBarBgColor, readerContentColor) = when (uiState.settings.themeMode) {
        ReaderThemeMode.LIGHT -> Triple(
            Color(0xFFFFFFFF),
            Color(0xFFF1F5F9),
            Color(0xFF0F172A)
        )

        ReaderThemeMode.DARK -> Triple(
            Color(0xFF0F172A),
            Color(0xFF1E293B),
            Color(0xFFF8FAFC)
        )

        ReaderThemeMode.SEPIA -> Triple(
            Color(0xFFFBF0D9),
            Color(0xFFEFE0C2),
            Color(0xFF3B2F23)
        )

        ReaderThemeMode.PAPER -> Triple(
            Color(0xFFF5F0E8),
            Color(0xFFE5DDD0),
            Color(0xFF2C2825)
        )

        ReaderThemeMode.MIDNIGHT -> Triple(
            Color(0xFF000000),
            Color(0xFF18181B),
            Color(0xFFE2E8F0)
        )
    }
    val isDarkTheme =
        uiState.settings.themeMode in listOf(ReaderThemeMode.DARK, ReaderThemeMode.MIDNIGHT)

    val currentStatusBarColor = if (uiState.showControls) readerBarBgColor else readerBgColor

    DisposableEffect(currentStatusBarColor, readerBgColor, isDarkTheme) {
        val originalStatusBarColor = window?.statusBarColor
        val originalNavBarColor = window?.navigationBarColor
        val controller =
            window?.let { androidx.core.view.WindowCompat.getInsetsController(it, hostView) }
        val originalLightStatus = controller?.isAppearanceLightStatusBars ?: true
        val originalLightNav = controller?.isAppearanceLightNavigationBars ?: true

        if (window != null) {
            window.statusBarColor = currentStatusBarColor.toArgb()
            window.navigationBarColor = readerBgColor.toArgb()
            controller?.isAppearanceLightStatusBars = !isDarkTheme
            controller?.isAppearanceLightNavigationBars = !isDarkTheme
        }

        onDispose {
            if (window != null && originalStatusBarColor != null && originalNavBarColor != null) {
                window.statusBarColor = originalStatusBarColor
                window.navigationBarColor = originalNavBarColor
                controller?.isAppearanceLightStatusBars = originalLightStatus
                controller?.isAppearanceLightNavigationBars = originalLightNav
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(readerBgColor)
    ) {
        // Fullscreen Reader Content Layer (Never resizes when controls toggle)
        if (uiState.isLoading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        } else if (uiState.loadError != null) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Không thể tải nội dung sách",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = uiState.loadError.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = viewModel::retryLoad) {
                    Text("Thử lại")
                }
            }
        } else if (uiState.chapters.isNotEmpty()) {
            if (uiState.displayedChapterHtml.isNotEmpty()) {
                val activeChunkIndex =
                    (uiState.ttsPlayerState as? TtsPlayerState.Playing)?.currentChunk?.paragraphIndex
                        ?: (uiState.ttsPlayerState as? TtsPlayerState.Paused)?.currentChunk?.paragraphIndex

                EpubProWebView(
                    htmlContent = uiState.displayedChapterHtml,
                    preSanitizedHtml = uiState.preSanitizedDisplayedHtml,
                    previousChapterHtml = uiState.previousChapterHtml,
                    nextChapterHtml = uiState.nextChapterHtml,
                    preSanitizedPreviousChapterHtml = uiState.sanitizedPreviousChapterHtml,
                    preSanitizedNextChapterHtml = uiState.sanitizedNextChapterHtml,
                    hasPreviousChapter = uiState.currentChapterIndex > 0,
                    hasNextChapter = uiState.currentChapterIndex < uiState.chapters.lastIndex,
                    initialPage = uiState.initialPageRequest,
                    initialVisibleParagraphIndex = uiState.firstVisibleParagraphIndex,
                    settings = uiState.settings,
                    filterPreferences = uiState.filterPreferences,
                    activeTtsParagraphIndex = activeChunkIndex,
                    onPageTapped = viewModel::toggleControls,
                    onPageChanged = viewModel::updatePageMetrics,
                    onNextChapter = viewModel::nextChapter,
                    onPreviousChapter = viewModel::previousChapter,
                    onNextChapterPrefetch = {
                        viewModel.prefetchChapter(uiState.currentChapterIndex + 1)
                    },
                    onPreviousChapterPrefetch = {
                        viewModel.prefetchChapter(uiState.currentChapterIndex - 1)
                    },
                    onTextSelected = { json ->
                        try {
                            val obj = JSONObject(json)
                            val text = obj.optString("selectedText")
                            if (text.isNotBlank()) {
                                viewModel.addHighlight(text)
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    },
                    onAddSelectionToFilter = viewModel::addSelectionToContentFilter,
                    onCfiChanged = viewModel::updateCfiPosition
                )
            }
        }

        // Lớp phủ siêu tối Extra Dim (Không chặn cảm ứng)
        ExtraDimOverlay(alpha = brightnessOutput.extraDimAlpha)

        // Cảm biến vuốt mép trái để tăng giảm độ sáng nhanh
        BrightnessEdgeSensor(
            onBrightnessDelta = { delta ->
                val next = (draftBrightness + delta).coerceIn(0.0f, 1.0f)
                draftBrightness = next
                showBrightnessHud = true
                hudHideJob?.cancel()
                hudHideJob = coroutineScope.launch {
                    delay(1200)
                    showBrightnessHud = false
                }
            },
            onDragEnd = {
                viewModel.updateSettings(uiState.settings.copy(brightness = draftBrightness))
            },
            modifier = Modifier
                .fillMaxHeight()
                .width(36.dp)
                .align(Alignment.CenterStart)
        )

        // HUD thông báo độ sáng nổi ở trung tâm màn hình
        BrightnessHud(
            visible = showBrightnessHud,
            brightness = draftBrightness,
            modifier = Modifier.align(Alignment.Center)
        )

        // Floating Top Header Bar Overlay
        AnimatedVisibility(
            visible = uiState.showControls,
            enter = fadeIn() + slideInVertically(initialOffsetY = { -it }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { -it }),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            TopAppBar(
                windowInsets = WindowInsets.statusBars,
                title = {
                    Text(
                        text = uiState.book?.title ?: stringResource(R.string.reader_loading),
                        maxLines = 1,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::openAiBottomSheet) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = "AI thuần Việt",
                            tint = if (
                                uiState.contentVersion == ReaderContentVersion.AI ||
                                uiState.isAiProcessing
                            ) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                readerContentColor
                            }
                        )
                    }
                    IconButton(onClick = { viewModel.onTtsIconButtonClicked() }) {
                        Icon(
                            imageVector = Icons.Default.Headset,
                            contentDescription = stringResource(R.string.reader_tts),
                            tint = if (uiState.isTtsSpeaking) MaterialTheme.colorScheme.primary else readerContentColor
                        )
                    }
                    IconButton(onClick = { viewModel.addBookmark() }) {
                        Icon(
                            Icons.Default.BookmarkBorder,
                            contentDescription = stringResource(R.string.reader_save_bookmark)
                        )
                    }
                    IconButton(onClick = { showTocDrawer = true }) {
                        Icon(
                            Icons.Default.List,
                            contentDescription = stringResource(R.string.reader_toc)
                        )
                    }
                    Box {
                        IconButton(onClick = { showReaderOverflowMenu = true }) {
                            Icon(
                                Icons.Default.MoreVert,
                                contentDescription = stringResource(R.string.book_bible_menu_action)
                            )
                        }
                        DropdownMenu(
                            expanded = showReaderOverflowMenu,
                            onDismissRequest = { showReaderOverflowMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.book_bible_menu_action)) },
                                leadingIcon = {
                                    Icon(
                                        Icons.Outlined.AutoStories,
                                        contentDescription = null
                                    )
                                },
                                onClick = {
                                    showReaderOverflowMenu = false
                                    val bookId = uiState.book?.id ?: ""
                                    val chapterNumber = uiState.currentChapterIndex + 1
                                    onOpenBookBible(bookId, chapterNumber)
                                }
                            )
                            if (uiState.isWebNovel) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            stringResource(
                                                if (uiState.isAppendingChapters) {
                                                    R.string.reader_web_chapters_loading
                                                } else {
                                                    R.string.reader_web_load_more
                                                }
                                            )
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.CloudDownload,
                                            contentDescription = null
                                        )
                                    },
                                    enabled = !uiState.isAppendingChapters,
                                    onClick = {
                                        showReaderOverflowMenu = false
                                        viewModel.loadMoreWebChapters()
                                    }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.convert_menu_action)) },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Translate,
                                        contentDescription = null
                                    )
                                },
                                onClick = {
                                    showReaderOverflowMenu = false
                                    viewModel.openConvertSheet()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.reader_display_settings)) },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.FormatSize,
                                        contentDescription = null
                                    )
                                },
                                onClick = {
                                    showReaderOverflowMenu = false
                                    showSettingsSheet = true
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = readerBarBgColor.copy(alpha = 0.98f),
                    titleContentColor = readerContentColor,
                    actionIconContentColor = readerContentColor,
                    navigationIconContentColor = readerContentColor
                )
            )
        }

        // Floating Bottom Navigation Bar Overlay
        AnimatedVisibility(
            visible = uiState.showControls,
            enter = fadeIn() + slideInVertically(initialOffsetY = { it }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Surface(
                color = readerBarBgColor.copy(alpha = 0.98f),
                contentColor = readerContentColor,
                tonalElevation = 8.dp,
                shadowElevation = 4.dp,
                modifier = Modifier.navigationBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = viewModel::previousChapter,
                        enabled = uiState.currentChapterIndex > 0
                    ) {
                        Icon(
                            Icons.Default.NavigateBefore,
                            contentDescription = stringResource(R.string.reader_prev_chapter)
                        )
                    }

                    val progressText = if (uiState.settings.isHorizontalPagination) {
                        "Trang ${uiState.currentPageInChapter} / ${uiState.totalPagesInChapter} • Chương ${uiState.currentChapterIndex + 1}"
                    } else {
                        "Chương ${uiState.currentChapterIndex + 1} / ${
                            uiState.chapters.size.coerceAtLeast(
                                1
                            )
                        }"
                    }

                    if (uiState.settings.showStatusBar) {
                        Text(
                            text = progressText,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                    IconButton(
                        onClick = viewModel::nextChapter,
                        enabled = uiState.currentChapterIndex < uiState.chapters.size - 1
                    ) {
                        Icon(
                            Icons.Default.NavigateNext,
                            contentDescription = stringResource(R.string.reader_next_chapter)
                        )
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = !uiState.showControls && uiState.settings.showStatusBar,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomEnd)
        ) {
            val progressText = if (uiState.settings.isHorizontalPagination) {
                "Trang ${uiState.currentPageInChapter} / ${uiState.totalPagesInChapter}"
            } else {
                "Chương ${uiState.currentChapterIndex + 1} / ${uiState.chapters.size.coerceAtLeast(1)}"
            }
            Text(
                text = progressText,
                color = readerContentColor.copy(alpha = 0.7f),
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(end = 10.dp),
                style = MaterialTheme.typography.labelSmall
            )
        }

        // Table of Contents Drawer Sheet
        if (showTocDrawer) {
            val tocListState = rememberLazyListState(
                initialFirstVisibleItemIndex = (uiState.currentChapterIndex - 1).coerceAtLeast(0)
            )

            ModalBottomSheet(onDismissRequest = { showTocDrawer = false }) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text(
                        "Mục Lục",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                    LazyColumn(
                        state = tocListState
                    ) {
                        itemsIndexed(uiState.chapters) { index, ch ->
                            val isCurrent = index == uiState.currentChapterIndex
                            ListItem(
                                headlineContent = {
                                    Text(
                                        ch.title,
                                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                leadingContent = {
                                    if (isCurrent) {
                                        Icon(
                                            Icons.Default.Book,
                                            contentDescription = stringResource(R.string.reader_reading_now),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                },
                                modifier = Modifier.clickable {
                                    viewModel.onChapterSelected(index)
                                    showTocDrawer = false
                                }
                            )
                        }
                    }
                }
            }
        }

        if (showSettingsSheet) {
            ModalBottomSheet(onDismissRequest = { showSettingsSheet = false }) {
                ReaderSettingsContent(
                    settings = uiState.settings,
                    onSettingsChanged = viewModel::updateSettings
                )
            }
        }

        if (uiState.showAiBottomSheet) {
            AiVietnameseBottomSheet(
                uiState = uiState,
                chapterTitle = uiState.chapters
                    .getOrNull(uiState.currentChapterIndex)
                    ?.title
                    ?: "Chương " + (uiState.currentChapterIndex + 1),
                onDismiss = viewModel::dismissAiBottomSheet,
                onSaveConfiguration = viewModel::saveAiConfiguration,
                onTestConnection = viewModel::testAiConnection,
                onClearApiKey = viewModel::clearAiApiKey,
                onSelectVersion = viewModel::setContentVersion,
                onStartPolish = viewModel::startAiPolish,
                onCancelPolish = viewModel::cancelAiPolish,
                onDeleteChapter = viewModel::deleteCurrentAiChapter,
                onSaveRule = viewModel::saveAiRule,
                onDeleteRule = viewModel::deleteAiRule
            )
        }

        if (uiState.showConvertSheet) {
            ConvertBottomSheet(
                enabled = uiState.convertEnabled,
                hasDictionary = uiState.convertHasDictionary,
                names = uiState.convertNames,
                onDismiss = viewModel::dismissConvertSheet,
                onToggle = viewModel::setConvertEnabled,
                onSaveName = viewModel::saveConvertName,
                onDeleteName = viewModel::deleteConvertName
            )
        }

        val webNovelError = uiState.webNovelMessageRes == R.string.web_novel_download_failed ||
            uiState.webNovelMessageRes == R.string.reader_web_partial
        if (webNovelError) {
            AlertDialog(
                onDismissRequest = viewModel::consumeWebNovelMessage,
                text = { Text(stringResource(uiState.webNovelMessageRes!!, uiState.webNovelMessageArg)) },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.consumeWebNovelMessage()
                        viewModel.loadMoreWebChapters()
                    }) { Text(stringResource(R.string.reader_web_retry)) }
                },
                dismissButton = {
                    Row {
                        uiState.webNovelFailedUrl?.let { url ->
                            TextButton(onClick = { viewModel.consumeWebNovelMessage(); onOpenWebChapter(url) }) {
                                Text(stringResource(R.string.reader_web_open_source))
                            }
                        }
                        TextButton(onClick = viewModel::consumeWebNovelMessage) { Text(stringResource(R.string.action_cancel)) }
                    }
                }
            )
        }
        LaunchedEffect(uiState.webNovelMessageRes) {
            uiState.webNovelMessageRes?.let { messageRes ->
                if (webNovelError) return@let
                Toast.makeText(
                    context,
                    context.getString(messageRes, uiState.webNovelMessageArg),
                    Toast.LENGTH_SHORT
                ).show()
                viewModel.consumeWebNovelMessage()
            }
        }

        LaunchedEffect(uiState.convertMessageRes) {
            uiState.convertMessageRes?.let { messageRes ->
                Toast.makeText(context, context.getString(messageRes), Toast.LENGTH_LONG).show()
                viewModel.consumeConvertMessage()
            }
        }

        LaunchedEffect(uiState.convertSuggested) {
            if (uiState.convertSuggested) {
                Toast.makeText(context, context.getString(R.string.convert_suggestion), Toast.LENGTH_LONG).show()
                // Sự kiện một lần: tiêu thụ ngay để quay lại Reader từ màn hình khác không hiện lại.
                viewModel.consumeConvertSuggestion()
            }
        }

        uiState.replaceTextPattern?.let { pattern ->
            ReplaceTextBottomSheet(
                initialPattern = pattern,
                onDismiss = viewModel::dismissReplaceTextBottomSheet,
                onSaveRule = { targetPattern, replacement, isRegex ->
                    viewModel.saveReplaceRule(targetPattern, replacement, isRegex)
                    Toast.makeText(
                        context,
                        context.getString(R.string.replace_text_saved_toast),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            )
        }
        // Floating Mini Player Bar at bottom
        if (!uiState.showTtsPlayerScreen && uiState.ttsPlayerState !is TtsPlayerState.Idle) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = if (uiState.showControls) 64.dp else 12.dp)
            ) {
                TtsMiniPlayerBar(
                    playerState = uiState.ttsPlayerState,
                    onExpand = viewModel::openTtsPlayerScreen,
                    onPlayPause = { viewModel.toggleTtsPlayback(ttsService) },
                    onClose = { viewModel.stopTtsPlayback(ttsService) }
                )
            }
        }

        // Tts Setup Bottom Sheet
        if (uiState.showTtsSetupBottomSheet) {
            TtsSetupBottomSheet(
                currentSettings = uiState.ttsSettings,
                onGetAvailableVoices = { isAiVoice, language ->
                    ttsService?.getAvailableVoices(isAiVoice, language) ?: emptyList()
                },
                onDismiss = viewModel::dismissTtsSetupBottomSheet,
                onPreviewVoice = { draftSettings ->
                    ttsService?.speakPreview(
                        sampleText = if (draftSettings.language == "en") "Hello, this is the selected EpubPro voice." else "Xin ch\u00e0o, \u0111\u00e2y l\u00e0 gi\u1ecdng \u0111\u1ecdc th\u1eed nghi\u1ec7m c\u1ee7a \u1ee9ng d\u1ee5ng EpubPro.",
                        settings = draftSettings
                    )
                },
                onStartListening = { newSettings ->
                    viewModel.onStartListeningFromSetup(newSettings, ttsService)
                }
            )
        }

        // Fullscreen Tts Audio Player Dialog / Screen
        if (uiState.showTtsPlayerScreen && !uiState.showTtsSetupBottomSheet)
            Dialog(
                onDismissRequest = viewModel::closeTtsPlayerScreen,
                properties = DialogProperties(
                    usePlatformDefaultWidth = false
                )
            ) {
                TtsAudioPlayerScreen(
                    bookTitle = uiState.book?.title ?: "EpubPro Book",
                    author = uiState.book?.author ?: "Tác giả",
                    playerState = uiState.ttsPlayerState,
                    settings = uiState.ttsSettings,
                    selectedSleepTimer = uiState.selectedSleepTimer,
                    onCollapse = viewModel::closeTtsPlayerScreen,
                    onPlayPause = { viewModel.toggleTtsPlayback(ttsService) },
                    onSkipNext = { viewModel.nextTtsChunk(ttsService) },
                    onSkipPrevious = { viewModel.prevTtsChunk(ttsService) },
                    onOpenSetup = { viewModel.openTtsSetupBottomSheet() },
                    onSelectSleepTimer = { option ->
                        viewModel.setSleepTimerOption(option, ttsService)
                    },
                    onSeekToChunk = { index ->
                        viewModel.seekTtsChunk(index, ttsService)
                    }
                )
            }
    }
}

@Composable
fun ReaderSettingsContent(
    settings: ReaderSettings,
    onSettingsChanged: (ReaderSettings) -> Unit
) {
    var draftBrightness by remember(settings.brightness) { mutableFloatStateOf(settings.brightness) }
    var draftPageTurnSpeedMs by remember(settings.pageTurnSpeedMs) { mutableIntStateOf(settings.pageTurnSpeedMs) }
    var draftFontSizeSp by remember(settings.fontSizeSp) { mutableFloatStateOf(settings.fontSizeSp) }
    var draftMarginTopDp by remember(settings.marginTopDp) { mutableIntStateOf(settings.marginTopDp) }
    var draftMarginBottomDp by remember(settings.marginBottomDp) { mutableIntStateOf(settings.marginBottomDp) }
    var draftMarginLeftDp by remember(settings.marginLeftDp) { mutableIntStateOf(settings.marginLeftDp) }
    var draftMarginRightDp by remember(settings.marginRightDp) { mutableIntStateOf(settings.marginRightDp) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        Text(
            "Cấu hình đọc sách",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(20.dp))

        // --- SECTION 1: Chế độ đọc ---
        SettingSectionHeader(title = "Chế độ đọc", icon = Icons.Default.MenuBook)
        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.reader_horizontal_scroll),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    stringResource(R.string.reader_horizontal_scroll_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = settings.isHorizontalPagination,
                onCheckedChange = { isHorizontal ->
                    onSettingsChanged(
                        settings.copy(
                            isHorizontalPagination = isHorizontal,
                            readingMode = if (isHorizontal) {
                                com.epubpro.domain.model.ReadingMode.FLIP
                            } else {
                                com.epubpro.domain.model.ReadingMode.SCROLL
                            }
                        )
                    )
                }
            )
        }

        if (settings.isHorizontalPagination) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "Tốc độ trượt lật trang: $draftPageTurnSpeedMs ms",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Slider(
                value = draftPageTurnSpeedMs.toFloat(),
                onValueChange = { draftPageTurnSpeedMs = it.toInt() },
                onValueChangeFinished = {
                    onSettingsChanged(settings.copy(pageTurnSpeedMs = draftPageTurnSpeedMs))
                },
                valueRange = MIN_PAGE_TURN_SPEED_MS.toFloat()..MAX_PAGE_TURN_SPEED_MS.toFloat()
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PAGE_TURN_SPEED_PRESETS_MS.zip(listOf("Nhanh", "Vừa", "Chậm"))
                    .forEach { (speed, name) ->
                        FilterChip(
                            selected = draftPageTurnSpeedMs == speed,
                            onClick = {
                                draftPageTurnSpeedMs = speed
                                onSettingsChanged(settings.copy(pageTurnSpeedMs = speed))
                            },
                            label = { Text("$name (${speed}ms)", fontSize = 11.sp) },
                            modifier = Modifier.weight(1f)
                        )
                    }
            }
        }

        HorizontalDivider(
            modifier = Modifier.padding(vertical = 16.dp),
            thickness = DividerDefaults.Thickness,
            color = Color(0xFFE2E8F0)
        )

        // --- SECTION 2: Phông chữ & Kiểu chữ ---
        SettingSectionHeader(
            title = stringResource(R.string.reader_font_family),
            icon = Icons.Default.TextFields
        )
        Spacer(modifier = Modifier.height(10.dp))

        Text(
            stringResource(R.string.reader_font_family),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val fonts = listOf(
                "serif" to "Có chân",
                "sans-serif" to "Không chân",
                "monospace" to "Đơn cách"
            )
            fonts.forEach { (fontFamily, label) ->
                FontFamilyChip(
                    label = label,
                    isSelected = settings.fontFamily.equals(fontFamily, ignoreCase = true),
                    onClick = { onSettingsChanged(settings.copy(fontFamily = fontFamily)) }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        val formattedFontSize = if (draftFontSizeSp % 1f == 0f) {
            "${draftFontSizeSp.toInt()}"
        } else {
            "%.1f".format(java.util.Locale.US, draftFontSizeSp)
        }
        Text(
            stringResource(R.string.reader_font_size_format, formattedFontSize),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        Slider(
            value = draftFontSizeSp,
            onValueChange = { draftFontSizeSp = kotlin.math.round(it * 2f) / 2f },
            onValueChangeFinished = { onSettingsChanged(settings.copy(fontSizeSp = draftFontSizeSp)) },
            valueRange = 12f..32f,
            steps = 39
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            stringResource(R.string.reader_line_spacing),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(
                1.2f to "1.2x",
                1.5f to "1.5x",
                1.8f to "1.8x",
                2.0f to "2.0x"
            ).forEach { (ratio, label) ->
                FilterChip(
                    selected = settings.lineHeightRatio == ratio,
                    onClick = { onSettingsChanged(settings.copy(lineHeightRatio = ratio)) },
                    label = { Text(label) },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        HorizontalDivider(
            modifier = Modifier.padding(vertical = 16.dp),
            thickness = DividerDefaults.Thickness,
            color = Color(0xFFE2E8F0)
        )

        // --- SECTION 3: Phông nền & Màu sắc ---
        SettingSectionHeader(
            title = stringResource(R.string.profile_appearance_title),
            icon = Icons.Default.Palette
        )
        Spacer(modifier = Modifier.height(10.dp))

        // Độ sáng màn hình đọc sách
        val brightnessPercent = (draftBrightness * 100).toInt()
        val brightnessLabel = if (draftBrightness < EXTRA_DIM_THRESHOLD) {
            stringResource(R.string.reader_brightness_extra_dim_format, brightnessPercent)
        } else {
            stringResource(R.string.reader_brightness_percent_format, brightnessPercent)
        }
        Text(
            text = brightnessLabel,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = if (draftBrightness < EXTRA_DIM_THRESHOLD) Icons.Default.Nightlight else Icons.Default.BrightnessLow,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Slider(
                value = draftBrightness,
                onValueChange = { draftBrightness = it },
                onValueChangeFinished = {
                    onSettingsChanged(settings.copy(brightness = draftBrightness))
                },
                valueRange = 0.0f..1.0f,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Default.BrightnessHigh,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ThemeChip(
                "Sáng",
                Color.White,
                Color.Black,
                settings.themeMode == ReaderThemeMode.LIGHT
            ) {
                onSettingsChanged(settings.copy(themeMode = ReaderThemeMode.LIGHT))
            }
            ThemeChip(
                "Tối",
                Color(0xFF1E293B),
                Color.White,
                settings.themeMode == ReaderThemeMode.DARK
            ) {
                onSettingsChanged(settings.copy(themeMode = ReaderThemeMode.DARK))
            }
            ThemeChip(
                "Sepia",
                Color(0xFFFBF0D9),
                Color(0xFF4A3B32),
                settings.themeMode == ReaderThemeMode.SEPIA
            ) {
                onSettingsChanged(settings.copy(themeMode = ReaderThemeMode.SEPIA))
            }
            ThemeChip(
                "Giấy",
                Color(0xFFF5F0E8),
                Color(0xFF3C3530),
                settings.themeMode == ReaderThemeMode.PAPER
            ) {
                onSettingsChanged(settings.copy(themeMode = ReaderThemeMode.PAPER))
            }
            ThemeChip(
                "Đêm",
                Color(0xFF0F172A),
                Color(0xFF94A3B8),
                settings.themeMode == ReaderThemeMode.MIDNIGHT
            ) {
                onSettingsChanged(settings.copy(themeMode = ReaderThemeMode.MIDNIGHT))
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            stringResource(R.string.reader_page_margin),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.reader_margin_top_format, draftMarginTopDp),
                    style = MaterialTheme.typography.bodySmall
                )
                Slider(
                    value = draftMarginTopDp.toFloat(),
                    onValueChange = { draftMarginTopDp = it.toInt() },
                    onValueChangeFinished = { onSettingsChanged(settings.copy(marginTopDp = draftMarginTopDp)) },
                    valueRange = 0f..64f
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.reader_margin_bottom_format, draftMarginBottomDp),
                    style = MaterialTheme.typography.bodySmall
                )
                Slider(
                    value = draftMarginBottomDp.toFloat(),
                    onValueChange = { draftMarginBottomDp = it.toInt() },
                    onValueChangeFinished = { onSettingsChanged(settings.copy(marginBottomDp = draftMarginBottomDp)) },
                    valueRange = 0f..64f
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.reader_margin_left_format, draftMarginLeftDp),
                    style = MaterialTheme.typography.bodySmall
                )
                Slider(
                    value = draftMarginLeftDp.toFloat(),
                    onValueChange = { draftMarginLeftDp = it.toInt() },
                    onValueChangeFinished = { onSettingsChanged(settings.copy(marginLeftDp = draftMarginLeftDp)) },
                    valueRange = 0f..64f
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.reader_margin_right_format, draftMarginRightDp),
                    style = MaterialTheme.typography.bodySmall
                )
                Slider(
                    value = draftMarginRightDp.toFloat(),
                    onValueChange = { draftMarginRightDp = it.toInt() },
                    onValueChangeFinished = { onSettingsChanged(settings.copy(marginRightDp = draftMarginRightDp)) },
                    valueRange = 0f..64f
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
fun SettingSectionHeader(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun RowScope.FontFamilyChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    FilterChip(
        selected = isSelected,
        onClick = onClick,
        label = { Text(label, fontSize = 11.sp) },
        modifier = Modifier.weight(1f)
    )
}

@Composable
fun RowScope.ThemeChip(
    label: String,
    bg: Color,
    fg: Color,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .weight(1f)
            .height(48.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = bg,
        border = BorderStroke(
            width = if (isSelected) 2.dp else 1.dp,
            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                color = fg,
                fontSize = 12.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
            )
        }
    }
}
