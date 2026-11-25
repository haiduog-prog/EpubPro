package com.epubpro.feature.reader.webview

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.PixelCopy
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.viewinterop.AndroidView
import com.epubpro.core.reader.bridge.ReaderJsBridge
import com.epubpro.core.reader.filter.EpubHtmlSanitizer
import com.epubpro.core.reader.filter.SanitizedEpubHtml
import com.epubpro.core.reader.style.CssInjector
import com.epubpro.domain.model.ContentFilterPreferences
import kotlinx.coroutines.Dispatchers
import com.epubpro.domain.model.ReaderSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale

/** Thời gian chờ tối đa (ms) trước khi tự động giải phóng màn che chuyển chương. */
private const val CHAPTER_TRANSITION_TIMEOUT_MS = 2_500L

/** Hướng chuyển chương EPUB (tiến hoặc lùi). */
private enum class ChapterTransitionDirection {
    NEXT,
    PREVIOUS
}

/**
 * Dữ liệu màn che chuyển chương hiển thị khung hình Bitmap chụp từ WebView trước khi nạp trang mới.
 *
 * @property token Token xác định duy nhất lần chuyển cảnh.
 * @property expectedLoadGeneration Mã thế hệ nạp HTML mục tiêu.
 * @property direction Hướng chuyển chương (kế tiếp hoặc trước đó).
 * @property image Khung hình Bitmap chuyển cảnh dạng [ImageBitmap].
 */
private data class ChapterTransitionCover(
    val token: Long,
    val expectedLoadGeneration: Int,
    val direction: ChapterTransitionDirection,
    val image: ImageBitmap
)

/**
 * Cầu nối để UI bên ngoài WebView (nút chuyển chương ở thanh dưới) yêu cầu chuyển chương qua cùng luồng
 * màn che [PixelCopy] với chuyển chương bằng cử chỉ, tránh WebView nháy trắng khi nạp lại document.
 * [EpubProWebView] đăng ký xử lý khi đang hiển thị; khi chưa đăng ký, caller tự gọi ViewModel.
 */
@Stable
class ReaderChapterNavigator internal constructor() {
    internal var handler: ((next: Boolean) -> Unit)? = null

    /**
     * Yêu cầu chuyển sang chương kế tiếp có màn che chuyển cảnh.
     *
     * @return `true` nếu WebView đã nhận yêu cầu; `false` nếu chưa có WebView để xử lý.
     */
    fun goToNextChapter(): Boolean = handler?.let { it(true); true } ?: false

    /**
     * Yêu cầu quay về chương trước (mở ở trang cuối) có màn che chuyển cảnh.
     *
     * @return `true` nếu WebView đã nhận yêu cầu; `false` nếu chưa có WebView để xử lý.
     */
    fun goToPreviousChapter(): Boolean = handler?.let { it(false); true } ?: false
}

/**
 * Tạo và ghi nhớ một [ReaderChapterNavigator] trong composition hiện tại.
 *
 * @return Navigator dùng chung giữa nút chuyển chương và [EpubProWebView].
 */
@Composable
fun rememberReaderChapterNavigator(): ReaderChapterNavigator = remember { ReaderChapterNavigator() }

/**
 * Gói dữ liệu HTML chương hiện tại và hai chương xem trước đã được làm sạch an toàn.
 *
 * @property current Mã HTML chương hiện tại.
 * @property previous Mã HTML xem trước của chương trước đó.
 * @property next Mã HTML xem trước của chương kế tiếp.
 */
private data class SanitizedEpubHtmlBundle(
    val current: String,
    val previous: String?,
    val next: String?
)

/**
 * Làm sạch chuỗi mã HTML EPUB bằng [EpubHtmlSanitizer], trả về chuỗi rỗng nếu có lỗi.
 *
 * @param html Chuỗi HTML nguyên bản cần làm sạch.
 * @return Chuỗi HTML đã được lọc bỏ các thẻ active script/style nguy hiểm.
 */
