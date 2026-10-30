package com.epubpro.feature.comic

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.ViewCarousel
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
 * Màn hình đọc truyện tranh CBZ với ba chế độ: trái sang phải, phải sang trái (manga) và cuộn dọc (webtoon).
 *
 * @param onNavigateBack Callback quay lại màn hình trước.
 * @param viewModel ViewModel quản lý archive và tiến độ đọc.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ComicReaderScreen(
    onNavigateBack: () -> Unit,
    viewModel: ComicReaderViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    ComicReaderWindowEffects(
        keepScreenOn = uiState.keepScreenOn,
        showSystemBars = uiState.showControls || uiState.isLoading || uiState.errorRes != null
    )
    ComicProgressFlushEffect(onFlush = viewModel::flushProgress)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        when {
            uiState.isLoading -> CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = Color.White
            )
            uiState.errorRes != null -> ComicReaderError(
                messageRes = checkNotNull(uiState.errorRes),
                onNavigateBack = onNavigateBack,
                modifier = Modifier.align(Alignment.Center)
            )
            else -> ComicReaderContent(
                uiState = uiState,
                viewModel = viewModel,
                onNavigateBack = onNavigateBack
            )
        }
    }

    ComicTranslationDialogs(uiState = uiState, viewModel = viewModel)
}

/**
 * Bảng dịch, hộp thoại tải gói dịch, chi tiết bóng thoại và thông báo của tính năng dịch truyện tranh.
 *
 * @param uiState Trạng thái hiện tại.
 * @param viewModel ViewModel nhận sự kiện.
 */
@Composable
private fun ComicTranslationDialogs(uiState: ComicReaderUiState, viewModel: ComicReaderViewModel) {
    val context = LocalContext.current
    if (uiState.showTranslateSheet) {
        ComicTranslateSheet(
            settings = uiState.translation,
            currentTranslation = uiState.pageTranslations[uiState.currentPage],
            onSettingsChange = viewModel::updateTranslationSettings,
            onTranslate = { viewModel.translatePage(force = true) },
            onDismiss = viewModel::dismissTranslateSheet
        )
    }
    uiState.pendingModelDownload?.let { language ->
        ModelDownloadDialog(
            language = language,
            downloading = uiState.isDownloadingModel,
            onDownload = viewModel::confirmModelDownload,
            onDismiss = viewModel::cancelModelDownload
        )
    }
    uiState.selectedBubble?.let { bubble ->
        BubbleDetailDialog(bubble = bubble, onDismiss = { viewModel.selectBubble(null) })
    }
    LaunchedEffect(uiState.messageRes) {
        uiState.messageRes?.let { messageRes ->
            Toast.makeText(context, context.getString(messageRes), Toast.LENGTH_LONG).show()
            viewModel.consumeMessage()
        }
    }
}

/**
 * Nội dung Reader sau khi archive đã mở: vùng trang, thanh điều khiển và điều hướng bằng phím.
 *
 * @param uiState Trạng thái hiện tại.
 * @param viewModel ViewModel cung cấp ảnh trang và nhận sự kiện.
 * @param onNavigateBack Callback quay lại.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ComicReaderContent(
    uiState: ComicReaderUiState,
    viewModel: ComicReaderViewModel,
    onNavigateBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    val pageLoader: suspend (Int, Int) -> ComicPageImage = viewModel::loadPage

    // Mỗi lần đổi chế độ tạo lại state cuộn, bắt đầu từ trang đang đọc.
    key(uiState.mode) {
        val pagerState = rememberPagerState(initialPage = uiState.currentPage) { uiState.pageCount }
        val listState = rememberLazyListState(initialFirstVisibleItemIndex = uiState.currentPage)
        val navigator = remember(uiState.mode, pagerState, listState) {
            ComicPageNavigator(uiState.mode, pagerState, listState)
        }

        LaunchedEffect(navigator) {
            snapshotFlow { navigator.visiblePage() }
                .distinctUntilChanged()
                .collect(viewModel::onPageChanged)
        }

        val translations = uiState.pageTranslations
        val showOverlay = uiState.translation.showOverlay
        val overlay: ComicPageOverlay = { pageIndex ->
            if (showOverlay) TranslationOverlay(translations[pageIndex], viewModel::selectBubble)
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
                    // Tiêu thụ cả ACTION_UP để hệ thống không đổi âm lượng.
                    if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                        scope.launch { navigator.step(direction) }
                    }
                    true
                }
        ) {
            if (uiState.mode == ComicReadingMode.WEBTOON) {
                WebtoonPages(
                    pageCount = uiState.pageCount,
                    listState = listState,
                    pageLoader = pageLoader,
                    onTap = onTapAction,
                    overlay = overlay
                )
            } else {
                PagedPages(
                    pagerState = pagerState,
                    mode = uiState.mode,
                    pageLoader = pageLoader,
                    onTap = onTapAction,
                    overlay = overlay
                )
            }

            ComicReaderControls(
                uiState = uiState,
                onNavigateBack = onNavigateBack,
                onModeSelected = viewModel::setReadingMode,
                onSeek = { page -> scope.launch { navigator.jumpTo(page) } },
                onTranslate = viewModel::openTranslateSheet
            )
        }

        // Lấy lại focus sau khi đổi chế độ hoặc đóng menu để phím âm lượng tiếp tục lật trang.
        LaunchedEffect(focusRequester, uiState.showControls) {
            runCatching { focusRequester.requestFocus() }
        }
    }
}

/**
 * Điều hướng trang dùng chung cho chế độ lật trang và cuộn dọc.
 *
 * @property mode Chế độ đọc hiện tại.
 * @property pagerState State của pager (chế độ lật trang).
 * @property listState State của danh sách (chế độ webtoon).
 */
