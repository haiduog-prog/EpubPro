package com.epubpro.core.reader.tts.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.session.MediaSession
import android.support.v4.media.session.MediaSessionCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.media.app.NotificationCompat as MediaNotificationCompat
import com.epubpro.core.designsystem.R
import com.epubpro.core.reader.tts.TtsService

/** Dữ liệu bất biến dùng để hiển thị notification phát TTS hiện tại. */
internal data class TtsPlaybackNotificationModel(
    val bookTitle: String,
    val currentSnippet: String,
    val isPlaying: Boolean,
    val openIntent: PendingIntent?
)

/**
 * Quản lý notification channel của TTS, việc hiển thị, đưa service lên foreground, cập nhật tại chỗ
 * và gỡ notification trong toàn bộ vòng đời của một [TtsService].
 */
internal class TtsPlaybackNotificationManager(
    private val service: Service,
    mediaSessionToken: MediaSession.Token
) {
    private val notificationManager = NotificationManagerCompat.from(service)
    private val compatSessionToken = MediaSessionCompat.Token.fromToken(mediaSessionToken)

    var isForeground: Boolean = false
        private set

    private var foregroundServiceTypes: Int = 0
    private var lastModel: TtsPlaybackNotificationModel? = null

    init {
        createNotificationChannel()
    }

    /**
     * Hiển thị hoặc cập nhật notification phát bằng thao tác foreground nhỏ nhất cần thiết.
     *
     * @return `false` khi Android từ chối hiển thị notification hoặc đưa service lên foreground.
     */
    fun showOrUpdate(
        model: TtsPlaybackNotificationModel,
        serviceTypes: Int,
        forceRestart: Boolean = false
    ): Boolean {
        val action = TtsNotificationUpdatePolicy.resolve(
            isForeground = isForeground,
            currentTypes = foregroundServiceTypes,
            requestedTypes = serviceTypes,
            forceRestart = forceRestart
        )
        if (action == TtsNotificationUpdateAction.UPDATE_IN_PLACE && model == lastModel) {
            return true
        }

        val notification = buildNotification(model)
        return try {
            when (action) {
                TtsNotificationUpdateAction.START_FOREGROUND -> {
                    startForeground(notification, serviceTypes)
                }
                TtsNotificationUpdateAction.UPDATE_FOREGROUND_TYPES -> {
                    startForeground(notification, serviceTypes)
                }
                TtsNotificationUpdateAction.UPDATE_IN_PLACE -> {
                    notificationManager.notify(NOTIFICATION_ID, notification)
                }
                TtsNotificationUpdateAction.RESTART_FOREGROUND -> {
                    startForeground(notification, serviceTypes)
                }
            }
            lastModel = model
            true
        } catch (_: RuntimeException) {
            false
        }
    }

    /** Gỡ foreground notification và xóa trạng thái foreground đang được theo dõi. */
    fun remove() {
        runCatching {
            ServiceCompat.stopForeground(service, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
        runCatching { notificationManager.cancel(NOTIFICATION_ID) }
        resetState()
    }

    /** Xóa trạng thái quản lý nội bộ khi service sở hữu manager bị hủy. */
    fun resetState() {
        isForeground = false
        foregroundServiceTypes = 0
        lastModel = null
    }

    /** Đưa service lên foreground và lưu chính xác tập foreground service type đang sử dụng. */
    private fun startForeground(notification: Notification, serviceTypes: Int) {
        ServiceCompat.startForeground(
            service,
            NOTIFICATION_ID,
            notification,
            serviceTypes
        )
        isForeground = true
        foregroundServiceTypes = serviceTypes
    }

    /** Tạo hoặc làm mới notification channel ổn định, mức ưu tiên thấp dành cho phát TTS. */
    private fun createNotificationChannel() {
        val manager = service.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            service.getString(R.string.tts_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = service.getString(R.string.tts_channel_desc)
        }
        manager.createNotificationChannel(channel)
    }

    /** Tạo MediaStyle notification cho thiết bị trước Android 13 và để service giữ foreground. */
    private fun buildNotification(model: TtsPlaybackNotificationModel): Notification {
        val playPauseIcon = if (model.isPlaying) {
            android.R.drawable.ic_media_pause
        } else {
            android.R.drawable.ic_media_play
        }
        val playPauseTitle = if (model.isPlaying) {
            service.getString(R.string.tts_action_pause)
        } else {
            service.getString(R.string.tts_action_play)
        }

        return NotificationCompat.Builder(service, CHANNEL_ID)
            .setContentTitle(model.bookTitle)
            .setContentText(model.currentSnippet)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(model.openIntent ?: createOpenAppPendingIntent())
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .addAction(
                NotificationCompat.Action(
                    android.R.drawable.ic_media_previous,
                    service.getString(R.string.tts_action_prev),
                    createServicePendingIntent(ACTION_PREV)
                )
            )
            .addAction(
                NotificationCompat.Action(
                    playPauseIcon,
                    playPauseTitle,
                    createServicePendingIntent(if (model.isPlaying) ACTION_PAUSE else ACTION_PLAY)
                )
            )
            .addAction(
                NotificationCompat.Action(
                    android.R.drawable.ic_media_next,
                    service.getString(R.string.tts_action_next),
                    createServicePendingIntent(ACTION_NEXT)
                )
            )
            .addAction(
                NotificationCompat.Action(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    service.getString(R.string.tts_action_stop),
                    createServicePendingIntent(ACTION_STOP)
                )
            )
            .setStyle(
                MediaNotificationCompat.MediaStyle()
                    .setMediaSession(compatSessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    /** Tạo launcher intent dự phòng khi không có intent dành riêng cho sách hiện tại. */
    private fun createOpenAppPendingIntent(): PendingIntent? {
        val launchIntent = service.packageManager
            .getLaunchIntentForPackage(service.packageName)
            ?.apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            ?: return null
        return PendingIntent.getActivity(
            service,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Tạo intent điều khiển bất biến và gửi tới TTS service đang hoạt động. */
    private fun createServicePendingIntent(action: String): PendingIntent {
        val intent = Intent(service, TtsService::class.java).setAction(action)
        return PendingIntent.getService(
            service,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        const val CHANNEL_ID = "tts_service_channel"
        const val NOTIFICATION_ID = 2001
        const val ACTION_PLAY = "com.epubpro.tts.ACTION_PLAY"
        const val ACTION_PAUSE = "com.epubpro.tts.ACTION_PAUSE"
        const val ACTION_NEXT = "com.epubpro.tts.ACTION_NEXT"
        const val ACTION_PREV = "com.epubpro.tts.ACTION_PREV"
        const val ACTION_STOP = "com.epubpro.tts.ACTION_STOP"
    }
}
