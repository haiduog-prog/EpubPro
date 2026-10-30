package com.epubpro.feature.profile.convert

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.epubpro.core.designsystem.R
import com.epubpro.core.storage.ConvertDictionaryInfo
import com.epubpro.core.storage.ConvertDictionaryKind

/**
 * Màn hình nhập/xóa từ điển Convert Hán-Việt (định dạng QuickTranslator).
 *
 * @param onNavigateBack Callback quay lại.
 * @param viewModel ViewModel quản lý từ điển.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConvertDictionaryScreen(
    onNavigateBack: () -> Unit,
    viewModel: ConvertDictionaryViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var pendingKind by remember { mutableStateOf<ConvertDictionaryKind?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val kind = pendingKind
        pendingKind = null
        if (uri != null && kind != null) viewModel.import(kind, uri)
    }

    LaunchedEffect(uiState.messageRes) {
        uiState.messageRes?.let { messageRes ->
            snackbarHostState.showSnackbar(context.getString(messageRes, uiState.messageArg))
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.convert_dictionaries_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.convert_dictionaries_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            uiState.dictionaries.forEach { info ->
                DictionaryCard(
                    info = info,
                    importing = uiState.importingKind == info.kind,
                    importedLines = uiState.importedLines,
                    busy = uiState.importingKind != null,
                    onImport = {
                        pendingKind = info.kind
                        picker.launch(arrayOf("text/*", "application/octet-stream"))
                    },
                    onDelete = { viewModel.delete(info.kind) }
                )
            }
        }
    }
}

/**
 * Thẻ thông tin và thao tác cho một loại từ điển.
 *
 * @param info Thông tin từ điển.
 * @param importing Loại này đang được nhập.
 * @param importedLines Số dòng đã đọc khi đang nhập.
 * @param busy Có tác vụ nhập khác đang chạy.
 * @param onImport Callback chọn file nhập.
 * @param onDelete Callback xóa từ điển.
 */
@Composable
private fun DictionaryCard(
    info: ConvertDictionaryInfo,
    importing: Boolean,
    importedLines: Int,
    busy: Boolean,
    onImport: () -> Unit,
    onDelete: () -> Unit
) {
    val (titleRes, fileRes) = when (info.kind) {
        ConvertDictionaryKind.NAMES -> R.string.convert_dictionary_names to R.string.convert_dictionary_names_file
        ConvertDictionaryKind.VIET_PHRASE -> R.string.convert_dictionary_vietphrase to R.string.convert_dictionary_vietphrase_file
        ConvertDictionaryKind.PHIEN_AM -> R.string.convert_dictionary_phienam to R.string.convert_dictionary_phienam_file
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(titleRes), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(fileRes), style = MaterialTheme.typography.bodySmall)
            Text(
                text = when {
                    importing -> stringResource(R.string.convert_dictionary_importing, importedLines)
                    else -> info.entryCount?.let { stringResource(R.string.convert_dictionary_entries, it) }
                        ?: stringResource(R.string.convert_dictionary_missing)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (importing) CircularProgressIndicator(modifier = Modifier.padding(4.dp))
                OutlinedButton(onClick = onImport, enabled = !busy) {
                    Text(stringResource(R.string.convert_dictionary_import))
                }
                if (info.entryCount != null) {
                    TextButton(onClick = onDelete, enabled = !busy) {
                        Text(stringResource(R.string.action_delete))
                    }
                }
            }
        }
    }
}
