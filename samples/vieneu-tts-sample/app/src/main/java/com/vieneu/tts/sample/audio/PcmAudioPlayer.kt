package com.vieneu.tts.sample.audio

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.io.Closeable
import kotlin.math.max
import kotlin.math.min

/**
 * Trình phát âm thanh PCM float32 mono ở tần số 24 kHz cho VieNeu-TTS v3-Nano.
 *
 * @param sampleRate Tần số lấy mẫu đầu ra (Hz), mặc định 24.000 Hz.
 * @param audioManager Dịch vụ hệ thống quản lý AudioFocus (tuỳ chọn).
 * @param onAudioFocusLost Callback được gọi khi mất quyền phát âm thanh lâu dài hoặc tạm thời.
 */
class PcmAudioPlayer(
    private val sampleRate: Int = 24_000,
    private val audioManager: AudioManager? = null,
    private val onAudioFocusLost: (() -> Unit)? = null,
) : Closeable {
    private val lock = Any()
    private var track: AudioTrack? = null
    private var focusRequest: AudioFocusRequest? = null
    private var totalFramesWritten: Long = 0L

    @Volatile
    private var stopped: Boolean = false

    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            -> onAudioFocusLost?.invoke()
        }
    }

    /**
     * Xin AudioFocus (nếu có [AudioManager]), khởi tạo và bắt đầu [AudioTrack] chế độ stream.
     *
     * @throws IllegalStateException Nếu thiết bị không khởi tạo được [AudioTrack] hợp lệ.
     */
    fun start() {
        synchronized(lock) {
            releaseTrackLocked()
            stopped = false
            totalFramesWritten = 0L

            requestAudioFocusLocked()

            val minBytes = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_FLOAT,
            )
            check(minBytes > 0) { "Thiết bị không hỗ trợ định dạng PCM float $sampleRate Hz (code=$minBytes)" }

            val bufferBytes = max(minBytes, sampleRate * Float.SIZE_BYTES)
            val format = AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
            val created = AudioTrack.Builder()
                .setAudioAttributes(audioAttributes)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            if (created.state != AudioTrack.STATE_INITIALIZED) {
                created.release()
                abandonAudioFocusLocked()
                throw IllegalStateException("Không thể khởi tạo AudioTrack (state=${created.state})")
            }

            created.play()
            track = created
        }
    }

    /**
     * Ghi mảng mẫu PCM float32 vào [AudioTrack] đang chạy.
     *
     * @param samples Mảng mẫu mono nằm trong dải `[-1f, 1f]`.
     */
    fun writeSamples(samples: FloatArray) {
        if (samples.isEmpty() || stopped) return
        val current = synchronized(lock) { track } ?: return
        var offset = 0
        while (offset < samples.size && !stopped) {
            val written = current.write(samples, offset, samples.size - offset, AudioTrack.WRITE_BLOCKING)
            if (written <= 0) break
            offset += written
            synchronized(lock) {
                totalFramesWritten += written.toLong()
            }
        }
    }

    /**
     * Chèn khoảng im lặng giữa hai chunk để khớp độ nghỉ câu/đoạn tự nhiên.
     *
     * @param sampleCount Số mẫu bằng 0 cần ghi vào stream.
     */
    fun writeSilence(sampleCount: Int) {
        if (sampleCount <= 0 || stopped) return
        val chunk = FloatArray(min(sampleCount, SILENCE_CHUNK_SAMPLES))
        var remaining = sampleCount
        while (remaining > 0 && !stopped) {
            val count = min(remaining, chunk.size)
            if (count == chunk.size) {
                writeSamples(chunk)
            } else {
                writeSamples(FloatArray(count))
            }
            remaining -= count
        }
    }

    /**
     * Chờ phát hết toàn bộ số frame đã ghi rồi đóng [AudioTrack] một cách an toàn.
     *
     * Không áp dụng trần timeout cứng để tránh cắt đuôi các văn bản dài nhiều chục giây;
     * chỉ thoát sớm khi có lệnh dừng, huỷ coroutine hoặc phần cứng ngừng tiến triển quá [MAX_STALL_WAIT_MS].
     */
    suspend fun finishAndRelease() {
        val current = synchronized(lock) { track }
        if (current != null && !stopped) {
            try {
                // Thêm ~80ms im lặng cuối để hardware buffer không cắt gắt ở âm tiết cuối.
                writeSilence(sampleRate / 12)
                val targetFrames = synchronized(lock) { totalFramesWritten }
                var lastPlayedFrames = current.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                val remainingMs = ((targetFrames - lastPlayedFrames).coerceAtLeast(0L) * 1000L) / sampleRate
                var lastProgressAtMs = System.currentTimeMillis()
                val absoluteDeadlineMs = lastProgressAtMs + remainingMs + DRAIN_SAFETY_MARGIN_MS

                while (!stopped && currentCoroutineContext().isActive) {
                    val now = System.currentTimeMillis()
                    val playedFrames = current.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                    if (playedFrames >= targetFrames) break
                    if (playedFrames > lastPlayedFrames) {
                        lastPlayedFrames = playedFrames
                        lastProgressAtMs = now
                    } else if (now - lastProgressAtMs >= MAX_STALL_WAIT_MS) {
                        break
                    }
                    if (now >= absoluteDeadlineMs) break
                    delay(DRAIN_POLL_INTERVAL_MS)
                }
            } catch (_: IllegalStateException) {
            }
        }
        synchronized(lock) {
            releaseTrackLocked()
        }
    }

    /**
     * Dừng phát ngay lập tức, xoá phần đệm trong [AudioTrack] và trả AudioFocus.
     */
    fun stopImmediately() {
        stopped = true
        synchronized(lock) {
            val current = track
            if (current != null) {
                try {
                    current.pause()
                    current.flush()
                } catch (_: IllegalStateException) {
                }
            }
            releaseTrackLocked()
        }
    }

    /**
     * Giải phóng tài nguyên phần cứng âm thanh.
     */
    override fun close() {
        stopImmediately()
    }

    /**
     * Yêu cầu quyền phát âm thanh tạm thời trên hệ thống khi có [AudioManager].
     */
    private fun requestAudioFocusLocked() {
        val manager = audioManager ?: return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(audioAttributes)
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener(focusChangeListener)
            .build()
        manager.requestAudioFocus(request)
        focusRequest = request
    }

    /**
     * Giải phóng AudioFocus đã xin trước đó (nếu còn giữ).
     */
    private fun abandonAudioFocusLocked() {
        val request = focusRequest ?: return
        focusRequest = null
        audioManager?.abandonAudioFocusRequest(request)
    }

    /**
     * Dừng, giải phóng [AudioTrack] hiện tại và trả AudioFocus dưới khoá đồng bộ.
     */
    private fun releaseTrackLocked() {
        val current = track
        track = null
        if (current != null) {
            try {
                current.stop()
            } catch (_: IllegalStateException) {
            }
            try {
                current.release()
            } catch (_: IllegalStateException) {
            }
        }
        abandonAudioFocusLocked()
    }

    companion object {
        private const val SILENCE_CHUNK_SAMPLES = 4_096
        private const val DRAIN_POLL_INTERVAL_MS = 20L
        private const val DRAIN_SAFETY_MARGIN_MS = 3_000L
        private const val MAX_STALL_WAIT_MS = 2_500L
    }
}
