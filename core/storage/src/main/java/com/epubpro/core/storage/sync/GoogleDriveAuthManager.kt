package com.epubpro.core.storage.sync

import android.accounts.AccountManager
import android.content.Context
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.Scope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cầu nối lấy OAuth access token ngắn hạn cho Google Drive.
 *
 * Token chỉ tồn tại trong bộ nhớ của phiên gọi; không ghi vào DataStore, log hay manifest.
 */
@Singleton
class GoogleDriveAuthManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /**
     * Lấy access token cho đúng tài khoản đã chọn trong Google Sign-In.
     *
     * @return Token trong bộ nhớ, hoặc null nếu cần người dùng cấp quyền/đăng nhập.
     * @throws com.google.android.gms.auth.GoogleAuthException Khi Google từ chối xác thực.
     * @throws java.io.IOException Khi không thể kết nối dịch vụ xác thực.
     */
    suspend fun getAccessToken(): String? = withContext(Dispatchers.IO) {
        val signedInAccount = GoogleSignIn.getLastSignedInAccount(context) ?: return@withContext null
        if (!GoogleSignIn.hasPermissions(signedInAccount, Scope(DRIVE_FILE_SCOPE))) return@withContext null
        val account = signedInAccount.account ?: return@withContext null
        try {
            GoogleAuthUtil.getToken(context, account, TOKEN_TYPE)
        } catch (_: UserRecoverableAuthException) {
            // Worker không thể mở giao diện cấp quyền; yêu cầu đăng nhập lại ở foreground.
            null
        }
    }

    /**
     * Loại bỏ token bị từ chối để lần sau Android xin token mới.
     *
     * @param token Token đã bị Drive từ chối.
     */
    fun invalidate(token: String) {
        AccountManager.get(context).invalidateAuthToken("com.google", token)
    }

    companion object {
        /** Scope OAuth tối thiểu để app chỉ thao tác file do app tạo/chọn trên Drive. */
        const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"

        private const val TOKEN_TYPE = "oauth2:$DRIVE_FILE_SCOPE"
    }
}

/** File remote cùng key để download/delete mà không tin cache ID tuyệt đối. */
data class DriveFileRef(
    val id: String,
    val key: String,
    val parentId: String
)

/** Snapshot remote đọc từ manifest và cây folder Drive. */
data class DriveSyncSnapshot(
    val manifest: com.epubpro.domain.sync.SyncManifest,
    val files: Map<String, DriveFileRef>
)

/** Lỗi đã được phân loại ở boundary Drive. */
class DriveSyncException(
    message: String,
    val requiresAuth: Boolean = false,
    cause: Throwable? = null
) : IllegalStateException(message, cause)
