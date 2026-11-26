package com.epubpro.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.WorkManager
import com.epubpro.app.shortcut.BookShortcutManager
import com.epubpro.core.security.AppLockManager
import com.epubpro.core.storage.worker.WebNovelCoverBackfillScheduler
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * Lớp Application chính của ứng dụng EpubPro, khởi tạo Hilt Dependency Injection và cấu hình WorkManager.
 */
@HiltAndroidApp
class EpubApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var bookShortcutManager: BookShortcutManager

    @Inject
    lateinit var webNovelCoverBackfillScheduler: WebNovelCoverBackfillScheduler

    @Inject
    lateinit var appLockManager: AppLockManager

    private val applicationScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        appLockManager.install()
        cleanupLegacyDriveSyncArtifacts()
        bookShortcutManager.startSync(applicationScope)
        runCatching { webNovelCoverBackfillScheduler.enqueue() }
    }

    /**
     * Hủy unique work `epub-sync` còn tồn đọng trong WorkManager database và dọn sạch các file/DataStore
     * của tính năng đồng bộ Google Drive cũ đã được gỡ bỏ.
     */
    private fun cleanupLegacyDriveSyncArtifacts() {
        runCatching {
            WorkManager.getInstance(this).cancelUniqueWork(LEGACY_DRIVE_SYNC_WORK_NAME)
        }
        applicationScope.launch {
            runCatching {
                File(filesDir, "sync-rollback").deleteRecursively()
                File(filesDir, "datastore/epub_sync_state.preferences_pb").delete()
            }
        }
    }

    /**
     * Cung cấp cấu hình tùy chỉnh cho WorkManager với HiltWorkerFactory để hỗ trợ Dependency Injection trong Worker.
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    private companion object {
        const val LEGACY_DRIVE_SYNC_WORK_NAME = "epub-sync"
    }
}
