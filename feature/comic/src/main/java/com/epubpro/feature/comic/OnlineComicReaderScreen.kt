package com.epubpro.feature.comic

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ViewCarousel
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.epubpro.core.comic.ComicPageImage
import com.epubpro.core.designsystem.R
import com.epubpro.domain.model.ComicReadingMode
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Màn hình đọc truyện tranh trực tuyến (NetTruyen và các nguồn webtoon tương thích).
 *
 * Hỗ trợ cuộn dọc liên tục, lật trang ngang LTR/RTL, chuyển chương trước/sau,
 * chạm giữa màn hình để ẩn/hiện thanh điều khiển và lưu lại tiến độ đọc khi thoát.
 *
 * @param onNavigateBack Callback quay lại màn hình trước.
 * @param onOpenInBrowser Callback mở trang web gốc trong trình duyệt khi nguồn yêu cầu xác minh bảo vệ.
 * @param viewModel ViewModel quản lý trạng thái đọc trực tuyến.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OnlineComicReaderScreen(
    onNavigateBack: () -> Unit,
    onOpenInBrowser: (String) -> Unit = {},
    viewModel: OnlineComicReaderViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    OnlineReaderWindowEffects(
        keepScreenOn = uiState.keepScreenOn,
        showSystemBars = uiState.showControls || uiState.isLoading || uiState.errorRes != null
    )
    OnlineProgressFlushEffect(onFlush = viewModel::flushProgress)

    BackHandler {
        onNavigateBack()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        when {
            uiState.isLoading -> {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    CircularProgressIndicator(color = Color.White)
                    Text(
                        text = stringResource(R.string.online_comic_loading_chapter),
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            uiState.errorRes != null -> {
                OnlineComicReaderError(
                    messageRes = checkNotNull(uiState.errorRes),
                    chapterUrl = uiState.chapterInfo?.chapterUrl,
                    onNavigateBack = onNavigateBack,
                    onRetry = viewModel::retryCurrentChapter,
                    onOpenInBrowser = onOpenInBrowser,
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            uiState.chapterInfo != null -> {
                OnlineComicReaderContent(
                    uiState = uiState,
                    viewModel = viewModel,
                    onNavigateBack = onNavigateBack,
                    onOpenInBrowser = onOpenInBrowser
                )
            }
        }

        if (uiState.isChangingChapter) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
            )
        }
    }

    LaunchedEffect(uiState.messageRes) {
        uiState.messageRes?.let { resId ->
            Toast.makeText(context, context.getString(resId), Toast.LENGTH_SHORT).show()
            viewModel.consumeMessage()
        }
    }
}

/**
 * Nội dung hiển thị các trang ảnh khi chương đã được nạp thành công.
 *
 * @param uiState Trạng thái giao diện.
 * @param viewModel ViewModel điều khiển.
 * @param onNavigateBack Callback khi bấm nút quay lại.
 * @param onOpenInBrowser Callback mở trình duyệt ngoài.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun OnlineComicReaderContent(
    uiState: OnlineComicReaderUiState,
    viewModel: OnlineComicReaderViewModel,
    onNavigateBack: () -> Unit,
    onOpenInBrowser: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    val chapter = checkNotNull(uiState.chapterInfo)
    val pageCount = chapter.pages.size
    val pageLoader: suspend (Int, Int) -> ComicPageImage = viewModel::loadPage

    key(uiState.mode, chapter.chapterUrl, uiState.selectedServer?.id) {
        val pagerState = rememberPagerState(initialPage = uiState.currentPage) { pageCount }
        val listState = rememberLazyListState(initialFirstVisibleItemIndex = uiState.currentPage)
        val navigator = remember(uiState.mode, pagerState, listState) {
            OnlinePageNavigator(uiState.mode, pagerState, listState)
        }

        LaunchedEffect(navigator) {
            snapshotFlow { navigator.visiblePage() }
                .distinctUntilChanged()
                .collect(viewModel::onPageChanged)
        }

        val onTapAction: (ComicTapAction) -> Unit = { action ->
            when (action) {
                ComicTapAction.PREVIOUS -> scope.launch { navigator.step(-1) }
                ComicTapAction.NEXT -> scope.launch { navigator.step(1) }
                ComicTapAction.TOGGLE_CONTROLS -> viewModel.toggleControls()
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(focusRequester)
                .focusable()
                .onPreviewKeyEvent { event ->
                    if (!uiState.volumeKeyNavigation) return@onPreviewKeyEvent false
                    val direction = when (event.key.nativeKeyCode) {
                        KeyEvent.KEYCODE_VOLUME_DOWN -> 1
                        KeyEvent.KEYCODE_VOLUME_UP -> -1
                        else -> return@onPreviewKeyEvent false
                    }
                    if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                        scope.launch { navigator.step(direction) }
                    }
                    true
                }
        ) {
            if (uiState.mode == ComicReadingMode.WEBTOON) {
                WebtoonPages(
                    pageCount = pageCount,
                    listState = listState,
                    pageLoader = pageLoader,
                    onTap = onTapAction
                )
            } else {
                PagedPages(
                    pagerState = pagerState,
                    mode = uiState.mode,
                    pageLoader = pageLoader,
                    onTap = onTapAction
                )
            }

            OnlineComicReaderControls(
                uiState = uiState,
                onNavigateBack = onNavigateBack,
                onModeSelected = viewModel::setReadingMode,
                onSeek = { page -> scope.launch { navigator.jumpTo(page) } },
                onPreviousChapter = viewModel::navigateToPreviousChapter,
                onNextChapter = viewModel::navigateToNextChapter,
                onServerSelected = viewModel::selectImageServer,
                onOpenInBrowser = { onOpenInBrowser(chapter.chapterUrl) }
            )
        }

        LaunchedEffect(focusRequester, uiState.showControls) {
            runCatching { focusRequester.requestFocus() }
        }
    }
}

/**
 * Điều hướng trang dùng chung cho cả chế độ cuộn dọc và lật trang ngang trực tuyến.
 *
 * @property mode Chế độ đọc hiện tại.
 * @property pagerState State của HorizontalPager.
 * @property listState State của LazyColumn.
 */
@OptIn(ExperimentalFoundationApi::class)
private class OnlinePageNavigator(
    private val mode: ComicReadingMode,
    private val pagerState: PagerState,
    private val listState: LazyListState
) {
    fun visiblePage(): Int =
        if (mode == ComicReadingMode.WEBTOON) listState.firstVisibleItemIndex else pagerState.currentPage

    suspend fun step(direction: Int) {
        if (mode == ComicReadingMode.WEBTOON) {
            val viewport = listState.layoutInfo.viewportSize.height
            listState.animateScrollBy(direction * viewport * WEBTOON_STEP_FRACTION)
        } else {
            val target = (pagerState.currentPage + direction).coerceIn(0, (pagerState.pageCount - 1).coerceAtLeast(0))
            if (target != pagerState.currentPage) pagerState.animateScrollToPage(target)
        }
    }

    suspend fun jumpTo(page: Int) {
        if (mode == ComicReadingMode.WEBTOON) listState.scrollToItem(page) else pagerState.scrollToPage(page)
    }

    private companion object {
        const val WEBTOON_STEP_FRACTION = 0.8f
    }
}

/**
 * Thanh điều khiển phía trên và thanh điều hướng chương/trang phía dưới.
 *
 * @param uiState Trạng thái hiện tại.
 * @param onNavigateBack Callback khi nhấn nút back.
 * @param onModeSelected Callback khi chọn chế độ đọc mới.
 * @param onSeek Callback khi kéo thanh trượt trang.
 * @param onPreviousChapter Callback chuyển chương trước.
 * @param onNextChapter Callback chuyển chương sau.
 * @param onServerSelected Callback chọn server ảnh.
 * @param onOpenInBrowser Callback mở trang gốc.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OnlineComicReaderControls(
    uiState: OnlineComicReaderUiState,
    onNavigateBack: () -> Unit,
    onModeSelected: (ComicReadingMode) -> Unit,
    onSeek: (Int) -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onServerSelected: (com.epubpro.core.comic.ComicImageServer) -> Unit,
    onOpenInBrowser: () -> Unit
) {
    val barColor = Color.Black.copy(alpha = 0.85f)
    val chapter = uiState.chapterInfo ?: return
    val pageCount = chapter.pages.size

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = uiState.showControls,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            var showModeMenu by remember { mutableStateOf(false) }
            TopAppBar(
                title = {
                    Text(
                        text = chapter.title.ifBlank { stringResource(R.string.online_comic_reader_title) },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                },
                actions = {
                    val imageServers = chapter.availableServers.filter {
                        chapter.pagesByServer[it.id]?.size == pageCount
                    }
                    var showServerMenu by remember(chapter.chapterUrl) { mutableStateOf(false) }
                    if (imageServers.size > 1) {
                        val selectServerDescription = stringResource(R.string.online_comic_select_server)
                        val selectedServer = uiState.selectedServer ?: imageServers.first()
                        val selectedServerIndex = imageServers.indexOf(selectedServer) + 1
                        val selectedServerLabel = selectedServer.label.ifBlank {
                            stringResource(R.string.online_comic_server_number, selectedServerIndex)
                        }
                        Box {
                            TextButton(
                                onClick = { showServerMenu = true },
                                modifier = Modifier.semantics { contentDescription = selectServerDescription }
                            ) {
                                Text(selectedServerLabel)
                            }
                            DropdownMenu(expanded = showServerMenu, onDismissRequest = { showServerMenu = false }) {
                                imageServers.forEachIndexed { index, server ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(server.label.ifBlank {
                                                stringResource(R.string.online_comic_server_number, index + 1)
                                            })
                                        },
                                        onClick = {
                                            showServerMenu = false
                                            onServerSelected(server)
                                        }
                                    )
                                }
                            }
                        }
                    }
                    IconButton(onClick = onOpenInBrowser) {
                        Icon(
                            Icons.Default.OpenInBrowser,
                            contentDescription = stringResource(R.string.online_comic_open_source)
                        )
                    }
                    Box {
                        IconButton(onClick = { showModeMenu = true }) {
                            Icon(
                                Icons.Default.ViewCarousel,
                                contentDescription = stringResource(R.string.comic_reader_mode_menu)
                            )
                        }
                        DropdownMenu(expanded = showModeMenu, onDismissRequest = { showModeMenu = false }) {
                            ComicReadingMode.entries.forEach { mode ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(mode.labelRes())) },
                                    onClick = {
                                        showModeMenu = false
                                        onModeSelected(mode)
                                    },
                                    leadingIcon = {
                                        if (mode == uiState.mode) Icon(Icons.Default.Check, contentDescription = null)
                                    }
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = barColor,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White
                )
            )
        }

        AnimatedVisibility(
            visible = uiState.showControls,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(barColor)
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OnlineComicSeekBar(
                    currentPage = uiState.currentPage,
                    pageCount = pageCount,
                    reversed = uiState.mode == ComicReadingMode.RIGHT_TO_LEFT,
                    onSeek = onSeek,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = onPreviousChapter,
                        enabled = chapter.previousChapterUrl != null && !uiState.isChangingChapter
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                        Text(
                            text = stringResource(R.string.online_comic_chapter_previous),
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }

                    OutlinedButton(
                        onClick = onNextChapter,
                        enabled = chapter.nextChapterUrl != null && !uiState.isChangingChapter
                    ) {
                        Text(
                            text = stringResource(R.string.online_comic_chapter_next),
                            modifier = Modifier.padding(end = 4.dp)
                        )
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                    }
                }
            }
        }
    }
}

/**
 * Thanh trượt trang kèm chỉ số trang hiện tại.
 *
 * @param currentPage Trang hiện tại 0-based.
 * @param pageCount Tổng số trang.
 * @param reversed true khi đọc phải sang trái.
 * @param onSeek Callback khi thả thanh trượt tại trang mới.
 * @param modifier Modifier tùy biến.
 */
@Composable
private fun OnlineComicSeekBar(
    currentPage: Int,
    pageCount: Int,
    reversed: Boolean,
    onSeek: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var dragValue by remember { mutableStateOf<Float?>(null) }
    val maxIndex = (pageCount - 1).coerceAtLeast(0)
    val shownPage = dragValue?.toInt() ?: currentPage
    val currentOnSeek by rememberUpdatedState(onSeek)
    val seekDescription = stringResource(R.string.comic_reader_seek)

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(R.string.comic_reader_page_indicator, shownPage + 1, pageCount),
            color = Color.White,
            style = MaterialTheme.typography.labelLarge
        )
        if (maxIndex > 0) {
            val sliderValue = (dragValue ?: currentPage.toFloat()).let { if (reversed) maxIndex - it else it }
            Slider(
                value = sliderValue,
                onValueChange = { raw -> dragValue = if (reversed) maxIndex - raw else raw },
                onValueChangeFinished = {
                    dragValue?.let { currentOnSeek(it.toInt().coerceIn(0, maxIndex)) }
                    dragValue = null
                },
                valueRange = 0f..maxIndex.toFloat(),
                modifier = Modifier
                    .weight(1f)
                    .semantics { contentDescription = seekDescription }
            )
        }
    }
}

