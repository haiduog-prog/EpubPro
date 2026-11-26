package com.epubpro.core.reader.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.epubpro.core.designsystem.R
import com.epubpro.domain.model.TtsChunk
import com.epubpro.domain.model.TtsVoice
import java.util.Locale

class AndroidNativeTtsEngine(
    private val context: Context
) : TtsEngine, TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private var initFailed = false
    private var onReadyCallback: (() -> Unit)? = null
    private var onErrorCallback: ((String) -> Unit)? = null

    private var currentOnChunkStart: ((Int) -> Unit)? = null
    private var currentOnChunkDone: ((Int) -> Unit)? = null
    private var currentOnChunkError: ((String) -> Unit)? = null

    private var pendingLanguage: String = "vi"
    private var pendingSpeed: Float = 1.0f
    private var pendingPitch: Float = 1.0f
    private var pendingVoiceId: String? = null
    private var pendingSpeech: PendingSpeech? = null

    private data class PendingSpeech(
        val chunk: TtsChunk,
        val onChunkStart: (Int) -> Unit,
        val onChunkDone: (Int) -> Unit,
        val onError: (String) -> Unit
    )

    override fun initialize(onReady: () -> Unit, onError: (String) -> Unit) {
        onReadyCallback = onReady
        onErrorCallback = onError
        if (tts == null) {
            initFailed = false
            tts = TextToSpeech(context.applicationContext, this)
            if (initFailed) {
                tts?.shutdown()
                tts = null
            }
        } else if (isInitialized) {
            onReady()
        }
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            initFailed = true
            isInitialized = false
            pendingSpeech = null
            tts?.shutdown()
            tts = null
            onErrorCallback?.invoke(context.getString(R.string.tts_error_native_init_failed, status))
            return
        }
        initFailed = false
        val activeTts = tts ?: return

        activeTts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                val chunkId = utteranceId?.toIntOrNull() ?: return
                currentOnChunkStart?.invoke(chunkId)
            }

            override fun onDone(utteranceId: String?) {
                val chunkId = utteranceId?.toIntOrNull() ?: return
                currentOnChunkDone?.invoke(chunkId)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                currentOnChunkError?.invoke(context.getString(R.string.tts_error_native_speak_failed))
            }
        })

        isInitialized = true
        setLanguage(pendingLanguage)
        setSpeed(pendingSpeed)
        setPitch(pendingPitch)
        setVoice(pendingVoiceId)

        onReadyCallback?.invoke()
        pendingSpeech?.also { speech ->
            pendingSpeech = null
            speak(speech.chunk, speech.onChunkStart, speech.onChunkDone, speech.onError)
        }
    }

    override fun speak(
        chunk: TtsChunk,
        onChunkStart: (Int) -> Unit,
        onChunkDone: (Int) -> Unit,
        onError: (String) -> Unit
    ) {
        if (!isInitialized || tts == null) {
            pendingSpeech = PendingSpeech(chunk, onChunkStart, onChunkDone, onError)
            initialize(
                onReady = {},
                onError = onError
            )
            return
        }
        currentOnChunkStart = onChunkStart
        currentOnChunkDone = onChunkDone
        currentOnChunkError = onError

        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, chunk.id.toString())
        }
        val result = tts?.speak(
            chunk.text,
            TextToSpeech.QUEUE_FLUSH,
            params,
            chunk.id.toString()
        ) ?: TextToSpeech.ERROR
        if (result == TextToSpeech.ERROR) {
            currentOnChunkError?.invoke(context.getString(R.string.tts_error_native_speak_rejected))
        }
    }

    override fun pause() {
        tts?.stop()
        pendingSpeech = null
    }

    override fun resume() = Unit

    override fun stop() {
        tts?.stop()
        pendingSpeech = null
    }

    override fun setLanguage(language: String) {
        pendingLanguage = language.lowercase(Locale.ROOT).takeIf { it == "en" } ?: "vi"
        if (!isInitialized) return

        val result = tts?.setLanguage(localeFor(pendingLanguage))
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts?.language = Locale.getDefault()
        }
    }

    override fun setSpeed(speed: Float) {
        pendingSpeed = speed
        if (isInitialized) tts?.setSpeechRate(speed)
    }

    override fun setPitch(pitch: Float) {
        pendingPitch = pitch
        if (isInitialized) tts?.setPitch(pitch)
    }

    override fun setVoice(voiceId: String?) {
        pendingVoiceId = voiceId?.takeIf { it.isNotBlank() }
        if (!isInitialized) return

        val selectedVoice = pendingVoiceId?.let { id -> tts?.voices?.firstOrNull { it.name == id } }
        if (selectedVoice != null) {
            tts?.voice = selectedVoice
        } else {
            setLanguage(pendingLanguage)
        }
    }

    override fun getAvailableVoices(language: String): List<TtsVoice> {
        if (!isInitialized || tts == null) return emptyList()
        val targetLanguage = localeFor(language).language
        return tts?.voices.orEmpty()
            .filter { it.locale.language == targetLanguage }
            .distinctBy { it.name }
            .sortedBy { it.name }
            .map { voice ->
                TtsVoice(
                    id = voice.name,
                    name = voice.name
                        .replace(voice.locale.language, "")
                        .replace("-", " ")
                        .trim()
                        .ifEmpty { voice.name },
                    language = voice.locale.language,
                    isNetworkRequired = voice.isNetworkConnectionRequired
                )
            }
    }

    override fun shutdown() {
        tts?.stop()
        pendingSpeech = null
        onReadyCallback = null
        onErrorCallback = null
        currentOnChunkStart = null
        currentOnChunkDone = null
        currentOnChunkError = null
        tts?.shutdown()
        tts = null
        isInitialized = false
        initFailed = false
    }

    private fun localeFor(language: String): Locale =
        if (language.equals("en", ignoreCase = true)) Locale.ENGLISH else Locale("vi", "VN")
}
