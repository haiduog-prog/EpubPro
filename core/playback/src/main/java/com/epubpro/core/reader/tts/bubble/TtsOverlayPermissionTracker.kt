package com.epubpro.core.reader.tts.bubble

import android.app.AppOpsManager
import android.content.Context
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Observes SYSTEM_ALERT_WINDOW changes without keeping an idle polling loop alive. */
internal class TtsOverlayPermissionTracker(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val appOpsManager = appContext.getSystemService(AppOpsManager::class.java)
    private val _granted = MutableStateFlow(Settings.canDrawOverlays(appContext))
    val granted = _granted.asStateFlow()

    private val listener = AppOpsManager.OnOpChangedListener { op, packageName ->
        if (
            op == AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW &&
            (packageName == null || packageName == appContext.packageName)
        ) {
            refresh()
        }
    }

    init {
        runCatching {
            appOpsManager?.startWatchingMode(
                AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW,
                appContext.packageName,
                listener
            )
        }
    }

    fun refresh() {
        _granted.value = Settings.canDrawOverlays(appContext)
    }

    override fun close() {
        runCatching { appOpsManager?.stopWatchingMode(listener) }
    }
}

/** Reports only real availability transitions so service synchronization stays idempotent. */
internal class TtsBubbleAvailabilityObserver(initial: Boolean) {
    private var previous = initial

    fun update(current: Boolean): Boolean {
        if (current == previous) return false
        previous = current
        return true
    }
}