private fun sanitizeEpubHtmlOrEmpty(html: String): String =
    runCatching { EpubHtmlSanitizer.sanitize(html).rawHtml }.getOrDefault("")

/**
 * Tạo mã hash khóa nạp lại nội dung WebView dựa trên các thuộc tính giao diện cần render lại.
 *
 * @return Giá trị hash mã khóa nạp lại HTML.
 */
internal fun ReaderSettings.contentReloadKey(): Int = listOf(
    fontSizeSp,
    fontFamily,
    lineHeightRatio,
    marginTopDp,
    marginBottomDp,
    marginLeftDp,
    marginRightDp,
    themeMode,
    isHorizontalPagination,
    paragraphSpacingDp,
    firstLineIndentDp,
    textAlignment,
    showScrollBar
).hashCode()

/**
 * Tìm đối tượng [Activity] từ [Context] hiện tại.
 *
 * @return Đối tượng [Activity] nếu tìm thấy, hoặc `null` nếu context không thuộc Activity.
 */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Chụp khung hình giao diện WebView hiện tại thành [Bitmap] thông qua API [PixelCopy].
 *
 * @param callbackHandler Handler xử lý callback trên Main Looper.
 * @param onCaptured Callback trả về [Bitmap] đã chụp hoặc `null` nếu thất bại.
 */
private fun WebView.captureTransitionFrame(
    callbackHandler: Handler,
    onCaptured: (Bitmap?) -> Unit
) {
    val activity = context.findActivity()
    if (width <= 0 || height <= 0 || !isAttachedToWindow || activity == null) {
        onCaptured(null)
        return
    }

    val locationInWindow = IntArray(2)
    getLocationInWindow(locationInWindow)
    val sourceRect = Rect(
        locationInWindow[0],
        locationInWindow[1],
        locationInWindow[0] + width,
        locationInWindow[1] + height
    )
    val bitmap = runCatching {
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    }.onFailure { error ->
        Log.e("EpubPro_TRANSITION", "Unable to allocate transition bitmap", error)
    }.getOrElse {
        onCaptured(null)
        return
    }

    runCatching {
        PixelCopy.request(
            activity.window,
            sourceRect,
            bitmap,
            { result ->
                if (result == PixelCopy.SUCCESS) {
                    onCaptured(bitmap)
                } else {
                    bitmap.recycle()
                    Log.e(
                        "EpubPro_TRANSITION",
                        "PixelCopy failed for transition frame, result=${result}"
                    )
                    onCaptured(null)
                }
            },
            callbackHandler
        )
    }.onFailure { error ->
        bitmap.recycle()
        Log.e("EpubPro_TRANSITION", "Unable to capture WebView transition frame", error)
        onCaptured(null)
    }
}

