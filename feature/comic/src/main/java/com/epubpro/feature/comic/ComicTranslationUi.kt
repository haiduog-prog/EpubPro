package com.epubpro.feature.comic

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.epubpro.core.designsystem.R
import com.epubpro.core.storage.ComicTranslationSettings
import com.epubpro.domain.model.ComicSourceLanguage
import com.epubpro.domain.model.ComicTranslationEngine
import com.epubpro.domain.model.TranslatedBubble
import kotlin.math.sqrt

private const val MIN_BUBBLE_FONT_SP = 7f
private const val MAX_BUBBLE_FONT_SP = 18f
private const val BUBBLE_FONT_FILL = 0.8f

/**
 * Lớp bản dịch đè lên trang: mỗi bóng thoại được phủ một khung trắng chứa bản dịch, chạm để xem chi tiết.
 *
 * @receiver Box của trang (đã khớp khung ảnh).
 * @param translation Trạng thái dịch của trang, null nếu chưa dịch.
 * @param onBubbleClick Callback khi chạm vào một bóng thoại.
 */
@Composable
internal fun BoxScope.TranslationOverlay(translation: PageTranslation?, onBubbleClick: (TranslatedBubble) -> Unit) {
    when (translation) {
        null, PageTranslation.Failed -> Unit
        PageTranslation.Translating -> CircularProgressIndicator(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .size(24.dp),
            strokeWidth = 2.dp
        )
        is PageTranslation.Done -> BoxWithConstraints(modifier = Modifier.matchParentSize()) {
            val density = LocalDensity.current
            translation.bubbles.forEach { bubble ->
                val width = maxWidth * (bubble.right - bubble.left)
                val height = maxHeight * (bubble.bottom - bubble.top)
                val fontSize = with(density) {
                    bubbleFontSize(width.toPx(), height.toPx(), bubble.translated.length).toSp()
                }
                Box(
                    modifier = Modifier
                        .offset(x = maxWidth * bubble.left, y = maxHeight * bubble.top)
                        .size(width, height)
                        .background(Color.White.copy(alpha = 0.94f), RoundedCornerShape(4.dp))
                        .clickable { onBubbleClick(bubble) }
                        .padding(1.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = bubble.translated,
                        color = Color.Black,
                        fontSize = fontSize.coerceIn(MIN_BUBBLE_FONT_SP, MAX_BUBBLE_FONT_SP),
                        lineHeight = fontSize.coerceIn(MIN_BUBBLE_FONT_SP, MAX_BUBBLE_FONT_SP) * 1.1f,
                        textAlign = TextAlign.Center,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * Ước lượng cỡ chữ (px) để bản dịch lấp vừa khung bóng thoại.
 *
 * @param widthPx Chiều rộng khung.
 * @param heightPx Chiều cao khung.
 * @param length Số ký tự bản dịch.
 * @return Cỡ chữ theo pixel.
 */
internal fun bubbleFontSize(widthPx: Float, heightPx: Float, length: Int): Float {
    if (length <= 0 || widthPx <= 0f || heightPx <= 0f) return 0f
    return sqrt(widthPx * heightPx / length) * BUBBLE_FONT_FILL
}

/**
 * Giới hạn cỡ chữ trong khoảng sp.
 *
 * @receiver Cỡ chữ.
 * @param min Cỡ nhỏ nhất (sp).
 * @param max Cỡ lớn nhất (sp).
 * @return Cỡ chữ đã giới hạn.
 */
private fun TextUnit.coerceIn(min: Float, max: Float): TextUnit = value.coerceIn(min, max).sp

/**
 * Bảng dịch truyện tranh: chọn ngôn ngữ, cách dịch, tự dịch và dịch trang hiện tại.
 *
 * @param settings Cài đặt hiện tại.
 * @param currentTranslation Trạng thái dịch của trang hiện tại.
 * @param onSettingsChange Callback đổi cài đặt.
 * @param onTranslate Callback dịch (lại) trang hiện tại.
 * @param onDismiss Callback đóng bảng.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ComicTranslateSheet(
    settings: ComicTranslationSettings,
    currentTranslation: PageTranslation?,
    onSettingsChange: (ComicTranslationSettings) -> Unit,
    onTranslate: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(R.string.comic_translate_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.comic_translate_language), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ComicSourceLanguage.entries.forEach { language ->
                    FilterChip(
                        selected = settings.language == language,
                        onClick = { onSettingsChange(settings.copy(language = language)) },
                        label = { Text(stringResource(language.labelRes())) }
                    )
                }
            }
            Text(stringResource(R.string.comic_translate_engine), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ComicTranslationEngine.entries.forEach { engine ->
                    FilterChip(
                        selected = settings.engine == engine,
                        enabled = engine != ComicTranslationEngine.HAN_VIET || settings.language == ComicSourceLanguage.CHINESE,
                        onClick = { onSettingsChange(settings.copy(engine = engine)) },
                        label = { Text(stringResource(engine.labelRes())) }
                    )
                }
            }
            SwitchRow(
                label = stringResource(R.string.comic_translate_auto),
                checked = settings.autoTranslate,
                onCheckedChange = { onSettingsChange(settings.copy(autoTranslate = it)) }
            )
            SwitchRow(
                label = stringResource(R.string.comic_translate_show_overlay),
                checked = settings.showOverlay,
                onCheckedChange = { onSettingsChange(settings.copy(showOverlay = it)) }
            )
            Text(
                text = stringResource(R.string.comic_translate_privacy_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = onTranslate,
                enabled = currentTranslation != PageTranslation.Translating,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    stringResource(
                        if (currentTranslation is PageTranslation.Done || currentTranslation == PageTranslation.Failed) {
                            R.string.comic_translate_page_again
                        } else {
                            R.string.comic_translate_page
                        }
                    )
                )
            }
        }
    }
}

/**
 * Dòng nhãn kèm công tắc.
 *
 * @param label Nhãn.
 * @param checked Trạng thái công tắc.
 * @param onCheckedChange Callback đổi trạng thái.
 */
@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * Hộp thoại xin phép tải gói dịch ngoại tuyến.
 *
 * @param language Ngôn ngữ cần tải gói.
 * @param downloading Đang tải.
 * @param onDownload Callback tải (true = chỉ qua Wi-Fi).
 * @param onDismiss Callback hủy.
 */
@Composable
internal fun ModelDownloadDialog(
    language: ComicSourceLanguage,
    downloading: Boolean,
    onDownload: (wifiOnly: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.comic_translate_model_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.comic_translate_model_message, stringResource(language.labelRes())))
                if (downloading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = { onDownload(false) }, enabled = !downloading) {
                Text(stringResource(R.string.comic_translate_model_download_now))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onDownload(true) }, enabled = !downloading) {
                    Text(stringResource(R.string.comic_translate_model_download_wifi))
                }
                TextButton(onClick = onDismiss, enabled = !downloading) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        }
    )
}

/**
 * Hộp thoại xem bản gốc và bản dịch của một bóng thoại.
 *
 * @param bubble Bóng thoại.
 * @param onDismiss Callback đóng.
 */
@Composable
internal fun BubbleDetailDialog(bubble: TranslatedBubble, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.comic_translate_bubble_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.comic_translate_bubble_original), style = MaterialTheme.typography.labelLarge)
                Text(bubble.original, style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(R.string.comic_translate_bubble_translated), style = MaterialTheme.typography.labelLarge)
                Text(bubble.translated, style = MaterialTheme.typography.bodyLarge)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        }
    )
}

/**
 * Nhãn string resource của ngôn ngữ nguồn.
 *
 * @receiver Ngôn ngữ nguồn.
 * @return ID string resource.
 */
private fun ComicSourceLanguage.labelRes(): Int = when (this) {
    ComicSourceLanguage.CHINESE -> R.string.comic_language_chinese
    ComicSourceLanguage.JAPANESE -> R.string.comic_language_japanese
    ComicSourceLanguage.KOREAN -> R.string.comic_language_korean
    ComicSourceLanguage.ENGLISH -> R.string.comic_language_english
}

/**
 * Nhãn string resource của cách dịch.
 *
 * @receiver Cách dịch.
 * @return ID string resource.
 */
private fun ComicTranslationEngine.labelRes(): Int = when (this) {
    ComicTranslationEngine.ON_DEVICE -> R.string.comic_translate_engine_on_device
    ComicTranslationEngine.HAN_VIET -> R.string.comic_translate_engine_hanviet
}