@OptIn(ExperimentalFoundationApi::class)
private class ComicPageNavigator(
    private val mode: ComicReadingMode,
    private val pagerState: PagerState,
    private val listState: LazyListState
) {
    /**
     * Trang đang hiển thị; đọc trong snapshotFlow để theo dõi thay đổi.
     *
     * @return Chỉ số trang 0-based.
     */
    fun visiblePage(): Int =
        if (mode == ComicReadingMode.WEBTOON) listState.firstVisibleItemIndex else pagerState.currentPage

    /**
     * Lùi/tiến theo hướng chỉ định. Chế độ webtoon cuộn 80% chiều cao màn hình thay vì nhảy cả trang.
     *
     * @param direction -1 để lùi, 1 để tiến.
     */
    suspend fun step(direction: Int) {
        if (mode == ComicReadingMode.WEBTOON) {
            val viewport = listState.layoutInfo.viewportSize.height
            listState.animateScrollBy(direction * viewport * WEBTOON_STEP_FRACTION)
        } else {
            val target = (pagerState.currentPage + direction).coerceIn(0, (pagerState.pageCount - 1).coerceAtLeast(0))
            if (target != pagerState.currentPage) pagerState.animateScrollToPage(target)
        }
    }

    /**
     * Nhảy thẳng tới một trang (từ thanh trượt).
     *
     * @param page Trang đích 0-based.
     */
    suspend fun jumpTo(page: Int) {
        if (mode == ComicReadingMode.WEBTOON) listState.scrollToItem(page) else pagerState.scrollToPage(page)
    }

    private companion object {
        const val WEBTOON_STEP_FRACTION = 0.8f
    }
}

/**
 * Thanh điều khiển trên (quay lại, tên truyện, chế độ đọc) và dưới (số trang, thanh trượt).
 *
 * @param uiState Trạng thái hiện tại.
 * @param onNavigateBack Callback quay lại.
 * @param onModeSelected Callback khi chọn chế độ đọc.
 * @param onSeek Callback nhảy tới trang đã chọn trên thanh trượt.
 * @param onTranslate Callback mở bảng dịch truyện tranh.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ComicReaderControls(
    uiState: ComicReaderUiState,
    onNavigateBack: () -> Unit,
    onModeSelected: (ComicReadingMode) -> Unit,
    onSeek: (Int) -> Unit,
    onTranslate: () -> Unit
) {
    val barColor = Color.Black.copy(alpha = 0.75f)
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
                    Text(text = uiState.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = onTranslate) {
                        Icon(Icons.Default.Translate, contentDescription = stringResource(R.string.comic_translate_title))
                    }
                    Box {
                        IconButton(onClick = { showModeMenu = true }) {
                            Icon(Icons.Default.ViewCarousel, contentDescription = stringResource(R.string.comic_reader_mode_menu))
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
            ComicSeekBar(
                currentPage = uiState.currentPage,
                pageCount = uiState.pageCount,
                reversed = uiState.mode == ComicReadingMode.RIGHT_TO_LEFT,
                onSeek = onSeek,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(barColor)
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
    }
}

/**
 * Thanh trượt chọn trang kèm chỉ số trang hiện tại.
 *
 * @param currentPage Trang hiện tại 0-based.
 * @param pageCount Tổng số trang.
 * @param reversed true khi đọc phải sang trái, để kéo sang trái là tiến tới.
 * @param onSeek Callback khi thả thanh trượt tại trang mới.
 * @param modifier Modifier của thanh.
 */
@Composable
private fun ComicSeekBar(
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
 * Màn hình lỗi khi không mở được truyện.
 *
 * @param messageRes Thông báo lỗi.
 * @param onNavigateBack Callback quay lại.
 * @param modifier Modifier vị trí.
 */
@Composable
private fun ComicReaderError(messageRes: Int, onNavigateBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(text = stringResource(messageRes), color = Color.White, style = MaterialTheme.typography.bodyLarge)
        Button(onClick = onNavigateBack) {
            Text(text = stringResource(R.string.action_back))
        }
    }
}

/**
 * Áp dụng giữ màn hình sáng và ẩn/hiện thanh hệ thống theo trạng thái Reader; khôi phục khi rời màn hình.
 *
 * @param keepScreenOn Giữ màn hình sáng.
 * @param showSystemBars Hiện thanh trạng thái/điều hướng.
 */
@Composable
private fun ComicReaderWindowEffects(keepScreenOn: Boolean, showSystemBars: Boolean) {
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
 * Ghi tiến độ ngay khi app vào nền hoặc khi màn hình bị hủy.
 *
 * @param onFlush Callback ghi tiến độ.
 */
@Composable
private fun ComicProgressFlushEffect(onFlush: () -> Unit) {
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

/**
 * Lấy nhãn string resource của chế độ đọc.
 *
 * @receiver Chế độ đọc.
 * @return ID string resource.
 */
private fun ComicReadingMode.labelRes(): Int = when (this) {
    ComicReadingMode.LEFT_TO_RIGHT -> R.string.comic_reading_mode_ltr
    ComicReadingMode.RIGHT_TO_LEFT -> R.string.comic_reading_mode_rtl
    ComicReadingMode.WEBTOON -> R.string.comic_reading_mode_webtoon
}

/**
 * Tìm Activity chứa Context hiện tại.
 *
 * @receiver Context của Compose.
 * @return Activity hoặc null nếu không tìm thấy.
 */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
