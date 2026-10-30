package com.epubpro.feature.comic

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.epubpro.core.comic.ComicPageImage
import com.epubpro.core.designsystem.R
import com.epubpro.domain.model.ComicReadingMode
import kotlinx.coroutines.CancellationException

/** Hàm nạp ảnh trang: (chỉ số trang, chiều rộng đích px) -> ảnh đã giải mã. */
internal typealias ComicPageLoader = suspend (pageIndex: Int, targetWidth: Int) -> ComicPageImage

/** Nội dung vẽ đè lên một trang (ví dụ bản dịch bóng thoại), nhận chỉ số trang. */
internal typealias ComicPageOverlay = @Composable BoxScope.(pageIndex: Int) -> Unit

/** Trạng thái nạp ảnh của một trang. */
private sealed interface PageLoadState {
    data object Loading : PageLoadState
    data class Loaded(val image: ComicPageImage) : PageLoadState
    data object Failed : PageLoadState
}

private const val MAX_ZOOM = 4f
private const val DOUBLE_TAP_ZOOM = 2.5f

/** Giải mã rộng hơn màn hình để ảnh vẫn nét khi phóng to trong chế độ lật trang. */
private const val PAGED_DECODE_WIDTH_FACTOR = 1.5f

/**
 * Chế độ lật trang ngang (trái sang phải hoặc phải sang trái).
 *
 * @param pagerState State của pager.
 * @param mode Chế độ đọc để đảo chiều pager và vùng chạm.
 * @param pageLoader Hàm nạp ảnh trang.
 * @param onTap Callback khi chạm vào trang.
 * @param overlay Nội dung vẽ đè lên trang, cùng tỉ lệ và zoom với ảnh.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PagedPages(
    pagerState: PagerState,
    mode: ComicReadingMode,
    pageLoader: ComicPageLoader,
    onTap: (ComicTapAction) -> Unit,
    overlay: ComicPageOverlay = {}
) {
    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
        beyondBoundsPageCount = 1,
        reverseLayout = mode == ComicReadingMode.RIGHT_TO_LEFT,
        key = { it }
    ) { pageIndex ->
        ZoomablePage(
            pageIndex = pageIndex,
            isSettledPage = pagerState.settledPage == pageIndex,
            mode = mode,
            pageLoader = pageLoader,
            onTap = onTap,
            overlay = overlay
        )
    }
}

/**
 * Chế độ cuộn dọc webtoon: các trang nối liền, chiều rộng bằng màn hình.
 *
 * @param pageCount Tổng số trang.
 * @param listState State của danh sách.
 * @param pageLoader Hàm nạp ảnh trang.
 * @param onTap Callback khi chạm vào nội dung.
 * @param overlay Nội dung vẽ đè lên từng trang.
 */
@Composable
internal fun WebtoonPages(
    pageCount: Int,
    listState: LazyListState,
    pageLoader: ComicPageLoader,
    onTap: (ComicTapAction) -> Unit,
    overlay: ComicPageOverlay = {}
) {
    val currentOnTap by rememberUpdatedState(onTap)
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val widthPx = constraints.maxWidth
        val viewportHeight = maxHeight
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { position ->
                        currentOnTap(
                            resolveComicTap(
                                position.x, position.y,
                                size.width.toFloat(), size.height.toFloat(),
                                ComicReadingMode.WEBTOON
                            )
                        )
                    }
                }
        ) {
            items(count = pageCount, key = { it }, contentType = { "comic_page" }) { pageIndex ->
                val state = rememberPageLoadState(pageIndex, widthPx, pageLoader)
                when (val current = state.value) {
                    is PageLoadState.Loaded -> PageTiles(
                        image = current.image,
                        pageIndex = pageIndex,
                        modifier = Modifier.fillMaxWidth(),
                        overlay = overlay
                    )
                    else -> Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(viewportHeight),
                        contentAlignment = Alignment.Center
                    ) {
                        PagePlaceholder(failed = current is PageLoadState.Failed, onRetry = state.retry)
                    }
                }
            }
        }
    }
}

/**
 * Một trang trong chế độ lật trang, hỗ trợ pinch-zoom, kéo khi đang phóng to và chạm hai lần để zoom.
 *
 * Khi chưa phóng to, cử chỉ một ngón không bị tiêu thụ để pager vẫn vuốt chuyển trang được.
 *
 * @param pageIndex Chỉ số trang.
 * @param isSettledPage true khi đây là trang pager đang dừng; rời trang thì zoom được đặt lại.
 * @param mode Chế độ đọc để xác định vùng chạm.
 * @param pageLoader Hàm nạp ảnh trang.
 * @param onTap Callback khi chạm một lần.
 * @param overlay Nội dung vẽ đè lên trang.
 */
