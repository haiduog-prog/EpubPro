package com.epubpro.app

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.compose.rememberNavController
import com.epubpro.app.intent.ExternalBookImportHandler
import com.epubpro.app.intent.SharedLinkParser
import com.epubpro.app.navigation.AppNavHost
import com.epubpro.app.shortcut.BookEntryContract
import com.epubpro.core.designsystem.theme.EpubProTheme
import com.epubpro.core.security.AppLockGate
import com.epubpro.core.security.AppLockManager
import com.epubpro.core.reader.tts.TtsOpenBookContract
import com.epubpro.core.reader.tts.TtsOpenBookRequest
import com.epubpro.core.reader.tts.TtsService
import com.epubpro.core.reader.tts.TtsWidgetContract
import com.epubpro.core.storage.ReaderPreferencesManager
import com.epubpro.core.storage.TtsBubblePreferencesManager
import com.epubpro.domain.model.BookSourceFormat
import com.epubpro.domain.repository.BookRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Activity duy nhất của app. Kế thừa [FragmentActivity] vì hộp thoại xác thực khoá app (BiometricPrompt) cần nó.
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    @Inject
    lateinit var bubblePreferencesManager: TtsBubblePreferencesManager

    @Inject
    lateinit var readerPreferencesManager: ReaderPreferencesManager

    @Inject
    lateinit var bookRepository: BookRepository

    @Inject
    lateinit var appLockManager: AppLockManager

    private val intentViewModel: MainIntentViewModel by viewModels()
    private var bubbleStartupRestored = false
    private var hasDispatchedStartupBook = false
    private var autoResumeJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val isFromHistory = (intent?.flags ?: 0) and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
        val shouldProcessIntent = savedInstanceState == null && !isFromHistory
        val hasExplicitOpenBook = shouldProcessIntent &&
            (dispatchOpenBookRequest(intent) || dispatchBookEntryRequest(intent))
        val hasExplicitOpenLibrary = shouldProcessIntent && dispatchOpenLibraryRequest(intent)
        val hasExplicitImportBook = shouldProcessIntent && dispatchImportBookRequest(intent)
        val hasExplicitSharedLink = shouldProcessIntent && dispatchSharedLinkRequest(intent)

        if (savedInstanceState == null && !hasExplicitSharedLink && !hasExplicitOpenBook && !hasExplicitOpenLibrary && !hasExplicitImportBook) {
            checkAndAutoResumeLastBook()
        }
        collectNotices()

        enableEdgeToEdge()
        setContent {
            EpubProTheme {
                val navController = rememberNavController()
                // Khoá app phủ lên trên điều hướng; khi khoá tắt chỉ vẽ AppNavHost như trước.
                AppLockGate(manager = appLockManager) {
                    AppNavHost(
                        navController = navController,
                        openBookRequests = intentViewModel.openBookRequests,
                        openLibraryRequests = intentViewModel.openLibraryRequests,
                        openComicRequests = intentViewModel.openComicRequests,
                        openBrowserRequests = intentViewModel.openBrowserRequests
                    )
                }
            }
        }
    }

    /**
     * Khi app đang khoá, không cho phím đi vào cây View (WebView của Reader giữ focus sẽ lật trang bằng phím âm
     * lượng hay bàn phím ngay dưới màn khoá). Phím vẫn tới callback của Activity, nên Back vẫn tới màn khoá và
     * phím âm lượng vẫn chỉnh âm lượng của hệ thống.
     *
     * @param event Sự kiện phím.
     * @return `true` nếu sự kiện đã được xử lý.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (appLockManager.locked.value) {
            return event.dispatch(this, window.decorView.keyDispatcherState, this)
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onPostResume() {
        super.onPostResume()
        if (!bubbleStartupRestored) {
            bubbleStartupRestored = true
            restoreEnabledAudioBubble()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val hasExplicitOpenBook = dispatchOpenBookRequest(intent) || dispatchBookEntryRequest(intent)
        val hasExplicitOpenLibrary = dispatchOpenLibraryRequest(intent)
        val hasExplicitImportBook = dispatchImportBookRequest(intent)
        val hasExplicitSharedLink = dispatchSharedLinkRequest(intent)
        if (hasExplicitOpenBook || hasExplicitOpenLibrary || hasExplicitImportBook || hasExplicitSharedLink) {
            autoResumeJob?.cancel()
        }
    }

    /**
     * Thu thông báo một lần từ [MainIntentViewModel] (kết quả nạp sách) và hiển thị Toast khi Activity
     * đang ở trạng thái STARTED; thông báo phát lúc app ở nền được giữ lại tới khi quay lại.
     */
    private fun collectNotices() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                intentViewModel.notices.collect(::showNotice)
            }
        }
    }

    /**
     * Hiển thị một thông báo từ ViewModel dưới dạng Toast.
     *
     * @param notice Thông báo cần hiển thị.
     */
    private fun showNotice(notice: MainIntentNotice) {
        val (message, duration) = when (notice) {
            is MainIntentNotice.Res -> getString(notice.resId, *notice.args.toTypedArray()) to
                if (notice.isLong) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
            is MainIntentNotice.Text -> notice.message to Toast.LENGTH_LONG
        }
        Toast.makeText(this, message, duration).show()
    }

    /**
     * Kiểm tra cấu hình và tự động mở cuốn sách vừa đọc gần nhất khi khởi động ứng dụng mới.
     */
    private fun checkAndAutoResumeLastBook() {
        if (hasDispatchedStartupBook) return
        val settings = readerPreferencesManager.getSettings()
        if (!settings.autoResumeLastBookOnStartup) return

        autoResumeJob = lifecycleScope.launch {
            val latestBook = bookRepository.getLatestReadBook() ?: return@launch
            val progress = bookRepository.getReadingProgressDirect(latestBook.id)
            if (!isActive) return@launch
            val chapterIndex = progress?.chapterIndex ?: 0
            hasDispatchedStartupBook = true
            if (latestBook.sourceFormat == BookSourceFormat.CBZ) {
                intentViewModel.dispatchOpenComic(latestBook.id)
                return@launch
            }
            intentViewModel.dispatch(
                TtsOpenBookRequest(
                    bookId = latestBook.id,
                    chapterIndex = chapterIndex,
                    openTtsPlayer = false
                )
            )
        }
    }

    /**
     * Phân tích và chuyển tiếp yêu cầu mở sách từ Intent.
     *
     * @param intent Intent nhận được từ hệ thống hoặc widget/notification.
     * @return true nếu intent chứa yêu cầu mở sách hợp lệ, ngược lại false.
     */
    private fun dispatchOpenBookRequest(intent: Intent?): Boolean {
        // Action nội bộ chỉ được tin khi đi qua alias không export; app khác gọi thẳng MainActivity bị bỏ qua.
        if (!TtsOpenBookContract.isInternalEntry(intent)) return false
        val request = TtsOpenBookContract.parse(intent) ?: return false
        intentViewModel.dispatch(request)
        intent?.apply {
            action = null
            removeExtra(TtsOpenBookContract.EXTRA_BOOK_ID)
            removeExtra(TtsOpenBookContract.EXTRA_CHAPTER_INDEX)
            removeExtra(TtsOpenBookContract.EXTRA_OPEN_TTS_PLAYER)
        }
        return true
    }

    /**
     * Mở sách từ lối tắt màn hình chính; Reader tự tiếp tục từ tiến độ đã lưu.
     *
     * @param intent Intent nhận được từ launcher.
     * @return true nếu intent là yêu cầu mở sách theo lối tắt hợp lệ đi qua alias nội bộ, ngược lại false.
     */
    private fun dispatchBookEntryRequest(intent: Intent?): Boolean {
        if (!TtsOpenBookContract.isInternalEntry(intent)) return false
        val bookId = BookEntryContract.parseBookId(intent) ?: return false
        intentViewModel.openBookEntry(bookId)
        intent?.apply {
            action = null
            removeExtra(BookEntryContract.EXTRA_BOOK_ID)
        }
        return true
    }

    /**
     * Phân tích và chuyển tiếp yêu cầu mở màn hình kệ sách từ Intent.
     *
     * @param intent Intent nhận được từ hệ thống.
     * @return true nếu intent chứa action mở kệ sách, ngược lại false.
     */
    private fun dispatchOpenLibraryRequest(intent: Intent?): Boolean {
        if (intent?.action != TtsWidgetContract.ACTION_OPEN_LIBRARY) return false
        if (!TtsOpenBookContract.isInternalEntry(intent)) return false
        intentViewModel.dispatchOpenLibrary()
        intent.action = null
        return true
    }

    /**
     * Mở trình duyệt tích hợp khi ứng dụng khác chia sẻ một liên kết http(s) dạng văn bản.
     *
     * @param intent Intent nhận được từ hệ thống.
     * @return true nếu intent chứa liên kết hợp lệ và đã được chuyển tới trình duyệt.
     */
    private fun dispatchSharedLinkRequest(intent: Intent?): Boolean {
        val baseMime = intent?.type?.substringBefore(';')?.trim()
        if (intent?.action != Intent.ACTION_SEND || !baseMime.equals("text/plain", ignoreCase = true)) return false
        // Extra từ app khác có thể hỏng; lỗi unparcel không được làm crash Activity.
        val sharedText = runCatching { intent.getStringExtra(Intent.EXTRA_TEXT) }.getOrNull()
        val url = SharedLinkParser.extractUrl(sharedText) ?: return false
        intentViewModel.dispatchOpenBrowser(url)
        intent.action = null
        intent.removeExtra(Intent.EXTRA_TEXT)
        return true
    }

    /**
     * Phân tích và xử lý yêu cầu nạp sách từ ứng dụng bên ngoài qua Intent.
     *
     * @param intent Intent nhận được từ hệ thống hoặc ứng dụng chia sẻ/mở file.
     * @return true nếu intent chứa yêu cầu nạp sách hợp lệ và đã được tiếp nhận, ngược lại false.
     */
    private fun dispatchImportBookRequest(intent: Intent?): Boolean {
        val request = runCatching {
            ExternalBookImportHandler.parse(intent, contentResolver, privateStorageDirs())
        }.getOrNull() ?: return false
        intentViewModel.importExternalBook(request.uri, request.displayName)
        intent?.apply {
            action = null
            data = null
            removeExtra(Intent.EXTRA_STREAM)
        }
        return true
    }

    /**
     * Liệt kê các thư mục dữ liệu riêng của app không được phép nạp qua `file://`.
     *
     * @return Thư mục dữ liệu credential-encrypted và device-protected của ứng dụng.
     */
    private fun privateStorageDirs(): List<java.io.File> = listOfNotNull(
        java.io.File(applicationInfo.dataDir),
        runCatching { createDeviceProtectedStorageContext().dataDir }.getOrNull()
    )

    private fun restoreEnabledAudioBubble() {
        if (
            bubblePreferencesManager.getPreferences().enabled &&
            Settings.canDrawOverlays(this)
        ) {
            TtsService.syncBubbleState(this, enabled = true)
        }
    }

}
