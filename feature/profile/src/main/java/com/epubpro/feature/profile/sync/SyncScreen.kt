package com.epubpro.feature.profile.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.epubpro.core.designsystem.R
import com.epubpro.domain.sync.SyncStatus
import com.epubpro.domain.sync.SyncUiState

/**
 * Màn hình điều khiển backup/restore qua Google Drive.
 *
 * @param onNavigateBack Callback quay lại Profile.
 * @param viewModel ViewModel điều phối state và thao tác sync.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(
    onNavigateBack: () -> Unit,
    viewModel: SyncViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val running = uiState.status == SyncStatus.SYNCING_UP || uiState.status == SyncStatus.SYNCING_DOWN
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.sync_screen_title)) },
                navigationIcon = {
                    OutlinedButton(onClick = onNavigateBack, enabled = !running) {
                        Text(stringResource(R.string.action_close))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SyncStatusCard(uiState)
            if (running) {
                CircularProgressIndicator(
                    progress = uiState.progress.coerceIn(0f, 1f),
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            }
            SyncActions(
                enabled = !running,
                onCheck = viewModel::check,
                onSyncReadingHistory = viewModel::syncReadingHistory,
                onBackup = viewModel::backup,
                onRestore = viewModel::restore
            )
            if (uiState.conflictKeys.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.sync_conflict_keys_title),
                    style = MaterialTheme.typography.titleSmall
                )
                uiState.conflictKeys.forEach { key ->
                    Text(key, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/** Hiển thị status, progress và thông báo lỗi của phiên sync. */
@Composable
private fun SyncStatusCard(uiState: SyncUiState) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CloudSync, contentDescription = stringResource(R.string.sync_icon_description))
                Spacer(Modifier.width(6.dp))
                Text(syncStatusText(uiState.status), style = MaterialTheme.typography.titleMedium)
            }
            if (uiState.totalItems > 0) {
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.sync_progress, uiState.completedItems, uiState.totalItems))
            }
            uiState.message?.takeIf(String::isNotBlank)?.let { message ->
                Spacer(Modifier.height(8.dp))
                Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Hiển thị ba action chính với semantics role button rõ ràng. */
@Composable
private fun SyncActions(
    enabled: Boolean,
    onCheck: () -> Unit,
    onSyncReadingHistory: () -> Unit,
    onBackup: () -> Unit,
    onRestore: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = onSyncReadingHistory,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().semantics { role = Role.Button }
        ) {
            Text(stringResource(R.string.sync_action_reading_history))
        }
        Button(onClick = onCheck, enabled = enabled, modifier = Modifier.fillMaxWidth().semantics { role = Role.Button }) {
            Text(stringResource(R.string.sync_action_check))
        }
        Button(onClick = onBackup, enabled = enabled, modifier = Modifier.fillMaxWidth().semantics { role = Role.Button }) {
            Text(stringResource(R.string.sync_action_backup))
        }
        OutlinedButton(onClick = onRestore, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.sync_action_restore))
        }
    }
}

/**
 * Ánh xạ public status sang string resource, không để domain chứa text UI.
 *
 * @param status Public sync status.
 * @return Chuỗi hiển thị tương ứng.
 */
@Composable
private fun syncStatusText(status: SyncStatus): String = stringResource(
    when (status) {
        SyncStatus.READY -> R.string.sync_status_ready
        SyncStatus.CHANGES_PENDING -> R.string.sync_status_changes_pending
        SyncStatus.SYNCING_UP -> R.string.sync_status_syncing_up
        SyncStatus.SYNCING_DOWN -> R.string.sync_status_syncing_down
        SyncStatus.SYNCED -> R.string.sync_status_synced
        SyncStatus.DRIVE_PENDING -> R.string.sync_status_drive_pending
        SyncStatus.CONFLICT -> R.string.sync_status_conflict
        SyncStatus.AUTH_REQUIRED -> R.string.sync_status_auth_required
        SyncStatus.ERROR -> R.string.sync_status_error
    }
)
