package com.epubpro.app

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.rememberNavController
import com.epubpro.app.intent.ExternalBookImportHandler
import com.epubpro.app.intent.ExternalBookImportRequest
import com.epubpro.app.intent.SharedLinkParser
import com.epubpro.app.navigation.AppNavHost
import com.epubpro.core.designsystem.R
import com.epubpro.core.designsystem.theme.EpubProTheme
import com.epubpro.core.reader.tts.TtsOpenBookContract
import com.epubpro.core.reader.tts.TtsOpenBookRequest
import com.epubpro.core.reader.tts.TtsService
import com.epubpro.core.reader.tts.TtsWidgetContract
import com.epubpro.core.storage.BookFileTooLargeException
import com.epubpro.core.storage.ReaderPreferencesManager
import com.epubpro.core.storage.TtsBubblePreferencesManager
import com.epubpro.core.storage.worker.LocalBookImportScheduler
import com.epubpro.core.storage.worker.LocalBookImportWorker
import com.epubpro.domain.model.BookSourceFormat
import com.epubpro.domain.repository.BookRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var bubblePreferencesManager: TtsBubblePreferencesManager

    @Inject
    lateinit var readerPreferencesManager: ReaderPreferencesManager

    @Inject
    lateinit var bookRepository: BookRepository

    @Inject
    lateinit var localBookImportScheduler: LocalBookImportScheduler

    private val intentViewModel: MainIntentViewModel by viewModels()
    private var bubbleStartupRestored = false
    private var hasDispatchedStartupBook = false
    private var autoResumeJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val hasExplicitOpenBook = dispatchOpenBookRequest(intent)
        val hasExplicitOpenLibrary = dispatchOpenLibraryRequest(intent)
        val hasExplicitImportBook = dispatchImportBookRequest(intent)
        val hasExplicitSharedLink = dispatchSharedLinkRequest(intent)

        if (savedInstanceState == null && !hasExplicitSharedLink && !hasExplicitOpenBook && !hasExplicitOpenLibrary && !hasExplicitImportBook) {
            checkAndAutoResumeLastBook()
        }

        enableEdgeToEdge()
        setContent {
            EpubProTheme {
                val navController = rememberNavController()
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
        val hasExplicitOpenBook = dispatchOpenBookRequest(intent)
        val hasExplicitOpenLibrary = dispatchOpenLibraryRequest(intent)
        val hasExplicitImportBook = dispatchImportBookRequest(intent)
        val hasExplicitSharedLink = dispatchSharedLinkRequest(intent)
        if (hasExplicitOpenBook || hasExplicitOpenLibrary || hasExplicitImportBook || hasExplicitSharedLink) {
            autoResumeJob?.cancel()
        }
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
     * Phân tích và chuyển tiếp yêu cầu mở màn hình kệ sách từ Intent.
     *
     * @param intent Intent nhận được từ hệ thống.
     * @return true nếu intent chứa action mở kệ sách, ngược lại false.
     */
    private fun dispatchOpenLibraryRequest(intent: Intent?): Boolean {
        if (intent?.action != TtsWidgetContract.ACTION_OPEN_LIBRARY) return false
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
        if (intent?.action != Intent.ACTION_SEND || intent.type != "text/plain") return false
        val url = SharedLinkParser.extractUrl(intent.getStringExtra(Intent.EXTRA_TEXT)) ?: return false
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
        val request = ExternalBookImportHandler.parse(intent, contentResolver) ?: return false
        handleExternalBookImport(request)
        intent?.apply {
            action = null
            data = null
            removeExtra(Intent.EXTRA_STREAM)
        }
        return true
    }

    /**
     * Thực hiện điều hướng UI về Kệ sách, hiển thị thông báo và lập lịch nạp file sách bất đồng bộ.
     *
     * @param request Thông tin yêu cầu nạp sách gồm URI và tên hiển thị.
     */
    private fun handleExternalBookImport(request: ExternalBookImportRequest) {
        intentViewModel.dispatchOpenLibrary()
        val bookTitle = request.displayName ?: getString(R.string.nav_library)
        Toast.makeText(
            this,
            getString(R.string.import_external_started, bookTitle),
            Toast.LENGTH_SHORT
        ).show()

        lifecycleScope.launch {
            try {
                val workId = localBookImportScheduler.enqueue(request.uri, request.displayName)
                    ?: return@launch
                val workInfo = WorkManager.getInstance(this@MainActivity)
                    .getWorkInfoByIdFlow(workId)
                    .first { it?.state?.isFinished == true }
                if (workInfo?.state == WorkInfo.State.SUCCEEDED) {
                    workInfo.outputData
                        .getString(LocalBookImportWorker.KEY_BOOK_ID)
                        ?.takeIf { it.isNotBlank() }
                        ?.let { bookId ->
                            val importedFormat = workInfo.outputData.getString(LocalBookImportWorker.KEY_SOURCE_FORMAT)
                            if (importedFormat == BookSourceFormat.CBZ.name) {
                                intentViewModel.dispatchOpenComic(bookId)
                                return@let
                            }
                            intentViewModel.dispatch(
                                TtsOpenBookRequest(
                                    bookId = bookId,
                                    chapterIndex = 0,
                                    openTtsPlayer = false
                                )
                            )
                        }
                }
            } catch (error: Exception) {
                val errorMsg = when {
                    error is BookFileTooLargeException -> {
                        getString(R.string.book_import_error_too_large, error.limitMiB)
                    }
                    error.javaClass.name.endsWith("BookConversionException") -> {
                        getString(R.string.library_import_book_failed)
                    }
                    else -> {
                        getString(R.string.library_import_failed)
                    }
                }
                Toast.makeText(this@MainActivity, errorMsg, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun restoreEnabledAudioBubble() {
        if (
            bubblePreferencesManager.getPreferences().enabled &&
            Settings.canDrawOverlays(this)
        ) {
            TtsService.syncBubbleState(this, enabled = true)
        }
    }

}

internal class MainIntentViewModel : ViewModel() {
    private val requestChannel = Channel<TtsOpenBookRequest>(Channel.BUFFERED)
    private val libraryChannel = Channel<Unit>(Channel.BUFFERED)
    private val comicChannel = Channel<String>(Channel.BUFFERED)
    private val browserChannel = Channel<String>(Channel.BUFFERED)
    val openBookRequests: Flow<TtsOpenBookRequest> = requestChannel.receiveAsFlow()
    val openLibraryRequests: Flow<Unit> = libraryChannel.receiveAsFlow()
    val openComicRequests: Flow<String> = comicChannel.receiveAsFlow()
    val openBrowserRequests: Flow<String> = browserChannel.receiveAsFlow()

    fun dispatch(request: TtsOpenBookRequest) {
        requestChannel.trySend(request)
    }

    fun dispatchOpenLibrary() {
        libraryChannel.trySend(Unit)
    }

    /**
     * Yêu cầu mở Reader truyện tranh cho một cuốn CBZ (sau import từ app ngoài hoặc tự mở sách gần nhất).
     *
     * @param bookId ID sách trong thư viện.
     */
    fun dispatchOpenComic(bookId: String) {
        comicChannel.trySend(bookId)
    }

    /**
     * Yêu cầu mở trình duyệt tích hợp tại một URL được chia sẻ từ ứng dụng khác.
     *
     * @param url URL http(s) đã kiểm tra.
     */
    fun dispatchOpenBrowser(url: String) {
        browserChannel.trySend(url)
    }
}