/**
 * Màn hình thông báo lỗi khi không thể tải hoặc phân tích chương trực tuyến.
 *
 * @param messageRes Thông báo lỗi dạng string resource.
 * @param chapterUrl URL chương đang lỗi.
 * @param onNavigateBack Callback quay lại.
 * @param onRetry Callback thử lại.
 * @param onOpenInBrowser Callback mở trang web gốc.
 * @param modifier Modifier vị trí.
 */
@Composable
private fun OnlineComicReaderError(
    messageRes: Int,
    chapterUrl: String?,
    onNavigateBack: () -> Unit,
    onRetry: () -> Unit,
    onOpenInBrowser: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = stringResource(messageRes),
            color = Color.White,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onRetry) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Text(text = stringResource(R.string.comic_reader_retry), modifier = Modifier.padding(start = 4.dp))
            }
            if (!chapterUrl.isNullOrBlank()) {
                OutlinedButton(onClick = { onOpenInBrowser(chapterUrl) }) {
                    Icon(Icons.Default.OpenInBrowser, contentDescription = null)
                    Text(text = stringResource(R.string.online_comic_open_source), modifier = Modifier.padding(start = 4.dp))
                }
            }
        }
        OutlinedButton(onClick = onNavigateBack) {
            Text(text = stringResource(R.string.action_back))
        }
    }
}

