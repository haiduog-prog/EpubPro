package com.epubpro.feature.profile

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.ScreenLockPortrait
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.epubpro.core.security.AppLockDelay
import com.epubpro.core.security.AppLockManager
import com.epubpro.core.security.AuthResult
import com.epubpro.core.security.BiometricAvailability
import com.epubpro.core.security.R
import com.epubpro.core.security.findFragmentActivity
import kotlinx.coroutines.launch

/**
 * Nhóm cài đặt "Bảo mật" ở tab Cá nhân: bật/tắt khoá app, thời gian khoá lại và chặn chụp màn hình.
 *
 * Bật hoặc tắt khoá đều yêu cầu xác thực. Khả năng xác thực của máy được kiểm tra lại mỗi khi màn hình hiển thị
 * (người dùng có thể vừa cài vân tay trong Cài đặt hệ thống rồi quay về).
 *
 * @param manager Bộ điều phối khoá app.
 * @param modifier Modifier cho nhóm cài đặt.
 */
@Composable
fun AppLockSettingsSection(manager: AppLockManager, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val activity = remember(context) { context.findFragmentActivity() }
    val scope = rememberCoroutineScope()
    val settings by manager.settings.collectAsStateWithLifecycle()
    var availability by remember { mutableStateOf(manager.availability()) }
    var showDelayDialog by rememberSaveable { mutableStateOf(false) }
    var toggling by remember { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { availability = manager.availability() }

    val canToggle = activity != null && !toggling &&
        (settings.enabled || availability == BiometricAvailability.AVAILABLE)

    ProfileSectionCard(title = stringResource(R.string.app_lock_section_title), modifier = modifier) {
        ProfileSwitchItem(
            title = stringResource(R.string.app_lock_setting_title),
            subtitle = stringResource(
                if (settings.enabled || availability == BiometricAvailability.AVAILABLE) {
                    R.string.app_lock_setting_subtitle
                } else {
                    R.string.app_lock_setting_unavailable
                }
            ),
            icon = Icons.Default.Fingerprint,
            checked = settings.enabled,
            enabled = canToggle,
            onCheckedChange = { target ->
                val host = activity ?: return@ProfileSwitchItem
                toggling = true
                scope.launch {
                    try {
                        if (manager.setEnabled(host, target) is AuthResult.Error) {
                            Toast.makeText(context, R.string.app_lock_auth_failed, Toast.LENGTH_SHORT).show()
                        }
                    } finally {
                        toggling = false
                    }
                }
            }
        )
        if (settings.enabled) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
            ProfileCardItem(
                title = stringResource(R.string.app_lock_delay_title),
                subtitle = stringResource(settings.delay.labelRes()),
                icon = Icons.Default.Timer,
                onClick = { showDelayDialog = true }
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
            ProfileSwitchItem(
                title = stringResource(R.string.app_lock_secure_screen_title),
                subtitle = stringResource(R.string.app_lock_secure_screen_subtitle),
                icon = Icons.Default.ScreenLockPortrait,
                checked = settings.secureScreen,
                onCheckedChange = manager::setSecureScreen
            )
        }
    }

    if (showDelayDialog) {
        AppLockDelayDialog(
            selected = settings.delay,
            onSelect = { delay ->
                manager.setDelay(delay)
                showDelayDialog = false
            },
            onDismiss = { showDelayDialog = false }
        )
    }
}

/**
 * Hộp thoại chọn thời gian khoá lại.
 *
 * @param selected Giá trị đang chọn.
 * @param onSelect Callback khi người dùng chọn một giá trị.
 * @param onDismiss Callback khi đóng hộp thoại.
 */
@Composable
private fun AppLockDelayDialog(
    selected: AppLockDelay,
    onSelect: (AppLockDelay) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.app_lock_delay_title)) },
        text = {
            Column(modifier = Modifier.selectableGroup()) {
                AppLockDelay.entries.forEach { delay ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = delay == selected,
                                role = Role.RadioButton,
                                onClick = { onSelect(delay) }
                            )
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = delay == selected, onClick = null)
                        Text(
                            text = stringResource(delay.labelRes()),
                            modifier = Modifier.padding(start = 12.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.app_lock_dialog_cancel)) }
        }
    )
}

/**
 * Nhãn hiển thị của một thời gian khoá lại.
 *
 * @return String resource tương ứng.
 */
private fun AppLockDelay.labelRes(): Int = when (this) {
    AppLockDelay.IMMEDIATE -> R.string.app_lock_delay_immediate
    AppLockDelay.ONE_MINUTE -> R.string.app_lock_delay_one_minute
    AppLockDelay.FIVE_MINUTES -> R.string.app_lock_delay_five_minutes
    AppLockDelay.FIFTEEN_MINUTES -> R.string.app_lock_delay_fifteen_minutes
}
