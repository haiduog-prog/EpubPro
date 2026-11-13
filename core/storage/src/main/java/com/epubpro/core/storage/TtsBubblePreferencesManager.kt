package com.epubpro.core.storage

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

enum class TtsBubbleSide {
    LEFT,
    RIGHT
}

enum class TtsBubblePowerMode {
    ALWAYS_ON,
    BATTERY_SAVER
}

data class TtsBubblePreferences(
    val enabled: Boolean = false,
    val pendingEnable: Boolean = false,
    val side: TtsBubbleSide = TtsBubbleSide.RIGHT,
    val normalizedY: Float = DEFAULT_BUBBLE_NORMALIZED_Y,
    val hiddenForCurrentSession: Boolean = false,
    val powerMode: TtsBubblePowerMode = TtsBubblePowerMode.ALWAYS_ON
)

internal const val DEFAULT_BUBBLE_NORMALIZED_Y = 0.5f

internal fun TtsBubblePreferences.normalized(): TtsBubblePreferences = copy(
    normalizedY = normalizedY.takeIf(Float::isFinite)
        ?.coerceIn(0f, 1f)
        ?: DEFAULT_BUBBLE_NORMALIZED_Y
)

/**
 * Persists the user-controlled TTS bubble state in private app storage.
 *
 * All fields are encoded into one SharedPreferences value and synchronously committed. This keeps
 * a position or enable-state update logically atomic instead of exposing a partially updated group
 * of independent preference keys after a process interruption.
 */
@Singleton
open class TtsBubblePreferencesManager @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    private val _preferencesFlow = MutableStateFlow(readPreferences())
    open val preferencesFlow: StateFlow<TtsBubblePreferences> = _preferencesFlow.asStateFlow()

    open fun getPreferences(): TtsBubblePreferences = _preferencesFlow.value

    @Synchronized
    open fun updatePreferences(
        transform: (TtsBubblePreferences) -> TtsBubblePreferences
    ): Boolean = savePreferences(transform(_preferencesFlow.value))

    @Synchronized
    open fun savePreferences(preferences: TtsBubblePreferences): Boolean {
        val normalized = preferences.normalized()
        if (normalized == _preferencesFlow.value) return true

        val committed = prefs.edit()
            .putString(KEY_PREFERENCES, TtsBubblePreferencesCodec.encode(normalized))
            .commit()
        if (committed) {
            _preferencesFlow.value = normalized
        }
        return committed
    }

    open fun setEnabled(enabled: Boolean): Boolean = updatePreferences {
        it.copy(
            enabled = enabled,
            pendingEnable = false,
            hiddenForCurrentSession = false
        )
    }

    open fun setPendingEnable(pending: Boolean): Boolean = updatePreferences {
        it.copy(pendingEnable = pending)
    }

    fun savePosition(side: TtsBubbleSide, normalizedY: Float): Boolean = updatePreferences {
        it.copy(side = side, normalizedY = normalizedY)
    }

    open fun setPowerMode(mode: TtsBubblePowerMode): Boolean = updatePreferences { it.copy(powerMode = mode) }

    fun setHiddenForCurrentSession(hidden: Boolean): Boolean = updatePreferences {
        it.copy(hiddenForCurrentSession = hidden)
    }

    fun beginNewPlaybackSession(): Boolean = setHiddenForCurrentSession(false)

    private fun readPreferences(): TtsBubblePreferences {
        val encoded = prefs.getString(KEY_PREFERENCES, null) ?: return TtsBubblePreferences()
        return TtsBubblePreferencesCodec.decode(encoded) ?: TtsBubblePreferences()
    }

    private companion object {
        const val PREFERENCES_NAME = "tts_bubble_preferences"
        const val KEY_PREFERENCES = "bubble_preferences_v1"
    }
}

internal object TtsBubblePreferencesCodec {
    private const val SCHEMA_VERSION = "1"
    private const val FIELD_SEPARATOR = '|'

    fun encode(preferences: TtsBubblePreferences): String {
        val value = preferences.normalized()
        return listOf(
            SCHEMA_VERSION,
            value.enabled.encoded(),
            value.pendingEnable.encoded(),
            value.side.name,
            value.normalizedY.toString(),
            value.hiddenForCurrentSession.encoded(),
            value.powerMode.name
        ).joinToString(FIELD_SEPARATOR.toString())
    }

    fun decode(encoded: String): TtsBubblePreferences? {
        val fields = encoded.split(FIELD_SEPARATOR)
        if (fields.size < 6 || fields[0] != SCHEMA_VERSION) return null

        val enabled = fields[1].decodedBoolean() ?: return null
        val pendingEnable = fields[2].decodedBoolean() ?: return null
        val side = runCatching { TtsBubbleSide.valueOf(fields[3]) }.getOrNull() ?: return null
        val normalizedY = fields[4].toFloatOrNull() ?: return null
        val hidden = fields[5].decodedBoolean() ?: return null
        val powerMode = if (fields.size >= 7) {
            runCatching { TtsBubblePowerMode.valueOf(fields[6]) }.getOrNull()
                ?: return null
        } else {
            TtsBubblePowerMode.ALWAYS_ON
        }

        return TtsBubblePreferences(
            enabled = enabled,
            pendingEnable = pendingEnable,
            side = side,
            normalizedY = normalizedY,
            hiddenForCurrentSession = hidden,
            powerMode = powerMode
        ).normalized()
    }

    private fun Boolean.encoded(): String = if (this) "1" else "0"

    private fun String.decodedBoolean(): Boolean? = when (this) {
        "1" -> true
        "0" -> false
        else -> null
    }
}
