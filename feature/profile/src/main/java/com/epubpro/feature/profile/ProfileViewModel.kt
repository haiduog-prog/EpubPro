package com.epubpro.feature.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.epubpro.core.security.AppLockManager
import com.epubpro.core.storage.ReaderPreferencesManager
import com.epubpro.core.storage.ServerPreferencesManager
import com.epubpro.domain.model.AuthState
import com.epubpro.domain.model.ReaderSettings
import com.epubpro.domain.repository.AuthRepository
import com.epubpro.domain.repository.OnlineNovelRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel phụ trách cung cấp dữ liệu cấu hình và trạng thái tài khoản cho màn hình Cá nhân.
 *
 * @param serverPreferencesManager Trình quản lý cấu hình kết nối máy chủ.
 * @param onlineNovelRepository Repository kho truyện trực tuyến.
 * @param readerPreferencesManager Trình quản lý cài đặt đọc sách.
 * @param authRepository Repository quản lý trạng thái xác thực và phiên đăng nhập.
 */
@HiltViewModel
class ProfileViewModel @Inject constructor(
    val serverPreferencesManager: ServerPreferencesManager,
    val onlineNovelRepository: OnlineNovelRepository,
    val readerPreferencesManager: ReaderPreferencesManager,
    private val authRepository: AuthRepository,
    val appLockManager: AppLockManager
) : ViewModel() {

    val readerSettings: StateFlow<ReaderSettings> = readerPreferencesManager.settings

    val baseUrl: StateFlow<String> = serverPreferencesManager.baseUrlFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), serverPreferencesManager.getBaseUrl())

    val authState: StateFlow<AuthState> = authRepository.authState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AuthState.Loading)

    /**
     * Bật hoặc tắt tính năng tự động mở cuốn sách đang đọc dở khi khởi động ứng dụng.
     *
     * @param enabled true nếu bật tính năng, false nếu tắt.
     */
    fun setAutoResumeLastBook(enabled: Boolean) {
        readerPreferencesManager.setAutoResumeLastBookOnStartup(enabled)
    }

    /**
     * Đăng xuất khỏi tài khoản hiện tại và đưa ứng dụng về trạng thái Chưa đăng nhập.
     */
    fun logout() {
        viewModelScope.launch {
            authRepository.logout()
        }
    }
}
