package com.epubpro.core.security

import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Bọc nội dung app và phủ màn khoá lên trên khi [AppLockManager.locked] là `true`.
 *
 * Nội dung vẫn được giữ trong composition (Reader/WebView không phải nạp lại, điều hướng từ lối tắt hay
 * notification vẫn được xử lý phía dưới) nhưng bị ẩn khỏi trình đọc màn hình và không nhận chạm khi đang khoá.
 * Khi khoá tắt, chỉ vẽ [content].
 *
 * @param manager Bộ điều phối khoá app.
 * @param modifier Modifier cho vùng bao.
 * @param content Nội dung app.
 */
@Composable
fun AppLockGate(
    manager: AppLockManager,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val activity = LocalContext.current.findFragmentActivity()
    val locked by manager.locked.collectAsStateWithLifecycle()
    val settings by manager.settings.collectAsStateWithLifecycle()

    SecureWindowEffect(activity, secure = settings.enabled && settings.secureScreen)

    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(if (locked) Modifier.clearAndSetSemantics { } else Modifier)
        ) {
            content()
        }
        if (locked && activity != null) {
            AppLockScreen(manager = manager, activity = activity)
        }
    }
}

/**
 * Màn khoá toàn màn hình: tự mở hộp thoại xác thực khi app đã hiển thị, có nút mở lại khi người dùng huỷ.
 *
 * Coroutine xác thực gắn với composition, không gắn với RESUMED: màn nhập PIN là Activity riêng làm app tạm dừng,
 * nếu huỷ theo vòng đời sẽ đóng luôn màn nhập PIN.
 *
 * @param manager Bộ điều phối khoá app.
 * @param activity Activity đang hiển thị.
 */
@Composable
private fun AppLockScreen(manager: AppLockManager, activity: FragmentActivity) {
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    var authenticating by remember { mutableStateOf(false) }

    /** Mở hộp thoại xác thực nếu chưa có hộp thoại nào đang mở. */
    fun requestUnlock() {
        if (authenticating) return
        authenticating = true
        scope.launch {
            try {
                manager.unlock(activity)
            } finally {
                authenticating = false
            }
        }
    }

    // Mở hộp thoại khi màn khoá xuất hiện và mỗi lần người dùng quay lại app (hộp thoại cũ bị hệ thống đóng khi
    // rời app). Lần quay về từ màn PIN của hệ thống không làm tăng foregroundCount nên không gây vòng lặp.
    val foregroundCount by manager.foregroundCount.collectAsStateWithLifecycle()
    LaunchedEffect(foregroundCount) {
        // Chờ Activity hiển thị hẳn: BiometricPrompt gọi khi chưa RESUMED có thể bị hệ thống bỏ qua.
        lifecycleOwner.lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
        requestUnlock()
    }

    BackHandler { activity.moveTaskToBack(true) }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            // Chặn mọi chạm xuống nội dung phía dưới.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent().changes.forEach { it.consume() }
                    }
                }
            },
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(56.dp)
            )
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = stringResource(R.string.app_lock_title),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() }
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.app_lock_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(28.dp))
            Button(onClick = ::requestUnlock, enabled = !authenticating) {
                Text(stringResource(R.string.app_lock_unlock))
            }
        }
    }
}

/**
 * Bật/tắt `FLAG_SECURE` (chặn chụp màn hình, quay màn hình) và ảnh xem trước trong Recents.
 *
 * @param activity Activity chứa cửa sổ cần đặt cờ.
 * @param secure `true` để chặn.
 */
@Composable
private fun SecureWindowEffect(activity: FragmentActivity?, secure: Boolean) {
    DisposableEffect(activity, secure) {
        val window = activity?.window
        if (window != null) {
            if (secure) {
                window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                activity.setRecentsScreenshotEnabled(!secure)
            }
        }
        onDispose { }
    }
}

/**
 * Tìm [FragmentActivity] chứa một Context (Compose có thể bọc Context trong ContextWrapper).
 *
 * @return Activity, hoặc `null` nếu Context không thuộc FragmentActivity nào.
 */
fun Context.findFragmentActivity(): FragmentActivity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is FragmentActivity) return current
        current = current.baseContext
    }
    return null
}
