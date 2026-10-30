package com.epubpro.feature.browser

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.epubpro.core.designsystem.R
import com.epubpro.core.storage.worker.WebNovelDownloadState
import com.epubpro.core.webnovel.WebArticle
import org.json.JSONArray

private const val READ_HTML_SCRIPT = "document.documentElement.outerHTML"

/**
 * Trình duyệt tích hợp: duyệt trang truyện, bật chế độ đọc sạch quảng cáo và lưu nhiều chương thành EPUB.
 *
 * @param onNavigateBack Callback rời trình duyệt khi WebView không còn lịch sử để lùi.
 * @param onOpenBook Callback mở sách (ID) trong Reader EPUB sau khi chuẩn bị xong.
 * @param viewModel ViewModel trình duyệt.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    onNavigateBack: () -> Unit,
    onOpenBook: (String) -> Unit = {},
    viewModel: BrowserViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    val currentReaderMode by rememberUpdatedState(uiState.readerMode)

    val webView = remember {
        createSafeWebView(
            context = context,
            onPageStarted = { url ->
                viewModel.onPageStarted(url)
            },
            onPageFinished = { view, url ->
                canGoBack = view.canGoBack()
                canGoForward = view.canGoForward()
                if (currentReaderMode) requestPageHtml(view, url, viewModel::onPageHtml)
            },
            onProgress = viewModel::onPageProgress
        )
    }
    DisposableEffect(webView) {
        onDispose {
            webView.stopLoading()
            webView.destroy()
        }
    }

    LaunchedEffect(uiState.loadRequestId) {
        uiState.loadRequest?.let { webView.loadUrl(it) }
    }
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { messageRes ->
            snackbarHostState.showSnackbar(context.getString(messageRes))
        }
    }
    val currentOnOpenBook by rememberUpdatedState(onOpenBook)
    LaunchedEffect(viewModel) {
        viewModel.openBookEvents.collect { bookId -> currentOnOpenBook(bookId) }
    }
    val openInReader = { viewModel.openInReader(webView.settings.userAgentString) }

    BackHandler {
        when {
            uiState.readerMode -> viewModel.toggleReaderMode()
            webView.canGoBack() -> webView.goBack()
            else -> onNavigateBack()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
                    },
                    title = {
                        OutlinedTextField(
                            value = uiState.addressText,
                            onValueChange = viewModel::onAddressChanged,
                            singleLine = true,
                            placeholder = { Text(stringResource(R.string.browser_address_hint)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = {
                                focusManager.clearFocus()
                                viewModel.openAddress(uiState.addressText)
                            }),
                            textStyle = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(end = 4.dp)
                        )
                    },
                    actions = {
                        IconButton(
                            onClick = openInReader,
                            enabled = isAllowedBrowserUrl(uiState.currentUrl)
                        ) {
                            Icon(
                                Icons.Default.AutoStories,
                                contentDescription = stringResource(R.string.browser_open_in_reader),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        IconButton(onClick = {
                            if (viewModel.toggleReaderMode()) {
                                requestPageHtml(webView, webView.url.orEmpty(), viewModel::onPageHtml)
                            }
                        }) {
                            Icon(
                                Icons.AutoMirrored.Filled.MenuBook,
                                contentDescription = stringResource(R.string.browser_reader_mode),
                                tint = if (uiState.readerMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Box {
                            IconButton(onClick = { showMenu = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.browser_more))
                            }
                            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.browser_web_back)) },
                                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) },
                                    enabled = canGoBack,
                                    onClick = {
                                        showMenu = false
                                        webView.goBack()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.browser_web_forward)) },
                                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null) },
                                    enabled = canGoForward,
                                    onClick = {
                                        showMenu = false
                                        webView.goForward()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.browser_reload)) },
                                    leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                                    onClick = {
                                        showMenu = false
                                        webView.reload()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.browser_save_novel)) },
                                    enabled = isAllowedBrowserUrl(uiState.currentUrl),
                                    onClick = {
                                        showMenu = false
                                        viewModel.setSaveDialogVisible(true)
                                    }
                                )
                            }
                        }
                    }
                )
                if (uiState.progress in 1..99) {
                    LinearProgressIndicator(
                        progress = { uiState.progress / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
            if (uiState.readerMode) {
                ReaderModeContent(
                    uiState = uiState,
                    onOpenLink = { url -> webView.loadUrl(url) },
                    onOpenInReader = openInReader
                )
            }
        }
    }

    if (uiState.showSaveDialog) {
        SaveNovelDialog(
            initialTitle = uiState.article?.novelTitle ?: uiState.pageTitle,
            onDismiss = { viewModel.setSaveDialogVisible(false) },
            onConfirm = { title, count -> viewModel.saveAsNovel(title, count, webView.settings.userAgentString) }
        )
    }

    val preparation = uiState.readerPreparation
    if (preparation != null && uiState.showPreparationDialog) {
        ReaderPreparationDialog(
            preparation = preparation,
            onBackground = viewModel::continuePreparationInBackground,
            onCancel = viewModel::cancelPreparation
        )
    }
}

/**
 * Hộp thoại tiến độ tải nhanh các chương đầu trước khi mở bằng Reader.
 *
 * @param preparation Tiến độ hiện tại.
 * @param onBackground Callback ẩn hộp thoại, tiếp tục chạy nền.
 * @param onCancel Callback hủy tải.
 */
