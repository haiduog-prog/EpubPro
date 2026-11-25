package com.epubpro.core.reader.tts

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.epubpro.domain.model.TtsPlayerState

/**
 * Chính sách thuần quyết định khi nào [TtsService] cần giữ `PARTIAL_WAKE_LOCK`, tách riêng để kiểm thử
 * không cần Android framework.
 */
internal object TtsWakeLockPolicy {
    /** Thời hạn tối đa của mỗi lần giữ wake lock; hết hạn mà không được làm mới thì hệ thống tự nhả. */
    const val HOLD_TIMEOUT_MS: Long = 10 * 60 * 1000L

    /** Khoảng tối thiểu giữa hai lần làm mới thời hạn wake lock khi trạng thái phát vẫn tiếp diễn. */
    const val REFRESH_INTERVAL_MS: Long = 60 * 1000L

    /**
     * Xác định trạng thái phát có cần giữ CPU thức hay không. Chỉ các trạng thái đang đọc hoặc đang
     * chuẩn bị đọc (tổng hợp đoạn, tải chương kế tiếp) mới giữ, để CPU không ngủ trong khoảng lặng
     * giữa hai đoạn khi màn hình tắt.
     *
     * @param state Trạng thái phát hiện tại của [TtsService].
     * @return `true` nếu cần giữ wake lock; `false` với `Idle`, `Paused`, `Completed`, `Error`.
     */
    fun shouldHold(state: TtsPlayerState): Boolean = when (state) {
        is TtsPlayerState.Playing,
        is TtsPlayerState.Preparing,
        TtsPlayerState.Loading -> true
        TtsPlayerState.Idle,
        is TtsPlayerState.Paused,
        is TtsPlayerState.Completed,
        is TtsPlayerState.Error -> false
    }

    /**
     * Xác định có cần gọi `acquire(timeout)` lại hay không, nhằm gia hạn wake lock khi phát lâu mà
     * không gọi binder liên tục theo từng nhịp cập nhật tiến độ.
     *
     * @param isHeld `true` nếu wake lock đang được giữ.
     * @param lastAcquireAtMs Thời điểm (`elapsedRealtime`) lần `acquire` gần nhất.
     * @param nowMs Thời điểm hiện tại (`elapsedRealtime`).
     * @return `true` nếu chưa giữ hoặc đã quá [REFRESH_INTERVAL_MS] kể từ lần giữ gần nhất.
     */
    fun shouldAcquire(isHeld: Boolean, lastAcquireAtMs: Long, nowMs: Long): Boolean =
        !isHeld || nowMs - lastAcquireAtMs >= REFRESH_INTERVAL_MS
}

/**
 * Bọc `PowerManager.PARTIAL_WAKE_LOCK` cho phiên đọc TTS: giữ CPU thức khi đang đọc lúc tắt màn hình
 * (khoảng tổng hợp giọng AI hoặc chuyển chương không có âm thanh nên hệ thống âm thanh không giữ máy thức)
 * và nhả ngay khi tạm dừng/dừng. Wake lock không đếm tham chiếu và luôn có thời hạn để tránh hao pin
 * nếu luồng nhả bị bỏ sót.
 *
 * @param context Context dùng để lấy [PowerManager].
 */
internal class TtsPlaybackWakeLock(context: Context) {
    private val wakeLock: PowerManager.WakeLock? = runCatching {
        (context.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            ?.apply { setReferenceCounted(false) }
    }.getOrNull()

    private var lastAcquireAtMs: Long = 0L

    /**
     * Đồng bộ wake lock theo trạng thái phát: giữ (hoặc gia hạn) khi đang đọc, nhả khi không đọc.
     * Cần gọi trên cùng một thread (Main của [TtsService]).
     *
     * @param state Trạng thái phát mới nhất.
     * @param nowMs Thời điểm hiện tại theo `SystemClock.elapsedRealtime()`.
     */
    fun update(state: TtsPlayerState, nowMs: Long = SystemClock.elapsedRealtime()) {
        val lock = wakeLock ?: return
        if (TtsWakeLockPolicy.shouldHold(state)) {
            if (TtsWakeLockPolicy.shouldAcquire(lock.isHeld, lastAcquireAtMs, nowMs)) {
                runCatching { lock.acquire(TtsWakeLockPolicy.HOLD_TIMEOUT_MS) }
                    .onSuccess { lastAcquireAtMs = nowMs }
                    .onFailure { Log.w(TAG, "Không giữ được wake lock TTS: ${it.message}") }
            }
        } else {
            release()
        }
    }

    /** Nhả wake lock nếu đang giữ; an toàn khi gọi nhiều lần (ví dụ trong `onDestroy`). */
    fun release() {
        val lock = wakeLock ?: return
        if (lock.isHeld) {
            runCatching { lock.release() }
        }
        lastAcquireAtMs = 0L
    }

    private companion object {
        const val TAG = "TtsPlaybackWakeLock"
        const val WAKE_LOCK_TAG = "EpubPro:TtsPlayback"
    }
}
