package com.epubpro.feature.library

import androidx.compose.foundation.verticalScroll
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import coil.compose.AsyncImage
import com.epubpro.core.designsystem.R
import com.epubpro.feature.library.components.GeneratedBookCover

/**
 * Đọc tên hiển thị do Storage Access Framework cung cấp cho URI.
 *
 * @param context Context dùng để truy vấn ContentResolver.
 * @param uri URI file người dùng vừa chọn.
 * @return Tên file hiển thị hoặc null nếu provider không cung cấp.
 */
private fun queryDisplayName(context: Context, uri: Uri): String? {
    val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
    return context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (nameIndex < 0) null else cursor.getString(nameIndex)
    }
}

/**
 * Đọc MIME type do Storage Access Framework cung cấp cho URI.
 *
 * @param context Context dùng để truy vấn ContentResolver.
 * @param uri URI file người dùng vừa chọn.
 * @return MIME type hoặc null nếu provider không cung cấp.
 */
private fun queryMimeType(context: Context, uri: Uri): String? =
    context.contentResolver.getType(uri)

/**
 * Màn hình Thư viện sách (Library Screen) theo phong cách thiết kế Material 3 chuẩn Stitch.
 *
 * @param onBookClick Callback điều hướng khi người dùng nhấn chọn một cuốn sách.
 * @param onNavigateToBookmarks Callback điều hướng sang màn hình Dấu trang & Ghi chú.
 * @param onNavigateToOnlineLibrary Callback điều hướng sang Kho truyện Online.
 * @param viewModel ViewModel quản lý trạng thái thư viện.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onBookClick: (bookId: String) -> Unit,
    onNavigateToBookmarks: () -> Unit,
    onNavigateToOnlineLibrary: () -> Unit,
    viewModel: LibraryViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val pendingImports by viewModel.pendingLocalImports.collectAsStateWithLifecycle()
    pendingImports.firstOrNull()?.let { pending ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissPendingLocalImport(pending.sourcePath) },
            title = { Text(stringResource(R.string.library_duplicate_epub_title)) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.library_duplicate_epub_message))
                    pending.matches.forEach { book ->
                        TextButton(onClick = { viewModel.resolveLocalImport(pending.sourcePath, book.id) }) {
                            Text(stringResource(R.string.library_duplicate_epub_update, book.title))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.resolveLocalImport(pending.sourcePath, null) }) {
                    Text(stringResource(R.string.library_duplicate_epub_add_new))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissPendingLocalImport(pending.sourcePath) }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val snackbarHostState = remember { SnackbarHostState() }
    var showAddBookBottomSheet by rememberSaveable { mutableStateOf(false) }
    var showDriveImportHelp by rememberSaveable { mutableStateOf(false) }
    var pendingDeleteBookId by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(viewModel, lifecycleOwner, snackbarHostState) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.events.collect { message ->
                Log.d(
                    "TAG",
                    "LibraryScreen: " + context.getString(
                        message.textRes,
                        *message.formatArgs.toTypedArray()
                    )
                )
                snackbarHostState.showSnackbar(
                    context.getString(
                        message.textRes,
                        *message.formatArgs.toTypedArray()
                    )
                )
            }
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.importBook(it, queryDisplayName(context, it)) }
    }

    val uploadPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            viewModel.uploadEpubToServer(it, queryDisplayName(context, it), queryMimeType(context, it))
        }
    }

    val drivePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { viewModel.importBook(it, null) }
    }

    if (showDriveImportHelp) {
        AlertDialog(
            onDismissRequest = { showDriveImportHelp = false },
            title = { Text(stringResource(R.string.add_book_drive)) },
            text = { Text(stringResource(R.string.library_drive_import_help)) },
            confirmButton = {
                TextButton(onClick = {
                    showDriveImportHelp = false
                    drivePickerLauncher.launch(arrayOf("*/*"))
                }) {
                    Text(stringResource(R.string.library_drive_open_picker))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDriveImportHelp = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        uploadPickerLauncher.launch("*/*")
    }

    val onStartUpload = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            uploadPickerLauncher.launch("*/*")
        }
    }

    if (showAddBookBottomSheet) {
        AddBookBottomSheet(
            onDismissRequest = { showAddBookBottomSheet = false },
            onNavigateToOnlineLibrary = onNavigateToOnlineLibrary,
            onPickLocalEpub = { filePickerLauncher.launch("*/*") },
            onUploadEpubToServer = onStartUpload,
            onPickDriveBook = { showDriveImportHelp = true }
        )
    }

    pendingDeleteBookId?.let { bookId ->
        uiState.books.firstOrNull { it.book.id == bookId }?.let { item ->
            AlertDialog(
                onDismissRequest = { pendingDeleteBookId = null },
                title = {
                    Text(
                        text = stringResource(R.string.library_delete_confirm_title),
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Text(
                        text = stringResource(
                            R.string.library_delete_confirm_message,
                            item.book.title
                        )
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            pendingDeleteBookId = null
                            viewModel.deleteBook(item)
                        }
                    ) {
                        Text(
                            text = stringResource(R.string.library_delete_book),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDeleteBookId = null }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            )
        }
    }

    val activeImportStatus = uiState.localImportJobStatus ?: uiState.uploadJobStatus
    val isLocalImport = uiState.localImportJobStatus != null
    var showImportDetailsDialog by rememberSaveable { mutableStateOf(false) }

    if (showImportDetailsDialog && activeImportStatus != null) {
        AlertDialog(
            onDismissRequest = {
                showImportDetailsDialog = false
                if (isLocalImport) viewModel.dismissLocalImportDialog() else viewModel.dismissUploadDialog()
            },
            title = {
                Text(
                    text = stringResource(
                        if (isLocalImport) R.string.book_conversion_dialog_title
                        else R.string.epub_import_dialog_title
                    ),
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = activeImportStatus.title ?: stringResource(R.string.tts_default_book_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    val progress = (activeImportStatus.progressPercentage / 100f).coerceIn(0f, 1f)
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = activeImportStatus.currentStep?.let { it.substringAfter("): ", it) }
                            ?: stringResource(R.string.epub_import_notification_starting),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    val errorMsg = activeImportStatus.errorMessage
                    if (activeImportStatus.isFailed && !errorMsg.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = errorMsg,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                if (activeImportStatus.isFailed || activeImportStatus.isCompleted) {
                    TextButton(onClick = {
                        showImportDetailsDialog = false
                        if (isLocalImport) viewModel.dismissLocalImportDialog() else viewModel.dismissUploadDialog()
                    }) {
                        Text(stringResource(R.string.action_close))
                    }
                } else {
                    TextButton(onClick = {
                        showImportDetailsDialog = false
                        if (isLocalImport) viewModel.dismissLocalImportDialog() else viewModel.dismissUploadDialog()
                    }) {
                        Text(stringResource(R.string.action_background))
                    }
                }
            },
            dismissButton = {
                if (!activeImportStatus.isFailed && !activeImportStatus.isCompleted) {
                    TextButton(onClick = {
                        showImportDetailsDialog = false
                        if (isLocalImport) viewModel.cancelLocalImportWork() else viewModel.cancelUploadWork()
                    }) {
                        Text(stringResource(R.string.action_cancel), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.semantics { heading() }
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.MenuBook,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(26.dp)
                        )
                        Text(
                            text = stringResource(R.string.app_name),
                            fontFamily = FontFamily.Serif,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToOnlineLibrary) {
                        Icon(
                            imageVector = Icons.Default.CloudDownload,
                            contentDescription = stringResource(R.string.add_book_online),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(onClick = onNavigateToBookmarks) {
                        Icon(
                            imageVector = Icons.Default.Bookmarks,
                            contentDescription = stringResource(R.string.library_bookmarks_highlights),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddBookBottomSheet = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = stringResource(R.string.library_add_book),
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
        ) {
            // Thanh tìm kiếm sách / tác giả
            OutlinedTextField(
                value = uiState.searchQuery,
                onValueChange = viewModel::onSearchQueryChanged,
                placeholder = {
                    Text(
                        text = stringResource(R.string.library_search_placeholder),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                trailingIcon = {
                    if (uiState.searchQuery.isNotBlank()) {
                        IconButton(onClick = { viewModel.onSearchQueryChanged("") }) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = stringResource(R.string.library_action_clear_search),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, bottom = 8.dp),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.surfaceVariant,
                    cursorColor = MaterialTheme.colorScheme.primary
                ),
                singleLine = true
            )

            // Thanh Filter Chips (Tất cả, Đang đọc, Chưa đọc, Đã đọc xong)
            LibraryFilterChips(
                selectedFilter = uiState.selectedFilter,
                totalCount = uiState.totalBookCount,
                readingCount = uiState.readingBookCount,
                unreadCount = uiState.unreadBookCount,
                completedCount = uiState.completedBookCount,
                onFilterSelected = viewModel::onFilterSelected
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Sync / Import Widget Banner (khi có tác vụ chạy nền)
            if (activeImportStatus != null && !activeImportStatus.isCompleted) {
                SyncProgressWidget(
                    status = activeImportStatus,
                    onClick = { showImportDetailsDialog = true }
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            when {
                uiState.isLoading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(bottom = 80.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = stringResource(R.string.library_loading),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                uiState.totalBookCount == 0 -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(bottom = 80.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.MenuBook,
                                contentDescription = null,
                                modifier = Modifier.size(72.dp),
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                            )
                            Text(
                                text = stringResource(R.string.library_empty_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontFamily = FontFamily.Serif,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.semantics { heading() }
                            )
                            Text(
                                text = stringResource(R.string.library_empty_subtitle),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                uiState.books.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(bottom = 80.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.SearchOff,
                                contentDescription = null,
                                modifier = Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                            )
                            Text(
                                text = stringResource(
                                    R.string.library_search_empty_title,
                                    uiState.searchQuery
                                ),
                                style = MaterialTheme.typography.titleMedium,
                                fontFamily = FontFamily.Serif,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.semantics { heading() }
                            )
                            Text(
                                text = stringResource(R.string.library_search_empty_subtitle),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(top = 4.dp, bottom = 96.dp)
                    ) {
                        items(
                            items = uiState.books,
                            key = { it.book.id }
                        ) { item ->
                            BookCard(
                                item = item,
                                onClick = { onBookClick(item.book.id) },
                                onDelete = { pendingDeleteBookId = item.book.id }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Hàng chip bộ lọc danh mục theo trạng thái đọc sách.
 *
 * @param selectedFilter Bộ lọc hiện tại đang được chọn.
 * @param totalCount Tổng số sách.
 * @param readingCount Số lượng sách đang đọc.
 * @param unreadCount Số lượng sách chưa đọc.
 * @param completedCount Số lượng sách đã đọc xong.
 * @param onFilterSelected Callback khi người dùng nhấn chọn bộ lọc mới.
 */
@Composable
private fun LibraryFilterChips(
    selectedFilter: LibraryFilter,
    totalCount: Int,
    readingCount: Int,
    unreadCount: Int,
    completedCount: Int,
    onFilterSelected: (LibraryFilter) -> Unit
) {
    val scrollState = rememberScrollState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FilterChipItem(
            text = stringResource(R.string.library_filter_all, totalCount),
            selected = selectedFilter == LibraryFilter.ALL,
            onClick = { onFilterSelected(LibraryFilter.ALL) }
        )
        FilterChipItem(
            text = stringResource(R.string.library_filter_reading, readingCount),
            selected = selectedFilter == LibraryFilter.READING,
            onClick = { onFilterSelected(LibraryFilter.READING) }
        )
        FilterChipItem(
            text = stringResource(R.string.library_filter_unread, unreadCount),
            selected = selectedFilter == LibraryFilter.UNREAD,
            onClick = { onFilterSelected(LibraryFilter.UNREAD) }
        )
        FilterChipItem(
            text = stringResource(R.string.library_filter_completed, completedCount),
            selected = selectedFilter == LibraryFilter.COMPLETED,
            onClick = { onFilterSelected(LibraryFilter.COMPLETED) }
        )
    }
}

/**
 * Một item chip lọc trạng thái đọc sách với bo góc tròn hoàn hảo.
 *
 * @param text Chuỗi hiển thị bên trong chip.
 * @param selected Trạng thái kích hoạt.
 * @param onClick Callback khi nhấn chọn chip.
 */
@Composable
private fun FilterChipItem(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
        border = if (selected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
        shadowElevation = if (selected) 2.dp else 0.dp
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}

/**
 * Thẻ hiển thị tiến trình nạp hoặc tải sách chạy ngầm (Sync Widget).
 *
 * @param status Thông tin tiến trình nạp sách.
 * @param onClick Callback khi người dùng nhấn vào thẻ để xem chi tiết.
 */
@Composable
private fun SyncProgressWidget(
    status: com.epubpro.domain.model.ImportJobStatus,
    onClick: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "SyncRotation")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "SyncRotationAngle"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Sync,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(24.dp)
                    .rotate(rotation)
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(
                        R.string.library_sync_status_prefix,
                        status.title ?: stringResource(R.string.tts_default_book_title)
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(6.dp))

                val progress = (status.progressPercentage / 100f).coerceIn(0f, 1f)
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }
        }
    }
}

/**
 * Hiển thị thẻ sách dạng danh sách nằm ngang (Horizontal List Book Card) chuẩn Stitch UI.
 *
 * @param item Dữ liệu cuốn sách và tiến độ đọc.
 * @param onClick Callback khi người dùng chọn đọc sách.
 * @param onDelete Callback khi người dùng chọn xóa sách.
 */
@Composable
fun BookCard(
    item: BookItemUiState,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.08f)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Ảnh bìa sách bên trái
            Box(
                modifier = Modifier
                    .width(92.dp)
                    .height(124.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                var coverLoadFailed by remember(item.book.coverPath) { mutableStateOf(false) }
                if (!item.book.coverPath.isNullOrBlank() && !coverLoadFailed) {
                    AsyncImage(
                        model = item.book.coverPath,
                        contentDescription = item.book.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                        onError = { coverLoadFailed = true }
                    )
                } else {
                    GeneratedBookCover(
                        title = item.book.title,
                        author = item.book.author,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            // Thông tin chi tiết sách bên phải
            Column(
                modifier = Modifier
                    .weight(1f)
                    .height(124.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Phần trên: Tiêu đề, tác giả, tag & menu
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            text = item.book.title,
                            fontFamily = FontFamily.Serif,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            lineHeight = 22.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )

                        Box {
                            IconButton(
                                onClick = { showMenu = true },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = stringResource(
                                        R.string.library_book_menu_format,
                                        item.book.title
                                    ),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = stringResource(R.string.library_delete_book),
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    },
                                    onClick = {
                                        showMenu = false
                                        onDelete()
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    Text(
                        text = item.book.author,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    // Meta: Định dạng sách + Lần đọc cuối
                    val lastReadText = when {
                        item.progressPercentage >= 1f -> stringResource(R.string.library_status_completed)
                        item.currentChapter > 0 -> {
                            val elapsedMillis = System.currentTimeMillis() - item.book.lastReadAt
                            val elapsedHours = (elapsedMillis / (1000 * 60 * 60)).toInt()
                            val elapsedDays = (elapsedMillis / (1000 * 60 * 60 * 24)).toInt()
                            when {
                                elapsedHours < 1 -> stringResource(R.string.library_last_read_today)
                                elapsedHours < 24 -> stringResource(R.string.library_last_read_hours_ago, elapsedHours)
                                elapsedDays == 1 -> stringResource(R.string.library_last_read_yesterday)
                                else -> stringResource(R.string.library_last_read_days_ago, elapsedDays)
                            }
                        }
                        else -> stringResource(R.string.library_last_read_not_started)
                    }

                    val sourceFormatLabel = when (item.book.sourceFormat) {
                        com.epubpro.domain.model.BookSourceFormat.EPUB -> R.string.book_source_format_epub
                        com.epubpro.domain.model.BookSourceFormat.PRC -> R.string.book_source_format_prc
                        com.epubpro.domain.model.BookSourceFormat.MOBI -> R.string.book_source_format_mobi
                        com.epubpro.domain.model.BookSourceFormat.AZW3 -> R.string.book_source_format_azw3
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = stringResource(sourceFormatLabel),
                            modifier = Modifier
                                .background(
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    shape = RoundedCornerShape(4.dp)
                                )
                                .padding(horizontal = 4.dp, vertical = 1.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Text(
                            text = "•",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.outline
                        )
                        Text(
                            text = lastReadText,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Phần dưới: Thanh tiến trình + Phần trăm hoàn thành
                Column {
                    val progress = item.progressPercentage.coerceIn(0f, 1f)
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    val progressLabel = when {
                        item.progressPercentage >= 1f -> stringResource(R.string.library_status_completed)
                        item.progressPercentage > 0f -> stringResource(
                            R.string.library_read_percentage_format,
                            (item.progressPercentage * 100).toInt()
                        )
                        else -> stringResource(R.string.library_not_started)
                    }

                    Text(
                        text = progressLabel,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = if (item.progressPercentage > 0f) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