@Composable
private fun ReaderPreparationDialog(
    preparation: WebNovelDownloadState.Running,
    onBackground: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onBackground,
        title = { Text(stringResource(R.string.browser_reader_preparing_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(
                        R.string.browser_reader_preparing_progress,
                        preparation.downloaded,
                        preparation.max.coerceAtLeast(1)
                    )
                )
                LinearProgressIndicator(
                    progress = { preparation.downloaded.toFloat() / preparation.max.coerceAtLeast(1) },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    stringResource(R.string.browser_reader_preparing_note),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onBackground) { Text(stringResource(R.string.action_background)) }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

/**
 * Lớp phủ chế độ đọc: hiển thị nội dung đã bóc tách và nút chuyển chương.
 *
 * @param uiState Trạng thái trình duyệt.
 * @param onOpenLink Callback nạp liên kết chương trong WebView (nội dung mới sẽ tự bóc tách).
 * @param onOpenInReader Callback mở truyện bằng Reader EPUB.
 */
@Composable
private fun ReaderModeContent(uiState: BrowserUiState, onOpenLink: (String) -> Unit, onOpenInReader: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        val article = uiState.article
        when {
            uiState.isExtracting || (article == null && !uiState.extractFailed) -> Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator()
                Text(
                    text = stringResource(R.string.browser_extracting),
                    modifier = Modifier.padding(top = 12.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            article == null -> Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.browser_extract_failed),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
            else -> ArticleContent(article = article, onOpenLink = onOpenLink, onOpenInReader = onOpenInReader)
        }
    }
}

/**
 * Danh sách đoạn văn của chương kèm thanh chuyển chương ở cuối.
 *
 * @param article Nội dung đã bóc tách.
 * @param onOpenLink Callback mở liên kết chương.
 * @param onOpenInReader Callback mở truyện bằng Reader EPUB.
 */
@Composable
private fun ArticleContent(article: WebArticle, onOpenLink: (String) -> Unit, onOpenInReader: () -> Unit) {
    val listState = rememberLazyListState()
    LaunchedEffect(article.url) { listState.scrollToItem(0) }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "open_in_reader", contentType = "action") {
            Button(onClick = onOpenInReader, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.AutoStories, contentDescription = null)
                Text(
                    text = stringResource(R.string.browser_open_in_reader),
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
        item(key = "title", contentType = "title") {
            Text(text = article.title, style = MaterialTheme.typography.headlineSmall)
        }
        itemsIndexed(article.paragraphs, key = { index, _ -> index }, contentType = { _, _ -> "paragraph" }) { _, text ->
            Text(text = text, style = MaterialTheme.typography.bodyLarge.copy(lineHeight = MaterialTheme.typography.bodyLarge.fontSize * 1.6))
        }
        item(key = "navigation", contentType = "navigation") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ChapterLinkButton(R.string.browser_chapter_previous, article.links.previous, onOpenLink, Modifier.weight(1f))
                ChapterLinkButton(R.string.browser_chapter_toc, article.links.tableOfContents, onOpenLink, Modifier.weight(1f))
                ChapterLinkButton(R.string.browser_chapter_next, article.links.next, onOpenLink, Modifier.weight(1f))
            }
        }
    }
}

/**
 * Nút chuyển chương, tự vô hiệu khi không có liên kết.
 *
 * @param labelRes Nhãn nút.
 * @param url Liên kết đích.
 * @param onOpenLink Callback mở liên kết.
 * @param modifier Modifier kích thước.
 */
@Composable
private fun ChapterLinkButton(labelRes: Int, url: String?, onOpenLink: (String) -> Unit, modifier: Modifier) {
    OutlinedButton(onClick = { url?.let(onOpenLink) }, enabled = url != null, modifier = modifier) {
        Text(stringResource(labelRes), maxLines = 1)
    }
}

/**
 * Hộp thoại nhập tên truyện và số chương cần tải.
 *
 * @param initialTitle Tên gợi ý.
 * @param onDismiss Callback đóng.
 * @param onConfirm Callback xác nhận (tên, số chương).
 */
@Composable
private fun SaveNovelDialog(initialTitle: String, onDismiss: () -> Unit, onConfirm: (String, Int) -> Unit) {
    var title by rememberSaveable { mutableStateOf(initialTitle) }
    var chapterCount by rememberSaveable { mutableIntStateOf(BrowserViewModel.CHAPTER_COUNT_OPTIONS[1]) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.browser_save_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.browser_save_name_label)) },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = stringResource(R.string.browser_save_chapter_count, chapterCount),
                    style = MaterialTheme.typography.bodyMedium
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BrowserViewModel.CHAPTER_COUNT_OPTIONS.forEach { option ->
                        FilterChip(
                            selected = option == chapterCount,
                            onClick = { chapterCount = option },
                            label = { Text(option.toString()) }
                        )
                    }
                }
                Text(text = stringResource(R.string.browser_save_note), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(title, chapterCount) }) { Text(stringResource(R.string.browser_save_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

/**
 * Lấy HTML hiện tại của WebView và chuyển cho bộ bóc tách.
 *
 * @param webView WebView đang hiển thị trang.
 * @param url URL của trang.
 * @param onHtml Callback nhận (HTML, URL).
 */
private fun requestPageHtml(webView: WebView, url: String, onHtml: (String, String) -> Unit) {
    if (!isAllowedBrowserUrl(url)) return
    webView.evaluateJavascript(READ_HTML_SCRIPT) { encoded ->
        // evaluateJavascript trả về chuỗi JSON; bọc trong mảng để JSONArray giải mã escape.
        val html = runCatching { JSONArray("[$encoded]").optString(0) }.getOrNull().orEmpty()
        if (html.isNotEmpty()) onHtml(html, url)
    }
}

/**
 * Tạo WebView với cấu hình an toàn: không truy cập file/content, không có JavaScript bridge,
 * chỉ cho điều hướng http/https.
 *
 * @param context Context của màn hình.
 * @param onPageStarted Callback khi bắt đầu nạp trang.
 * @param onPageFinished Callback khi nạp xong trang.
 * @param onProgress Callback tiến độ và tiêu đề.
 * @return WebView đã cấu hình.
 */
@SuppressLint("SetJavaScriptEnabled")
private fun createSafeWebView(
    context: android.content.Context,
    onPageStarted: (String) -> Unit,
    onPageFinished: (WebView, String) -> Unit,
    onProgress: (Int, String?) -> Unit
): WebView = WebView(context).apply {
    settings.apply {
        // Site truyện hiện đại cần JavaScript để hiển thị nội dung; không có bridge native nào được gắn.
        javaScriptEnabled = true
        domStorageEnabled = true
        allowFileAccess = false
        allowContentAccess = false
        setSupportMultipleWindows(false)
        javaScriptCanOpenWindowsAutomatically = false
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        loadWithOverviewMode = true
        useWideViewPort = true
    }
    webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
            !isAllowedBrowserUrl(request.url?.toString())

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            onPageStarted(url)
        }

        override fun onPageFinished(view: WebView, url: String) {
            onPageFinished(view, url)
        }
    }
    webChromeClient = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            onProgress(newProgress, view.title)
        }
    }
}
