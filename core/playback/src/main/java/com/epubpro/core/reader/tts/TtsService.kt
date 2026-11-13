package com.epubpro.core.reader.tts

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.epubpro.core.designsystem.R
import com.epubpro.core.designsystem.R as ReaderR
import com.epubpro.core.reader.filter.ContentSanitizer
import com.epubpro.core.reader.tts.bubble.TtsBubbleCommand
import com.epubpro.core.reader.tts.bubble.TtsBubbleRuntime
import com.epubpro.core.reader.tts.bubble.TtsBubbleUiModel
import com.epubpro.core.reader.tts.notification.TtsPlaybackNotificationManager
import com.epubpro.core.reader.tts.notification.TtsPlaybackNotificationModel
import com.epubpro.core.storage.ReaderPreferencesManager
import com.epubpro.core.storage.TtsBubblePreferencesManager
import com.epubpro.core.storage.TtsBubblePowerMode
import com.epubpro.core.storage.TtsPlaybackSnapshot
import com.epubpro.core.storage.TtsPlaybackSnapshotStore
import com.epubpro.core.storage.TtsPreferencesManager
import com.epubpro.core.storage.TtsWidgetPlaybackStatus
import com.epubpro.core.storage.TtsWidgetState
import com.epubpro.core.storage.TtsWidgetStateStore
import com.epubpro.domain.model.Book
import com.epubpro.domain.model.SleepTimerOption
import com.epubpro.domain.model.TtsChunk
import com.epubpro.domain.model.TtsPlayerState
import com.epubpro.domain.model.TtsSettings
import com.epubpro.domain.model.normalizedForPlayback
import com.epubpro.domain.repository.BookRepository
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class TtsService : Service() {

    @Inject
    lateinit var preferencesManager: TtsPreferencesManager

    @Inject
    lateinit var readerPreferencesManager: ReaderPreferencesManager

    @Inject
    lateinit var piperTtsEngineWrapper: PiperTtsEngineWrapper

    @Inject
    lateinit var vieNeuTtsEngineWrapper: VieNeuTtsEngineWrapper

    @Inject
    lateinit var chapterPlaybackCoordinator: TtsChapterPlaybackCoordinator

    @Inject
    lateinit var bubblePreferencesManager: TtsBubblePreferencesManager

    @Inject
    lateinit var playbackSnapshotStore: TtsPlaybackSnapshotStore

    @Inject
    lateinit var bookRepository: BookRepository

    @Inject
    lateinit var widgetStateStore: TtsWidgetStateStore

    private val binder = TtsBinder()
    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    private lateinit var nativeTtsEngine: AndroidNativeTtsEngine
    private lateinit var currentEngine: TtsEngine
    private lateinit var mediaSessionManager: TtsMediaSessionManager
    private lateinit var playbackNotificationManager: TtsPlaybackNotificationManager
    private lateinit var audioFocusController: TtsAudioFocusController

    private var chunks: List<TtsChunk> = emptyList()
    private var currentIndex: Int = 0
    private var currentChapterIndex: Int = 0
    private var currentChapterTitle: String = ""
    private var bookId: String = ""
    private var bookTitle: String = ""
    private var bookCoverPath: String? = null
    private var author: String = "EpubPro Reader"
    private var preferAiContent: Boolean = false

    private var activeSettings: TtsSettings = TtsSettings()
    private var sleepTimerOption: SleepTimerOption = SleepTimerOption.OFF
    private var sleepTimerJob: Job? = null
    private var idleTimeoutJob: Job? = null
    private var notificationProgressJob: Job? = null
    private var chapterPreparation: Deferred<Result<Unit>>? = null
    private var remainingSleepSeconds: Int = 0
    private var playbackGeneration: Long = 0
    private var pausedBySystem: Boolean = false
    private var chapterTransitionPlayWhenReady: Boolean = true
    private var hasPlaybackStartedInSession: Boolean = false
    private var startedSession: Boolean = false
    private var isRestoringSnapshot: Boolean = false
    private var pendingSnapshotMove: Int = 0
    private var snapshotRestoreJob: Job? = null
    private var readingWidgetMoveJob: Job? = null
    private val readingWidgetMoveQueue = TtsReadingWidgetMoveQueue(MAX_QUEUED_SNAPSHOT_MOVES)
    private var latestReadingWidgetStartId: Int = 0
    private var idleEpisodeId: Long = 0L
    private var isShuttingDownIdle: Boolean = false
    private var currentCoverBitmap: Bitmap? = null

    private lateinit var bubbleRuntime: TtsBubbleRuntime

    private var estimatedChunkDurationsMs: LongArray = LongArray(0)
    private var estimatedChunkStartPositionsMs: LongArray = LongArray(0)
    private var estimatedTotalDurationMs: Long = 0L
    private var estimatedTimelineSpeed: Float = Float.NaN
    private var currentChunkStartPositionMs: Long = 0L
    private var currentTimelinePositionMs: Long = 0L
    private var playbackStartedAtElapsedRealtimeMs: Long? = null

    override fun onCreate() {
        super.onCreate()
        bookTitle = getString(R.string.tts_default_book_title)
        nativeTtsEngine = AndroidNativeTtsEngine(applicationContext)

        mediaSessionManager = TtsMediaSessionManager(
            context = applicationContext,
            onPlay = { resume() },
            onPause = { pause() },
            onSkipNext = { nextChunk() },
            onSkipPrevious = { previousChunk() },
            onStop = { stopSession() }
        )
        playbackNotificationManager = TtsPlaybackNotificationManager(
            service = this,
            mediaSessionToken = mediaSessionManager.sessionToken
        )
        audioFocusController = TtsAudioFocusController(
            context = applicationContext,
            onFocusLost = { shouldAutoResume ->
                pauseFromSystem(autoResumeOnFocusGain = shouldAutoResume)
            },
            onFocusGained = {
                if (pausedBySystem) {
                    pausedBySystem = false
                    resume()
                }
            },
            onBecomingNoisy = {
                pauseFromSystem(autoResumeOnFocusGain = false)
                audioFocusController.abandonFocus()
            }
        )

        activeSettings = preferencesManager.getSettings().normalizedForPlayback()
        currentEngine = engineFor(activeSettings)
        applySettingsToEngines(activeSettings)
        nativeTtsEngine.initialize(
            onReady = {},
            onError = ::handleEngineInitializationError
        )


        bubbleRuntime = TtsBubbleRuntime(
            context = applicationContext,
            scope = serviceScope,
            preferencesManager = bubblePreferencesManager,
            onCommand = ::handleBubbleCommand,
            onAvailabilityChanged = { syncBubbleLifecycle() },
            onOverlayUnavailable = ::handleOverlayUnavailable,
            onEnvironmentChanged = { if (::bubbleRuntime.isInitialized) syncPowerPolicy() },
            onInteraction = ::handleBubbleInteraction
        )
        publishBubbleModel()
        publishWidgetState()

        serviceScope.launch {
            preferencesManager.settingsFlow.collect { settings ->
                updateSettings(settings)
            }
        }
        serviceScope.launch {
            bubblePreferencesManager.preferencesFlow.collect {
                bubbleRuntime.refreshEnvironment()
                syncBubbleLifecycle()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            null,
            ACTION_SYNC_BUBBLE -> syncBubbleLifecycle()
            ACTION_START_SESSION -> Unit
            TtsPlaybackNotificationManager.ACTION_PLAY -> resume()
            TtsPlaybackNotificationManager.ACTION_PAUSE -> pause()
            TtsPlaybackNotificationManager.ACTION_NEXT -> nextChunk()
            TtsPlaybackNotificationManager.ACTION_PREV -> previousChunk()
            TtsPlaybackNotificationManager.ACTION_STOP -> stopSession()
            ACTION_WIDGET_PLAY_PAUSE -> handleWidgetPlayPause(startId)
            ACTION_WIDGET_PREVIOUS -> handleWidgetPrevious(startId)
            ACTION_WIDGET_NEXT -> handleWidgetNext(startId)
            ACTION_WIDGET_READING_PREVIOUS -> handleReadingWidgetMove(startId, -1)
            ACTION_WIDGET_READING_NEXT -> handleReadingWidgetMove(startId, 1)
        }
        return if (
            bubbleRuntime.isBubbleAvailable() &&
            bubblePreferencesManager.getPreferences().powerMode == TtsBubblePowerMode.ALWAYS_ON
        ) START_STICKY else START_NOT_STICKY
    }

    private fun handleWidgetPlayPause(startId: Int) {
        if (playbackSnapshotStore.getSnapshot() == null &&
            _playerState.value is TtsPlayerState.Idle
        ) {
            stopSelf(startId)
            return
        }
        startedSession = true
        if (isPlaybackRunning()) pause() else resume()
    }

    private fun handleWidgetPrevious(startId: Int) {
        if (playbackSnapshotStore.getSnapshot() == null && chunks.isEmpty()) {
            stopSelf(startId)
            return
        }
        startedSession = true
        previousChunk()
    }

    private fun handleWidgetNext(startId: Int) {
        if (playbackSnapshotStore.getSnapshot() == null && chunks.isEmpty()) {
            stopSelf(startId)
            return
        }
        startedSession = true
        nextChunk()
    }

    private fun handleReadingWidgetMove(startId: Int, relativeMove: Int) {
        if (playbackSnapshotStore.getSnapshot() == null) {
            stopSelf(startId)
            return
        }
        if (!playbackNotificationManager.isForeground) {
            showForegroundNotification(
                text = currentNotificationText(),
                isPlaying = false
            )
        }
        latestReadingWidgetStartId = startId
        readingWidgetMoveQueue.enqueue(relativeMove)
        if (readingWidgetMoveJob?.isActive == true) return

        readingWidgetMoveJob = serviceScope.launch { processReadingWidgetMoves() }
    }

    private suspend fun processReadingWidgetMoves() {
        try {
            while (true) {
                val relativeMove = readingWidgetMoveQueue.drain()
                if (relativeMove == 0) break
                val snapshot = playbackSnapshotStore.getSnapshot() ?: break
                val expectedGeneration = playbackGeneration
                val projection = loadReadingWidgetProjection(
                    snapshot = snapshot,
                    relativeMove = relativeMove,
                    expectedGeneration = expectedGeneration
                )

                playbackSnapshotStore.saveSnapshot(
                    TtsPlaybackSnapshot(
                        bookId = projection.book.id,
                        chapterIndex = projection.chapter.content.chapterIndex,
                        paragraphIndex = projection.paragraphIndex,
                        sentenceIndex = 0,
                        preferAiContent = snapshot.preferAiContent,
                        timelinePositionMs = 0L
                    )
                )

                val changed = widgetStateStore.saveState(
                    TtsWidgetState(
                        bookTitle = projection.book.title,
                        chapterTitle = projection.chapter.content.chapterTitle,
                        playbackStatus = if (isPlaybackRunning()) {
                            TtsWidgetPlaybackStatus.PLAYING
                        } else {
                            TtsWidgetPlaybackStatus.IDLE
                        },
                        progress = if (projection.totalParagraphs > 0) {
                            ((projection.paragraphIndex + 1).toFloat() / projection.totalParagraphs.toFloat())
                                .coerceIn(0f, 1f)
                        } else {
                            0f
                        },
                        hasSnapshot = true,
                        coverPath = projection.book.coverPath,
                        paragraphIndex = projection.paragraphIndex,
                        totalParagraphs = projection.totalParagraphs,
                        paragraphText = buildReadingWidgetText(
                            chunks = projection.chapter.chunks,
                            paragraphIndex = projection.paragraphIndex,
                            maxChars = WIDGET_READING_TEXT_MAX_CHARS
                        )
                    )
                )
                if (changed) {
                    sendBroadcast(Intent(TtsWidgetContract.ACTION_STATE_CHANGED).setPackage(packageName))
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            readingWidgetMoveQueue.clear()
        } finally {
            readingWidgetMoveJob = null
            if (!isPlaybackSessionActive() && !bubbleRuntime.isBubbleAvailable()) {
                stopForegroundAndRemove()
                startedSession = false
                stopSelf(latestReadingWidgetStartId)
            }
        }
    }
    fun loadContent(
        id: String,
        title: String,
        bookAuthor: String,
        parsedChunks: List<TtsChunk>,
        startIndex: Int = 0,
        chapterIndex: Int = 0,
        chapterTitle: String = "",
        preferAiContent: Boolean = false
    ) {
        ensureStartedSession()
        bubblePreferencesManager.beginNewPlaybackSession()
        invalidatePlayback()
        currentEngine.stop()
        hasPlaybackStartedInSession = false

        bookId = id
        bookTitle = title
        author = bookAuthor
        currentChapterIndex = chapterIndex
        currentChapterTitle = chapterTitle
        this.preferAiContent = preferAiContent
        activeSettings = preferencesManager.getSettings().normalizedForPlayback()
        applySettingsToEngines(activeSettings)

        chunks = TtsSentenceSegmenter.segment(parsedChunks, activeSettings.language)
        currentIndex = chunks.indexOfFirst { it.paragraphIndex >= startIndex }
            .takeIf { it >= 0 }
            ?: 0
        rebuildEstimatedTimeline(activeSettings.speed)
        saveCurrentSnapshot(currentChunkStartPositionMs)
        refreshBookVisuals(id)

        chapterPreparation?.cancel()
        chapterPreparation = serviceScope.async {
            runCatching {
                chapterPlaybackCoordinator.prepare(bookId, preferAiContent)
            }
        }

        if (chunks.isEmpty()) {
            showPlaybackError("Chương hiện tại không có nội dung để đọc")
            return
        }
        playCurrentChunk()
    }

    /**
     * Chuẩn bị và phát đoạn văn bản hiện tại, đồng thời duy trì trạng thái MediaSession liên tục sau
     * khi đoạn đọc đầu tiên đã bắt đầu.
     */
    fun playCurrentChunk() {
        if (chunks.isEmpty() || currentIndex !in chunks.indices) return
        ensureStartedSession()

        val filterPrefs = readerPreferencesManager.getFilterPreferences()
        var candidateIndex = currentIndex
        var chunkToSpeak: TtsChunk? = null
        while (candidateIndex in chunks.indices) {
            val candidate = chunks[candidateIndex]
            val text = if (filterPrefs.isFilterEnabled) {
                ContentSanitizer.sanitize(candidate.text, filterPrefs)
            } else {
                candidate.text
            }
            if (text.isNotBlank()) {
                currentIndex = candidateIndex
                chunkToSpeak = candidate.copy(text = text)
                break
            }
            candidateIndex++
        }

        if (chunkToSpeak == null) {
            currentIndex = chunks.lastIndex
            val expectedGeneration = playbackGeneration
            serviceScope.launch { advanceToNextChapter(expectedGeneration) }
            return
        }

        val originalChunk = chunks[currentIndex]
        preferencesManager.saveLastTtsChunkIndex(
            bookId,
            currentChapterIndex,
            originalChunk.paragraphIndex
        )

        if (!audioFocusController.requestFocus()) {
            showPlaybackError(getString(R.string.tts_audio_focus_error))
            return
        }

        currentEngine = engineFor(activeSettings)
        ensureEstimatedTimeline(activeSettings.speed)
        currentChunkStartPositionMs =
            estimatedChunkStartPositionsMs.getOrElse(currentIndex) { 0L }
        currentTimelinePositionMs = currentChunkStartPositionMs
        playbackStartedAtElapsedRealtimeMs = null
        notificationProgressJob?.cancel()
        saveCurrentSnapshot(currentChunkStartPositionMs)

        val expectedIndex = currentIndex
        val expectedChapterIndex = currentChapterIndex
        val playbackId = ++playbackGeneration

        _playerState.value = TtsPlayerState.Preparing(
            bookId = bookId,
            chapterIndex = currentChapterIndex,
            currentChunkIndex = currentIndex,
            totalChunks = chunks.size,
            currentChunk = chunkToSpeak
        )
        publishWidgetState()
        mediaSessionManager.updateMetadata(
            bookTitle = bookTitle,
            author = author,
            currentSnippet = chunkToSpeak.text,
            durationMs = estimatedTotalDurationMs
        )
        updateMediaSessionForPreparation(currentChunkStartPositionMs)
        publishBubbleModel()
        // Keep the technical Preparing state for transport controls, but do not
        // expose a loading message between every two consecutive chunks.
        showForegroundNotification(
            text = chunkToSpeak.text,
            isPlaying = true
        )

        currentEngine.speak(
            chunk = chunkToSpeak,
            onChunkStart = { startedChunkId ->
                serviceScope.launch {
                    if (!isCurrentPlayback(
                            playbackId,
                            expectedChapterIndex,
                            expectedIndex,
                            startedChunkId,
                            chunkToSpeak.id
                        )
                    ) {
                        return@launch
                    }

                    currentEngine.prefetchNextChunkIfSupported(
                        chunks = chunks,
                        currentIndex = expectedIndex,
                        readerPreferencesManager = readerPreferencesManager
                    )
                    playbackStartedAtElapsedRealtimeMs = SystemClock.elapsedRealtime()
                    hasPlaybackStartedInSession = true
                    _playerState.value = TtsPlayerState.Playing(
                        bookId = bookId,
                        chapterIndex = currentChapterIndex,
                        currentChunkIndex = currentIndex,
                        totalChunks = chunks.size,
                        currentChunk = chunkToSpeak,
                        progressMs = currentChunkStartPositionMs,
                        totalMs = estimatedTotalDurationMs
                    )
                    publishWidgetState()
                    saveCurrentSnapshot(currentChunkStartPositionMs)
                    publishBubbleModel()
                    mediaSessionManager.updatePlaybackState(
                        isPlaying = true,
                        positionMs = currentChunkStartPositionMs,
                        playbackSpeed = 1.0f
                    )
                    showForegroundNotification(
                        text = chunkToSpeak.text,
                        isPlaying = true
                    )
                    startNotificationProgressUpdates(
                        playbackId = playbackId,
                        expectedChapterIndex = expectedChapterIndex,
                        expectedIndex = expectedIndex
                    )
                }
            },
            onChunkDone = { completedChunkId ->
                serviceScope.launch {
                    if (!isCurrentPlayback(
                            playbackId,
                            expectedChapterIndex,
                            expectedIndex,
                            completedChunkId,
                            chunkToSpeak.id
                        )
                    ) {
                        return@launch
                    }
                    currentTimelinePositionMs =
                        estimatedChunkStartPositionsMs.getOrElse(currentIndex) { 0L } +
                            estimatedChunkDurationsMs.getOrElse(currentIndex) { 0L }
                    advancePastCurrentSentence()
                }
            },
            onError = { message ->
                serviceScope.launch {
                    if (playbackId == playbackGeneration) {
                        showPlaybackError(message)
                    }
                }
            }
        )
    }

    fun pause() {
        if (isRestoringSnapshot) {
            cancelSnapshotRestoreToIdle()
            return
        }
        pauseInternal(autoResumeOnFocusGain = false, abandonAudioFocus = true)
    }

    private fun cancelSnapshotRestoreToIdle() {
        invalidatePlayback()
        currentEngine.stop()
        audioFocusController.abandonFocus()
        chapterPlaybackCoordinator.clear()
        playbackStartedAtElapsedRealtimeMs = null
        pausedBySystem = false
        hasPlaybackStartedInSession = false
        _playerState.value = TtsPlayerState.Idle
        publishWidgetState()
        mediaSessionManager.updateStoppedState(currentTimelinePositionMs)
        publishBubbleModel()
        if (bubbleRuntime.isBubbleAvailable()) {
            showIdleBubbleNotification(forceForegroundEpisodeRestart = true)
        } else {
            stopForegroundAndRemove()
            startedSession = false
            stopSelf()
        }
    }

    private fun pauseFromSystem(autoResumeOnFocusGain: Boolean) {
        pauseInternal(
            autoResumeOnFocusGain = autoResumeOnFocusGain,
            abandonAudioFocus = false
        )
    }

    /**
     * Xử lý yêu cầu tạm dừng từ người dùng hoặc hệ thống, đồng thời lưu ý định phát mà không hủy
     * quá trình chuyển chương đang chạy.
     */
    private fun pauseInternal(
        autoResumeOnFocusGain: Boolean,
        abandonAudioFocus: Boolean
    ) {
        val state = _playerState.value
        if (state == TtsPlayerState.Loading) {
            pauseChapterTransition(autoResumeOnFocusGain, abandonAudioFocus)
            return
        }
        if (state !is TtsPlayerState.Playing && state !is TtsPlayerState.Preparing) return

        currentTimelinePositionMs = currentPlaybackPositionMs()
        saveCurrentSnapshot(currentTimelinePositionMs)
        pausedBySystem = autoResumeOnFocusGain
        invalidatePlayback()
        currentEngine.pause()
        playbackStartedAtElapsedRealtimeMs = null
        if (abandonAudioFocus) audioFocusController.abandonFocus()

        val currentChunk = chunks.getOrNull(currentIndex) ?: return
        _playerState.value = TtsPlayerState.Paused(
            bookId = bookId,
            chapterIndex = currentChapterIndex,
            currentChunkIndex = currentIndex,
            totalChunks = chunks.size,
            currentChunk = currentChunk
        )
        publishWidgetState()
        mediaSessionManager.updatePlaybackState(
            isPlaying = false,
            positionMs = currentTimelinePositionMs
        )
        publishBubbleModel()
        showForegroundNotification(
            text = currentChunk.text,
            isPlaying = false
        )
    }

    /**
     * Đánh dấu quá trình tải chương hiện tại là tạm dừng nhưng vẫn cho coroutine hoàn tất, để chương
     * mới ổn định tại vị trí có thể tiếp tục phát.
     */
    private fun pauseChapterTransition(
        autoResumeOnFocusGain: Boolean,
        abandonAudioFocus: Boolean
    ) {
        if (!chapterTransitionPlayWhenReady) return
        chapterTransitionPlayWhenReady = false
        pausedBySystem = autoResumeOnFocusGain
        if (abandonAudioFocus) audioFocusController.abandonFocus()
        publishWidgetState()
        mediaSessionManager.updatePlaybackState(
            isPlaying = false,
            positionMs = currentTimelinePositionMs
        )
        publishBubbleModel()
        showForegroundNotification(
            text = currentNotificationText(),
            isPlaying = false
        )
    }

    /** Tiếp tục phiên phát đang dừng, quá trình tải chương hoặc snapshot phát gần nhất đã lưu. */
    fun resume() {
        if (pausedBySystem) return
        pausedBySystem = false
        when (_playerState.value) {
            TtsPlayerState.Loading -> resumeChapterTransition()
            is TtsPlayerState.Paused,
            is TtsPlayerState.Error -> {
                if (chunks.isNotEmpty()) playCurrentChunk() else restoreSnapshotAndPlay(0)
            }
            TtsPlayerState.Idle,
            is TtsPlayerState.Completed -> restoreSnapshotAndPlay(0)
            else -> Unit
        }
    }

    /**
     * Khôi phục ý định phát trong lúc tải chương và giữ progress trên notification đứng yên cho tới
     * khi giọng đọc thực sự bắt đầu.
     */
    private fun resumeChapterTransition() {
        if (chapterTransitionPlayWhenReady) return
        chapterTransitionPlayWhenReady = true
        mediaSessionManager.updatePlaybackState(
            isPlaying = true,
            positionMs = currentTimelinePositionMs,
            playbackSpeed = 0f
        )
        publishWidgetState()
        publishBubbleModel()
        showForegroundNotification(
            text = currentNotificationText(),
            isPlaying = true
        )
    }

    fun stop() {
        stopSession()
    }

    private fun stopSession() {
        resetSleepTimer()
        if (_playerState.value is TtsPlayerState.Idle && !isRestoringSnapshot) {
            mediaSessionManager.updateStoppedState(currentTimelinePositionMs)
            publishBubbleModel()
            if (bubbleRuntime.isBubbleAvailable()) {
                showIdleBubbleNotification(forceForegroundEpisodeRestart = false)
            } else {
                stopForegroundAndRemove()
                startedSession = false
                stopSelf()
            }
            return
        }

        currentTimelinePositionMs = currentPlaybackPositionMs()
        saveCurrentSnapshot(currentTimelinePositionMs)
        invalidatePlayback()
        chapterPreparation?.cancel()
        currentEngine.stop()
        audioFocusController.abandonFocus()
        chapterPlaybackCoordinator.clear()

        playbackStartedAtElapsedRealtimeMs = null
        pausedBySystem = false
        hasPlaybackStartedInSession = false
        isRestoringSnapshot = false

        _playerState.value = TtsPlayerState.Idle
        publishWidgetState()
        mediaSessionManager.updateStoppedState(currentTimelinePositionMs)
        publishBubbleModel()
        if (bubbleRuntime.isBubbleAvailable()) {
            showIdleBubbleNotification(forceForegroundEpisodeRestart = true)
        } else {
            stopForegroundAndRemove()
            startedSession = false
            stopSelf()
        }
    }

    private fun moveToChunk(index: Int) {
        if (_playerState.value == TtsPlayerState.Loading) return
        if (index !in chunks.indices) {
            if (index >= chunks.size) {
                val expectedGeneration = playbackGeneration
                serviceScope.launch { advanceToNextChapter(expectedGeneration) }
            } else if (index < 0 && currentChapterIndex > 0) {
                val expectedGeneration = playbackGeneration
                serviceScope.launch { advanceToPreviousChapter(expectedGeneration) }
            }
            return
        }
        invalidatePlayback()
        currentEngine.stop()
        currentIndex = index
        playCurrentChunk()
    }

    fun nextChunk() {
        if (_playerState.value == TtsPlayerState.Loading) return
        if (_playerState.value is TtsPlayerState.Idle ||
            _playerState.value is TtsPlayerState.Completed
        ) {
            restoreSnapshotAndPlay(1)
        } else {
            moveToChunk(currentIndex + 1)
        }
    }

    fun previousChunk() {
        if (_playerState.value == TtsPlayerState.Loading) return
        if (_playerState.value is TtsPlayerState.Idle ||
            _playerState.value is TtsPlayerState.Completed
        ) {
            restoreSnapshotAndPlay(-1)
        } else {
            moveToChunk(currentIndex - 1)
        }
    }

    fun seekToChunk(index: Int) = moveToChunk(index)

    private fun restoreSnapshotAndPlay(relativeMove: Int) {
        val targetMove = if (isRestoringSnapshot) {
            (pendingSnapshotMove + relativeMove).coerceIn(
                -MAX_QUEUED_SNAPSHOT_MOVES,
                MAX_QUEUED_SNAPSHOT_MOVES
            )
        } else {
            relativeMove
        }
        restoreSnapshot(relativeMove = targetMove, playWhenReady = true)
    }

    private fun hydrateSnapshotForIdleIfNeeded() {
        val snapshot = playbackSnapshotStore.getSnapshot() ?: run {
            publishBubbleModel()
            return
        }
        if (snapshotRestoreJob?.isActive == true) return
        if (bookId == snapshot.bookId && chunks.isNotEmpty()) {
            publishBubbleModel()
            return
        }
        restoreSnapshot(relativeMove = 0, playWhenReady = false)
    }

    private fun restoreSnapshot(relativeMove: Int, playWhenReady: Boolean) {
        val snapshot = playbackSnapshotStore.getSnapshot() ?: run {
            publishBubbleModel()
            return
        }
        if (playWhenReady) {
            ensureStartedSession()
            bubblePreferencesManager.beginNewPlaybackSession()
            invalidatePlayback()
            currentEngine.stop()
            audioFocusController.abandonFocus()
            hasPlaybackStartedInSession = false
            isRestoringSnapshot = true
            pendingSnapshotMove = relativeMove
            mediaSessionManager.updatePreparingState(snapshot.timelinePositionMs)
            publishBubbleModel()
            showForegroundNotification(
                // Do not surface a loading message while a next/previous request
                // restores the snapshot; the media state already reports buffering.
                text = bookTitle,
                isPlaying = true
            )
        }

        val expectedGeneration = playbackGeneration
        projectionRestoreInProgress = true
        snapshotRestoreJob = serviceScope.launch {
            try {
                val restored = loadRestoredPlayback(
                    snapshot = snapshot,
                    relativeMove = relativeMove,
                    expectedGeneration = expectedGeneration
                )
                if (expectedGeneration != playbackGeneration) return@launch

                applyRestoredPlayback(restored, snapshot, relativeMove)
                projectionRestoreInProgress = false
                isRestoringSnapshot = false
                pendingSnapshotMove = 0
                snapshotRestoreJob = null
                if (playWhenReady) {
                    saveCurrentSnapshot(currentChunkStartPositionMs)
                    playCurrentChunk()
                } else {
                    mediaSessionManager.updateMetadata(
                        bookTitle = bookTitle,
                        author = author,
                        currentSnippet = chunks.getOrNull(currentIndex)?.text.orEmpty(),
                        durationMs = estimatedTotalDurationMs
                    )
                    mediaSessionManager.updateStoppedState(currentTimelinePositionMs)
                    publishWidgetState()
                    publishBubbleModel()
                    if (bubbleRuntime.isBubbleAvailable()) {
                        showIdleBubbleNotification(forceForegroundEpisodeRestart = false)
                    }
                }
            } catch (cancelled: CancellationException) {
                if (expectedGeneration == playbackGeneration) {
                    projectionRestoreInProgress = false
                }
                throw cancelled
            } catch (error: Throwable) {
                if (expectedGeneration != playbackGeneration) return@launch
                projectionRestoreInProgress = false
                isRestoringSnapshot = false
                pendingSnapshotMove = 0
                snapshotRestoreJob = null
                playbackSnapshotStore.clearSnapshot()
                clearRestoredPlaybackData()
                val message = error.message ?: "Không thể khôi phục phiên đọc gần nhất"
                if (playWhenReady) {
                    showPlaybackError(message)
                } else {
                    mediaSessionManager.updateStoppedState()
                    publishBubbleModel()
                    if (bubbleRuntime.isBubbleAvailable()) {
                        showIdleBubbleNotification(forceForegroundEpisodeRestart = false)
                    }
                }
            }
        }
    }

    private suspend fun loadRestoredPlayback(
        snapshot: TtsPlaybackSnapshot,
        relativeMove: Int,
        expectedGeneration: Long
    ): RestoredPlayback {
        val book = bookRepository.getBookById(snapshot.bookId)
            ?: error("Sách của phiên đọc gần nhất không còn trong thư viện")
        ensureRestoreCurrent(expectedGeneration)
        chapterPlaybackCoordinator.prepare(snapshot.bookId, snapshot.preferAiContent)
        ensureRestoreCurrent(expectedGeneration)

        val initialChapter = loadSegmentedChapter(snapshot.chapterIndex, expectedGeneration)
            ?: loadSegmentedChapter(0, expectedGeneration)
            ?: error("Sách không còn chương có thể đọc")
        var chapter: RestoredChapter = if (initialChapter.chunks.isEmpty()) {
            findNonEmptyChapter(
                startChapterIndex = initialChapter.content.chapterIndex,
                expectedGeneration = expectedGeneration
            ) ?: error("Sách không có nội dung để đọc")
        } else {
            initialChapter
        }

        var targetIndex = if (chapter.content.chapterIndex == snapshot.chapterIndex) {
            TtsPlaybackCursorResolver.resolveChunkIndex(
                chunks = chapter.chunks,
                paragraphIndex = snapshot.paragraphIndex,
                sentenceIndex = snapshot.sentenceIndex
            ) ?: 0
        } else {
            0
        }
        targetIndex += relativeMove

        while (targetIndex < 0) {
            val previous = findPreviousNonEmptyChapter(
                startChapterIndex = chapter.content.chapterIndex - 1,
                expectedGeneration = expectedGeneration
            )
            if (previous == null) {
                targetIndex = 0
                break
            }
            chapter = previous
            targetIndex += chapter.chunks.size
        }
        while (targetIndex > chapter.chunks.lastIndex) {
            targetIndex -= chapter.chunks.size
            val next = findNextNonEmptyChapter(
                startChapterIndex = chapter.content.chapterIndex + 1,
                expectedGeneration = expectedGeneration
            )
            if (next == null) {
                targetIndex = chapter.chunks.lastIndex
                break
            }
            chapter = next
        }

        val coverBitmap = withContext(Dispatchers.IO) {
            decodeCoverBitmap(book.coverPath)
        }
        ensureRestoreCurrent(expectedGeneration)
        return RestoredPlayback(
            book = book,
            chapter = chapter,
            currentIndex = targetIndex.coerceIn(0, chapter.chunks.lastIndex),
            coverBitmap = coverBitmap
        )
    }

    private suspend fun loadSegmentedChapter(
        chapterIndex: Int,
        expectedGeneration: Long
    ): RestoredChapter? {
        if (chapterIndex < 0) return null
        val content = chapterPlaybackCoordinator.loadChapter(chapterIndex) ?: return null
        ensureRestoreCurrent(expectedGeneration)
        return RestoredChapter(
            content = content,
            chunks = TtsSentenceSegmenter.segment(content.chunks, activeSettings.language)
        )
    }

    private suspend fun findNonEmptyChapter(
        startChapterIndex: Int,
        expectedGeneration: Long
    ): RestoredChapter? = findNextNonEmptyChapter(
        startChapterIndex = startChapterIndex + 1,
        expectedGeneration = expectedGeneration
    ) ?: findPreviousNonEmptyChapter(
        startChapterIndex = startChapterIndex - 1,
        expectedGeneration = expectedGeneration
    )

    private suspend fun findNextNonEmptyChapter(
        startChapterIndex: Int,
        expectedGeneration: Long
    ): RestoredChapter? {
        var chapterIndex = startChapterIndex.coerceAtLeast(0)
        while (true) {
            val chapter = loadSegmentedChapter(chapterIndex, expectedGeneration) ?: return null
            if (chapter.chunks.isNotEmpty()) return chapter
            chapterIndex++
        }
    }

    private suspend fun findPreviousNonEmptyChapter(
        startChapterIndex: Int,
        expectedGeneration: Long
    ): RestoredChapter? {
        var chapterIndex = startChapterIndex
        while (chapterIndex >= 0) {
            val chapter = loadSegmentedChapter(chapterIndex, expectedGeneration)
            if (chapter != null && chapter.chunks.isNotEmpty()) return chapter
            chapterIndex--
        }
        return null
    }


    private suspend fun loadReadingWidgetProjection(
        snapshot: TtsPlaybackSnapshot,
        relativeMove: Int,
        expectedGeneration: Long
    ): ReadingWidgetProjection {
        val book = bookRepository.getBookById(snapshot.bookId)
            ?: error("Widget book is no longer in the library")
        ensureRestoreCurrent(expectedGeneration)
        chapterPlaybackCoordinator.prepare(snapshot.bookId, snapshot.preferAiContent)
        ensureRestoreCurrent(expectedGeneration)

        val loadedChapters = mutableMapOf<Int, RestoredChapter>()
        val totalChapters = book.totalChapters.coerceAtLeast(1)

        suspend fun getOrLoadChapter(cIndex: Int): RestoredChapter? {
            if (cIndex < 0 || cIndex >= totalChapters) return null
            if (loadedChapters.containsKey(cIndex)) return loadedChapters[cIndex]
            val loaded = loadSegmentedChapter(cIndex, expectedGeneration)
            if (loaded != null) {
                loadedChapters[cIndex] = loaded
            }
            return loaded
        }

        val targetPos = TtsReadingWidgetParagraphNavigator.calculateTargetPosition(
            currentChapterIndex = snapshot.chapterIndex,
            currentParagraphIndex = snapshot.paragraphIndex,
            relativeMove = relativeMove,
            totalChapters = totalChapters,
            getParagraphCount = { cIndex ->
                val ch = getOrLoadChapter(cIndex)
                if (ch != null) totalParagraphCount(ch.chunks) else 0
            }
        )

        var chapter = getOrLoadChapter(targetPos.chapterIndex)
            ?: loadSegmentedChapter(0, expectedGeneration)
            ?: error("Book has no readable chapter")

        if (chapter.chunks.isEmpty()) {
            chapter = findNonEmptyChapter(chapter.content.chapterIndex, expectedGeneration)
                ?: error("Book has no readable content")
        }

        val targetParagraph = targetPos.paragraphIndex
        val currentIndex = chapter.chunks.indexOfFirst { it.paragraphIndex >= targetParagraph }
            .takeIf { it >= 0 }
            ?: chapter.chunks.lastIndex
        val paragraphIndex = chapter.chunks.getOrNull(currentIndex)?.paragraphIndex ?: 0
        return ReadingWidgetProjection(
            book = book,
            chapter = chapter,
            currentIndex = currentIndex.coerceAtLeast(0),
            paragraphIndex = paragraphIndex,
            totalParagraphs = totalParagraphCount(chapter.chunks)
        )
    }
    private fun ensureRestoreCurrent(expectedGeneration: Long) {
        if (expectedGeneration != playbackGeneration) {
            throw CancellationException("Playback restore was superseded")
        }
    }

    private fun applyRestoredPlayback(
        restored: RestoredPlayback,
        snapshot: TtsPlaybackSnapshot,
        relativeMove: Int
    ) {
        bookId = restored.book.id
        bookTitle = restored.book.title
        bookCoverPath = restored.book.coverPath
        author = restored.book.author
        preferAiContent = snapshot.preferAiContent
        currentChapterIndex = restored.chapter.content.chapterIndex
        currentChapterTitle = restored.chapter.content.chapterTitle
        chunks = restored.chapter.chunks
        currentIndex = restored.currentIndex
        currentCoverBitmap = restored.coverBitmap
        activeSettings = preferencesManager.getSettings().normalizedForPlayback()
        applySettingsToEngines(activeSettings)
        rebuildEstimatedTimeline(activeSettings.speed)
        currentTimelinePositionMs = if (
            relativeMove == 0 && currentChapterIndex == snapshot.chapterIndex
        ) {
            snapshot.timelinePositionMs.coerceIn(0L, estimatedTotalDurationMs)
        } else {
            currentChunkStartPositionMs
        }
        playbackStartedAtElapsedRealtimeMs = null
    }

    private fun clearRestoredPlaybackData() {
        chunks = emptyList()
        currentIndex = 0
        currentChapterIndex = 0
        bookId = ""
        bookTitle = getString(R.string.tts_default_book_title)
        author = "EpubPro Reader"
        currentChapterTitle = ""
        bookCoverPath = null
        preferAiContent = false
        currentCoverBitmap = null
        currentTimelinePositionMs = 0L
        currentChunkStartPositionMs = 0L
        estimatedChunkDurationsMs = LongArray(0)
        estimatedChunkStartPositionsMs = LongArray(0)
        estimatedTotalDurationMs = 0L
        chapterPlaybackCoordinator.clear()
    }

    private data class RestoredChapter(
        val content: TtsChapterContent,
        val chunks: List<TtsChunk>
    )

    private data class ReadingWidgetProjection(
        val book: Book,
        val chapter: RestoredChapter,
        val currentIndex: Int,
        val paragraphIndex: Int,
        val totalParagraphs: Int
    )
    private data class RestoredPlayback(
        val book: Book,
        val chapter: RestoredChapter,
        val currentIndex: Int,
        val coverBitmap: Bitmap?
    )

    fun getAvailableVoices(
        isAiVoice: Boolean? = null,
        language: String = "vi"
    ): List<com.epubpro.domain.model.TtsVoice> = when (isAiVoice) {
        true -> vieNeuTtsEngineWrapper.getAvailableVoices(language) +
            piperTtsEngineWrapper.getAvailableVoices(language)
        false -> nativeTtsEngine.getAvailableVoices(language)
        null -> currentEngine.getAvailableVoices(language)
    }

    fun speakPreview(
        sampleText: String = "Xin chào, đây là giọng đọc thử nghiệm của ứng dụng EpubPro.",
        settings: TtsSettings
    ) {
        val normalized = settings.normalizedForPlayback()
        val testEngine = engineFor(normalized)
        testEngine.setLanguage(normalized.language)
        testEngine.setSpeed(normalized.speed)
        testEngine.setPitch(normalized.pitch)
        testEngine.setVoice(normalized.voiceId)
        if (testEngine is VieNeuTtsEngineWrapper) {
            testEngine.setSynthesisConfig(
                steps = normalized.vieNeuSteps,
                cfg = normalized.vieNeuCfg,
                sway = normalized.vieNeuSway,
                threads = normalized.vieNeuThreads
            )
        }
        testEngine.speak(
            chunk = TtsChunk(id = PREVIEW_CHUNK_ID, paragraphIndex = 0, text = sampleText),
            onChunkStart = {},
            onChunkDone = {},
            onError = {}
        )
    }

    fun updateSettings(settings: TtsSettings) {
        val normalized = settings.normalizedForPlayback()
        if (normalized == activeSettings) return

        val previousState = _playerState.value
        val shouldRestoreSnapshot = isRestoringSnapshot
        val shouldRestart =
            previousState is TtsPlayerState.Playing ||
                previousState is TtsPlayerState.Preparing

        invalidatePlayback()
        currentEngine.stop()
        activeSettings = normalized
        applySettingsToEngines(normalized)
        currentEngine = engineFor(normalized)
        rebuildEstimatedTimeline(normalized.speed)

        if (shouldRestoreSnapshot) {
            restoreSnapshotAndPlay(0)
        } else if (shouldRestart && chunks.isNotEmpty()) {
            playCurrentChunk()
        } else if (previousState is TtsPlayerState.Paused && chunks.isNotEmpty()) {
            currentChunkStartPositionMs =
                estimatedChunkStartPositionsMs.getOrElse(currentIndex) { 0L }
            currentTimelinePositionMs = currentChunkStartPositionMs
            val currentChunk = chunks[currentIndex]
            _playerState.value = previousState.copy(
                currentChunk = currentChunk,
                totalChunks = chunks.size
            )
            publishWidgetState()
            mediaSessionManager.updateMetadata(
                bookTitle = bookTitle,
                author = author,
                currentSnippet = currentChunk.text,
                durationMs = estimatedTotalDurationMs
            )
            mediaSessionManager.updatePlaybackState(
                isPlaying = false,
                positionMs = currentTimelinePositionMs
            )
            saveCurrentSnapshot(currentTimelinePositionMs)
            publishBubbleModel()
        }
    }

    fun setSleepTimer(option: SleepTimerOption) {
        sleepTimerOption = option
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        if (option == SleepTimerOption.OFF || option == SleepTimerOption.END_OF_CHAPTER) {
            remainingSleepSeconds = 0
            return
        }

        remainingSleepSeconds = option.minutes.coerceAtLeast(0) * 60
        if (remainingSleepSeconds > 0) {
            sleepTimerJob = serviceScope.launch {
                while (remainingSleepSeconds > 0) {
                    delay(1_000)
                    remainingSleepSeconds--
                }
                stopSession()
            }
        }
    }

    private fun resetSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        remainingSleepSeconds = 0
        sleepTimerOption = SleepTimerOption.OFF
    }

    private fun TtsEngine.prefetchNextChunkIfSupported(
        chunks: List<TtsChunk>,
        currentIndex: Int,
        readerPreferencesManager: ReaderPreferencesManager
    ) {
        val next = chunks.getOrNull(currentIndex + 1) ?: return
        val filterPrefs = readerPreferencesManager.getFilterPreferences()
        val text = if (filterPrefs.isFilterEnabled) {
            ContentSanitizer.sanitize(next.text, filterPrefs)
        } else {
            next.text
        }
        if (text.isNotBlank()) prefetch(next.copy(text = text))
    }
    private fun advancePastCurrentSentence() {
        if (currentIndex < chunks.lastIndex) {
            currentIndex++
            playCurrentChunk()
        } else {
            val expectedGeneration = playbackGeneration
            serviceScope.launch { advanceToNextChapter(expectedGeneration) }
        }
    }

    private suspend fun advanceToNextChapter(expectedGeneration: Long) {
        if (expectedGeneration != playbackGeneration) return
        if (sleepTimerOption == SleepTimerOption.END_OF_CHAPTER) {
            finishPlayback()
            return
        }

        invalidatePlayback()
        val navigationGeneration = playbackGeneration
        currentEngine.stop()
        playbackStartedAtElapsedRealtimeMs = null
        enterChapterTransitionState()

        val preparationResult = chapterPreparation?.await()
            ?: runCatching {
                chapterPlaybackCoordinator.prepare(bookId, preferAiContent)
            }
        if (navigationGeneration != playbackGeneration) return
        if (preparationResult.isFailure) {
            showPlaybackError(
                preparationResult.exceptionOrNull()?.message
                    ?: "Không thể chuẩn bị chương kế tiếp"
            )
            return
        }

        var nextChapterIndex = currentChapterIndex + 1
        while (true) {
            val nextChapterResult = runCatching {
                chapterPlaybackCoordinator.loadChapter(nextChapterIndex)
            }
            if (navigationGeneration != playbackGeneration) return
            val nextChapter = nextChapterResult.getOrElse { error ->
                showPlaybackError(error.message ?: "Không thể tải chương kế tiếp")
                return
            }

            if (nextChapter == null) {
                chapterPlaybackCoordinator.saveBookCompletedProgress()
                if (navigationGeneration != playbackGeneration) return
                finishPlayback()
                return
            }

            val nextChunks = TtsSentenceSegmenter.segment(
                nextChapter.chunks,
                activeSettings.language
            )
            if (nextChunks.isNotEmpty()) {
                chapterPlaybackCoordinator.saveChapterProgress(nextChapter.chapterIndex)
                if (navigationGeneration != playbackGeneration) return
                currentChapterIndex = nextChapter.chapterIndex
                currentChapterTitle = nextChapter.chapterTitle
                chunks = nextChunks
                currentIndex = 0
                rebuildEstimatedTimeline(activeSettings.speed)
                preferencesManager.saveLastTtsChunkIndex(
                    bookId,
                    currentChapterIndex,
                    0
                )
                completeChapterTransition()
                return
            }
            nextChapterIndex++
        }
    }

    private suspend fun advanceToPreviousChapter(expectedGeneration: Long) {
        if (expectedGeneration != playbackGeneration || currentChapterIndex <= 0) return

        val originalIndex = currentIndex
        invalidatePlayback()
        val navigationGeneration = playbackGeneration
        currentEngine.stop()
        playbackStartedAtElapsedRealtimeMs = null
        enterChapterTransitionState()

        val preparationResult = chapterPreparation?.await()
            ?: runCatching {
                chapterPlaybackCoordinator.prepare(bookId, preferAiContent)
            }
        if (navigationGeneration != playbackGeneration) return
        if (preparationResult.isFailure) {
            showPlaybackError(
                preparationResult.exceptionOrNull()?.message
                    ?: "Không thể chuẩn bị chương trước"
            )
            return
        }

        var previousChapterIndex = currentChapterIndex - 1
        while (previousChapterIndex >= 0) {
            val previousChapterResult = runCatching {
                chapterPlaybackCoordinator.loadChapter(previousChapterIndex)
            }
            if (navigationGeneration != playbackGeneration) return
            val previousChapter = previousChapterResult.getOrElse { error ->
                showPlaybackError(error.message ?: "Không thể tải chương trước")
                return
            }

            if (previousChapter == null) {
                previousChapterIndex--
                continue
            }

            val previousChunks = TtsSentenceSegmenter.segment(
                previousChapter.chunks,
                activeSettings.language
            )
            if (previousChunks.isNotEmpty()) {
                chapterPlaybackCoordinator.saveChapterProgress(previousChapter.chapterIndex)
                if (navigationGeneration != playbackGeneration) return
                currentChapterIndex = previousChapter.chapterIndex
                currentChapterTitle = previousChapter.chapterTitle
                chunks = previousChunks
                currentIndex = chunks.lastIndex
                rebuildEstimatedTimeline(activeSettings.speed)
                preferencesManager.saveLastTtsChunkIndex(
                    bookId,
                    currentChapterIndex,
                    chunks[currentIndex].paragraphIndex
                )
                completeChapterTransition()
                return
            }
            previousChapterIndex--
        }

        // No readable previous chapter: resume the current chunk instead of
        // leaving the service stuck in Loading.
        currentIndex = originalIndex
        completeChapterTransition()
    }

    /**
     * Chuyển sang trạng thái kỹ thuật đang tải chương nhưng giữ MediaSession ở trạng thái đang phát
     * với progress đứng yên, để SystemUI giữ nút pause thay vì hiển thị vòng xoay buffering.
     */
    private fun enterChapterTransitionState() {
        chapterTransitionPlayWhenReady = true
        _playerState.value = TtsPlayerState.Loading
        publishWidgetState()
        mediaSessionManager.updatePlaybackState(
            isPlaying = true,
            positionMs = currentTimelinePositionMs,
            playbackSpeed = 0f
        )
        publishBubbleModel()
        showForegroundNotification(
            text = currentNotificationText(),
            isPlaying = isPlaybackRunning()
        )
    }

    /** Hoàn tất tải chương theo ý định phát hoặc tạm dừng gần nhất của người dùng. */
    private fun completeChapterTransition() {
        if (chapterTransitionPlayWhenReady) {
            playCurrentChunk()
        } else {
            settlePausedChapterTransition()
        }
    }

    /**
     * Đưa chương vừa tải về trạng thái tạm dừng tại đoạn đã xác định, sau đó cập nhật đồng bộ
     * snapshot, metadata, progress và notification tương ứng.
     */
    private fun settlePausedChapterTransition() {
        val currentChunk = chunks.getOrNull(currentIndex) ?: run {
            showPlaybackError("Chapter does not contain readable content")
            return
        }
        currentChunkStartPositionMs =
            estimatedChunkStartPositionsMs.getOrElse(currentIndex) { 0L }
        currentTimelinePositionMs = currentChunkStartPositionMs
        playbackStartedAtElapsedRealtimeMs = null
        _playerState.value = TtsPlayerState.Paused(
            bookId = bookId,
            chapterIndex = currentChapterIndex,
            currentChunkIndex = currentIndex,
            totalChunks = chunks.size,
            currentChunk = currentChunk
        )
        saveCurrentSnapshot(currentTimelinePositionMs)
        publishWidgetState()
        mediaSessionManager.updateMetadata(
            bookTitle = bookTitle,
            author = author,
            currentSnippet = currentChunk.text,
            durationMs = estimatedTotalDurationMs
        )
        mediaSessionManager.updatePlaybackState(
            isPlaying = false,
            positionMs = currentTimelinePositionMs
        )
        publishBubbleModel()
        showForegroundNotification(
            text = currentChunk.text,
            isPlaying = false
        )
    }

    private fun finishPlayback() {
        currentTimelinePositionMs = estimatedTotalDurationMs
        saveCurrentSnapshot(currentTimelinePositionMs)
        invalidatePlayback()
        resetSleepTimer()
        currentEngine.stop()
        audioFocusController.abandonFocus()
        chapterPlaybackCoordinator.clear()
        playbackStartedAtElapsedRealtimeMs = null
        hasPlaybackStartedInSession = false
        isRestoringSnapshot = false

        _playerState.value = TtsPlayerState.Completed(
            bookId = bookId,
            chapterIndex = currentChapterIndex
        )
        publishWidgetState()
        mediaSessionManager.updateStoppedState(estimatedTotalDurationMs)
        publishBubbleModel()
        if (bubbleRuntime.isBubbleAvailable()) {
            showIdleBubbleNotification(forceForegroundEpisodeRestart = true)
        } else {
            stopForegroundAndRemove()
            startedSession = false
            stopSelf()
        }
    }

    private fun showPlaybackError(message: String) {
        currentTimelinePositionMs = currentPlaybackPositionMs()
        saveCurrentSnapshot(currentTimelinePositionMs)
        invalidatePlayback()
        currentEngine.stop()
        audioFocusController.abandonFocus()
        pausedBySystem = false
        playbackStartedAtElapsedRealtimeMs = null
        isRestoringSnapshot = false

        _playerState.value = TtsPlayerState.Error(message)
        publishWidgetState()
        mediaSessionManager.updateErrorState(
            positionMs = currentTimelinePositionMs,
            message = message
        )
        publishBubbleModel()
        if (startedSession) {
            showForegroundNotification(
                text = message,
                isPlaying = false
            )
        }
    }

    private fun handleEngineInitializationError(message: String) {
        serviceScope.launch {
            val state = _playerState.value
            if (state is TtsPlayerState.Preparing || state is TtsPlayerState.Playing) {
                showPlaybackError(message)
            }
        }
    }

    private fun applySettingsToEngines(settings: TtsSettings) {
        nativeTtsEngine.setLanguage(settings.language)
        nativeTtsEngine.setSpeed(settings.speed)
        nativeTtsEngine.setPitch(settings.pitch)
        nativeTtsEngine.setVoice(settings.voiceId.takeUnless { settings.isAiVoice })

        val isVieNeuVoice = settings.isAiVoice && TtsSettings.isVieNeuVoiceId(settings.voiceId)
        piperTtsEngineWrapper.setLanguage(settings.language)
        piperTtsEngineWrapper.setSpeed(settings.speed)
        piperTtsEngineWrapper.setPitch(1.0f)
        piperTtsEngineWrapper.setVoice(settings.voiceId.takeIf { settings.isAiVoice && !isVieNeuVoice })

        vieNeuTtsEngineWrapper.setLanguage(settings.language)
        vieNeuTtsEngineWrapper.setSpeed(settings.speed)
        vieNeuTtsEngineWrapper.setPitch(1.0f)
        vieNeuTtsEngineWrapper.setVoice(settings.voiceId.takeIf { isVieNeuVoice })
        vieNeuTtsEngineWrapper.setSynthesisConfig(
            steps = settings.vieNeuSteps,
            cfg = settings.vieNeuCfg,
            sway = settings.vieNeuSway,
            threads = settings.vieNeuThreads
        )
    }

    private fun engineFor(settings: TtsSettings): TtsEngine = when {
        !settings.isAiVoice -> nativeTtsEngine
        TtsSettings.isVieNeuVoiceId(settings.voiceId) -> vieNeuTtsEngineWrapper
        else -> piperTtsEngineWrapper
    }

    private fun ensureStartedSession() {
        if (startedSession) return
        startedSession = true
        ContextCompat.startForegroundService(
            applicationContext,
            Intent(applicationContext, TtsService::class.java).apply {
                action = ACTION_START_SESSION
            }
        )
    }

    private fun handleBubbleInteraction() {
        if (!isIdleTimeoutEligibleState()) return
        idleTimeoutJob?.cancel()
        idleTimeoutJob = null
        idleEpisodeId++
        syncPowerPolicy()
    }

    private fun syncPowerPolicy() {
        if (!::bubbleRuntime.isInitialized || isShuttingDownIdle) return
        val preferences = bubblePreferencesManager.getPreferences()
        val shouldSchedule = TtsPowerPolicy.shouldScheduleIdleTimeout(
            TtsPowerPolicyInput(
                powerMode = preferences.powerMode,
                playbackState = currentPowerPlaybackState(),
                bubbleEnabled = preferences.enabled,
                bubbleAvailable = bubbleRuntime.isBubbleAvailable(),
                appVisible = bubbleRuntime.isAppVisible()
            )
        )
        if (!shouldSchedule) {
            if (idleTimeoutJob != null) idleEpisodeId++
            idleTimeoutJob?.cancel()
            idleTimeoutJob = null
            return
        }
        if (idleTimeoutJob?.isActive == true) return

        val episode = ++idleEpisodeId
        val generation = playbackGeneration
        idleTimeoutJob = serviceScope.launch {
            delay(IDLE_TIMEOUT_MS)
            if (episode != idleEpisodeId || generation != playbackGeneration) return@launch
            val latestPreferences = bubblePreferencesManager.getPreferences()
            val latestInput = TtsPowerPolicyInput(
                powerMode = latestPreferences.powerMode,
                playbackState = currentPowerPlaybackState(),
                bubbleEnabled = latestPreferences.enabled,
                bubbleAvailable = bubbleRuntime.isBubbleAvailable(),
                appVisible = bubbleRuntime.isAppVisible()
            )
            if (episode != idleEpisodeId ||
                !TtsPowerPolicy.shouldShutdownIdleRuntime(latestInput)
            ) {
                syncPowerPolicy()
                return@launch
            }
            shutdownIdleRuntime(episode, generation)
        }
    }

    private fun currentPowerPlaybackState(): TtsPowerPlaybackState = when (_playerState.value) {
        TtsPlayerState.Idle -> TtsPowerPlaybackState.IDLE
        is TtsPlayerState.Completed -> TtsPowerPlaybackState.COMPLETED
        is TtsPlayerState.Paused -> TtsPowerPlaybackState.PAUSED
        else -> TtsPowerPlaybackState.ACTIVE
    }

    private fun isIdleTimeoutEligibleState(): Boolean =
        _playerState.value == TtsPlayerState.Idle ||
            _playerState.value is TtsPlayerState.Completed

    private fun currentSnapshotOrNull(): TtsPlaybackSnapshot? {
        val currentChunk = chunks.getOrNull(currentIndex) ?: return null
        if (bookId.isBlank()) return null
        return TtsPlaybackSnapshot(
            bookId = bookId,
            chapterIndex = currentChapterIndex,
            paragraphIndex = currentChunk.paragraphIndex,
            sentenceIndex = TtsPlaybackCursorResolver.sentenceIndexInParagraph(
                chunks = chunks,
                currentIndex = currentIndex
            ),
            preferAiContent = preferAiContent,
            timelinePositionMs = currentPlaybackPositionMs().coerceAtLeast(0L)
        )
    }

    private suspend fun shutdownIdleRuntime(episode: Long, generation: Long) {
        val finalSnapshot = currentSnapshotOrNull()
        if (finalSnapshot != null) {
            withContext(Dispatchers.IO) {
                playbackSnapshotStore.saveSnapshot(finalSnapshot)
            }
        }
        val preferences = bubblePreferencesManager.getPreferences()
        val input = TtsPowerPolicyInput(
            powerMode = preferences.powerMode,
            playbackState = currentPowerPlaybackState(),
            bubbleEnabled = preferences.enabled,
            bubbleAvailable = bubbleRuntime.isBubbleAvailable(),
            appVisible = bubbleRuntime.isAppVisible()
        )
        if (episode != idleEpisodeId ||
            generation != playbackGeneration ||
            !TtsPowerPolicy.shouldShutdownIdleRuntime(input)
        ) {
            syncPowerPolicy()
            return
        }

        isShuttingDownIdle = true
        idleTimeoutJob = null
        idleEpisodeId++
        invalidatePlayback()
        chapterPreparation?.cancel()
        chapterPreparation = null
        currentEngine.stop()
        audioFocusController.abandonFocus()
        chapterPlaybackCoordinator.clear()
        playbackStartedAtElapsedRealtimeMs = null
        pausedBySystem = false
        hasPlaybackStartedInSession = false
        isRestoringSnapshot = false
        _playerState.value = TtsPlayerState.Idle
        publishWidgetState()
        mediaSessionManager.updateStoppedState(currentTimelinePositionMs)
        publishBubbleModel()
        stopForegroundAndRemove()
        startedSession = false
        isShuttingDownIdle = false
        stopSelf()
    }

    private fun syncBubbleLifecycle() {
        if (!::bubbleRuntime.isInitialized) return
        bubbleRuntime.refreshEnvironment()
        if (bubbleRuntime.isBubbleAvailable()) {
            startedSession = true
            publishBubbleModel()
            when (_playerState.value) {
                TtsPlayerState.Idle,
                is TtsPlayerState.Completed -> {
                    mediaSessionManager.updateStoppedState(currentTimelinePositionMs)
                    showIdleBubbleNotification(forceForegroundEpisodeRestart = false)
                    hydrateSnapshotForIdleIfNeeded()
                }
                else -> showForegroundNotification(
                    text = currentNotificationText(),
                    isPlaying = isPlaybackRunning()
                )
            }
        } else if (isPlaybackSessionActive()) {
            if (startedSession) {
                showForegroundNotification(
                    text = currentNotificationText(),
                    isPlaying = isPlaybackRunning()
                )
            }
        } else {
            stopForegroundAndRemove()
            startedSession = false
            stopSelf()
        }
    }

    private fun showIdleBubbleNotification(forceForegroundEpisodeRestart: Boolean) {
        if (!bubbleRuntime.isBubbleAvailable()) return
        showForegroundNotification(
            text = getString(ReaderR.string.tts_bubble_notification_idle),
            isPlaying = false,
            forceForegroundEpisodeRestart = forceForegroundEpisodeRestart
        )
    }

    /**
     * Chuyển việc hiển thị notification cho [TtsPlaybackNotificationManager] và dùng luồng dừng an
     * toàn hiện có nếu Android từ chối đưa service lên foreground.
     */
    private fun showForegroundNotification(
        text: String,
        isPlaying: Boolean,
        forceForegroundEpisodeRestart: Boolean = false
    ) {
        val updated = playbackNotificationManager.showOrUpdate(
            model = TtsPlaybackNotificationModel(
                bookTitle = bookTitle,
                currentSnippet = text,
                isPlaying = isPlaying,
                openIntent = createOpenBookPendingIntent()
            ),
            serviceTypes = requiredForegroundServiceTypes(),
            forceRestart = forceForegroundEpisodeRestart
        )
        if (updated) {
            startedSession = true
        } else {
            handleForegroundPromotionFailure()
        }
    }

    private fun handleForegroundPromotionFailure() {
        currentTimelinePositionMs = currentPlaybackPositionMs()
        saveCurrentSnapshot(currentTimelinePositionMs)
        invalidatePlayback()
        resetSleepTimer()
        chapterPreparation?.cancel()
        chapterPreparation = null
        snapshotRestoreJob?.cancel()
        snapshotRestoreJob = null
        currentEngine.stop()
        audioFocusController.abandonFocus()
        chapterPlaybackCoordinator.clear()

        playbackStartedAtElapsedRealtimeMs = null
        pausedBySystem = false
        hasPlaybackStartedInSession = false
        isRestoringSnapshot = false
        pendingSnapshotMove = 0
        _playerState.value = TtsPlayerState.Idle
        publishWidgetState()
        mediaSessionManager.updateStoppedState(currentTimelinePositionMs)
        publishBubbleModel()

        stopForegroundAndRemove()
        startedSession = false
        stopSelf()
    }

    private fun requiredForegroundServiceTypes(): Int {
        var serviceTypes = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && requiresMediaPlaybackType()) {
            serviceTypes = serviceTypes or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            bubbleRuntime.isBubbleAvailable()
        ) {
            serviceTypes = serviceTypes or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        }
        return serviceTypes
    }

    private fun requiresMediaPlaybackType(): Boolean {
        val state = _playerState.value
        return isRestoringSnapshot ||
            state == TtsPlayerState.Loading ||
            state is TtsPlayerState.Preparing ||
            state is TtsPlayerState.Playing ||
            state is TtsPlayerState.Paused ||
            (!bubbleRuntime.isBubbleAvailable() && state is TtsPlayerState.Error)
    }

    /** Trả về trạng thái giao diện dùng chung tương ứng với trạng thái phát kỹ thuật mới nhất. */
    private fun currentPlaybackPresentation(): TtsPlaybackPresentation =
        _playerState.value.toPlaybackPresentation(
            isRestoringSnapshot = isRestoringSnapshot,
            loadingPlayWhenReady = chapterTransitionPlayWhenReady
        )

    private fun isPlaybackRunning(): Boolean =
        currentPlaybackPresentation().isPlaybackRunning

    private fun isPlaybackSessionActive(): Boolean = when (_playerState.value) {
        TtsPlayerState.Idle,
        is TtsPlayerState.Completed -> isRestoringSnapshot
        else -> true
    }

    /** Gỡ trạng thái foreground và notification thông qua manager chuyên trách. */
    private fun stopForegroundAndRemove() {
        playbackNotificationManager.remove()
    }

    private fun handleBubbleCommand(command: TtsBubbleCommand) {
        when (command) {
            TtsBubbleCommand.Previous -> previousChunk()
            TtsBubbleCommand.TogglePlayPause -> {
                if (isPlaybackRunning()) pause() else resume()
            }
            TtsBubbleCommand.Next -> nextChunk()
            TtsBubbleCommand.Stop -> stopSession()
            TtsBubbleCommand.OpenBook -> openCurrentBook()
        }
    }

    private fun handleOverlayUnavailable(@Suppress("UNUSED_PARAMETER") error: Throwable) {
        if (isPlaybackSessionActive()) {
            showForegroundNotification(
                text = currentNotificationText(),
                isPlaying = isPlaybackRunning()
            )
        } else {
            stopForegroundAndRemove()
            startedSession = false
            stopSelf()
        }
    }

    private fun publishWidgetState() {
        if (!::widgetStateStore.isInitialized) return
        val presentation = currentPlaybackPresentation()
        val positionMs = currentPlaybackPositionMs()
        val progress = if (estimatedTotalDurationMs > 0L) {
            (positionMs.coerceAtLeast(0L).toFloat() / estimatedTotalDurationMs.toFloat())
                .coerceIn(0f, 1f)
        } else if (chunks.isNotEmpty()) {
            ((currentIndex + 1).toFloat() / chunks.size.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
        val changed = widgetStateStore.saveState(
            TtsWidgetState(
                bookTitle = bookTitle,
                chapterTitle = currentChapterTitle,
                playbackStatus = presentation.widgetStatus,
                progress = progress,
                positionMs = positionMs.coerceAtLeast(0L),
                durationMs = estimatedTotalDurationMs.coerceAtLeast(0L),
                hasSnapshot = playbackSnapshotStore.getSnapshot() != null,
                coverPath = bookCoverPath,
                paragraphIndex = chunks.getOrNull(currentIndex)?.paragraphIndex ?: 0,
                totalParagraphs = totalParagraphCount(chunks),
                paragraphText = buildReadingWidgetText(
                    chunks.getOrNull(currentIndex)?.paragraphIndex ?: 0
                )
            )
        )
        if (changed) {
            sendBroadcast(Intent(TtsWidgetContract.ACTION_STATE_CHANGED).setPackage(packageName))
        }
    }

    private fun buildReadingWidgetText(
        paragraphIndex: Int,
        maxChars: Int = WIDGET_READING_TEXT_MAX_CHARS
    ): String = buildReadingWidgetText(chunks, paragraphIndex, maxChars)

    private fun buildReadingWidgetText(
        chunks: List<TtsChunk>,
        paragraphIndex: Int,
        maxChars: Int = WIDGET_READING_TEXT_MAX_CHARS
    ): String {
        if (chunks.isEmpty()) return ""
        val text = chunks
            .filter { it.paragraphIndex == paragraphIndex }
            .joinToString(" ") { it.text.trim() }
            .trim()
        if (text.isNotBlank()) return text.take(maxChars)

        return chunks
            .dropWhile { it.paragraphIndex < paragraphIndex }
            .joinToString(" ") { it.text.trim() }
            .trim()
            .take(maxChars)
    }

    private fun totalParagraphCount(chunks: List<TtsChunk>): Int =
        chunks.maxOfOrNull { it.paragraphIndex + 1 }?.coerceAtLeast(1) ?: 0

    private fun lastParagraphIndex(chunks: List<TtsChunk>): Int =
        chunks.maxOfOrNull { it.paragraphIndex } ?: 0

    private fun publishBubbleModel() {
        if (!::bubbleRuntime.isInitialized) return
        val presentation = currentPlaybackPresentation()
        val state = _playerState.value
        val positionMs = currentPlaybackPositionMs()
        val progress = if (estimatedTotalDurationMs > 0L) {
            positionMs.toFloat() / estimatedTotalDurationMs.toFloat()
        } else {
            0f
        }
        bubbleRuntime.updateModel(
            TtsBubbleUiModel(
                playbackStatus = presentation.bubbleStatus,
                bookTitle = bookTitle,
                currentText = chunks.getOrNull(currentIndex)?.text.orEmpty(),
                progress = progress,
                coverBitmap = currentCoverBitmap,
                hasPlaybackSnapshot = playbackSnapshotStore.getSnapshot() != null,
                canOpenBook = true,
                errorMessage = (state as? TtsPlayerState.Error)?.message
            )
        )
    }

    private fun currentNotificationText(): String = when (val state = _playerState.value) {
        is TtsPlayerState.Preparing -> state.currentChunk.text
        is TtsPlayerState.Playing -> state.currentChunk.text
        is TtsPlayerState.Paused -> state.currentChunk.text
        is TtsPlayerState.Error -> state.message
        TtsPlayerState.Loading -> chunks.getOrNull(currentIndex)?.text.orEmpty()
        TtsPlayerState.Idle,
        is TtsPlayerState.Completed -> getString(ReaderR.string.tts_bubble_notification_idle)
    }

    private fun saveCurrentSnapshot(positionMs: Long = currentPlaybackPositionMs()) {
        val currentChunk = chunks.getOrNull(currentIndex) ?: return
        if (bookId.isBlank()) return
        playbackSnapshotStore.saveSnapshot(
            TtsPlaybackSnapshot(
                bookId = bookId,
                chapterIndex = currentChapterIndex,
                paragraphIndex = currentChunk.paragraphIndex,
                sentenceIndex = TtsPlaybackCursorResolver.sentenceIndexInParagraph(
                    chunks = chunks,
                    currentIndex = currentIndex
                ),
                preferAiContent = preferAiContent,
                timelinePositionMs = positionMs.coerceAtLeast(0L)
            )
        )
        publishBubbleModel()
    }

    private fun refreshBookVisuals(expectedBookId: String) {
        serviceScope.launch {
            val book = runCatching { bookRepository.getBookById(expectedBookId) }
                .getOrNull()
                ?: return@launch
            val cover = withContext(Dispatchers.IO) { decodeCoverBitmap(book.coverPath) }
            if (bookId != expectedBookId) return@launch
            bookTitle = book.title
            bookCoverPath = book.coverPath
            author = book.author
            currentCoverBitmap = cover
            publishBubbleModel()
            publishWidgetState()
        }
    }

    private fun decodeCoverBitmap(coverPath: String?): Bitmap? = runCatching {
        val path = coverPath?.takeIf { it.isNotBlank() } ?: return@runCatching null
        val file = File(path)
        if (!file.isFile) return@runCatching null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

        var sampleSize = 1
        while (bounds.outWidth / sampleSize > MAX_COVER_SIZE_PX ||
            bounds.outHeight / sampleSize > MAX_COVER_SIZE_PX
        ) {
            sampleSize *= 2
        }
        BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sampleSize }
        )
    }.getOrNull()

    private fun createOpenBookPendingIntent(): PendingIntent? {
        val intent = createOpenBookIntent() ?: return null
        return PendingIntent.getActivity(
            this,
            OPEN_BOOK_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createOpenBookIntent(): Intent? {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val snapshot = playbackSnapshotStore.getSnapshot()
        val targetBookId = bookId.ifBlank { snapshot?.bookId.orEmpty() }
        val targetChapterIndex = if (bookId.isNotBlank()) {
            currentChapterIndex
        } else {
            snapshot?.chapterIndex ?: TtsOpenBookContract.NO_CHAPTER_OVERRIDE
        }
        if (targetBookId.isNotBlank() && targetChapterIndex >= 0) {
            TtsOpenBookContract.configureIntent(
                intent = intent,
                bookId = targetBookId,
                chapterIndex = targetChapterIndex
            )
        }
        return intent
    }

    private fun openCurrentBook() {
        createOpenBookIntent()
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?.let { startActivity(it) }
    }

    private fun isCurrentPlayback(
        playbackId: Long,
        expectedChapterIndex: Int,
        expectedIndex: Int,
        callbackChunkId: Int,
        expectedChunkId: Int
    ): Boolean =
        playbackId == playbackGeneration &&
            currentChapterIndex == expectedChapterIndex &&
            currentIndex == expectedIndex &&
            callbackChunkId == expectedChunkId

    private fun invalidatePlayback() {
        playbackGeneration++
        notificationProgressJob?.cancel()
        notificationProgressJob = null
        snapshotRestoreJob?.cancel()
        snapshotRestoreJob = null
        projectionRestoreInProgress = false
        isRestoringSnapshot = false
        pendingSnapshotMove = 0
    }

    private fun ensureEstimatedTimeline(speed: Float) {
        val normalizedSpeed = speed.coerceAtLeast(MIN_TIMELINE_SPEED)
        if (estimatedChunkDurationsMs.size != chunks.size ||
            estimatedTimelineSpeed != normalizedSpeed
        ) {
            rebuildEstimatedTimeline(normalizedSpeed)
        }
    }

    private fun rebuildEstimatedTimeline(speed: Float) {
        val normalizedSpeed = speed.coerceAtLeast(MIN_TIMELINE_SPEED)
        var accumulatedMs = 0L
        estimatedChunkStartPositionsMs = LongArray(chunks.size)
        estimatedChunkDurationsMs = LongArray(chunks.size) { index ->
            estimatedChunkStartPositionsMs[index] = accumulatedMs
            estimateChunkDurationMs(chunks[index].text, normalizedSpeed).also {
                accumulatedMs += it
            }
        }
        estimatedTotalDurationMs = accumulatedMs
        estimatedTimelineSpeed = normalizedSpeed
        currentChunkStartPositionMs =
            estimatedChunkStartPositionsMs.getOrElse(currentIndex) { 0L }
        currentTimelinePositionMs = currentChunkStartPositionMs
    }

    private fun estimateChunkDurationMs(text: String, speed: Float): Long =
        ((text.length / ESTIMATED_CHARACTERS_PER_SECOND / speed) * 1_000f)
            .toLong()
            .coerceAtLeast(MIN_CHUNK_DURATION_MS)

    private fun currentPlaybackPositionMs(): Long {
        val startedAt = playbackStartedAtElapsedRealtimeMs
            ?: return currentTimelinePositionMs.coerceIn(
                0L,
                estimatedTotalDurationMs.coerceAtLeast(0L)
            )
        val elapsedInChunkMs =
            (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)
        val currentChunkDurationMs =
            estimatedChunkDurationsMs.getOrElse(currentIndex) { 0L }
        return (currentChunkStartPositionMs +
            elapsedInChunkMs.coerceAtMost(currentChunkDurationMs))
            .coerceIn(0L, estimatedTotalDurationMs.coerceAtLeast(0L))
    }

    /**
     * Hiển thị lần chuẩn bị đầu tiên là buffering; với phiên đã phát, giữ trạng thái đang phát và
     * đóng băng tại vị trí được cung cấp trong lúc chuẩn bị đoạn tiếp theo.
     */
    private fun updateMediaSessionForPreparation(positionMs: Long) {
        if (TtsMediaPlaybackContinuityPolicy.shouldShowBuffering(hasPlaybackStartedInSession)) {
            mediaSessionManager.updatePreparingState(positionMs)
        } else {
            mediaSessionManager.updatePlaybackState(
                isPlaying = true,
                positionMs = positionMs,
                playbackSpeed = 0f
            )
        }
    }

    private fun bubbleUpdateIntervalMs(): Long =
        if (bubbleRuntime.isExpanded()) {
            BUBBLE_EXPANDED_UPDATE_INTERVAL_MS
        } else {
            BUBBLE_COLLAPSED_UPDATE_INTERVAL_MS
        }

    /**
     * Cập nhật vị trí MediaSession mỗi giây, đồng thời giới hạn tần suất cập nhật widget và bubble
     * một cách độc lập; notification không được tạo lại theo từng nhịp progress.
     */
    private fun startNotificationProgressUpdates(
        playbackId: Long,
        expectedChapterIndex: Int,
        expectedIndex: Int
    ) {
        notificationProgressJob?.cancel()
        notificationProgressJob = serviceScope.launch {
            var lastWidgetSec = -1L
            var nextBubbleUpdateAtElapsedRealtimeMs = 0L
            while (playbackId == playbackGeneration &&
                currentChapterIndex == expectedChapterIndex &&
                currentIndex == expectedIndex &&
                _playerState.value is TtsPlayerState.Playing
            ) {
                delay(NOTIFICATION_PROGRESS_UPDATE_INTERVAL_MS)
                if (playbackId != playbackGeneration ||
                    currentChapterIndex != expectedChapterIndex ||
                    currentIndex != expectedIndex ||
                    _playerState.value !is TtsPlayerState.Playing
                ) {
                    break
                }

                currentTimelinePositionMs = currentPlaybackPositionMs()
                mediaSessionManager.updatePlaybackState(
                    isPlaying = true,
                    positionMs = currentTimelinePositionMs,
                    playbackSpeed = 1.0f
                )
                val playingState = _playerState.value as? TtsPlayerState.Playing
                if (playingState != null) {
                    _playerState.value = playingState.copy(
                        progressMs = currentTimelinePositionMs,
                        totalMs = estimatedTotalDurationMs
                    )
                    val now = SystemClock.elapsedRealtime()
                    if (!bubbleRuntime.isAppVisible() &&
                        now >= nextBubbleUpdateAtElapsedRealtimeMs
                    ) {
                        publishBubbleModel()
                        nextBubbleUpdateAtElapsedRealtimeMs = now + bubbleUpdateIntervalMs()
                    }
                }

                val currentSec = currentTimelinePositionMs / 5000L
                if (currentSec != lastWidgetSec) {
                    lastWidgetSec = currentSec
                    publishWidgetState()
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        sleepTimerJob?.cancel()
        idleTimeoutJob?.cancel()
        chapterPreparation?.cancel()
        snapshotRestoreJob?.cancel()
        readingWidgetMoveJob?.cancel()
        readingWidgetMoveQueue.clear()
        audioFocusController.abandonFocus()
        nativeTtsEngine.shutdown()
        piperTtsEngineWrapper.shutdown()
        vieNeuTtsEngineWrapper.shutdown()
        if (::bubbleRuntime.isInitialized) bubbleRuntime.close()
        serviceJob.cancel()
        mediaSessionManager.release()
        currentCoverBitmap = null
        playbackNotificationManager.resetState()
        startedSession = false
        _playerState.value = TtsPlayerState.Idle
        super.onDestroy()
    }

    inner class TtsBinder : Binder() {
        fun getService(): TtsService = this@TtsService
    }

    companion object {
        const val ACTION_WIDGET_PLAY_PAUSE = "com.epubpro.tts.ACTION_WIDGET_PLAY_PAUSE"
        const val ACTION_WIDGET_PREVIOUS = "com.epubpro.tts.ACTION_WIDGET_PREVIOUS"
        const val ACTION_WIDGET_NEXT = "com.epubpro.tts.ACTION_WIDGET_NEXT"
        const val ACTION_WIDGET_READING_PREVIOUS = "com.epubpro.tts.ACTION_WIDGET_READING_PREVIOUS"
        const val ACTION_WIDGET_READING_NEXT = "com.epubpro.tts.ACTION_WIDGET_READING_NEXT"
        private const val ACTION_START_SESSION = "com.epubpro.tts.ACTION_START_SESSION"
        private const val ACTION_SYNC_BUBBLE = "com.epubpro.tts.ACTION_SYNC_BUBBLE"
        private const val PREVIEW_CHUNK_ID = 9999
        private const val OPEN_BOOK_REQUEST_CODE = 2002
        private const val MAX_COVER_SIZE_PX = 128
        private const val MAX_QUEUED_SNAPSHOT_MOVES = 100
        private const val WIDGET_READING_TEXT_MAX_CHARS = 800
        private const val ESTIMATED_CHARACTERS_PER_SECOND = 15f
        private const val MIN_TIMELINE_SPEED = 0.5f
        private const val MIN_CHUNK_DURATION_MS = 2_000L
        private const val NOTIFICATION_PROGRESS_UPDATE_INTERVAL_MS = 1_000L
        private const val IDLE_TIMEOUT_MS = 5 * 60 * 1_000L

        private val _playerState = MutableStateFlow<TtsPlayerState>(TtsPlayerState.Idle)
        val playerState: StateFlow<TtsPlayerState> = _playerState.asStateFlow()

        @Volatile
        private var projectionRestoreInProgress: Boolean = false

        fun isPlaybackProjectionOwned(): Boolean =
            projectionRestoreInProgress || _playerState.value !is TtsPlayerState.Idle

        fun syncBubbleState(context: Context, enabled: Boolean) {
            val appContext = context.applicationContext
            val intent = Intent(appContext, TtsService::class.java).apply {
                action = ACTION_SYNC_BUBBLE
            }
            if (enabled) {
                ContextCompat.startForegroundService(appContext, intent)
            } else {
                runCatching { appContext.startService(intent) }
            }
        }
    }
}
        private const val BUBBLE_EXPANDED_UPDATE_INTERVAL_MS = 1_000L
        private const val BUBBLE_COLLAPSED_UPDATE_INTERVAL_MS = 5_000L
