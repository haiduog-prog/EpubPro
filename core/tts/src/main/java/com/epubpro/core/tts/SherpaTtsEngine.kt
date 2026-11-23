package com.epubpro.core.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject
import kotlin.concurrent.withLock
import kotlin.coroutines.coroutineContext

open class SherpaTtsEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {
    @Volatile
    private var tts: OfflineTts? = null

    @Volatile
    private var audioTrack: AudioTrack? = null

    @Volatile
    private var currentSampleRate: Int = 22050

    private val synthesisMutex = Mutex()
    private val ttsLock = ReentrantLock()
    private val audioWriteLock = Any()
    private val playbackEpoch = AtomicLong(0L)

    companion object {
        private const val TAG = "EpubProTTS"
    }

    /**
     * Khởi tạo engine với file model từ bộ nhớ thiết bị.
     */
    open suspend fun initialize(
        onnxPath: String,
        tokensPath: String = "",
        lexiconPath: String = "",
        dataDirPath: String = ""
    ): Unit = withContext(Dispatchers.IO) {
        Log.d(TAG, "initialize() onnx=$onnxPath dataDirPath=$dataDirPath")
        release()

        val onnxFile = File(onnxPath)
        val tokensFile = File(tokensPath)

        if (!onnxFile.exists() || onnxFile.length() < 1_000_000L) {
            throw FileNotFoundException("ONNX file invalid (${onnxFile.length()} bytes): $onnxPath")
        }
        if (!tokensFile.exists() || tokensFile.length() == 0L) {
            throw FileNotFoundException("tokens.txt missing: $tokensPath")
        }

        try {
            val vitsConfig = OfflineTtsVitsModelConfig()
            vitsConfig.model = onnxPath
            vitsConfig.tokens = tokensPath
            vitsConfig.dataDir = dataDirPath
            vitsConfig.lexicon = if (dataDirPath.isEmpty() && lexiconPath.isNotEmpty()) lexiconPath else ""
            vitsConfig.dictDir = ""
            vitsConfig.noiseScale = 0.667f
            vitsConfig.noiseScaleW = 0.8f
            vitsConfig.lengthScale = 1.0f

            val modelConfig = OfflineTtsModelConfig()
            modelConfig.vits = vitsConfig
            modelConfig.numThreads = 2
            modelConfig.debug = false

            val ttsConfig = OfflineTtsConfig()
            ttsConfig.model = modelConfig
            ttsConfig.ruleFsts = ""
            ttsConfig.ruleFars = ""
            ttsConfig.maxNumSentences = 1

            Log.d(TAG, "Creating OfflineTts with dataDir='$dataDirPath'")
            val createdSampleRate = ttsLock.withLock {
                val createdTts = OfflineTts(assetManager = null, config = ttsConfig)
                tts = createdTts
                try {
                    createdTts.sampleRate()
                } catch (t: Throwable) {
                    Log.e(TAG, "Error getting sampleRate", t)
                    22050
                }
            }
            currentSampleRate = createdSampleRate
            Log.d(TAG, "OfflineTts created OK. sampleRate=$currentSampleRate Hz")

            if (currentSampleRate <= 0) {
                throw IllegalStateException("Sherpa TTS sampleRate không hợp lệ: $currentSampleRate")
            }

            val minBuf = AudioTrack.getMinBufferSize(
                currentSampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(4096)

            synchronized(audioWriteLock) {
                audioTrack = AudioTrack(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                    AudioFormat.Builder()
                        .setSampleRate(currentSampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                    minBuf,
                    AudioTrack.MODE_STREAM,
                    AudioManager.AUDIO_SESSION_ID_GENERATE
                )
                audioTrack?.play()
            }
            Log.d(TAG, "AudioTrack ready, minBuf=$minBuf")
            Unit
        } catch (t: Throwable) {
            Log.e(TAG, "Fatal error in initialize()", t)
            release()
            throw t
        }
    }

    open suspend fun synthesize(
        text: String,
        speed: Float = 1.0f
    ): ByteArray = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()

        val audio = try {
            synthesisMutex.withLock {
                coroutineContext.ensureActive()
                ttsLock.withLock {
                    val currentTts = tts
                        ?: throw IllegalStateException("Sherpa TTS chưa được khởi tạo")
                    currentTts.generate(text, sid = 0, speed = speed)
                }
            }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            if (t is IllegalStateException && tts == null) throw t
            Log.e(TAG, "OfflineTts.generate() threw", t)
            null
        }

        coroutineContext.ensureActive()
        val samples = audio?.samples
        if (samples == null || samples.isEmpty()) {
            throw IllegalStateException("Sherpa không tạo được dữ liệu âm thanh")
        }

        val pcm = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val sample = (samples[i].coerceIn(-1f, 1f) * 32767).toInt().toShort()
            pcm[i * 2] = (sample.toInt() and 0xFF).toByte()
            pcm[i * 2 + 1] = ((sample.toInt() shr 8) and 0xFF).toByte()
        }
        pcm
    }

    open suspend fun speak(
        text: String,
        speed: Float = 1.0f,
        onAudioStarted: () -> Unit = {}
    ) {
        val pcm = synthesize(text, speed)
        playPcm(pcm, onAudioStarted)
    }

    open suspend fun playPcm(
        pcm: ByteArray,
        onAudioStarted: () -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        val expectedEpoch = playbackEpoch.get()
        synchronized(audioWriteLock) {
            val track = audioTrack
                ?: throw IllegalStateException("AudioTrack chưa được khởi tạo")
            try {
                if (track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play()
            } catch (t: Throwable) {
                Log.e(TAG, "Error playing AudioTrack", t)
                throw IllegalStateException("Không thể bắt đầu AudioTrack", t)
            }
        }

        coroutineContext.ensureActive()
        if (playbackEpoch.get() != expectedEpoch) return@withContext
        onAudioStarted()

        val chunkSize = 4096
        var offset = 0
        while (offset < pcm.size && coroutineContext.isActive && playbackEpoch.get() == expectedEpoch) {
            val length = (pcm.size - offset).coerceAtMost(chunkSize)
            val written = synchronized(audioWriteLock) {
                if (playbackEpoch.get() != expectedEpoch || !coroutineContext.isActive) return@withContext
                val track = audioTrack ?: return@withContext
                try {
                    track.write(pcm, offset, length)
                } catch (error: IllegalStateException) {
                    if (playbackEpoch.get() != expectedEpoch || !coroutineContext.isActive) {
                        return@withContext
                    }
                    throw error
                }
            }
            if (written <= 0) {
                if (!coroutineContext.isActive || playbackEpoch.get() != expectedEpoch) {
                    return@withContext
                }
                throw IllegalStateException("AudioTrack không thể ghi PCM: $written")
            }
            offset += written
        }
    }

    /**
     * Dừng phát âm thanh ngay lập tức và xả buffer AudioTrack.
     */
    open fun stop() {
        Log.d(TAG, "stop() called")
        playbackEpoch.incrementAndGet()
        val track = audioTrack
        try {
            track?.pause()
            track?.flush()
        } catch (t: Throwable) {
            Log.e(TAG, "stop audioTrack error", t)
        }
    }

    open fun release() {
        Log.d(TAG, "release()")
        stop()
        synchronized(audioWriteLock) {
            try {
                audioTrack?.release()
            } catch (t: Throwable) {
                Log.e(TAG, "release audioTrack", t)
            }
            audioTrack = null
        }
        ttsLock.withLock {
            try {
                tts?.release()
            } catch (t: Throwable) {
                Log.e(TAG, "release tts", t)
            }
            tts = null
        }
    }
}
