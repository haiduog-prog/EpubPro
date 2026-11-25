package com.epubpro.app.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.epubpro.core.reader.tts.TtsWidgetContract
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Receiver không export nhận [TtsWidgetContract.ACTION_STATE_CHANGED] từ chính ứng dụng
 * (`TtsService`, Library, Reader) rồi vẽ lại cả hai loại widget TTS.
 *
 * Tách khỏi các `AppWidgetProvider` (bắt buộc export cho launcher) để app khác không thể
 * gửi broadcast này hàng loạt nhằm ép app đọc store và giải mã ảnh bìa liên tục.
 */
class TtsWidgetStateReceiver : BroadcastReceiver() {

    /**
     * Cập nhật audio widget và reading widget trên luồng nền khi trạng thái phát thay đổi.
     *
     * @param context Context của receiver.
     * @param intent Broadcast nhận được; chỉ xử lý action cập nhật trạng thái widget.
     */
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TtsWidgetContract.ACTION_STATE_CHANGED) return
        val appContext = context.applicationContext
        WidgetBackgroundUpdater.run(this) {
            TtsAudioWidgetProvider.updateAll(appContext)
            TtsReadingWidgetProvider.updateAll(appContext)
        }
    }
}

/**
 * Chạy việc dựng RemoteViews (đọc SharedPreferences, giải mã ảnh bìa) ngoài main thread.
 *
 * Dùng một luồng duy nhất để các lần cập nhật được áp dụng đúng thứ tự, tránh trạng thái cũ
 * ghi đè trạng thái mới khi broadcast tới dồn dập.
 */
internal object WidgetBackgroundUpdater {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "tts-widget-update")
    }

    /**
     * Giữ broadcast sống bằng [BroadcastReceiver.goAsync] và chạy [block] trên luồng nền.
     *
     * Phải gọi trong lúc [BroadcastReceiver.onReceive] (kể cả `onUpdate` của AppWidgetProvider).
     *
     * @param receiver Receiver đang xử lý broadcast.
     * @param block Công việc cập nhật widget.
     */
    fun run(receiver: BroadcastReceiver, block: () -> Unit) {
        val pendingResult = receiver.goAsync()
        executor.execute {
            try {
                block()
            } finally {
                pendingResult.finish()
            }
        }
    }
}
