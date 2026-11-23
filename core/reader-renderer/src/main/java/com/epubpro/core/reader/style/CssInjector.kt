package com.epubpro.core.reader.style

import com.epubpro.domain.model.ContentFilterPreferences
import com.epubpro.domain.model.ReaderSettings
import com.epubpro.domain.model.ReaderThemeMode
import com.epubpro.domain.model.TextAlignment
import org.json.JSONArray
import org.json.JSONObject

object CssInjector {

    fun generateMetaAndViewport(): String {
        return """
            <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
        """.trimIndent()
    }

    /**
     * Làm sạch tên font family trước khi nội suy vào CSS để chống CSS injection.
     * Chỉ giữ lại các ký tự `[a-zA-Z0-9\s\-_,']` và trả về `"serif"` nếu kết quả rỗng.
     *
     * @param raw Chuỗi tên font family thô từ cài đặt người dùng.
     * @return Chuỗi tên font family an toàn để nhúng vào CSS.
     */
    fun sanitizeFontFamily(raw: String): String {
        val cleaned = raw.replace(Regex("[^a-zA-Z0-9\\s\\-_,']"), "").trim()
        return cleaned.ifEmpty { "serif" }
    }

    fun generateCss(settings: ReaderSettings, statusFooterHeightDp: Int = 0): String {
        val safeFontFamily = sanitizeFontFamily(settings.fontFamily)
        val (bgColor, textColor) = when (settings.themeMode) {
            ReaderThemeMode.LIGHT -> "#FFFFFF" to "#0F172A"
            ReaderThemeMode.DARK -> "#0F172A" to "#F8FAFC"
            ReaderThemeMode.SEPIA -> "#FBF0D9" to "#4A3B32"
            ReaderThemeMode.PAPER -> "#F5F0E8" to "#3C3530"
            ReaderThemeMode.MIDNIGHT -> "#000000" to "#AAAAAA"
        }

        val fontSizePx = if (settings.fontSizeSp % 1f == 0f) {
            "${settings.fontSizeSp.toInt()}"
        } else {
            "%.1f".format(java.util.Locale.US, settings.fontSizeSp)
        }
        val textAlign = if (settings.textAlignment == TextAlignment.JUSTIFY) "justify" else "left"
        val scrollbarCss = if (settings.showScrollBar) "" else """
            ::-webkit-scrollbar {
                width: 0 !important;
                height: 0 !important;
                display: none !important;
            }
        """.trimIndent()

        val paginationCss = if (settings.isHorizontalPagination) {
            """
            html {
                background-color: $bgColor !important;
                color: $textColor !important;
                width: 100vw !important;
                height: 100vh !important;
                margin: 0 !important;
                padding: 0 !important;
                overflow: hidden !important;
            }
            body {
                background-color: $bgColor !important;
                color: $textColor !important;
                font-family: $safeFontFamily, serif, sans-serif !important;
                font-size: ${fontSizePx}px !important;
                line-height: ${settings.lineHeightRatio} !important;
                margin: 0 !important;
                padding-top: ${settings.marginTopDp}px !important;
                padding-bottom: ${settings.marginBottomDp + statusFooterHeightDp}px !important;
                padding-left: ${settings.marginLeftDp}px !important;
                padding-right: ${settings.marginRightDp}px !important;
                box-sizing: border-box !important;
                word-wrap: break-word !important;
                overflow-wrap: break-word !important;

                width: 100vw !important;
                height: 100vh !important;

                -webkit-column-width: calc(100vw - ${settings.marginLeftDp + settings.marginRightDp}px) !important;
                -moz-column-width: calc(100vw - ${settings.marginLeftDp + settings.marginRightDp}px) !important;
                column-width: calc(100vw - ${settings.marginLeftDp + settings.marginRightDp}px) !important;

                -webkit-column-gap: ${settings.marginLeftDp + settings.marginRightDp}px !important;
                -moz-column-gap: ${settings.marginLeftDp + settings.marginRightDp}px !important;
                column-gap: ${settings.marginLeftDp + settings.marginRightDp}px !important;

                -webkit-column-fill: auto !important;
                -moz-column-fill: auto !important;
                column-fill: auto !important;

                overflow: visible !important;
            }
            body > *,
            .epubpro-page-layer > * {
                margin-left: 0 !important;
                margin-right: 0 !important;
                padding-left: 0 !important;
                padding-right: 0 !important;
                max-width: 100% !important;
                box-sizing: border-box !important;
            }
            body > div, body > section, body div, body section, body p, body span,
            .epubpro-page-layer > div, .epubpro-page-layer > section, .epubpro-page-layer div, .epubpro-page-layer section, .epubpro-page-layer p, .epubpro-page-layer span {
                margin-left: 0 !important;
                margin-right: 0 !important;
                padding-left: 0 !important;
                padding-right: 0 !important;
                width: auto !important;
                max-width: 100% !important;
            }
            """
        } else {
            """
            html {
                background-color: $bgColor !important;
                color: $textColor !important;
                overflow-x: hidden !important;
                overflow-y: auto !important;
                height: auto !important;
            }
            body {
                background-color: $bgColor !important;
                color: $textColor !important;
                font-family: $safeFontFamily, serif, sans-serif !important;
                font-size: ${fontSizePx}px !important;
                line-height: ${settings.lineHeightRatio} !important;
                padding-top: ${settings.marginTopDp}px !important;
                padding-bottom: ${settings.marginBottomDp + statusFooterHeightDp}px !important;
                padding-left: ${settings.marginLeftDp}px !important;
                padding-right: ${settings.marginRightDp}px !important;
                margin: 0 auto !important;
                box-sizing: border-box !important;
                word-wrap: break-word !important;
                overflow-wrap: break-word !important;
                overflow-x: hidden !important;
                overflow-y: auto !important;
                height: auto !important;
                width: 100% !important;
                max-width: 100% !important;
            }
            body > div, body > section, body div, body section, body p, body span {
                margin-left: 0 !important;
                margin-right: 0 !important;
                padding-left: 0 !important;
                padding-right: 0 !important;
                width: auto !important;
                max-width: 100% !important;
            }
            """
        }

        return """
            <style id="epubpro-injected-style">
                p, span, blockquote, li {
                    color: $textColor !important;
                    line-height: ${settings.lineHeightRatio} !important;
                }
                p {
                    margin-top: 0 !important;
                    margin-bottom: ${settings.paragraphSpacingDp}px !important;
                    text-indent: ${settings.firstLineIndentDp}px !important;
                    text-align: $textAlign !important;
                    word-break: break-word !important;
                    overflow-wrap: break-word !important;
                }
                p:empty {
                    display: none !important;
                }
                h1, h2, h3, h4, h5, h6 {
                    color: $textColor !important;
                    line-height: 1.3 !important;
                    margin-top: 0.5em !important;
                    margin-bottom: 0.5em !important;
                }
                img, svg, video, iframe {
                    max-width: 100% !important;
                    max-height: calc(100vh - ${settings.marginTopDp + settings.marginBottomDp + 16}px) !important;
                    object-fit: contain !important;
                    height: auto !important;
                    display: block !important;
                    margin: 0.5em auto !important;
                }
                * {
                    max-width: 100% !important;
                    box-sizing: border-box !important;
                }
                a {
                    color: inherit !important;
                    text-decoration: none !important;
                }
                ::selection {
                    background: rgba(30, 58, 138, 0.3) !important;
                }
                .tts-active-paragraph {
                    background-color: rgba(217, 119, 87, 0.25) !important;
                    border-left: 4px solid #D97757 !important;
                    border-radius: 4px !important;
                    padding-left: 4px !important;
                    transition: background-color 0.3s ease !important;
                }
                $scrollbarCss
                $paginationCss
            </style>
        """.trimIndent()
    }

    /**
     * Mã hóa an toàn một chuỗi HTML/văn bản thành biểu thức chuỗi JSON để truyền làm tham số cho hàm JavaScript qua [android.webkit.WebView.evaluateJavascript].
     *
     * @param value Chuỗi văn bản/HTML cần mã hóa.
     * @return Chuỗi JSON an toàn đã escape các ký tự đặc biệt và dấu ngoặc nhọn.
     */
    fun quoteForJsArgument(value: String): String = JSONObject.quote(value)
        .replace("<", "\\u003C")
        .replace(">", "\\u003E")
        .replace("&", "\\u0026")