/**
 * Thành phần Composable hiển thị tệp sách EPUB bằng Android WebView với cơ chế phân trang CSS Multi-Column,
 * làm sạch dữ liệu HTML an toàn ngầm (background sanitization), hỗ trợ lật trang mượt không chớp trắng
 * qua [PixelCopy] và đồng bộ highlight TTS.
 *
 * @param modifier Modifier tùy chỉnh layout cho WebView.
 * @param htmlContent Mã HTML nguyên bản của chương sách hiện tại.
 * @param preSanitizedHtml HTML current đã được kiểm tra và làm sạch từ snapshot, nếu có.
 * @param previousChapterHtml Mã HTML xem trước của chương trước đó.
 * @param nextChapterHtml Mã HTML xem trước của chương kế tiếp.
 * @param preSanitizedPreviousChapterHtml HTML chương trước đã sanitize, nếu có.
 * @param preSanitizedNextChapterHtml HTML chương kế tiếp đã sanitize, nếu có.
 * @param hasPreviousChapter Cho biết danh sách còn chương trước hay không.
 * @param hasNextChapter Cho biết danh sách còn chương kế tiếp hay không.
 * @param initialPage Trang bắt đầu hiển thị khi mở chương.
 * @param initialVisibleParagraphIndex Chỉ số đoạn văn bắt đầu hiển thị khi mở chương.
 * @param chapterReloadToken Bộ đếm tăng khi yêu cầu nạp lại chương từ bên ngoài (TOC, nút chương, fallback),
 *        đảm bảo WebView vẫn nạp lại kể cả khi quay về đúng HTML đang hiển thị.
 * @param settings Cấu hình cài đặt đọc sách [ReaderSettings].
 * @param filterPreferences Cấu hình bộ lọc từ ngữ nhạy cảm.
 * @param activeTtsParagraphIndex Chỉ số đoạn văn đang được TTS đọc và tô màu highlight.
 * @param onPageTapped Callback gọi khi người dùng nhấp chạm vào vùng giữa màn hình để ẩn/hiện thanh công cụ.
 * @param onPageChanged Callback báo chỉ số trang hiện tại, tổng số trang và index đoạn văn hiển thị đầu tiên.
 * @param onNextChapter Callback yêu cầu chuyển sang chương kế tiếp.
 * @param onPreviousChapter Callback yêu cầu chuyển về chương trước đó.
 * @param onNextChapterPrefetch Callback preload chương kế tiếp ngay khi gesture commit.
 * @param onPreviousChapterPrefetch Callback preload chương trước ngay khi gesture commit.
 * @param onAdjacentChapterCommitted Callback khi JS đã thay DOM sang chương kề ngay tại chỗ (không reload);
 *        tham số là hướng chuyển: dương là chương sau, âm là chương trước.
 * @param chapterNavigator Navigator để nút chuyển chương bên ngoài đi qua luồng màn che chuyển cảnh.
 * @param onTextSelected Callback khi người dùng bôi đen chọn đoạn văn bản.
 * @param onAddSelectionToFilter Callback thêm đoạn văn bản đang chọn vào bộ lọc nội dung.
 * @param onCfiChanged Callback báo mã vị trí CFI thay đổi.
 */
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
fun EpubProWebView(
    modifier: Modifier = Modifier,
    htmlContent: String,
    preSanitizedHtml: SanitizedEpubHtml? = null,
    previousChapterHtml: String?,
    nextChapterHtml: String?,
    preSanitizedPreviousChapterHtml: SanitizedEpubHtml? = null,
    preSanitizedNextChapterHtml: SanitizedEpubHtml? = null,
    hasPreviousChapter: Boolean = true,
    hasNextChapter: Boolean = true,
    initialPage: Int,
    initialVisibleParagraphIndex: Int,
    chapterReloadToken: Int = 0,
    settings: ReaderSettings,
    filterPreferences: ContentFilterPreferences = ContentFilterPreferences(),
    activeTtsParagraphIndex: Int? = null,
    onPageTapped: () -> Unit,
    onPageChanged: (currentPage: Int, totalPages: Int, firstVisibleChunkIndex: Int) -> Unit,
    onNextChapter: () -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapterPrefetch: () -> Unit = {},
    onPreviousChapterPrefetch: () -> Unit = {},
    onAdjacentChapterCommitted: (direction: Int) -> Unit,
    chapterNavigator: ReaderChapterNavigator? = null,
    onTextSelected: (String) -> Unit,
    onAddSelectionToFilter: (String) -> Unit = {},
    onCfiChanged: (String) -> Unit
) {
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val generationTracker = remember { ReaderDocumentGenerationTracker() }
    val currentOnPageTapped by rememberUpdatedState(onPageTapped)
    val currentOnPageChanged by rememberUpdatedState(onPageChanged)
    val currentOnNextChapter by rememberUpdatedState(onNextChapter)
    val currentOnPreviousChapter by rememberUpdatedState(onPreviousChapter)
    val currentOnNextChapterPrefetch by rememberUpdatedState(onNextChapterPrefetch)
    val currentOnPreviousChapterPrefetch by rememberUpdatedState(onPreviousChapterPrefetch)
    val currentOnAdjacentChapterCommitted by rememberUpdatedState(onAdjacentChapterCommitted)
    val currentPreSanitizedPreviousChapterHtml by rememberUpdatedState(preSanitizedPreviousChapterHtml)
    val currentPreSanitizedNextChapterHtml by rememberUpdatedState(preSanitizedNextChapterHtml)
    val currentPreviousChapterHtml by rememberUpdatedState(previousChapterHtml)
    val currentNextChapterHtml by rememberUpdatedState(nextChapterHtml)
    val currentReaderSettings by rememberUpdatedState(settings)
    val currentFilterPreferences by rememberUpdatedState(filterPreferences)
    val currentChapterReloadToken by rememberUpdatedState(chapterReloadToken)
    val currentOnTextSelected by rememberUpdatedState(onTextSelected)
    val currentOnAddSelectionToFilter by rememberUpdatedState(onAddSelectionToFilter)
    val currentOnCfiChanged by rememberUpdatedState(onCfiChanged)

    var loadedHtmlKey by remember { mutableStateOf("") }
    var currentLoadedGeneration by remember { mutableStateOf(0) }
    var latestAdjacentPayload by remember { mutableStateOf<Triple<Int, String, String>?>(null) }
    var locallyCommittedHtmlKey by remember { mutableStateOf<String?>(null) }
    val currentAdjacentPayload by rememberUpdatedState(latestAdjacentPayload)
    val currentGenerationForPage by rememberUpdatedState(currentLoadedGeneration)
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var transitionCover by remember { mutableStateOf<ChapterTransitionCover?>(null) }
    var isTransitionCapturePending by remember { mutableStateOf(false) }
    // Token của màn che mà lần chuyển chương đầu tiên đã được gửi tới ViewModel.
    var dispatchedCoverToken by remember { mutableStateOf<Long?>(null) }
    // Tăng mỗi khi gộp thêm yêu cầu chuyển chương dưới màn che để gia hạn thời gian chờ timeout.
    var coalesceRevision by remember { mutableStateOf(0) }

    val sanitizedCurrentHtml by produceState<SanitizedEpubHtml?>(
        initialValue = preSanitizedHtml,
        htmlContent,
        preSanitizedHtml
    ) {
        value = preSanitizedHtml ?: withContext(Dispatchers.Default) {
            EpubHtmlSanitizer.sanitize(htmlContent)
        }
    }

    val requestChapterTransition by rememberUpdatedState<(ChapterTransitionDirection, Boolean) -> Unit> {
        direction, coalesceWhileCovered ->
        val webView = webViewRef
        val activeCover = transitionCover
        if (webView == null) {
            mainHandler.post {
                when (direction) {
                    ChapterTransitionDirection.NEXT -> currentOnNextChapter()
                    ChapterTransitionDirection.PREVIOUS -> currentOnPreviousChapter()
                }
            }
        } else if (
            coalesceWhileCovered &&
            activeCover != null &&
            !isTransitionCapturePending &&
            dispatchedCoverToken == activeCover.token &&
            // Dời đích chờ trước khi gửi yêu cầu, để document đang layout không gỡ màn che trước khi
            // document của chương mới được nạp.
            generationTracker.extendPendingToNextLoad()
        ) {
            // Nút chuyển chương bấm liên tục: màn che đang giữ khung hình cũ và lần chuyển đầu đã gửi đi,
            // nên chuyển tiếp ngay bên dưới màn che và gia hạn bộ đếm timeout.
            coalesceRevision += 1
            Log.d("EpubPro_TRANSITION", "Coalescing chapter request under active cover direction=${direction}")
            when (direction) {
                ChapterTransitionDirection.NEXT -> currentOnNextChapter()
                ChapterTransitionDirection.PREVIOUS -> currentOnPreviousChapter()
            }
        } else if (activeCover != null || isTransitionCapturePending) {
            Log.d("EpubPro_TRANSITION", "Ignoring duplicate boundary request")
        } else {
            isTransitionCapturePending = true
            webView.post {
                webView.captureTransitionFrame(mainHandler) { capturedBitmap ->
                    isTransitionCapturePending = false
                    if (webViewRef !== webView) {
                        capturedBitmap?.recycle()
                        return@captureTransitionFrame
                    }

                    if (capturedBitmap == null) {
                        when (direction) {
                            ChapterTransitionDirection.NEXT -> currentOnNextChapter()
                            ChapterTransitionDirection.PREVIOUS -> currentOnPreviousChapter()
                        }
                        return@captureTransitionFrame
                    }

                    val expectedGeneration = generationTracker.beginChapterTransition()
                    transitionCover = ChapterTransitionCover(
                        token = System.nanoTime(),
                        expectedLoadGeneration = expectedGeneration,
                        direction = direction,
                        image = capturedBitmap.asImageBitmap()
                    )
                    Log.d(
                        "EpubPro_TRANSITION",
                        "PixelCopy captured boundary frame, expectedGeneration=${expectedGeneration} direction=${direction}"
                    )
                }
            }
        }
    }

    val jsBridge = remember {
        ReaderJsBridge(
            onTextSelectedListener = { selectionJson ->
                mainHandler.post { currentOnTextSelected(selectionJson) }
            },
            onCfiChangedListener = { cfi ->
                mainHandler.post { currentOnCfiChanged(cfi) }
            },
            onPageTappedListener = {
                mainHandler.post { currentOnPageTapped() }
            },
            onPageChangedListener = { currentPage, totalPages, firstVisibleChunkIndex ->
                mainHandler.post {
                    currentOnPageChanged(currentPage, totalPages, firstVisibleChunkIndex)
                }
            },
            onNextChapterListener = {
                mainHandler.post {
                    requestChapterTransition(ChapterTransitionDirection.NEXT, false)
                }
            },
            onPreviousChapterListener = {
                mainHandler.post {
                    requestChapterTransition(ChapterTransitionDirection.PREVIOUS, false)
                }
            },
            onNextChapterPrefetchListener = {
                mainHandler.post { currentOnNextChapterPrefetch() }
            },
            onPreviousChapterPrefetchListener = {
                mainHandler.post { currentOnPreviousChapterPrefetch() }
            },
            onAdjacentChapterCommittedListener = { direction ->
                mainHandler.post {
                    val adjacent = if (direction > 0) {
                        currentPreSanitizedNextChapterHtml?.rawHtml
                            ?: currentNextChapterHtml?.takeIf { it.isNotBlank() }
                    } else {
                        currentPreSanitizedPreviousChapterHtml?.rawHtml
                            ?: currentPreviousChapterHtml?.takeIf { it.isNotBlank() }
                    }
                    if (adjacent != null) {
                        locallyCommittedHtmlKey =
                            "${adjacent.hashCode()}_${currentReaderSettings.contentReloadKey()}_${currentFilterPreferences.hashCode()}_$currentChapterReloadToken"
                    }
                    currentOnAdjacentChapterCommitted(direction)
                }
            },
            onReaderLayoutReadyListener = { loadGeneration ->
                mainHandler.post {
                    val cover = transitionCover
                    if (
                        cover != null &&
                        generationTracker.isPendingDestination(loadGeneration)
                    ) {
                        val destinationWebView = webViewRef ?: return@post
                        Log.d(
                            "EpubPro_TRANSITION",
                            "Layout ready, waiting for committed visual generation=${loadGeneration}"
                        )
                        destinationWebView.postVisualStateCallback(
                            loadGeneration.toLong(),
                            object : WebView.VisualStateCallback() {
                                override fun onComplete(requestId: Long) {
                                    if (requestId != loadGeneration.toLong()) return
                                    destinationWebView.postOnAnimation {
                                        if (
                                            webViewRef === destinationWebView &&
                                            transitionCover?.token == cover.token &&
                                            generationTracker.completeIfExpected(loadGeneration)
                                        ) {
                                            Log.d(
                                                "EpubPro_TRANSITION",
                                                "Visual committed, clearing cover generation=${loadGeneration}"
                                            )
                                            transitionCover = null
                                        }
                                    }
                                }
                            }
                        )
                    }
                }
            }
        )
    }

    LaunchedEffect(transitionCover?.token) {
        val cover = transitionCover ?: return@LaunchedEffect
        withFrameNanos { }
        if (transitionCover?.token != cover.token) return@LaunchedEffect

        when (cover.direction) {
            ChapterTransitionDirection.NEXT -> currentOnNextChapter()
            ChapterTransitionDirection.PREVIOUS -> currentOnPreviousChapter()
        }
        dispatchedCoverToken = cover.token

        var observedRevision: Int
        do {
            observedRevision = coalesceRevision
            delay(CHAPTER_TRANSITION_TIMEOUT_MS)
        } while (transitionCover?.token == cover.token && coalesceRevision != observedRevision)

        if (
            transitionCover?.token == cover.token &&
            // Đích có thể đã được dời sang document mới hơn khi bấm chuyển chương liên tục.
            generationTracker.cancelPending()
        ) {
            Log.w(
                "EpubPro_TRANSITION",
                "Timed out waiting for generation=${cover.expectedLoadGeneration}"
            )
            transitionCover = null
        }
    }

    LaunchedEffect(
        previousChapterHtml,
        nextChapterHtml,
        preSanitizedPreviousChapterHtml,
        preSanitizedNextChapterHtml,
        currentLoadedGeneration
    ) {
        if (currentLoadedGeneration <= 0) return@LaunchedEffect
        val webView = webViewRef ?: return@LaunchedEffect

        val (quotedPrev, quotedNext) = withContext(Dispatchers.Default) {
            val cleanPrev = preSanitizedPreviousChapterHtml?.rawHtml
                ?: previousChapterHtml?.takeIf { it.isNotBlank() }
                    ?.let { EpubHtmlSanitizer.sanitize(it).rawHtml }
                .orEmpty()
            val cleanNext = preSanitizedNextChapterHtml?.rawHtml
                ?: nextChapterHtml?.takeIf { it.isNotBlank() }
                    ?.let { EpubHtmlSanitizer.sanitize(it).rawHtml }
                .orEmpty()
            val prevJs = CssInjector.quoteForJsArgument(cleanPrev)
            val nextJs = CssInjector.quoteForJsArgument(cleanNext)
            prevJs to nextJs
        }

        latestAdjacentPayload = Triple(currentLoadedGeneration, quotedPrev, quotedNext)
        webView.evaluateJavascript(
            "if (typeof epubproSetAdjacentChapters === 'function') { epubproSetAdjacentChapters($currentLoadedGeneration, $quotedPrev, $quotedNext, $hasPreviousChapter, $hasNextChapter); }",
            null
        )
    }

    DisposableEffect(chapterNavigator) {
        chapterNavigator?.handler = { next ->
            requestChapterTransition(
                if (next) ChapterTransitionDirection.NEXT else ChapterTransitionDirection.PREVIOUS,
                true
            )
        }
        onDispose { chapterNavigator?.handler = null }
    }

    LaunchedEffect(activeTtsParagraphIndex) {
        if (activeTtsParagraphIndex != null && webViewRef != null) {
            webViewRef?.evaluateJavascript(
                "if (typeof epubproHighlightTtsParagraph === 'function') { epubproHighlightTtsParagraph($activeTtsParagraphIndex); }",
                null
            )
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            mainHandler.removeCallbacksAndMessages(null)
            val webView = webViewRef
            webViewRef = null
            isTransitionCapturePending = false
            webView?.apply {
                removeJavascriptInterface("ReaderJsBridge")
                stopLoading()
                destroy()
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            factory = { context ->
                ReaderSelectionWebView(context).apply {
                    webViewRef = this
                    this.onAddSelectionToFilter = { selectedText ->
                        currentOnAddSelectionToFilter(selectedText)
                    }
                    setLayerType(View.LAYER_TYPE_HARDWARE, null)
                    this.settings.javaScriptEnabled = true
                    this.settings.domStorageEnabled = false
                    this.settings.useWideViewPort = false
                    this.settings.loadWithOverviewMode = false
                    this.settings.textZoom = 100
                    this.settings.cacheMode = WebSettings.LOAD_NO_CACHE
                    this.settings.allowFileAccess = false
                    this.settings.allowContentAccess = false
                    this.settings.allowFileAccessFromFileURLs = false
                    this.settings.allowUniversalAccessFromFileURLs = false
                    this.settings.javaScriptCanOpenWindowsAutomatically = false
                    this.settings.setSupportMultipleWindows(false)
                    this.settings.mediaPlaybackRequiresUserGesture = true
                    isFocusable = true
                    isFocusableInTouchMode = true
                    requestFocus()

                    addJavascriptInterface(jsBridge, "ReaderJsBridge")
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            view?.evaluateJavascript(
                                "if (typeof epubproUpdateMetrics === 'function') { epubproUpdateMetrics(); }",
                                null
                            )
                            val payload = currentAdjacentPayload
                            val generation = currentGenerationForPage
                            if (view != null && payload?.first == generation) {
                                view.evaluateJavascript(
                                    "if (typeof epubproSetAdjacentChapters === 'function') { epubproSetAdjacentChapters(${payload.first}, ${payload.second}, ${payload.third}); }",
                                    null
                                )
                            }
                        }

                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean {
                            val uri = request?.url ?: return true
                            val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return true

                            if (scheme in listOf("http", "https")) {
                                runCatching {
                                    val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    view?.context?.startActivity(intent)
                                }
                            }
                            return true
                        }
                    }
                }
            },
            update = update@{ webView ->
                val sanitized = sanitizedCurrentHtml ?: return@update
                webView.isVerticalScrollBarEnabled =
                    settings.showScrollBar && !settings.isHorizontalPagination
                webView.isHorizontalScrollBarEnabled =
                    settings.showScrollBar && settings.isHorizontalPagination
                webView.setOnKeyListener { _, keyCode, event ->
                    if (event.action != KeyEvent.ACTION_UP) {
                        return@setOnKeyListener false
                    }

                    val direction = when {
                        settings.enableKeyboardNavigation && keyCode in listOf(
                            KeyEvent.KEYCODE_DPAD_RIGHT,
                            KeyEvent.KEYCODE_PAGE_DOWN,
                            KeyEvent.KEYCODE_SPACE
                        ) -> 1

                        settings.enableKeyboardNavigation && keyCode in listOf(
                            KeyEvent.KEYCODE_DPAD_LEFT,
                            KeyEvent.KEYCODE_PAGE_UP
                        ) -> -1

                        settings.enableVolumeKeyNavigation &&
                            keyCode == KeyEvent.KEYCODE_VOLUME_DOWN -> 1

                        settings.enableVolumeKeyNavigation &&
                            keyCode == KeyEvent.KEYCODE_VOLUME_UP -> -1

                        else -> 0
                    }

                    if (direction == 0) {
                        false
                    } else {
                        val functionName =
                            if (direction > 0) "epubproGoNextPage" else "epubproGoPrevPage"
                        webView.evaluateJavascript(
                            "if (typeof $functionName === 'function') { $functionName(); }",
                            null
                        )
                        true
                    }
                }

                val runtimeSpeedMs =
                    if (settings.enablePageAnimation) settings.pageTurnSpeedMs else 0
                val runtimeActions =
                    JSONObject.quote(settings.tapZoneActions.joinToString(",") { it.name })
                webView.evaluateJavascript(
                    "if (typeof epubproApplyRuntimeSettings === 'function') { epubproApplyRuntimeSettings($runtimeSpeedMs, $runtimeActions); }",
                    null
                )

                val newHtmlKey =
                    "${sanitized.rawHtml.hashCode()}_${settings.contentReloadKey()}_${filterPreferences.hashCode()}_$chapterReloadToken"
                if (loadedHtmlKey != newHtmlKey) {
                    loadedHtmlKey = newHtmlKey
                    // Key commit chỉ dùng cho lần đổi HTML đầu tiên sau khi commit. Nếu lần nạp chương đó bị
                    // hủy (bấm chuyển chương tiếp), key cũ không được giữ lại, vì lần sau quay về đúng chương
                    // đó sẽ bỏ qua reload và WebView hiển thị sai chương.
                    val committedKey = locallyCommittedHtmlKey
                    locallyCommittedHtmlKey = null
                    if (committedKey == newHtmlKey) {
                        Log.d("EpubPro_HTML", "Skipping WebView reload because chapter was committed in the existing DOM")
                    } else {
                        val loadGeneration = generationTracker.nextLoadGeneration()
                    currentLoadedGeneration = loadGeneration
                    val cleanHtml = sanitized.rawHtml
                    val statusFooterHeightDp = if (settings.showStatusBar) 20 else 0
                    val css = CssInjector.generateCss(settings, statusFooterHeightDp)
                    val jsScript = CssInjector.generateJsBridgeScript(
                        isHorizontalPagination = settings.isHorizontalPagination,
                        initialPage = initialPage,
                        initialVisibleParagraphIndex = initialVisibleParagraphIndex,
                        settings = settings,
                        statusFooterHeightDp = statusFooterHeightDp,
                        previousChapterHtml = preSanitizedPreviousChapterHtml?.rawHtml,
                        nextChapterHtml = preSanitizedNextChapterHtml?.rawHtml,
                        filterPreferences = filterPreferences,
                        loadGeneration = loadGeneration,
                        hasPreviousChapter = hasPreviousChapter,
                        hasNextChapter = hasNextChapter
                    )
                    val meta = CssInjector.generateMetaAndViewport()
                    val headInjection = """
                        $meta
                        $css
                        <script>
                        $jsScript
                        </script>
                    """.trimIndent()
                    val preparedHtml = when {
                        cleanHtml.contains("</head>", ignoreCase = true) -> {
                            "(?i)</head>".toRegex().replace(cleanHtml) {
                                "$headInjection</head>"
                            }
                        }

                        cleanHtml.contains("<head>", ignoreCase = true) -> {
                            "(?i)<head>".toRegex().replace(cleanHtml) {
                                "<head>$headInjection"
                            }
                        }

                        cleanHtml.contains("<html", ignoreCase = true) -> {
                            "(?i)(<html[^>]*>)".toRegex().replace(cleanHtml) { match ->
                                "${match.value}<head>$headInjection</head>"
                            }
                        }

                        else -> {
                            "<!DOCTYPE html><html><head>$headInjection</head><body>$cleanHtml</body></html>"
                        }
                    }

                    Log.d(
                        "EpubPro_HTML",
                        "Loading HTML, generation=${loadGeneration}, length=${preparedHtml.length}, " +
                            "isHorizontal=${settings.isHorizontalPagination}"
                    )

                    webView.loadDataWithBaseURL(
                        "file:///android_asset/",
                        preparedHtml,
                        "text/html",
                        "utf-8",
                        null
                    )
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        transitionCover?.let { cover ->
            Image(
                bitmap = cover.image,
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(cover.token) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent(PointerEventPass.Initial)
                                    .changes
                                    .forEach { change -> change.consume() }
                            }
                        }
                    }
            )
        }
    }
}