@Composable
private fun ZoomablePage(
    pageIndex: Int,
    isSettledPage: Boolean,
    mode: ComicReadingMode,
    pageLoader: ComicPageLoader,
    onTap: (ComicTapAction) -> Unit,
    overlay: ComicPageOverlay
) {
    val currentOnTap by rememberUpdatedState(onTap)
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    // Trang liền kề vẫn nằm trong composition (beyondBoundsPageCount), nên phải tự reset khi rời trang
    // bằng phím âm lượng/thanh trượt; nếu không, quay lại trang cũ vẫn bị phóng to và khóa vuốt.
    LaunchedEffect(isSettledPage) {
        if (!isSettledPage) {
            scale = 1f
            offset = Offset.Zero
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val zoomed = scale > 1f
                        if (event.changes.size > 1 || zoomed) {
                            val newScale = (scale * event.calculateZoom()).coerceIn(1f, MAX_ZOOM)
                            scale = newScale
                            offset = clampOffset(offset + event.calculatePan(), newScale, size)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                    if (scale <= 1f) offset = Offset.Zero
                }
            }
            .pointerInput(mode) {
                detectTapGestures(
                    onDoubleTap = { position ->
                        if (scale > 1f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = DOUBLE_TAP_ZOOM
                            val center = Offset(size.width / 2f, size.height / 2f)
                            offset = clampOffset((center - position) * (DOUBLE_TAP_ZOOM - 1f), DOUBLE_TAP_ZOOM, size)
                        }
                    },
                    onTap = { position ->
                        if (scale > 1f) return@detectTapGestures
                        currentOnTap(
                            resolveComicTap(position.x, position.y, size.width.toFloat(), size.height.toFloat(), mode)
                        )
                    }
                )
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
        contentAlignment = Alignment.Center
    ) {
        val decodeWidth = (constraints.maxWidth * PAGED_DECODE_WIDTH_FACTOR).toInt()
        val state = rememberPageLoadState(pageIndex, decodeWidth, pageLoader)
        when (val current = state.value) {
            is PageLoadState.Loaded -> {
                val image = current.image
                val imageRatio = image.width.toFloat() / image.height.coerceAtLeast(1)
                val containerRatio = maxWidth / maxHeight
                PageTiles(
                    image = image,
                    pageIndex = pageIndex,
                    modifier = Modifier.aspectRatio(imageRatio, matchHeightConstraintsFirst = imageRatio < containerRatio),
                    overlay = overlay
                )
            }
            else -> PagePlaceholder(failed = current is PageLoadState.Failed, onRetry = state.retry)
        }
    }
}

/**
 * Giới hạn độ dịch để nội dung phóng to không bị kéo ra khỏi khung.
 *
 * @param offset Độ dịch đề xuất.
 * @param scale Hệ số phóng hiện tại.
 * @param size Kích thước khung.
 * @return Độ dịch đã giới hạn.
 */
private fun clampOffset(offset: Offset, scale: Float, size: IntSize): Offset {
    val maxX = size.width * (scale - 1f) / 2f
    val maxY = size.height * (scale - 1f) / 2f
    return Offset(offset.x.coerceIn(-maxX, maxX), offset.y.coerceIn(-maxY, maxY))
}

/**
 * Hiển thị các dải bitmap của một trang xếp dọc liền nhau.
 *
 * @param image Ảnh trang đã giải mã.
 * @param pageIndex Chỉ số trang để mô tả trợ năng.
 * @param modifier Modifier kích thước của cả trang.
 * @param overlay Nội dung vẽ đè, phủ đúng khung ảnh trang.
 */
@Composable
private fun PageTiles(image: ComicPageImage, pageIndex: Int, modifier: Modifier, overlay: ComicPageOverlay) {
    val description = stringResource(R.string.comic_reader_page_description, pageIndex + 1)
    Box(modifier = modifier.semantics { contentDescription = description }) {
        PageTileColumn(image)
        overlay(pageIndex)
    }
}

/**
 * Cột các dải bitmap của trang.
 *
 * @param image Ảnh trang đã giải mã.
 */
@Composable
private fun PageTileColumn(image: ComicPageImage) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.Top
    ) {
        image.tiles.forEach { tile ->
            val bitmap = remember(tile) { tile.asImageBitmap() }
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(tile.width.toFloat() / tile.height.coerceAtLeast(1))
            )
        }
    }
}

/**
 * Hiển thị vòng chờ khi đang nạp, hoặc thông báo lỗi kèm nút thử lại.
 *
 * @param failed true nếu nạp trang thất bại.
 * @param onRetry Callback nạp lại trang.
 */
@Composable
private fun PagePlaceholder(failed: Boolean, onRetry: () -> Unit) {
    if (!failed) {
        CircularProgressIndicator(color = Color.White)
        return
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(16.dp)) {
        Text(
            text = stringResource(R.string.comic_reader_page_failed),
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium
        )
        TextButton(onClick = onRetry) {
            Text(text = stringResource(R.string.comic_reader_retry))
        }
    }
}

/**
 * Trạng thái nạp ảnh kèm hàm thử lại.
 *
 * @property value Trạng thái hiện tại.
 * @property retry Yêu cầu nạp lại trang.
 */
private class PageLoadHandle(val value: PageLoadState, val retry: () -> Unit)

/**
 * Nạp ảnh trang trong coroutine gắn với vòng đời composable; tự hủy khi trang rời màn hình.
 *
 * @param pageIndex Chỉ số trang.
 * @param targetWidth Chiều rộng giải mã (px).
 * @param pageLoader Hàm nạp ảnh trang.
 * @return Trạng thái nạp và hàm thử lại.
 */
@Composable
private fun rememberPageLoadState(
    pageIndex: Int,
    targetWidth: Int,
    pageLoader: ComicPageLoader
): PageLoadHandle {
    var attempt by remember(pageIndex) { mutableIntStateOf(0) }
    val state = produceState<PageLoadState>(PageLoadState.Loading, pageIndex, targetWidth, attempt) {
        value = PageLoadState.Loading
        value = try {
            PageLoadState.Loaded(pageLoader(pageIndex, targetWidth.coerceAtLeast(1)))
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            PageLoadState.Failed
        } catch (_: OutOfMemoryError) {
            PageLoadState.Failed
        }
    }
    return PageLoadHandle(state.value) { attempt++ }
}
