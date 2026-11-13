package com.epubpro.core.tts

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.io.Closeable
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

/**
 * Bộ phát PCM float32 mono 24 kHz dùng chung cho streaming đọc sách và nghe thử trong Cài đặt.
 *
 * Giữ một [AudioTrack] mở liên tục qua các chunk/câu để đọc nối tiếp không khựng tiếng,
 * đồng thời theo dõi `playbackHeadPosition` để đồng bộ highlight đúng thời điểm âm thanh kết thúc.
 *
 * @property sampleRate Tần số lấy mẫu đầu ra (Hz), mặc định 24.000 Hz.
 * @property audioManager Dịch vụ hệ thống quản lý AudioFocus (chỉ truyền khi phát độc lập như preview).
 * @property onAudioFocusLost Callback gọi khi bị mất AudioFocus tạm thời hoặc vĩnh viễn.
 */
open class VieNeuPcmPlayer(
    private val sampleRate: Int = VieNeuNanoEngine.SAMPLE_RATE,
    private val audioManager: AudioManager? = null,
    private val onAudioFocusLost: (() -> Unit)? = null,
) : Closeable {
    private val trackLock = Any()
    private val epoch = AtomicLong(0L)

    private var track: AudioTrack? = null
    private var focusRequest: AudioFocusRequest? = null
    private var totalFramesWritten: Long = 0L

    private val audioAttributes: AudioAttributes by lazy(LazyThreadSafetyMode.PUBLICATION) {
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }

    private val focusChangeListener: AudioManager.OnAudioFocusChangeListener by lazy(LazyThreadSafetyMode.PUBLICATION) {
        AudioManager.OnAudioFocusChangeListener { focusChange ->
            when (focusChange) {
                AudioManager.AUDIOFOCUS_LOSS,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                -> onAudioFocusLost?.invoke()
            }
        }
    }

    /**
     * Trả về epoch hiện tại để phát hiện chính xác việc [pauseAndFlush] giữa các lần ghi buffer.
     *
     * @return Giá trị bộ đếm epoch hiện tại.
     */
    fun currentEpoch(): Long = epoch.get()

    /**
     * Trả về tổng số frame đã ghi vào [AudioTrack] từ lúc mở phiên phát hiện tại.
     *
     * @return Số frame PCM đã ghi thành công.
     */
    fun currentFramesWritten(): Long = synchronized(trackLock) { totalFramesWritten }

    /**
     * Đảm bảo [AudioTrack] ở trạng thái sẵn sàng phát và trả về epoch hiện hành.
     *
     * @return Giá trị epoch gắn với [AudioTrack] đang hoạt động.
     * @throws IllegalStateException Nếu phần cứng không hỗ trợ PCM float 24 kHz hoặc khởi tạo thất bại.
     */
    open fun ensureStarted(): Long = synchronized(trackLock) {
        val current = track
        if (current != null && current.state == AudioTrack.STATE_INITIALIZED) {
            if (current.playState != AudioTrack.PLAYSTATE_PLAYING) {
                current.play()
            }
            return@synchronized epoch.get()
        }

        releaseTrackLocked()
        totalFramesWritten = 0L
        requestAudioFocusLocked()

        val minBytes = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        check(minBytes > 0) { "Thiết bị không hỗ trợ PCM float $sampleRate Hz (code=$minBytes)" }

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
        epoch.incrementAndGet()
    }

    /**
     * Ghi mảng mẫu PCM float32 vào [AudioTrack] theo từng khối vừa phải để có thể ngắt nhanh khi dừng.
     *
     * @param samples Mảng mẫu PCM float32 mono trong khoảng `[-1f, 1f]`.
     * @param expectedEpoch Epoch kỳ vọng lấy từ [ensureStarted].
     * @param isCancelled Hàm kiểm tra yêu cầu hủy giữa các lần ghi khối.
     * @return Tổng số frame đã ghi tính đến cuối mảng, hoặc `-1L` nếu bị ngắt/lỗi phần cứng.
     */
    open fun writeSamples(
        samples: FloatArray,
        expectedEpoch: Long = currentEpoch(),
        isCancelled: () -> Boolean = { false },
    ): Long {
        if (samples.isEmpty()) return currentFramesWritten()
        var offset = 0
        while (offset < samples.size && !isCancelled() && epoch.get() == expectedEpoch) {
            val activeTrack = synchronized(trackLock) {
                if (epoch.get() == expectedEpoch) track else null
            } ?: return -1L

            val sliceCount = min(WRITE_SLICE_SAMPLES, samples.size - offset)
            val written = try {
                activeTrack.write(samples, offset, sliceCount, AudioTrack.WRITE_BLOCKING)
            } catch (_: IllegalStateException) {
                return -1L
            }
            if (written <= 0) {
                synchronized(trackLock) {
                    if (epoch.get() == expectedEpoch) {
                        epoch.incrementAndGet()
                        releaseTrackLocked()
                    }
                }
                return -1L
            }
            offset += written
            synchronized(trackLock) {
                if (epoch.get() == expectedEpoch) {
                    totalFramesWritten += written.toLong()
                }
            }
        }
        return if (epoch.get() == expectedEpoch && !isCancelled()) currentFramesWritten() else -1L
    }

    /**
     * Chèn [sampleCount] mẫu im lặng vào luồng phát để tạo khoảng nghỉ câu/đoạn hoặc đệm cuối câu.
     *
     * @param sampleCount Số mẫu bằng `0f` cần ghi thêm.
     * @param expectedEpoch Epoch kỳ vọng lấy từ [ensureStarted].
     * @param isCancelled Hàm kiểm tra yêu cầu hủy.
     * @return Tổng số frame đã ghi sau khi chèn im lặng, hoặc `-1L` nếu bị ngắt.
     */
    open fun writeSilence(
        sampleCount: Int,
        expectedEpoch: Long = currentEpoch(),
        isCancelled: () -> Boolean = { false },
    ): Long {
        if (sampleCount <= 0) return currentFramesWritten()
        val reusableZeroChunk = FloatArray(min(sampleCount, WRITE_SLICE_SAMPLES))
        var remaining = sampleCount
        while (remaining > 0 && !isCancelled() && epoch.get() == expectedEpoch) {
            val count = min(remaining, reusableZeroChunk.size)
            val slice = if (count == reusableZeroChunk.size) reusableZeroChunk else FloatArray(count)
            val writtenTotal = writeSamples(slice, expectedEpoch, isCancelled)
            if (writtenTotal < 0L) return -1L
            remaining -= count
        }
        return if (epoch.get() == expectedEpoch && !isCancelled()) currentFramesWritten() else -1L
    }

    /**
     * Chờ phần cứng phát tới ít nhất [targetFrame] trước khi nhường sang chunk/câu kế tiếp.
     *
     * @param targetFrame Mốc số frame cuối lời thoại (không tính phần im lặng đệm cuối).
     * @param expectedEpoch Epoch kỳ vọng của phiên phát.
     * @param isCancelled Hàm kiểm tra yêu cầu hủy.
     * @return `true` nếu phát tới mốc [targetFrame], `false` nếu bị dừng/hủy hoặc stall phần cứng.
     */
    open suspend fun awaitFramePlayed(
        targetFrame: Long,
        expectedEpoch: Long = currentEpoch(),
        isCancelled: () -> Boolean = { false },
    ): Boolean {
        if (targetFrame <= 0L) return true
        var lastPlayedFrames = -1L
        var lastProgressAtMs = System.currentTimeMillis()
        var absoluteDeadlineMs = -1L

        while (!isCancelled() && epoch.get() == expectedEpoch && currentCoroutineContext().isActive) {
            val activeTrack = synchronized(trackLock) {
                if (epoch.get() == expectedEpoch) track else null
            } ?: return false

            val playedFrames = try {
                activeTrack.playbackHeadPosition.toLong() and 0xFFFFFFFFL
            } catch (_: IllegalStateException) {
                return false
            }

            if (playedFrames >= targetFrame) return true

            val now = System.currentTimeMillis()
            if (absoluteDeadlineMs < 0L) {
                val remainingMs = ((targetFrame - playedFrames).coerceAtLeast(0L) * 1_000L) / sampleRate
                absoluteDeadlineMs = now + remainingMs + DRAIN_SAFETY_MARGIN_MS
            }

            if (playedFrames > lastPlayedFrames) {
                lastPlayedFrames = playedFrames
                lastProgressAtMs = now
            } else if (now - lastProgressAtMs >= MAX_STALL_WAIT_MS) {
                return false
            }
            if (now >= absoluteDeadlineMs) return true

            delay(DRAIN_POLL_INTERVAL_MS)
        }
        return false
    }

    /**
     * Phát trọn vẹn một chunk có đồng bộ đầu/cuối và hỗ trợ chèn khoảng nghỉ trước lẫn im lặng đệm sau.
     *
     * @param samples Mảng mẫu PCM của phần lời nói.
     * @param prePauseSamples Số mẫu im lặng cần chèn trước [samples].
     * @param trailingSilenceSamples Số mẫu im lặng đệm thêm sau [samples] (không chờ phát hết khoảng này).
     * @param isCancelled Hàm kiểm tra yêu cầu hủy.
     * @param onSpeechStarted Callback gọi ngay trước khi bắt đầu đẩy mẫu lời nói vào [AudioTrack].
     * @return `true` nếu chunk phát hết lời thoại bình thường, `false` nếu bị ngắt/hủy.
     */
    suspend fun playChunkSynced(
        samples: FloatArray,
        prePauseSamples: Int = 0,
        trailingSilenceSamples: Int = 0,
        isCancelled: () -> Boolean = { false },
        onSpeechStarted: (() -> Unit)? = null,
    ): Boolean {
        val activeEpoch = ensureStarted()
        if (prePauseSamples > 0 && writeSilence(prePauseSamples, activeEpoch, isCancelled) < 0L) {
            return false
        }
        onSpeechStarted?.invoke()
        val speechEndFrame = writeSamples(samples, activeEpoch, isCancelled)
        if (speechEndFrame < 0L) return false
        if (trailingSilenceSamples > 0 && writeSilence(trailingSilenceSamples, activeEpoch, isCancelled) < 0L) {
            return false
        }
        return awaitFramePlayed(speechEndFrame, activeEpoch, isCancelled)
    }

    /**
     * Phát một đoạn mẫu ngắn độc lập (dùng cho nút Nghe thử trong Cài đặt) rồi tự giải phóng phần cứng.
     *
     * @param samples Mảng mẫu PCM cần phát.
     * @param isCancelled Hàm kiểm tra yêu cầu hủy.
     */
    open suspend fun playOneShot(
        samples: FloatArray,
        isCancelled: () -> Boolean = { false },
    ) {
        if (samples.isEmpty()) return
        pauseAndFlush()
        try {
            val activeEpoch = ensureStarted()
            if (writeSamples(samples, activeEpoch, isCancelled) < 0L) return
            val targetFrame = writeSilence(sampleRate / 12, activeEpoch, isCancelled)
            if (targetFrame >= 0L) {
                awaitFramePlayed(targetFrame, activeEpoch, isCancelled)
            }
        } finally {
            pauseAndFlush()
        }
    }

    /**
     * Dừng phát ngay lập tức, xoá toàn bộ dữ liệu đang xếp hàng trong [AudioTrack] và giải phóng track.
     */
    open fun pauseAndFlush() {
        epoch.incrementAndGet()
        synchronized(trackLock) {
            val current = track
            track = null
            totalFramesWritten = 0L
            if (current != null) {
                try {
                    current.pause()
                    current.flush()
                } catch (_: IllegalStateException) {
                }
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
    }

    /**
     * Dừng phát và giải phóng tài nguyên phần cứng khi không dùng nữa.
     */
    override fun close() {
        pauseAndFlush()
    }

    /**
     * Xin AudioFocus tạm thời khi [audioManager] được cung cấp.
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
     * Trả lại AudioFocus đã xin trước đó (nếu còn).
     */
    private fun abandonAudioFocusLocked() {
        val request = focusRequest ?: return
        focusRequest = null
        audioManager?.abandonAudioFocusRequest(request)
    }

    /**
     * Giải phóng [AudioTrack] hiện tại dưới `trackLock`.
     */
    private fun releaseTrackLocked() {
        val current = track
        track = null
        totalFramesWritten = 0L
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
        private const val WRITE_SLICE_SAMPLES = 4_096
        private const val DRAIN_POLL_INTERVAL_MS = 15L
        private const val DRAIN_SAFETY_MARGIN_MS = 3_000L
        private const val MAX_STALL_WAIT_MS = 2_500L
    }
}
