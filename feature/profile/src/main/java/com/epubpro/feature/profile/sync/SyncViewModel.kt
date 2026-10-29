package com.epubpro.feature.profile.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.epubpro.core.storage.sync.DriveSyncScheduler
import com.epubpro.domain.sync.SyncCoordinator
import com.epubpro.domain.sync.SyncOptions
import com.epubpro.domain.sync.SyncStatus
import com.epubpro.domain.sync.SyncUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** ViewModel sở hữu state và action của màn hình Google Drive sync. */
@HiltViewModel
class SyncViewModel @Inject constructor(
    private val coordinator: SyncCoordinator,
    private val scheduler: DriveSyncScheduler
) : ViewModel() {
    /** State sync được chia sẻ theo lifecycle của ViewModel. */
    val uiState: StateFlow<SyncUiState> = coordinator.observeState()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SyncUiState())

    /** Kiểm tra thay đổi local/Drive mà không ghi đè dữ liệu. */
    fun check() {
        if (uiState.value.isRunning()) return
        viewModelScope.launch { coordinator.check() }
    }

    /** Gộp tiến độ đọc local với ledger của các thiết bị trên Google Drive. */
    fun syncReadingHistory() {
        if (uiState.value.isRunning()) return
        viewModelScope.launch { coordinator.syncReadingHistory() }
    }

    /** Backup local sau khi người dùng chủ động nhấn nút. */
    fun backup() {
        if (uiState.value.isRunning()) return
        viewModelScope.launch { coordinator.backup() }
    }

    /** Restore từ Drive sau khi người dùng đã xác nhận thao tác. */
    fun restore() {
        if (uiState.value.isRunning()) return
        viewModelScope.launch { coordinator.restore() }
    }

    /** Đăng ký backup background với WorkManager unique work `epub-sync`. */
    fun scheduleBackup() = scheduler.enqueueBackup()

    private fun SyncUiState.isRunning(): Boolean =
        status == SyncStatus.SYNCING_UP || status == SyncStatus.SYNCING_DOWN
}
