package com.epubpro.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.epubpro.app.MainActivity
import com.epubpro.app.R
import com.epubpro.core.reader.tts.TtsService
import com.epubpro.core.reader.tts.TtsWidgetContract
import com.epubpro.core.storage.TtsWidgetPlaybackStatus
import com.epubpro.core.storage.TtsWidgetState
import com.epubpro.core.storage.TtsWidgetStateStore
import kotlin.math.roundToInt

class TtsAudioWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        val state = TtsWidgetStateStore(context).getState()
        appWidgetIds.forEach { updateWidget(context, manager, it, state) }
    }

    /**
     * Xử lý broadcast trạng thái phát trong phạm vi ứng dụng và chỉ cập nhật các audio widget;
     * reading widget tự nhận và hiển thị cùng broadcast một cách độc lập.
     */
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == TtsWidgetContract.ACTION_STATE_CHANGED) {
            updateAll(context)
        }
    }

    companion object {
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, TtsAudioWidgetProvider::class.java)
            val ids = manager.getAppWidgetIds(component)
            if (ids.isEmpty()) return
            val state = TtsWidgetStateStore(context).getState()
            ids.forEach { updateWidget(context, manager, it, state) }
        }

        private fun updateWidget(
            context: Context,
            manager: AppWidgetManager,
            appWidgetId: Int,
            state: TtsWidgetState
        ) {
            val views = RemoteViews(context.packageName, R.layout.tts_audio_widget)
            views.setTextViewText(
                R.id.tts_widget_title,
                state.bookTitle.ifBlank { context.getString(R.string.tts_widget_default_title) }
            )
            val statusLabel = statusText(context, state.playbackStatus)
            val statusFullText = if (state.chapterTitle.isNotBlank()) {
                "${state.chapterTitle} • $statusLabel"
            } else {
                statusLabel
            }
            views.setTextViewText(R.id.tts_widget_status, statusFullText)

            val percent = (state.normalizedProgress * 100f).roundToInt()
            val timeText = if (state.hasSnapshot && state.durationMs > 0L) {
                val posStr = formatTimeMs(state.positionMs)
                val durStr = formatTimeMs(state.durationMs)
                "$percent% • $posStr / $durStr"
            } else if (state.hasSnapshot) {
                "$percent%"
            } else {
                ""
            }
            views.setTextViewText(R.id.tts_widget_progress_text, timeText)

            views.setProgressBar(
                R.id.tts_widget_progress,
                100,
                percent,
                state.playbackStatus == TtsWidgetPlaybackStatus.PREPARING
            )

            val coverBitmap = decodeScaledCover(state.coverPath)
            if (coverBitmap != null) {
                views.setImageViewBitmap(R.id.tts_widget_icon, coverBitmap)
            } else {
                views.setImageViewResource(R.id.tts_widget_icon, R.drawable.ic_widget_book_placeholder)
            }

            val isPlaying = state.playbackStatus == TtsWidgetPlaybackStatus.PLAYING ||
                state.playbackStatus == TtsWidgetPlaybackStatus.PREPARING
            views.setImageViewResource(
                R.id.tts_widget_play_pause,
                if (isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play
            )
            val serviceFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val openLibraryIntent = openLibraryIntent(context, serviceFlags)
            views.setOnClickPendingIntent(
                R.id.tts_widget_play_pause,
                if (state.hasSnapshot) serviceIntent(context, TtsService.ACTION_WIDGET_PLAY_PAUSE, serviceFlags) else openLibraryIntent
            )
            views.setOnClickPendingIntent(
                R.id.tts_widget_previous,
                if (state.hasSnapshot) serviceIntent(context, TtsService.ACTION_WIDGET_PREVIOUS, serviceFlags) else openLibraryIntent
            )
            views.setOnClickPendingIntent(
                R.id.tts_widget_next,
                if (state.hasSnapshot) serviceIntent(context, TtsService.ACTION_WIDGET_NEXT, serviceFlags) else openLibraryIntent
            )
            views.setBoolean(R.id.tts_widget_previous, "setEnabled", state.hasSnapshot)
            views.setBoolean(R.id.tts_widget_next, "setEnabled", state.hasSnapshot)
            manager.updateAppWidget(appWidgetId, views)
        }

        private fun decodeScaledCover(coverPath: String?): android.graphics.Bitmap? {
            val path = coverPath?.takeIf { it.isNotBlank() } ?: return null
            val file = java.io.File(path)
            if (!file.isFile) return null
            return runCatching {
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

                var sampleSize = 1
                val targetPx = 144
                while (bounds.outWidth / sampleSize > targetPx || bounds.outHeight / sampleSize > targetPx) {
                    sampleSize *= 2
                }
                android.graphics.BitmapFactory.decodeFile(
                    file.absolutePath,
                    android.graphics.BitmapFactory.Options().apply { inSampleSize = sampleSize }
                )
            }.getOrNull()
        }

        /**
         * Tạo PendingIntent mở màn hình thư viện khi widget chưa có snapshot phát TTS.
         *
         * @param context Context ứng dụng.
         * @param flags Cờ cho [PendingIntent].
         * @return [PendingIntent] mở [MainActivity] với action mở thư viện.
         */
        private fun openLibraryIntent(context: Context, flags: Int): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                action = TtsWidgetContract.ACTION_OPEN_LIBRARY
                this.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            return PendingIntent.getActivity(context, REQUEST_LIBRARY, intent, flags)
        }

        private fun serviceIntent(context: Context, action: String, flags: Int): PendingIntent {
            val intent = Intent(context, TtsService::class.java).setAction(action)
            return PendingIntent.getForegroundService(context, action.hashCode(), intent, flags)
        }

        private fun statusText(context: Context, status: TtsWidgetPlaybackStatus): String =
            context.getString(
                when (status) {
                    TtsWidgetPlaybackStatus.IDLE -> R.string.tts_widget_idle
                    TtsWidgetPlaybackStatus.PREPARING -> R.string.tts_widget_preparing
                    TtsWidgetPlaybackStatus.PLAYING -> R.string.tts_widget_playing
                    TtsWidgetPlaybackStatus.PAUSED -> R.string.tts_widget_paused
                    TtsWidgetPlaybackStatus.ERROR -> R.string.tts_widget_error
                    TtsWidgetPlaybackStatus.COMPLETED -> R.string.tts_widget_completed
                }
            )

        private fun formatTimeMs(ms: Long): String {
            val totalSec = (ms / 1000L).coerceAtLeast(0L)
            val min = totalSec / 60L
            val sec = totalSec % 60L
            val hr = min / 60L
            val remMin = min % 60L
            return if (hr > 0L) {
                String.format(java.util.Locale.US, "%d:%02d:%02d", hr, remMin, sec)
            } else {
                String.format(java.util.Locale.US, "%02d:%02d", remMin, sec)
            }
        }

        private const val REQUEST_LIBRARY = 4100
    }
}