    fun generateJsBridgeScript(
        isHorizontalPagination: Boolean,
        initialPage: Int = 1,
        initialVisibleParagraphIndex: Int = 0,
        settings: ReaderSettings = ReaderSettings(),
        statusFooterHeightDp: Int = 0,
        previousChapterHtml: String? = null,
        nextChapterHtml: String? = null,
        filterPreferences: ContentFilterPreferences = ContentFilterPreferences(),
        loadGeneration: Int = 0,
        hasPreviousChapter: Boolean = true,
        hasNextChapter: Boolean = true
    ): String {
        val (bgColor, _) = when (settings.themeMode) {
            ReaderThemeMode.LIGHT -> "#FFFFFF" to "#0F172A"
            ReaderThemeMode.DARK -> "#0F172A" to "#F8FAFC"
            ReaderThemeMode.SEPIA -> "#FBF0D9" to "#4A3B32"
            ReaderThemeMode.PAPER -> "#F5F0E8" to "#3C3530"
            ReaderThemeMode.MIDNIGHT -> "#000000" to "#AAAAAA"
        }

        val previousChapterHtmlJson = quoteForJsArgument(previousChapterHtml.orEmpty())
        val nextChapterHtmlJson = quoteForJsArgument(nextChapterHtml.orEmpty())
        val tapZoneActionsJson = JSONObject.quote(settings.tapZoneActions.joinToString(",") { it.name })

        val rulesJsonArray = JSONArray()
        if (filterPreferences.isFilterEnabled) {
            filterPreferences.rules.filter { it.isEnabled && it.pattern.isNotBlank() }.forEach { rule ->
                val obj = JSONObject().apply {
                    put("pattern", rule.pattern)
                    put("replacement", rule.replacement)
                    put("isRegex", rule.isRegex)
                    put("isEnabled", rule.isEnabled)
                }
                rulesJsonArray.put(obj)
            }
        }
        val filterRulesJson = quoteForJsArgument(rulesJsonArray.toString())
        val isFilterEnabled = filterPreferences.isFilterEnabled

        return """
            (function() {
                window.epubproIsHorizontal = $isHorizontalPagination;
                var targetInitPage = $initialPage;
                var targetInitParagraph = $initialVisibleParagraphIndex;
                var marginTop = ${settings.marginTopDp};
                var marginBottom = ${settings.marginBottomDp + statusFooterHeightDp};
                var marginLeft = ${settings.marginLeftDp};
                var marginRight = ${settings.marginRightDp};
                var transitionSpeedMs = ${if (settings.enablePageAnimation) settings.pageTurnSpeedMs else 0};
                var currentPage = targetInitPage;
                var totalPages = 1;
                var startTouchX = 0;
                var startTouchY = 0;
                var startTouchTime = 0;
                var isExecutingScroll = false;
                var isLayoutReady = !window.epubproIsHorizontal;
                var hasInitializedLayout = false;
                var hasNotifiedLayoutReady = false;
                var scrollExtentElement = null;
                var scrollExtentBaseWidth = 0;
                var scrollExtentWidth = 0;

                function dbg(tag, msg) {
                    try {
                        if (window.ReaderJsBridge && window.ReaderJsBridge.onDebugLog) {
                            window.ReaderJsBridge.onDebugLog(tag, msg);
                        }
                    } catch(e) {}
                }

                function getEpubproTtsParagraphs() {
                    try {
                        var blockTags = ['body', 'section', 'article', 'main', 'center', 'td', 'th', 'p', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'li', 'blockquote', 'div'];
                        var isBlock = function(el) {
                            return el && el.tagName && blockTags.indexOf(el.tagName.toLowerCase()) !== -1;
                        };
                        var getClosestBlock = function(node) {
                            var p = node.parentNode;
                            while (p && !isBlock(p)) {
                                p = p.parentNode;
                            }
                            return p;
                        };

                        var paragraphs = [];
                        var currentBlock = null;
                        var currentText = '';

                        var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null, false);
                        var node;
                        while (node = walker.nextNode()) {
                            var txt = node.nodeValue;
                            if (txt.trim().length > 0) {
                                var block = getClosestBlock(node);
                                if (block) {
                                    if (block !== currentBlock) {
                                        if (currentBlock && currentText.trim().length > 1) {
                                            paragraphs.push(currentBlock);
                                        }
                                        currentBlock = block;
                                        currentText = '';
                                    }
                                    currentText += txt;
                                }
                            } else {
                                // If it's pure whitespace, but we are inside a block, we still append it to preserve spaces between words!
                                // Wait, in Kotlin we only checked `isNotBlank()` before processing.
                                // If Kotlin skips blank nodes entirely, we MUST skip them here too!
                                // Wait, if Kotlin skips blank nodes, then spaces between words separated by a blank TextNode are LOST in Kotlin!
                                // Ah! Let's check Kotlin code: `if (txt.isNotBlank()) { ... currentText.append(node.wholeText) }`
                                // So Kotlin IGNORES blank text nodes! JS MUST DO THE SAME to match perfectly!
                            }
                        }
                        if (currentBlock && currentText.trim().length > 1) {
                            paragraphs.push(currentBlock);
                        }
                        
                        return paragraphs;
                    } catch (e) {
                        return [];
                    }
                }

                function forceBodyDimensions() {
                    if (!window.epubproIsHorizontal) return;
                    var html = document.documentElement;
                    var body = document.body;
                    if (!html || !body) return;

                    var vh = window.innerHeight;
                    var vw = window.innerWidth;

                    // Force html dimensions via inline !important
                    html.style.setProperty('height', vh + 'px', 'important');
                    html.style.setProperty('width', vw + 'px', 'important');
                    html.style.setProperty('margin', '0', 'important');
                    html.style.setProperty('padding', '0', 'important');
                    html.style.setProperty('overflow', 'hidden', 'important');

                    var colWidth = vw - marginLeft - marginRight;
                    var colGap = marginLeft + marginRight;

                    // Force body dimensions via inline !important
                    body.style.setProperty('height', vh + 'px', 'important');
                    body.style.setProperty('width', vw + 'px', 'important');
                    body.style.setProperty('margin', '0', 'important');
                    body.style.setProperty('padding-top', marginTop + 'px', 'important');
                    body.style.setProperty('padding-bottom', marginBottom + 'px', 'important');
                    body.style.setProperty('padding-left', marginLeft + 'px', 'important');
                    body.style.setProperty('padding-right', marginRight + 'px', 'important');
                    body.style.setProperty('box-sizing', 'border-box', 'important');
                    body.style.setProperty('column-width', colWidth + 'px', 'important');
                    body.style.setProperty('-webkit-column-width', colWidth + 'px', 'important');
                    body.style.setProperty('column-gap', colGap + 'px', 'important');
                    body.style.setProperty('-webkit-column-gap', colGap + 'px', 'important');
                    body.style.setProperty('column-fill', 'auto', 'important');
                    body.style.setProperty('-webkit-column-fill', 'auto', 'important');
                    body.style.setProperty('overflow', 'visible', 'important');
                    body.style.setProperty('word-wrap', 'break-word', 'important');
                    body.style.setProperty('overflow-wrap', 'break-word', 'important');
                    // Keep the multi-column container at one viewport. Expanding
                    // the body changes the geometry of the final column.
                    body.style.removeProperty('min-width');

                    dbg('FORCE', 'vh=' + vh + ' vw=' + vw + ' bodyH=' + body.clientHeight);
                }

                function dumpLayout() {
                    try {
                        var html = document.documentElement;
                        var body = document.body;
                        if (!html || !body) { dbg('DUMP', 'html or body is null'); return; }

                        var htmlCS = window.getComputedStyle(html);
                        var bodyCS = window.getComputedStyle(body);

                        dbg('HTML', 'w=' + html.clientWidth + ' h=' + html.clientHeight
                            + ' scrollW=' + html.scrollWidth + ' scrollH=' + html.scrollHeight
                            + ' overflow=' + htmlCS.overflow);

                        dbg('BODY', 'w=' + body.clientWidth + ' h=' + body.clientHeight
                            + ' scrollW=' + body.scrollWidth + ' scrollH=' + body.scrollHeight
                            + ' colWidth=' + bodyCS.columnWidth + ' colGap=' + bodyCS.columnGap
                            + ' height=' + bodyCS.height + ' overflow=' + bodyCS.overflow
                            + ' bodyStyleAttr=' + (body.getAttribute('style') || 'none'));

                        dbg('WINDOW', 'innerW=' + window.innerWidth + ' innerH=' + window.innerHeight
                            + ' scrollX=' + window.scrollX);
                    } catch(e) {
                        dbg('ERROR', 'dumpLayout: ' + e.message);
                    }
                }

                function getFirstVisibleParagraphIndex() {
                    try {
                        var paragraphs = getEpubproTtsParagraphs();
                        var screenWidth = window.innerWidth || document.documentElement.clientWidth || 1;
                        var screenHeight = window.innerHeight || document.documentElement.clientHeight || 1;

                        for (var i = 0; i < paragraphs.length; i++) {
                            var rect = paragraphs[i].getBoundingClientRect();
                            if (window.epubproIsHorizontal) {
                                if (rect.right > 10 && rect.left < screenWidth - 10 && rect.bottom > 0 && rect.top < screenHeight) {
                                    return i;
                                }
                            } else {
                                if (rect.bottom > 0 && rect.top < screenHeight) {
                                    return i;
                                }
                            }
                        }
                    } catch(e) {}
                    return 0;
                }

                var ignoreScrollMetrics = false;
                var ignoreScrollTimeout = null;

                function suppressScrollMetrics(durationMs) {
                    ignoreScrollMetrics = true;
                    if (ignoreScrollTimeout) clearTimeout(ignoreScrollTimeout);
                    ignoreScrollTimeout = setTimeout(function() {
                        ignoreScrollMetrics = false;
                    }, durationMs || 250);
                }

                function setHorizontalScrollExtentWidth(requiredWidth) {
                    var html = document.documentElement;
                    if (!html) return;

                    if (!scrollExtentElement || !scrollExtentElement.parentNode) {
                        scrollExtentElement = document.createElement('div');
                        scrollExtentElement.id = 'epubpro-scroll-extent';
                        scrollExtentElement.setAttribute('aria-hidden', 'true');
                        scrollExtentElement.setAttribute('role', 'presentation');
                        scrollExtentElement.style.setProperty('position', 'absolute', 'important');
                        scrollExtentElement.style.setProperty('top', '0px', 'important');
                        scrollExtentElement.style.setProperty('width', '1px', 'important');
                        scrollExtentElement.style.setProperty('height', '1px', 'important');
                        scrollExtentElement.style.setProperty('margin', '0px', 'important');
                        scrollExtentElement.style.setProperty('padding', '0px', 'important');
                        scrollExtentElement.style.setProperty('opacity', '0', 'important');
                        scrollExtentElement.style.setProperty('pointer-events', 'none', 'important');
                        scrollExtentElement.style.setProperty('user-select', 'none', 'important');
                        html.appendChild(scrollExtentElement);
                    }

                    // This marker extends the document without participating in
                    // the body's multi-column flow.
                    scrollExtentElement.style.setProperty(
                        'left',
                        Math.max(0, requiredWidth - 1) + 'px',
                        'important'
                    );
                    scrollExtentWidth = requiredWidth;
                }

                function updateHorizontalScrollExtent(pageWidth) {
                    if (!window.epubproIsHorizontal) return;
                    var requiredWidth = Math.max(pageWidth, totalPages * pageWidth);
                    if (scrollExtentBaseWidth !== requiredWidth) {
                        scrollExtentBaseWidth = requiredWidth;
                        setHorizontalScrollExtentWidth(requiredWidth);
                    }
                }

                function measureTotalPages() {
                    var pw = window.innerWidth || document.documentElement.clientWidth || 1;
                    if (!window.epubproIsHorizontal) {
                        totalPages = 1;
                        return pw;
                    }
                    var body = document.body;
                    if (!body) return pw;

                    body.style.removeProperty('min-width');
                    var sw = body.scrollWidth;
                    totalPages = Math.max(1, Math.ceil((sw - 1) / pw));
                    updateHorizontalScrollExtent(pw);

                    return pw;
                }

                function notifyPositionChanged(paragraphIndex) {
                    if (window.ReaderJsBridge && window.ReaderJsBridge.onCfiChanged) {
                        window.ReaderJsBridge.onCfiChanged('epubpro:paragraph:' + Math.max(0, paragraphIndex));
                    }
                }

                function updatePageMetrics() {
                    if (!window.epubproIsHorizontal) {
                        var verticalParagraphIndex = getFirstVisibleParagraphIndex();
                        notifyPositionChanged(verticalParagraphIndex);
                        if (window.ReaderJsBridge && window.ReaderJsBridge.onPageChanged) {
                            window.ReaderJsBridge.onPageChanged(1, 1, verticalParagraphIndex);
                        }
                        return;
                    }
                    if (!isLayoutReady || isExecutingScroll || isDraggingPage || isCoverOverlayActive || ignoreScrollMetrics) return;
                    var pw = measureTotalPages();
                    var sl = window.scrollX || window.pageXOffset || 0;
                    currentPage = Math.min(totalPages, Math.max(1, Math.round(sl / pw) + 1));

                    dbg('METRICS', 'pw=' + pw + ' totalPages=' + totalPages + ' currentPage=' + currentPage);

                    var visibleIndex = getFirstVisibleParagraphIndex();
                    notifyPositionChanged(visibleIndex);

                    if (window.ReaderJsBridge && window.ReaderJsBridge.onPageChanged) {
                        window.ReaderJsBridge.onPageChanged(currentPage, totalPages, visibleIndex);
                    }
                }

                function notifyPageChangeCompleted() {
                    if (isDraggingPage || isCoverOverlayActive) return;
                    if (window.ReaderJsBridge && window.ReaderJsBridge.onPageChanged) {
                        var visibleIndex = getFirstVisibleParagraphIndex();
                        notifyPositionChanged(visibleIndex);
                        window.ReaderJsBridge.onPageChanged(currentPage, totalPages, visibleIndex);
                    }
                }

                function notifyReaderLayoutReady() {
                    if (hasNotifiedLayoutReady) return;
                    hasNotifiedLayoutReady = true;
                    window.requestAnimationFrame(function() {
                        if (window.ReaderJsBridge && window.ReaderJsBridge.onReaderLayoutReady) {
                            window.ReaderJsBridge.onReaderLayoutReady($loadGeneration);
                        }
                    });
                }

                function settlePageOffset(page, attemptsRemaining, onSettled) {
                    var pw = window.innerWidth || document.documentElement.clientWidth || 1;
                    var boundedPage = Math.min(totalPages, Math.max(1, page));
                    var targetX = (boundedPage - 1) * pw;
                    currentPage = boundedPage;
                    window.scrollTo(targetX, 0);

                    window.requestAnimationFrame(function() {
                        var actualX = window.scrollX || window.pageXOffset || 0;
                        var shortfall = targetX - actualX;
                        if (Math.abs(shortfall) <= 1 || attemptsRemaining <= 0) {
                            if (Math.abs(shortfall) > 1) {
                                dbg('SCROLL_MISMATCH', 'target=' + targetX + ' actual=' + actualX
                                    + ' pages=' + totalPages + ' extent=' + scrollExtentWidth);
                            } else if (boundedPage === totalPages) {
                                dbg('LAST_PAGE_OFFSET', 'target=' + targetX + ' actual=' + actualX
                                    + ' pages=' + totalPages + ' extent=' + scrollExtentWidth);
                            }
                            if (onSettled) onSettled(actualX, targetX);
                            return;
                        }
                        if (shortfall > 1) {
                            // Calibrate against the WebView's real native clamp.
                            // This uses the measured shortfall, never a margin-
                            // specific compensation.
                            setHorizontalScrollExtentWidth(scrollExtentWidth + shortfall);
                        }
                        settlePageOffset(boundedPage, attemptsRemaining - 1, onSettled);
                    });
                }

                function scrollToPage(page, animate, onSettled) {
                    if (!window.epubproIsHorizontal) return;
                    var pw = window.innerWidth || document.documentElement.clientWidth || 1;
                    var targetX = (page - 1) * pw;
                    isExecutingScroll = true;
                    currentPage = page;

                    if (animate !== false && transitionSpeedMs > 0) {
                        var startX = window.scrollX || window.pageXOffset;
                        var distance = targetX - startX;
                        var duration = transitionSpeedMs;
                        var startTime = null;

                        function step(timestamp) {
                            if (!startTime) startTime = timestamp;
                            var progress = timestamp - startTime;
                            var percent = Math.min(progress / duration, 1);
                            var easeOut = 1 - Math.pow(1 - percent, 3);
                            window.scrollTo(startX + distance * easeOut, 0);

                            if (progress < duration) {
                                window.requestAnimationFrame(step);
                            } else {
                                settlePageOffset(currentPage, 2, function() {
                                    isExecutingScroll = false;
                                    suppressScrollMetrics(100);
                                    notifyPageChangeCompleted();
                                    if (onSettled) onSettled();
                                });
                            }
                        }
                        window.requestAnimationFrame(step);
                    } else {
                        settlePageOffset(currentPage, 2, function() {
                            isExecutingScroll = false;
                            suppressScrollMetrics(100);
                            notifyPageChangeCompleted();
                            if (onSettled) onSettled();
                        });
                    }
                }

                function requestFallbackBoundary(direction) {
                    if (!window.ReaderJsBridge) return;
                    if (direction > 0 && typeof window.ReaderJsBridge.onNextChapterRequested === 'function') {
                        window.ReaderJsBridge.onNextChapterRequested();
                    } else if (direction < 0 && typeof window.ReaderJsBridge.onPreviousChapterRequested === 'function') {
                        window.ReaderJsBridge.onPreviousChapterRequested();
                    }
                }

                function animateChapterBoundary(direction) {
                    var hasAdjacentChapter = direction > 0 ? hasNextChapter : hasPreviousChapter;
                    if (!hasAdjacentChapter) {
                        requestFallbackBoundary(direction);
                        return;
                    }

                    if (!isCoverOverlayActive) {
                        initCoverOverlay(direction > 0 ? -1 : 1);
                    }
                    var boundaryOverlay = activeTopOverlay;
                    if (!boundaryOverlay) {
                        requestFallbackBoundary(direction);
                        return;
                    }

                    var token = gestureToken;
                    var pageWidth = window.innerWidth || document.documentElement.clientWidth || 1;
                    var chapterCommitted = false;
                    function finishTapBoundary() {
                        if (chapterCommitted || token !== gestureToken) return;
                        chapterCommitted = true;
                        var adjacentHtml = direction > 0 ? nextChapterHtml : previousChapterHtml;
                        var committedInDocument = replaceAdjacentChapterInDocument(adjacentHtml, direction);
                        if (committedInDocument && window.ReaderJsBridge && window.ReaderJsBridge.onAdjacentChapterCommitted) {
                            window.ReaderJsBridge.onAdjacentChapterCommitted(direction);
                        } else {
                            requestFallbackBoundary(direction);
                        }
                        setTimeout(function() {
                            if (token === gestureToken) {
                                cleanupCoverOverlay();
                                updatePageMetrics();
                            }
                        }, Math.max(transitionSpeedMs + 120, 180));
                    }

                    boundaryOverlay.addEventListener('transitionend', function onTapBoundaryEnd(event) {
                        if (event.propertyName !== 'transform') return;
                        boundaryOverlay.removeEventListener('transitionend', onTapBoundaryEnd);
                        finishTapBoundary();
                    });
                    boundaryOverlay.style.transition =
                        'transform ' + (transitionSpeedMs / 1000).toFixed(2) + 's cubic-bezier(0.2, 0.8, 0.2, 1)';
                    boundaryOverlay.style.transform =
                        direction > 0 ? 'translateX(-' + pageWidth + 'px)' : 'translateX(' + pageWidth + 'px)';
                    setTimeout(finishTapBoundary, transitionSpeedMs + 100);
                }

                window.epubproGoNextPage = function() {
                    if (!window.epubproIsHorizontal) {
                        var root = document.scrollingElement || document.documentElement;
                        var atEnd = root.scrollTop + window.innerHeight >= root.scrollHeight - 2;
                        if (atEnd && window.ReaderJsBridge && window.ReaderJsBridge.onNextChapterRequested) {
                            window.ReaderJsBridge.onNextChapterRequested();
                        } else {
                            window.scrollBy({ top: window.innerHeight * 0.85, behavior: transitionSpeedMs > 0 ? 'smooth' : 'auto' });
                        }
                        return;
                    }
                    if (currentPage < totalPages) {
                        scrollToPage(currentPage + 1, true);
                    } else {
                        animateChapterBoundary(1);
                    }
                };

                window.epubproGoPrevPage = function() {
                    if (!window.epubproIsHorizontal) {
                        var root = document.scrollingElement || document.documentElement;
                        if (root.scrollTop <= 2 && window.ReaderJsBridge && window.ReaderJsBridge.onPreviousChapterRequested) {
                            window.ReaderJsBridge.onPreviousChapterRequested();
                        } else {
                            window.scrollBy({ top: -window.innerHeight * 0.85, behavior: transitionSpeedMs > 0 ? 'smooth' : 'auto' });
                        }
                        return;
                    }
                    if (currentPage > 1) {
                        scrollToPage(currentPage - 1, true);
                    } else {
                        animateChapterBoundary(-1);
                    }
                };

                window.epubproUpdateMetrics = updatePageMetrics;

                var scrollTimeout = null;
                window.addEventListener('scroll', function() {
                    if (ignoreScrollMetrics || isDraggingPage || isCoverOverlayActive) return;
                    if (scrollTimeout) clearTimeout(scrollTimeout);
                    scrollTimeout = setTimeout(updatePageMetrics, 80);
                }, { passive: true });

                window.addEventListener('resize', function() {
                    if (!hasInitializedLayout) return;
                    var pageToRestore = currentPage;
                    isLayoutReady = !window.epubproIsHorizontal;
                    forceBodyDimensions();
                    window.requestAnimationFrame(function() {
                        window.requestAnimationFrame(function() {
                            if (window.epubproIsHorizontal) {
                                measureTotalPages();
                                isLayoutReady = true;
                                scrollToPage(Math.min(totalPages, pageToRestore), false);
                            } else {
                                updatePageMetrics();
                            }
                        });
                    });
                }, { passive: true });

                function initLayout() {
                    if (hasInitializedLayout || document.readyState !== 'complete' || !document.body) return;
                    hasInitializedLayout = true;
                    dbg('INIT', 'initLayout called, readyState=' + document.readyState + ', targetInitPage=' + targetInitPage);
                    forceBodyDimensions();
                    dumpLayout();
                    window.requestAnimationFrame(function() {
                        window.requestAnimationFrame(function() {
                            measureTotalPages();
                            var paragraphs = getEpubproTtsParagraphs();
                            var anchor = paragraphs[Math.min(paragraphs.length - 1, Math.max(0, targetInitParagraph))];
                            if (anchor && targetInitParagraph > 0) {
                                if (window.epubproIsHorizontal) {
                                    var pw = window.innerWidth || 1;
                                    var anchorX = anchor.getBoundingClientRect().left + (window.scrollX || 0);
                                    targetInitPage = Math.floor(anchorX / pw) + 1;
                                } else {
                                    anchor.scrollIntoView({ block: 'start', behavior: 'auto' });
                                }
                            }
                            targetInitPage = Math.min(totalPages, Math.max(1, targetInitPage));
                            if (window.epubproIsHorizontal) {
                                isLayoutReady = true;
                                scrollToPage(targetInitPage, false, notifyReaderLayoutReady);
                            } else {
                                updatePageMetrics();
                                notifyReaderLayoutReady();
                            }
                        });
                    });
                }

                function scheduleInitLayout() {
                    var scheduleAfterFonts = function() {
                        window.requestAnimationFrame(function() {
                            window.requestAnimationFrame(initLayout);
                        });
                    };
                    if (document.fonts && document.fonts.ready) {
                        document.fonts.ready.then(scheduleAfterFonts, scheduleAfterFonts);
                    } else {
                        scheduleAfterFonts();
                    }
                }

                if (document.readyState === 'complete') {
                    scheduleInitLayout();
                } else {
                    window.addEventListener('load', scheduleInitLayout, { once: true });
                }
                setTimeout(initLayout, 500);

                var startScrollX = 0;
                var currentDeltaX = 0;
                var isDraggingPage = false;
                var isHorizontalDragConfirmed = false;
                var activeTopOverlay = null;
                var activeBottomOverlay = null;
                var isCoverOverlayActive = false;
                var dragDirection = 0;
                var gestureToken = 0;
                var themeBgColor = '$bgColor';
                var previousChapterHtml = $previousChapterHtmlJson;
                var nextChapterHtml = $nextChapterHtmlJson;
                var hasPreviousChapter = $hasPreviousChapter;
                var hasNextChapter = $hasNextChapter;
                var tapZoneActions = $tapZoneActionsJson.split(',');

                window.epubproDocumentGeneration = $loadGeneration;
                window.epubproSetAdjacentChapters = function(generation, prevHtml, nextHtml, hasPrev, hasNext) {
                    if (generation !== window.epubproDocumentGeneration) return;
                    previousChapterHtml = prevHtml || '';
                    nextChapterHtml = nextHtml || '';
                    if (typeof hasPrev === 'boolean') hasPreviousChapter = hasPrev;
                    if (typeof hasNext === 'boolean') hasNextChapter = hasNext;
                };

                window.epubproApplyRuntimeSettings = function(speedMs, actionsCsv) {
                    transitionSpeedMs = Math.max(0, Number(speedMs) || 0);
                    if (typeof actionsCsv === 'string') {
                        var parsedActions = actionsCsv.split(',');
                        if (parsedActions.length === 9) tapZoneActions = parsedActions;
                    }
                };

                function handleConfiguredTap(x, y) {
                    var width = window.innerWidth || 1;
                    var height = window.innerHeight || 1;
                    var column = Math.min(2, Math.max(0, Math.floor(x / (width / 3))));
                    var row = Math.min(2, Math.max(0, Math.floor(y / (height / 3))));
                    var zoneIndex = row * 3 + column;
                    var action = tapZoneActions[zoneIndex] || 'TOGGLE_CONTROLS';
                    if (action === 'PREV_PAGE') {
                        window.epubproGoPrevPage();
                    } else if (action === 'NEXT_PAGE') {
                        window.epubproGoNextPage();
                    } else if (window.ReaderJsBridge && window.ReaderJsBridge.onPageTapped) {
                        window.ReaderJsBridge.onPageTapped();
                    }
                }

                function cleanupCoverOverlay() {
                    // Restore body scroll ability
                    var body = document.body;
                    if (body) body.style.removeProperty('touch-action');
                    var html = document.documentElement;
                    if (html) html.style.removeProperty('touch-action');
                    if (activeTopOverlay && activeTopOverlay.parentNode) {
                        activeTopOverlay.parentNode.removeChild(activeTopOverlay);
                    }
                    if (activeBottomOverlay && activeBottomOverlay.parentNode) {
                        activeBottomOverlay.parentNode.removeChild(activeBottomOverlay);
                    }
                    if (window.activeBackdropOverlay && window.activeBackdropOverlay.parentNode) {
                        window.activeBackdropOverlay.parentNode.removeChild(window.activeBackdropOverlay);
                    }
                    activeTopOverlay = null;
                    activeBottomOverlay = null;
                    window.activeBackdropOverlay = null;
                    isCoverOverlayActive = false;
                    dragDirection = 0;
                    isDraggingPage = false;
                    isHorizontalDragConfirmed = false;
                    currentDeltaX = 0;
                    suppressScrollMetrics(300);
                }

                function configurePageLayer(layer, pw, vh, bg) {
                    var bodyStyle = window.getComputedStyle(document.body);
                    layer.className = 'epubpro-page-layer';
                    layer.setAttribute('aria-hidden', 'true');
                    layer.style.setProperty('position', 'absolute', 'important');
                    layer.style.setProperty('top', '0px', 'important');
                    layer.style.setProperty('left', '0px', 'important');
                    layer.style.setProperty('display', 'block', 'important');
                    layer.style.setProperty('width', pw + 'px', 'important');
                    layer.style.setProperty('min-width', pw + 'px', 'important');
                    layer.style.setProperty('max-width', 'none', 'important');
                    layer.style.setProperty('height', vh + 'px', 'important');
                    layer.style.setProperty('margin', '0px', 'important');
                    layer.style.setProperty('padding-top', marginTop + 'px', 'important');
                    layer.style.setProperty('padding-bottom', marginBottom + 'px', 'important');
                    var colWidth = pw - marginLeft - marginRight;
                    var colGap = marginLeft + marginRight;

                    layer.style.setProperty('padding-left', marginLeft + 'px', 'important');
                    layer.style.setProperty('padding-right', marginRight + 'px', 'important');
                    layer.style.setProperty('box-sizing', 'border-box', 'important');
                    layer.style.setProperty('column-width', colWidth + 'px', 'important');
                    layer.style.setProperty('-webkit-column-width', colWidth + 'px', 'important');
                    layer.style.setProperty('column-gap', colGap + 'px', 'important');
                    layer.style.setProperty('-webkit-column-gap', colGap + 'px', 'important');
                    layer.style.setProperty('column-fill', 'auto', 'important');
                    layer.style.setProperty('-webkit-column-fill', 'auto', 'important');
                    layer.style.setProperty('overflow', 'visible', 'important');
                    layer.style.setProperty('pointer-events', 'none', 'important');
                    layer.style.setProperty('touch-action', 'none', 'important');
                    layer.style.setProperty('background-color', bg, 'important');
                    layer.style.setProperty('color', bodyStyle.color, 'important');
                    layer.style.setProperty('font-family', bodyStyle.fontFamily, 'important');
                    layer.style.setProperty('font-size', bodyStyle.fontSize, 'important');
                    layer.style.setProperty('line-height', bodyStyle.lineHeight, 'important');
                }

                function sanitizePageLayer(layer) {
                    var removable = layer.querySelectorAll(
                        'script, #epubpro-top-overlay, #epubpro-bottom-overlay, #epubpro-backdrop-overlay'
                    );
                    for (var i = 0; i < removable.length; i++) {
                        if (removable[i].parentNode) removable[i].parentNode.removeChild(removable[i]);
                    }

                    var elementsWithIds = layer.querySelectorAll('[id]');
                    for (var j = 0; j < elementsWithIds.length; j++) {
                        elementsWithIds[j].removeAttribute('id');
                    }
                }

                function createPageLayerFromNodes(nodes, pw, vh, bg) {
                    var layer = document.createElement('div');
                    for (var i = 0; i < nodes.length; i++) {
                        layer.appendChild(nodes[i].cloneNode(true));
                    }
                    sanitizePageLayer(layer);
                    configurePageLayer(layer, pw, vh, bg);
                    return layer;
                }

                function createCurrentChapterLayer(body, pw, vh, bg) {
                    return createPageLayerFromNodes(body.childNodes, pw, vh, bg);
                }

                function createAdjacentChapterLayer(html, pw, vh, bg) {
                    if (!html || !html.trim()) return null;
                    try {
                        var parsed = new DOMParser().parseFromString(html, 'text/html');
                        if (!parsed.body) return null;
                        return createPageLayerFromNodes(parsed.body.childNodes, pw, vh, bg);
                    } catch (error) {
                        dbg('PREVIEW_ERROR', error.message || 'Unable to parse adjacent chapter');
                        return null;
                    }
                }

                function replaceAdjacentChapterInDocument(html, direction) {
                    if (!html || !html.trim() || !document.body) return false;
                    try {
                        var parsed = new DOMParser().parseFromString(html, 'text/html');
                        if (!parsed.body) return false;
                        sanitizePageLayer(parsed.body);
                        var activeTtsInParsed = parsed.body.querySelectorAll('.tts-active-paragraph');
                        for (var k = 0; k < activeTtsInParsed.length; k++) {
                            activeTtsInParsed[k].classList.remove('tts-active-paragraph');
                        }
                        var activeTtsInBody = document.body.querySelectorAll('.tts-active-paragraph');
                        for (var m = 0; m < activeTtsInBody.length; m++) {
                            activeTtsInBody[m].classList.remove('tts-active-paragraph');
                        }
                        var oldHtml = document.body.innerHTML;
                        var nodes = [];
                        for (var i = 0; i < parsed.body.childNodes.length; i++) {
                            nodes.push(parsed.body.childNodes[i].cloneNode(true));
                        }
                        document.body.innerHTML = '';
                        for (var j = 0; j < nodes.length; j++) {
                            document.body.appendChild(nodes[j]);
                        }
                        if (direction > 0) {
                            previousChapterHtml = oldHtml;
                            nextChapterHtml = '';
                            targetInitPage = 1;
                        } else {
                            previousChapterHtml = '';
                            nextChapterHtml = oldHtml;
                            targetInitPage = 1000000;
                        }
                        targetInitParagraph = 0;
                        currentPage = targetInitPage;
                        totalPages = 1;
                        scrollExtentBaseWidth = 0;
                        hasInitializedLayout = false;
                        hasNotifiedLayoutReady = false;
                        isLayoutReady = !window.epubproIsHorizontal;
                        forceBodyDimensions();
                        if (window.epubproApplyContentFilter) window.epubproApplyContentFilter();
                        scheduleInitLayout();
                        return true;
                    } catch (error) {
                        dbg('CHAPTER_COMMIT_ERROR', error && error.message ? error.message : 'Unable to replace chapter DOM');
                        return false;
                    }
                }

                function mountPageOverlay(layer, pageOffset, pw, vh, bg, zIndex, shadowStyle, overlayId) {
                    if (!layer) return null;

                    var wrapper = document.createElement('div');
                    wrapper.style.cssText = 'position:absolute;top:0;left:0;width:' + pw + 'px;height:' + vh + 'px;pointer-events:none;background-color:' + bg + ';';
                    wrapper.appendChild(layer);

                    var overlay = document.createElement('div');
                    overlay.id = overlayId;
                    overlay.style.cssText = 'position:fixed;top:0;left:0;width:' + pw + 'px;height:' + vh + 'px;z-index:' + zIndex + ';overflow:hidden;pointer-events:none;background-color:' + bg + ';will-change:transform;';
                    if (shadowStyle) overlay.style.boxShadow = shadowStyle;
                    overlay.appendChild(wrapper);
                    document.documentElement.appendChild(overlay);

                    var contentWidth = Math.max(pw, layer.scrollWidth);
                    var pageCount = Math.max(1, Math.ceil((contentWidth - 1) / pw));
                    var resolvedOffset = pageOffset === 'last' ? (pageCount - 1) * pw : pageOffset;
                    resolvedOffset = Math.min((pageCount - 1) * pw, Math.max(0, resolvedOffset || 0));
                    wrapper.style.width = contentWidth + 'px';
                    wrapper.style.transform = 'translateX(-' + resolvedOffset + 'px)';

                    return overlay;
                }

                function initCoverOverlay(direction) {
                    if (isCoverOverlayActive) return;

                    isCoverOverlayActive = true;
                    dragDirection = direction;
                    suppressScrollMetrics(10000);

                    var pw = window.innerWidth || document.documentElement.clientWidth || 1;
                    var vh = window.innerHeight || document.documentElement.clientHeight || 1;
                    var body = document.body;
                    if (!body) {
                        isCoverOverlayActive = false;
                        return;
                    }

                    var bg = themeBgColor || '#FFFFFF';
                    var shadowStyle = direction < 0
                        ? '14px 0 36px rgba(0,0,0,0.45)'
                        : '-14px 0 36px rgba(0,0,0,0.45)';

                    var backdropOverlay = document.createElement('div');
                    backdropOverlay.id = 'epubpro-backdrop-overlay';
                    backdropOverlay.style.cssText = 'position:fixed;top:0;left:0;width:' + pw + 'px;height:' + vh + 'px;z-index:19997;pointer-events:none;background-color:' + bg + ';';
                    document.documentElement.appendChild(backdropOverlay);
                    window.activeBackdropOverlay = backdropOverlay;

                    var targetScrollX = startScrollX + (direction < 0 ? pw : -pw);
                    var isNextBoundary = direction < 0 && currentPage >= totalPages;
                    var isPreviousBoundary = direction > 0 && currentPage <= 1;
                    var targetLayer = null;
                    var targetOffset = targetScrollX;

                    if (isNextBoundary) {
                        targetLayer = createAdjacentChapterLayer(nextChapterHtml, pw, vh, bg);
                        targetOffset = 0;
                    } else if (isPreviousBoundary) {
                        targetLayer = createAdjacentChapterLayer(previousChapterHtml, pw, vh, bg);
                        targetOffset = 'last';
                    } else {
                        targetLayer = createCurrentChapterLayer(body, pw, vh, bg);
                    }

                    activeBottomOverlay = mountPageOverlay(
                        targetLayer,
                        targetOffset,
                        pw,
                        vh,
                        bg,
                        19998,
                        '',
                        'epubpro-bottom-overlay'
                    );

                    var currentLayer = createCurrentChapterLayer(body, pw, vh, bg);
                    activeTopOverlay = mountPageOverlay(
                        currentLayer,
                        startScrollX,
                        pw,
                        vh,
                        bg,
                        19999,
                        shadowStyle,
                        'epubpro-top-overlay'
                    );

                    body.style.setProperty('touch-action', 'none', 'important');
                    document.documentElement.style.setProperty('touch-action', 'none', 'important');
                }
                document.addEventListener('touchstart', function(e) {
                    if (e.touches && e.touches.length === 1) {
                        gestureToken += 1;
                        if (window.epubproIsHorizontal) {
                            cleanupCoverOverlay();
                        }
                        startTouchX = e.touches[0].clientX;
                        startTouchY = e.touches[0].clientY;
                        startTouchTime = Date.now();
                        startScrollX = window.scrollX || window.pageXOffset || 0;
                        currentDeltaX = 0;
                        isDraggingPage = true;
                        isHorizontalDragConfirmed = false;
                    }
                }, { passive: true });

                document.addEventListener('touchmove', function(e) {
                    if (!window.epubproIsHorizontal || !isDraggingPage || !e.touches || e.touches.length === 0) return;

                    // MUST preventDefault IMMEDIATELY before WebView commits scroll gesture
                    e.preventDefault();

                    var touchX = e.touches[0].clientX;
                    var touchY = e.touches[0].clientY;
                    var deltaX = touchX - startTouchX;
                    var deltaY = touchY - startTouchY;

                    if (!isHorizontalDragConfirmed) {
                        if (Math.abs(deltaX) > 8 && Math.abs(deltaX) > Math.abs(deltaY) * 1.2) {
                            isHorizontalDragConfirmed = true;
                        } else if (Math.abs(deltaY) > 8) {
                            isDraggingPage = false;
                            return;
                        }
                    }

                    if (isHorizontalDragConfirmed) {
                        currentDeltaX = deltaX;
                        var pw = window.innerWidth || document.documentElement.clientWidth || 1;

                        if (!isCoverOverlayActive) {
                            if (deltaX < 0) {
                                initCoverOverlay(-1);
                            } else if (deltaX > 0) {
                                initCoverOverlay(1);
                            }
                        }

                        if (isCoverOverlayActive && activeTopOverlay) {
                            var clampedX = deltaX;
                            var hasNextPreview = !!(nextChapterHtml && nextChapterHtml.trim());
                            var hasPreviousPreview = !!(previousChapterHtml && previousChapterHtml.trim());
                            var isBlockedNextBoundary = dragDirection < 0 && currentPage === totalPages && !hasNextChapter;
                            var isBlockedPreviousBoundary = dragDirection > 0 && currentPage === 1 && !hasPreviousChapter;

                            if (isBlockedNextBoundary) {
                                clampedX = Math.max(-pw * 0.3, deltaX * 0.3);
                            } else if (isBlockedPreviousBoundary) {
                                clampedX = Math.min(pw * 0.3, deltaX * 0.3);
                            } else {
                                if (dragDirection < 0) clampedX = Math.min(0, Math.max(-pw, deltaX));
                                if (dragDirection > 0) clampedX = Math.max(0, Math.min(pw, deltaX));
                            }
                            activeTopOverlay.style.transform = 'translateX(' + clampedX + 'px)';
                        }
                    }
                }, { passive: false });

                document.addEventListener('touchend', function(e) {
                    if (!isDraggingPage) return;
                    isDraggingPage = false;

                    if (!window.epubproIsHorizontal) {
                        if (e.changedTouches && e.changedTouches.length > 0) {
                            var endX = e.changedTouches[0].clientX;
                            var endY = e.changedTouches[0].clientY;
                            var diffX = endX - startTouchX;
                            var diffY = endY - startTouchY;
                            var duration = Date.now() - startTouchTime;

                            if (Math.abs(diffX) <= 15 && Math.abs(diffY) <= 15 && duration <= 300) {
                                handleConfiguredTap(endX, endY);
                            }
                        }
                        return;
                    }

                    if (!isHorizontalDragConfirmed) {
                        cleanupCoverOverlay();
                        if (!e.changedTouches || e.changedTouches.length === 0) return;
                        var endX = e.changedTouches[0].clientX;
                        var endY = e.changedTouches[0].clientY;
                        var diffX = endX - startTouchX;
                        var diffY = endY - startTouchY;
                        var duration = Date.now() - startTouchTime;

                        if (Math.abs(diffX) <= 15 && Math.abs(diffY) <= 15 && duration <= 300) {
                            handleConfiguredTap(endX, endY);
                        }
                        return;
                    }

                    var duration = Math.max(1, Date.now() - startTouchTime);
                    var pw = window.innerWidth || document.documentElement.clientWidth || 1;
                    var velocity = Math.abs(currentDeltaX) / duration;
                    var threshold = pw * 0.30; // Yêu cầu kéo qua 30% màn hình mới chuyển trang
                    var completingGestureToken = gestureToken;
                    var isNextBoundary = dragDirection < 0 && currentPage >= totalPages;
                    var isPreviousBoundary = dragDirection > 0 && currentPage <= 1;

                    if (isNextBoundary || isPreviousBoundary) {
                        var hasAdjacentPreview = isNextBoundary
                            ? !!(nextChapterHtml && nextChapterHtml.trim())
                            : !!(previousChapterHtml && previousChapterHtml.trim());
                        var crossedBoundaryThreshold = isNextBoundary
                            ? (currentDeltaX <= -threshold || (currentDeltaX < -pw * 0.20 && velocity > 0.35))
                            : (currentDeltaX >= threshold || (currentDeltaX > pw * 0.20 && velocity > 0.35));
                        var hasAdjacentChapter = isNextBoundary ? hasNextChapter : hasPreviousChapter;
                        var boundaryTriggered = hasAdjacentChapter && crossedBoundaryThreshold;
                        if (boundaryTriggered) {
                            if (isNextBoundary && window.ReaderJsBridge && window.ReaderJsBridge.onNextChapterPrefetchRequested) {
                                window.ReaderJsBridge.onNextChapterPrefetchRequested();
                            } else if (isPreviousBoundary && window.ReaderJsBridge && window.ReaderJsBridge.onPreviousChapterPrefetchRequested) {
                                window.ReaderJsBridge.onPreviousChapterPrefetchRequested();
                            }
                        }
                        var boundaryOverlay = activeTopOverlay;
                        var boundaryDone = false;

                        function finishBoundaryGesture() {
                            if (boundaryDone || completingGestureToken !== gestureToken) return;
                            boundaryDone = true;

                            if (!boundaryTriggered) {
                                cleanupCoverOverlay();
                                return;
                            }

                            var chapterRequested = false;
                            var chapterDirection = isNextBoundary ? 1 : -1;
                            var adjacentHtml = isNextBoundary ? nextChapterHtml : previousChapterHtml;
                            var committedInDocument = replaceAdjacentChapterInDocument(adjacentHtml, chapterDirection);
                            if (committedInDocument && window.ReaderJsBridge && window.ReaderJsBridge.onAdjacentChapterCommitted) {
                                chapterRequested = true;
                                window.ReaderJsBridge.onAdjacentChapterCommitted(chapterDirection);
                            } else if (isNextBoundary && window.ReaderJsBridge && window.ReaderJsBridge.onNextChapterRequested) {
                                chapterRequested = true;
                                window.ReaderJsBridge.onNextChapterRequested();
                            } else if (isPreviousBoundary && window.ReaderJsBridge && window.ReaderJsBridge.onPreviousChapterRequested) {
                                chapterRequested = true;
                                window.ReaderJsBridge.onPreviousChapterRequested();
                            }

                            if (!chapterRequested) {
                                cleanupCoverOverlay();
                                return;
                            }

                            setTimeout(function() {
                                if (completingGestureToken === gestureToken) {
                                     cleanupCoverOverlay();
                                     updatePageMetrics();
                                 }
                            }, Math.max(transitionSpeedMs + 120, 180));
                        }

                        if (boundaryOverlay) {
                            boundaryOverlay.addEventListener('transitionend', function onBoundaryTransitionEnd(ev) {
                                if (ev.propertyName !== 'transform') return;
                                boundaryOverlay.removeEventListener('transitionend', onBoundaryTransitionEnd);
                                finishBoundaryGesture();
                            });
                            boundaryOverlay.style.transition = 'transform ' + (transitionSpeedMs / 1000).toFixed(2) + 's cubic-bezier(0.2, 0.8, 0.2, 1)';
                            boundaryOverlay.style.transform = boundaryTriggered
                                ? (isNextBoundary ? 'translateX(-' + pw + 'px)' : 'translateX(' + pw + 'px)')
                                : 'translateX(0px)';
                        }
                        setTimeout(finishBoundaryGesture, transitionSpeedMs + 100);
                        return;
                    }

                    if (isCoverOverlayActive && activeTopOverlay) {
                        var speedSec = (transitionSpeedMs / 1000).toFixed(2);
                        if (dragDirection < 0) { // Dragging left to go next
                            if (currentDeltaX <= -threshold || (currentDeltaX < -pw * 0.20 && velocity > 0.35)) {
                                // COMMIT: slide overlay out, scroll native body, then cleanup
                                var commitOverlay = activeTopOverlay;
                                var commitDone = false;
                                function doCommitLeft() {
                                    if (commitDone || completingGestureToken !== gestureToken) return;
                                    commitDone = true;
                                    currentPage = Math.min(totalPages, currentPage + 1);
                                    settlePageOffset(currentPage, 2, function() {
                                        cleanupCoverOverlay();
                                        notifyPageChangeCompleted();
                                    });
                                }
                                activeTopOverlay.addEventListener('transitionend', function onTE(ev) {
                                    if (ev.propertyName !== 'transform') return;
                                    commitOverlay.removeEventListener('transitionend', onTE);
                                    doCommitLeft();
                                });
                                setTimeout(doCommitLeft, transitionSpeedMs + 100);
                                activeTopOverlay.style.transition = 'transform ' + speedSec + 's cubic-bezier(0.2, 0.8, 0.2, 1)';
                                activeTopOverlay.style.transform = 'translateX(-' + pw + 'px)';
                            } else {
                                // SNAP-BACK: body never moved, slide overlay back then cleanup
                                var snapOverlay = activeTopOverlay;
                                var snapDone = false;
                                function doSnapLeft() {
                                    if (snapDone || completingGestureToken !== gestureToken) return;
                                    snapDone = true;
                                    window.scrollTo(startScrollX, 0);
                                    dbg('SNAP_BACK', 'left: done');
                                    cleanupCoverOverlay();
                                }
                                activeTopOverlay.addEventListener('transitionend', function onTE2(ev) {
                                    if (ev.propertyName !== 'transform') return;
                                    snapOverlay.removeEventListener('transitionend', onTE2);
                                    doSnapLeft();
                                });
                                setTimeout(doSnapLeft, transitionSpeedMs + 100);
                                activeTopOverlay.style.transition = 'transform ' + speedSec + 's cubic-bezier(0.2, 0.8, 0.2, 1)';
                                activeTopOverlay.style.transform = 'translateX(0px)';
                                dbg('SNAP_BACK', 'left: anim start');
                            }
                        } else if (dragDirection > 0) { // Dragging right to go prev
                            if (currentDeltaX >= threshold || (currentDeltaX > pw * 0.20 && velocity > 0.35)) {
                                // COMMIT: slide overlay out, scroll native body, then cleanup
                                var commitOverlay = activeTopOverlay;
                                var commitDone = false;
                                function doCommitRight() {
                                    if (commitDone || completingGestureToken !== gestureToken) return;
                                    commitDone = true;
                                    currentPage = Math.max(1, currentPage - 1);
                                    settlePageOffset(currentPage, 2, function() {
                                        cleanupCoverOverlay();
                                        notifyPageChangeCompleted();
                                    });
                                }
                                activeTopOverlay.addEventListener('transitionend', function onTE3(ev) {
                                    if (ev.propertyName !== 'transform') return;
                                    commitOverlay.removeEventListener('transitionend', onTE3);
                                    doCommitRight();
                                });
                                setTimeout(doCommitRight, transitionSpeedMs + 100);
                                activeTopOverlay.style.transition = 'transform ' + speedSec + 's cubic-bezier(0.2, 0.8, 0.2, 1)';
                                activeTopOverlay.style.transform = 'translateX(' + pw + 'px)';
                            } else {
                                // SNAP-BACK: body never moved, slide overlay back then cleanup
                                var snapOverlay = activeTopOverlay;
                                var snapDone = false;
                                function doSnapRight() {
                                    if (snapDone || completingGestureToken !== gestureToken) return;
                                    snapDone = true;
                                    window.scrollTo(startScrollX, 0);
                                    dbg('SNAP_BACK', 'right: done');
                                    cleanupCoverOverlay();
                                }
                                activeTopOverlay.addEventListener('transitionend', function onTE4(ev) {
                                    if (ev.propertyName !== 'transform') return;
                                    snapOverlay.removeEventListener('transitionend', onTE4);
                                    doSnapRight();
                                });
                                setTimeout(doSnapRight, transitionSpeedMs + 100);
                                activeTopOverlay.style.transition = 'transform ' + speedSec + 's cubic-bezier(0.2, 0.8, 0.2, 1)';
                                activeTopOverlay.style.transform = 'translateX(0px)';
                                dbg('SNAP_BACK', 'right: anim start');
                            }
                        }
                    } else {
                        cleanupCoverOverlay();
                    }
                }, { passive: true });

                document.addEventListener('touchcancel', function() {
                    if (!window.epubproIsHorizontal) return;
                    gestureToken += 1;
                    window.scrollTo(startScrollX, 0);
                    cleanupCoverOverlay();
                }, { passive: true });

                window.epubproHighlightTtsParagraph = function(index) {
                    try {
                        var paragraphs = getEpubproTtsParagraphs();
                        dbg('TTS_HL', 'index=' + index + ' paragraphs=' + paragraphs.length);
                        
                        var oldElements = document.querySelectorAll('.tts-active-paragraph');
                        for (var i = 0; i < oldElements.length; i++) {
                            oldElements[i].classList.remove('tts-active-paragraph');
                        }
                        if (index >= 0 && index < paragraphs.length) {
                            var target = paragraphs[index];
                            target.classList.add('tts-active-paragraph');
                            
                            if (window.epubproIsHorizontal) {
                                var pw = window.innerWidth || document.documentElement.clientWidth || 1;
                                var rect = target.getBoundingClientRect();
                                var targetX = rect.left + (window.scrollX || window.pageXOffset || 0);
                                var targetPage = Math.floor(targetX / pw) + 1;
                                targetPage = Math.min(totalPages, Math.max(1, targetPage));
                                
                                if (targetPage !== currentPage) {
                                    scrollToPage(targetPage, true);
                                }
                            } else {
                                target.scrollIntoView({ behavior: 'smooth', block: 'center' });
                            }
                        } else {
                            dbg('TTS_HL_WARN', 'Index out of bounds or negative');
                        }
                    } catch(e) {
                        dbg('TTS_HL_ERR', e.message);
                    }
                };

                window.epubproApplyContentFilter = function() {
                    try {
                        // filterRulesJson là JSON string literal đã được quote an toàn cho JavaScript,
                        // nên chỉ parse một lần để lấy lại mảng rule thực tế.
                        var rules = JSON.parse($filterRulesJson);
                        var isEnabled = $isFilterEnabled;
                        if (!isEnabled || !rules || rules.length === 0) return;

                        var activeRules = [];
                        var detectPatterns = [];
                        for (var i = 0; i < rules.length; i++) {
                            var r = rules[i];
                            if (!r.isEnabled || !r.pattern) continue;
                            if (r.isRegex) {
                                try {
                                    var regex = new RegExp(r.pattern, 'gi');
                                    activeRules.push({ regex: regex, replacement: r.replacement || '' });
                                    detectPatterns.push('(?:' + r.pattern + ')');
                                } catch (regexError) {
                                    dbg('FILTER_RULE_ERROR', regexError.toString());
                                }
                            } else {
                                var escaped = r.pattern.replace(/[.*+?^${'$'}{}()|[\]\\]/g, '\\${'$'}&');
                                var regex = new RegExp(escaped, 'gi');
                                activeRules.push({ regex: regex, replacement: r.replacement || '' });
                                detectPatterns.push('(?:' + escaped + ')');
                            }
                        }
                        if (activeRules.length === 0) return;
                        var detectRegex = null;
                        if (detectPatterns.length > 0) {
                            try {
                                detectRegex = new RegExp(detectPatterns.join('|'), 'i');
                            } catch (detectErr) {
                                dbg('FILTER_DETECT_REGEX_ERR', detectErr.toString());
                            }
                        }

                        var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null, false);
                        var node;
                        var nodesToProcess = [];
                        while (node = walker.nextNode()) {
                            var shouldProcess = false;
                            if (detectRegex) {
                                shouldProcess = detectRegex.test(node.nodeValue);
                            } else {
                                for (var r = 0; r < activeRules.length; r++) {
                                    activeRules[r].regex.lastIndex = 0;
                                    if (activeRules[r].regex.test(node.nodeValue)) {
                                        shouldProcess = true;
                                        break;
                                    }
                                }
                            }
                            if (shouldProcess) {
                                nodesToProcess.push(node);
                            }
                        }
                        for (var j = 0; j < nodesToProcess.length; j++) {
                            var n = nodesToProcess[j];
                            for (var k = 0; k < activeRules.length; k++) {
                                activeRules[k].regex.lastIndex = 0;
                                n.nodeValue = n.nodeValue.replace(activeRules[k].regex, function() {
                                    return activeRules[k].replacement;
                                });
                            }
                            n.nodeValue = n.nodeValue.replace(/\s+/g, ' ');
                        }
                        document.body.normalize();
                    } catch(e) {
                        dbg('FILTER_ERROR', e ? e.toString() : 'Filter error');
                    }
                };

                if (document.readyState === 'loading') {
                    document.addEventListener('DOMContentLoaded', function() {
                        window.epubproApplyContentFilter();
                    });
                } else {
                    window.epubproApplyContentFilter();
                }
            })();
        """.trimIndent()
    }
}
