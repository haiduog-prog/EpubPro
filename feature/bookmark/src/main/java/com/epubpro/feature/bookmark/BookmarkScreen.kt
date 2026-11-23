package com.epubpro.feature.bookmark

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.epubpro.core.designsystem.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarkScreen(
    onNavigateBack: () -> Unit,
    onJumpToCfi: (chapterIndex: Int, cfi: String) -> Unit,
    onJumpToBookChapter: ((bookId: String, chapterIndex: Int, cfi: String) -> Unit)? = null,
    viewModel: BookmarkViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.bookmark_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            TabRow(selectedTabIndex = uiState.selectedTab) {
                Tab(
                    selected = uiState.selectedTab == 0,
                    onClick = { viewModel.onTabSelected(0) },
                    text = { Text(stringResource(R.string.bookmark_tab_format, uiState.bookmarks.size)) }
                )
                Tab(
                    selected = uiState.selectedTab == 1,
                    onClick = { viewModel.onTabSelected(1) },
                    text = { Text(stringResource(R.string.highlight_tab_format, uiState.highlights.size)) }
                )
            }

            when (uiState.selectedTab) {
                0 -> {
                    if (uiState.bookmarks.isEmpty()) {
                        EmptyPlaceholder(stringResource(R.string.bookmark_empty))
                    } else {
                        LazyColumn(contentPadding = PaddingValues(16.dp)) {
                            items(uiState.bookmarks, key = { it.id }) { bm ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                        .clickable {
                                            if (onJumpToBookChapter != null) {
                                                onJumpToBookChapter(bm.bookId, bm.chapterIndex, bm.cfi)
                                            } else {
                                                onJumpToCfi(bm.chapterIndex, bm.cfi)
                                            }
                                        },
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(bm.chapterTitle, fontWeight = FontWeight.Bold)
                                            Text(stringResource(R.string.bookmark_cfi_format, bm.cfi), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        IconButton(onClick = { viewModel.deleteBookmark(bm.id) }) {
                                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.action_delete), tint = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                1 -> {
                    if (uiState.highlights.isEmpty()) {
                        EmptyPlaceholder(stringResource(R.string.highlight_empty))
                    } else {
                        LazyColumn(contentPadding = PaddingValues(16.dp)) {
                            items(uiState.highlights, key = { it.id }) { hl ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                        .clickable { onJumpToCfi(hl.chapterIndex, hl.startCfi) },
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Column(modifier = Modifier.padding(16.dp)) {
                                        Text(
                                            "\"${hl.selectedText}\"",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium
                                        )
                                        val note = hl.note
                                        if (!note.isNullOrBlank()) {
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(stringResource(R.string.highlight_note_format, note), fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                                        }
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(top = 8.dp),
                                            horizontalArrangement = Arrangement.End
                                        ) {
                                            IconButton(onClick = { viewModel.deleteHighlight(hl.id) }) {
                                                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.action_delete), tint = MaterialTheme.colorScheme.error)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EmptyPlaceholder(message: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
