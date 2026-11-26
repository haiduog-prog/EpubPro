package com.epubpro.core.reader.tts

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState

/** Quản lý metadata, trạng thái phát và việc chuyển tiếp callback điều khiển của MediaSession. */
class TtsMediaSessionManager(
    private val context: Context,
    private val onPlay: () -> Unit,
    private val onPause: () -> Unit,
    private val onSkipNext: () -> Unit,
    private val onSkipPrevious: () -> Unit,
    private val onStop: () -> Unit
) {

    /** MediaSession đang hoạt động để SystemUI và các media controller bên ngoài sử dụng. */
    val mediaSession: MediaSession = MediaSession(context, "EpubProTtsSession").apply {
        setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
        setCallback(object : MediaSession.Callback() {
            // Phải gọi qua `this@TtsMediaSessionManager`: trong object này `onPlay()` trơn sẽ trỏ về
            // chính hàm override và đệ quy vô hạn (StackOverflowError) khi bấm phím media.
            override fun onPlay() { this@TtsMediaSessionManager.onPlay() }
            override fun onPause() { this@TtsMediaSessionManager.onPause() }
            override fun onSkipToNext() { onSkipNext() }
            override fun onSkipToPrevious() { onSkipPrevious() }
            override fun onStop() { this@TtsMediaSessionManager.onStop() }
        })
        isActive = true
    }

    /** Token được gắn vào MediaStyle notification để liên kết với MediaSession hiện tại. */
    val sessionToken: MediaSession.Token
        get() = mediaSession.sessionToken

    /** Cập nhật metadata của sách và thời lượng chương cho media card của SystemUI. */
    fun updateMetadata(
        bookTitle: String,
        author: String,
        currentSnippet: String,
        durationMs: Long
    ) {
        val metadata = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, bookTitle)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, author)
            .putString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST, currentSnippet)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs.coerceAtLeast(0L))
            .build()
        mediaSession.setMetadata(metadata)
    }

    /**
     * Cập nhật trạng thái đang phát hoặc tạm dừng cùng vị trí và tốc độ dùng để chạy progress trên
     * notification. Trạng thái đang phát với tốc độ `0f` giữ nút pause nhưng đóng băng progress.
     */
    fun updatePlaybackState(
        isPlaying: Boolean,
        positionMs: Long = 0L,
        playbackSpeed: Float = if (isPlaying) 1.0f else 0.0f
    ) {
        setPlaybackState(
            state = if (isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
            positionMs = positionMs,
            playbackSpeed = playbackSpeed
        )
    }

    /** Cập nhật trạng thái buffering thực sự trước khi đoạn đọc đầu tiên bắt đầu phát. */
    fun updatePreparingState(positionMs: Long) {
        setPlaybackState(
            state = PlaybackState.STATE_BUFFERING,
            positionMs = positionMs,
            playbackSpeed = 0.0f
        )
    }

    /** Cập nhật lỗi phát cuối cùng nhưng vẫn giữ lại vị trí progress gần nhất đã biết. */
    fun updateErrorState(positionMs: Long, message: String) {
        setPlaybackState(
            state = PlaybackState.STATE_ERROR,
            positionMs = positionMs,
            playbackSpeed = 0.0f,
            errorMessage = message
        )
    }

    /** Cập nhật phiên phát đã dừng tại vị trí cuối hoặc vị trí được khôi phục đã cung cấp. */
    fun updateStoppedState(positionMs: Long = 0L) {
        setPlaybackState(
            state = PlaybackState.STATE_STOPPED,
            positionMs = positionMs,
            playbackSpeed = 0.0f
        )
    }

    /** Ghi PlaybackState của hệ thống cùng tập action điều khiển được hỗ trợ. */
    private fun setPlaybackState(
        state: Int,
        positionMs: Long,
        playbackSpeed: Float,
        errorMessage: String? = null
    ) {
        val actions = PlaybackState.ACTION_PLAY or
                PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_SKIP_TO_NEXT or
                PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                PlaybackState.ACTION_STOP

        val builder = PlaybackState.Builder()
            .setActions(actions)
            .setState(state, positionMs.coerceAtLeast(0L), playbackSpeed)
        if (errorMessage != null) builder.setErrorMessage(errorMessage)
        mediaSession.setPlaybackState(builder.build())
    }
    /** Vô hiệu hóa và giải phóng MediaSession khi service bị hủy. */
    fun release() {
        mediaSession.isActive = false
        mediaSession.release()
    }

}
