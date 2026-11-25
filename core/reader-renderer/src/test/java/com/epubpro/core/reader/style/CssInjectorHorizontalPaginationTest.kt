package com.epubpro.core.reader.style

import com.epubpro.domain.model.ReaderSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CssInjectorHorizontalPaginationTest {

    private val asymmetricSettings = ReaderSettings(
        isHorizontalPagination = true,
        marginLeftDp = 24,
        marginRightDp = 12
    )

    @Test
    fun horizontalCssUsesOneViewportPageGeometryWithAsymmetricMargins() {
        val css = CssInjector.generateCss(asymmetricSettings)

        assertTrue(css.contains("padding-left: 24px !important"))
        assertTrue(css.contains("padding-right: 12px !important"))
        assertTrue(css.contains("column-width: calc(100vw - 36px) !important"))
        assertTrue(css.contains("column-gap: 36px !important"))
        assertTrue(css.contains("width: 100vw !important"))
    }

    @Test
    fun horizontalScriptUsesDedicatedScrollExtentInsteadOfExpandingBody() {
        val script = CssInjector.generateJsBridgeScript(
            isHorizontalPagination = true,
            settings = asymmetricSettings
        )

        assertTrue(script.contains("scrollExtentElement.id = 'epubpro-scroll-extent'"))
        assertTrue(script.contains("html.appendChild(scrollExtentElement)"))
        assertTrue(script.contains("body.style.removeProperty('min-width')"))
        assertFalse(script.contains("body.style.setProperty('min-width'"))
    }

    @Test
    fun committedPageSettlesAtCanonicalOffsetBeforeOverlayCleanup() {
        val script = CssInjector.generateJsBridgeScript(
            isHorizontalPagination = true,
            settings = asymmetricSettings
        )

        assertTrue(script.contains("var targetX = (boundedPage - 1) * pw"))
        assertTrue(script.contains("setHorizontalScrollExtentWidth(scrollExtentWidth + shortfall)"))
        val commitIndex = script.lastIndexOf("settlePageOffset(currentPage, 2")
        val cleanupIndex = script.indexOf("cleanupCoverOverlay()", startIndex = commitIndex)
        val notificationIndex = script.indexOf("notifyPageChangeCompleted()", startIndex = cleanupIndex)

        assertTrue(commitIndex >= 0)
        assertTrue(cleanupIndex > commitIndex)
        assertTrue(notificationIndex > cleanupIndex)
    }

    @Test
    fun metricsAreBlockedUntilHorizontalLayoutIsReady() {
        val script = CssInjector.generateJsBridgeScript(
            isHorizontalPagination = true,
            settings = asymmetricSettings
        )

        assertTrue(script.contains("var isLayoutReady = !window.epubproIsHorizontal"))
        assertTrue(
            script.contains(
                "if (!isLayoutReady || isExecutingScroll || isDraggingPage || " +
                    "isCoverOverlayActive) return"
            )
        )
        assertTrue(script.contains("if (ignoreScrollMetrics && force !== true) return"))
        assertTrue(script.contains("isLayoutReady = true"))
    }

    @Test
    fun pageMetricsBlockedByOverlayAreDeferredAndFlushedOnCleanup() {
        val script = CssInjector.generateJsBridgeScript(
            isHorizontalPagination = true,
            settings = asymmetricSettings
        )

        val notifyBody = script
            .substringAfter("function notifyPageChangeCompleted()")
            .substringBefore("function flushPendingPageMetrics()")
        assertTrue(notifyBody.contains("pendingPageMetrics = true;"))

        val flushBody = script
            .substringAfter("function flushPendingPageMetrics()")
            .substringBefore("function notifyReaderLayoutReady()")
        assertTrue(
            flushBody.contains(
                "if (!isLayoutReady || isExecutingScroll || isDraggingPage || isCoverOverlayActive) return;"
            )
        )
        assertTrue(flushBody.contains("updatePageMetrics(true);"))

        val cleanupBody = script
            .substringAfter("function cleanupCoverOverlay()")
            .substringBefore("function configurePageLayer(")
        val suppressIndex = cleanupBody.indexOf("suppressScrollMetrics(300);")
        val flushIndex = cleanupBody.indexOf("flushPendingPageMetrics();")
        assertTrue(suppressIndex >= 0)
        assertTrue(flushIndex > suppressIndex)
    }

    @Test
    fun pageTurnsAndGesturesAreIgnoredUntilLayoutIsMeasured() {
        val script = CssInjector.generateJsBridgeScript(
            isHorizontalPagination = true,
            settings = asymmetricSettings
        )

        val nextBody = script
            .substringAfter("window.epubproGoNextPage = function()")
            .substringBefore("window.epubproGoPrevPage = function()")
        assertTrue(nextBody.indexOf("if (!isLayoutReady) return;") in 0 until nextBody.indexOf("animateChapterBoundary(1)"))
        val prevBody = script
            .substringAfter("window.epubproGoPrevPage = function()")
            .substringBefore("window.epubproUpdateMetrics")
        assertTrue(prevBody.indexOf("if (!isLayoutReady) return;") in 0 until prevBody.indexOf("animateChapterBoundary(-1)"))

        val touchStartBody = script
            .substringAfter("document.addEventListener('touchstart'")
            .substringBefore("document.addEventListener('touchmove'")
        val blockGuardIndex = touchStartBody.indexOf("isGestureBlockedByLayout = !!window.epubproIsHorizontal && !isLayoutReady;")
        val cleanupInTouchStartIndex = touchStartBody.indexOf("cleanupCoverOverlay();")
        assertTrue(blockGuardIndex >= 0)
        assertTrue(cleanupInTouchStartIndex > blockGuardIndex)

        val touchEndBody = script
            .substringAfter("document.addEventListener('touchend'")
            .substringBefore("if (!isDraggingPage) return;")
        assertTrue(touchEndBody.contains("if (isGestureBlockedByLayout)"))
    }

    @Test
    fun chapterBoundaryWithoutPreviewUsesCoveredFallbackInsteadOfEmptySlide() {
        val script = CssInjector.generateJsBridgeScript(
            isHorizontalPagination = true,
            settings = asymmetricSettings
        )

        val boundaryBody = script
            .substringAfter("function animateChapterBoundary(direction)")
            .substringBefore("initCoverOverlay(")
        assertTrue(boundaryBody.contains("if (!adjacentPreviewHtml || !adjacentPreviewHtml.trim())"))
        assertTrue(boundaryBody.contains("requestFallbackBoundary(direction);"))
    }

    @Test
    fun adjacentChapterCommitResetsPendingMetricsAndDropsBlockedUpdateAfterCleanup() {
        val script = CssInjector.generateJsBridgeScript(
            isHorizontalPagination = true,
            settings = asymmetricSettings
        )

        val replaceBody = script
            .substringAfter("function replaceAdjacentChapterInDocument(")
            .substringBefore("function mountPageOverlay(")
        assertTrue(replaceBody.contains("pendingPageMetrics = false;"))
        assertFalse(
            Regex("""cleanupCoverOverlay\(\);\s*updatePageMetrics\(\);""").containsMatchIn(script)
        )
    }

    @Test
    fun committedChapterHasInitLayoutSafetyNetSoGesturesCannotStayBlocked() {
        val script = CssInjector.generateJsBridgeScript(
            isHorizontalPagination = true,
            settings = asymmetricSettings
        )

        val replaceBody = script
            .substringAfter("function replaceAdjacentChapterInDocument(")
            .substringBefore("function mountPageOverlay(")
        val scheduleIndex = replaceBody.indexOf("scheduleInitLayout();")
        val safetyNetIndex = replaceBody.indexOf("setTimeout(initLayout, 500);")
        assertTrue(scheduleIndex >= 0)
        assertTrue(safetyNetIndex > scheduleIndex)
    }

    @Test
    fun blockedTapStillRunsConfiguredActionSoControlsCanToggle() {
        val script = CssInjector.generateJsBridgeScript(
            isHorizontalPagination = true,
            settings = asymmetricSettings
        )

        val blockedTouchEnd = script
            .substringAfter("document.addEventListener('touchend'")
            .substringBefore("if (!isDraggingPage) return;")
        assertTrue(blockedTouchEnd.contains("handleConfiguredTap(blockedEndX, blockedEndY);"))
    }

    @Test
    fun boundarySwipeWithoutPreviewSnapsBackAndUsesCoveredFallback() {
        val script = CssInjector.generateJsBridgeScript(
            isHorizontalPagination = true,
            settings = asymmetricSettings
        )

        assertTrue(script.contains("(!hasNextChapter || !hasNextPreview)"))
        assertTrue(script.contains("(!hasPreviousChapter || !hasPreviousPreview)"))
        assertTrue(script.contains("var useCoveredFallback = boundaryTriggered && !hasAdjacentPreview;"))
        val finishBody = script
            .substringAfter("function finishBoundaryGesture()")
            .substringBefore("replaceAdjacentChapterInDocument(adjacentHtml, chapterDirection)")
        assertTrue(finishBody.contains("if (useCoveredFallback)"))
        assertTrue(finishBody.contains("requestFallbackBoundary(chapterDirection);"))
        assertTrue(script.contains("(boundaryTriggered && !useCoveredFallback)"))
    }

    @Test
    fun layoutReadyCarriesGenerationAfterInitialPageSettles() {
        val script = CssInjector.generateJsBridgeScript(
            isHorizontalPagination = true,
            settings = asymmetricSettings,
            loadGeneration = 42
        )

        assertTrue(
            script.contains(
                "scrollToPage(targetInitPage, false, notifyReaderLayoutReady)"
            )
        )
        assertTrue(script.contains("window.ReaderJsBridge.onReaderLayoutReady(42)"))
    }
}