/**
 * Quản lý chế độ giữ sáng màn hình và ẩn hiện thanh hệ thống.
 *
 * @param keepScreenOn Cờ giữ màn hình sáng.
 * @param showSystemBars Cờ hiện thanh trạng thái và thanh điều hướng.
 */
@Composable
private fun OnlineReaderWindowEffects(keepScreenOn: Boolean, showSystemBars: Boolean) {
    val view = LocalView.current
    val window = LocalContext.current.findActivity()?.window ?: return

    DisposableEffect(window, keepScreenOn) {
        if (keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    DisposableEffect(window, view, showSystemBars) {
        val controller = WindowCompat.getInsetsController(window, view)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (showSystemBars) {
            controller.show(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

/**
 * Kích hoạt lưu tiến độ khi màn hình dừng hoạt động.
 *
 * @param onFlush Callback ghi tiến độ.
 */
@Composable
private fun OnlineProgressFlushEffect(onFlush: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnFlush by rememberUpdatedState(onFlush)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) currentOnFlush()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            currentOnFlush()
        }
    }
}

private fun ComicReadingMode.labelRes(): Int = when (this) {
    ComicReadingMode.LEFT_TO_RIGHT -> R.string.comic_reading_mode_ltr
    ComicReadingMode.RIGHT_TO_LEFT -> R.string.comic_reading_mode_rtl
    ComicReadingMode.WEBTOON -> R.string.comic_reading_mode_webtoon
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
