package com.epubpro.core.reader.style

import com.epubpro.domain.model.ReadingMode
import com.epubpro.domain.model.ReaderSettings
import com.epubpro.domain.model.ReaderThemeMode
import com.epubpro.domain.model.TextAlignment
import com.epubpro.domain.model.ContentFilterPreferences
import com.epubpro.domain.model.ContentFilterRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CssInjectorTest {

    @Test
    fun `generateMetaAndViewport returns valid non-scalable viewport`() {
        val meta = CssInjector.generateMetaAndViewport()
        assertTrue(meta.contains("name=\"viewport\""))
        assertTrue(meta.contains("user-scalable=no"))
    }

    @Test
    fun `generateCss generates accurate theme colors and fractional font sizes`() {
        val sepiaSettings = ReaderSettings(
            themeMode = ReaderThemeMode.SEPIA,
            fontSizeSp = 17.5f,
            textAlignment = TextAlignment.JUSTIFY,
            lineHeightRatio = 1.6f
        )

        val css = CssInjector.generateCss(sepiaSettings)
        assertTrue("Should contain Sepia background", css.contains("#FBF0D9"))
        assertTrue("Should contain Sepia text color", css.contains("#4A3B32"))
        assertTrue("Should contain exact 17.5px font size", css.contains("17.5px"))
        assertTrue("Should contain text-align justify", css.contains("text-align: justify"))
    }

    @Test
    fun `generateCss for horizontal pagination applies strict multi-column math`() {
        val horizontalSettings = ReaderSettings(
            readingMode = ReadingMode.FLIP,
            isHorizontalPagination = true
        )

        val css = CssInjector.generateCss(horizontalSettings)
        assertTrue(css.contains("column-width:"))
        assertTrue(css.contains("column-gap:"))
        assertTrue(css.contains("overflow: hidden !important"))
    }

    @Test
    fun `generateCss for vertical scroll mode applies proper scrollable overflow`() {
        val scrollSettings = ReaderSettings(
            readingMode = ReadingMode.SCROLL,
            isHorizontalPagination = false
        )

        val css = CssInjector.generateCss(scrollSettings)
        assertTrue(css.contains("overflow-y: auto !important"))
        assertTrue(css.contains("overflow-x: hidden !important"))
        assertFalse(css.contains("column-width:"))
    }

    @Test
    fun generateJsBridgeScriptKeepsChapterNavigationEnabledWithoutLoadedPreview() {
        val script = CssInjector.generateJsBridgeScript(
            isHorizontalPagination = true,
            settings = ReaderSettings(),
            previousChapterHtml = null,
            nextChapterHtml = null,
            hasPreviousChapter = true,
            hasNextChapter = true
        )

        assertTrue(script.contains("var hasNextChapter = true;"))
        assertTrue(script.contains("var hasPreviousChapter = true;"))
        assertTrue(script.contains("var boundaryTriggered = hasAdjacentChapter && crossedBoundaryThreshold;"))
    }

    @Test
    fun generateJsBridgeScriptBlocksNavigationAtRealChapterBoundary() {
        val script = CssInjector.generateJsBridgeScript(
            isHorizontalPagination = true,
            settings = ReaderSettings(),
            hasPreviousChapter = false,
            hasNextChapter = false
        )

        assertTrue(script.contains("var hasNextChapter = false;"))
        assertTrue(script.contains("var hasPreviousChapter = false;"))
    }

    /**
     * Ki?m tra thao t?c ch?m ? c?nh trang cu?i c?ng ?i qua animation boundary
     * thay v? g?i callback native b? qua hi?u ?ng.
     */
    @Test
    fun generateJsBridgeScriptAnimatesTapAtChapterBoundary() {
        val script = CssInjector.generateJsBridgeScript(
            isHorizontalPagination = true,
            settings = ReaderSettings(),
            hasNextChapter = true,
            hasPreviousChapter = true
        )

        assertTrue(script.contains("function animateChapterBoundary(direction)"))
        assertTrue(script.contains("animateChapterBoundary(1);"))
        assertTrue(script.contains("animateChapterBoundary(-1);"))
    }

    @Test
    fun generateJsBridgeScriptProducesValidJsBridgeApiDefinitions() {
        val script = CssInjector.generateJsBridgeScript(
            isHorizontalPagination = true,
            initialPage = 1,
            settings = ReaderSettings()
        )

        assertTrue(script.contains("window.epubproHighlightTtsParagraph"))
        assertTrue(script.contains("window.epubproApplyContentFilter"))
        assertTrue(script.contains("window.epubproIsHorizontal"))
    }

    /**
     * Kiểm tra script chỉ parse một lần danh sách rule đã quote và chạy sau khi DOM sẵn sàng.
     */
    @Test
    fun generateJsBridgeScriptParsesQuotedFilterRulesAfterDomReady() {
        val script = CssInjector.generateJsBridgeScript(
            isHorizontalPagination = false,
            filterPreferences = ContentFilterPreferences(
                isFilterEnabled = true,
                rules = listOf(ContentFilterRule(pattern = "quảng cáo"))
            )
        )

        assertTrue(script.contains("var rules = JSON.parse("))
        assertFalse(script.contains("JSON.parse(JSON.parse("))
        assertTrue(script.contains("document.addEventListener('DOMContentLoaded'"))
        assertTrue(script.contains("quảng cáo"))
    }

    /**
     * Kiểm tra rule Regex lỗi không làm vô hiệu hóa các rule hợp lệ còn lại.
     */
    @Test
    fun generateJsBridgeScriptSkipsInvalidRegexRuleWithoutDisablingValidRules() {
        val script = CssInjector.generateJsBridgeScript(
            isHorizontalPagination = false,
            filterPreferences = ContentFilterPreferences(
                isFilterEnabled = true,
                rules = listOf(
                    ContentFilterRule(pattern = "[invalid", isRegex = true),
                    ContentFilterRule(pattern = "hợp lệ")
                )
            )
        )

        assertTrue(script.contains("new RegExp(r.pattern, 'gi')"))
        assertFalse(script.contains("new RegExp(r.pattern, 'i');"))
        assertTrue(script.contains("FILTER_RULE_ERROR"))
        assertTrue(script.contains("hợp lệ"))
    }

    @Test
    fun `sanitizeFontFamily strips css injection characters and falls back to serif`() {
        val maliciousSettings = ReaderSettings(
            fontFamily = "Roboto; } body { display: none; /*"
        )
        val css = CssInjector.generateCss(maliciousSettings)
        assertFalse(css.contains("body { display: none;"))
        assertTrue(css.contains("font-family: Roboto  body  display none, serif, sans-serif !important;"))
        org.junit.Assert.assertEquals("serif", CssInjector.sanitizeFontFamily(";;;{}/*"))
    }

    @Test
    fun `replaceAdjacentChapterInDocument sanitizes parsed body, strips tts class and resets scrollExtentBaseWidth`() {
        val script = CssInjector.generateJsBridgeScript(
            isHorizontalPagination = true,
            settings = ReaderSettings()
        )
        assertTrue(script.contains("sanitizePageLayer(parsed.body);"))
        assertTrue(script.contains("classList.remove('tts-active-paragraph')"))
        assertTrue(script.contains("scrollExtentBaseWidth = 0;"))
        assertEquals(1, Regex("var startTouchX = 0;").findAll(script).count())
    }

    /**
     * Kiểm tra script chứa thông tin thay thế từ ngữ (replacement) và áp dụng thay thế.
     */
    @Test
    fun generateJsBridgeScriptIncludesReplacementRules() {
        val script = CssInjector.generateJsBridgeScript(
            isHorizontalPagination = false,
            filterPreferences = ContentFilterPreferences(
                isFilterEnabled = true,
                rules = listOf(
                    ContentFilterRule(pattern = "tu sĩ", replacement = "pháp sư")
                )
            )
        )

        assertTrue(script.contains("tu sĩ"))
        assertTrue(script.contains("pháp sư"))
        assertTrue(script.contains("activeRules.push"))
    }

    /**
     * Kiểm tra script sử dụng function replacer và khối try-catch an toàn cho detectRegex.
     */
    @Test
    fun generateJsBridgeScriptUsesSafeDetectRegexAndFunctionReplacer() {
        val script = CssInjector.generateJsBridgeScript(
            isHorizontalPagination = false,
            filterPreferences = ContentFilterPreferences(
                isFilterEnabled = true,
                rules = listOf(
                    ContentFilterRule(pattern = "a", replacement = "$1", isRegex = true),
                    ContentFilterRule(pattern = "b", replacement = "$&", isRegex = true)
                )
            )
        )

        assertTrue(script.contains("FILTER_DETECT_REGEX_ERR"))
        assertTrue(script.contains("return activeRules[k].replacement;"))
    }
}
