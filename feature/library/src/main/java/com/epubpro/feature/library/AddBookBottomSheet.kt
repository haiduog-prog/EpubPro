package com.epubpro.feature.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.epubpro.core.designsystem.R

/**
 * Cho phép chọn nguồn sách để nhập vào thư viện hoặc gửi lên backend.
 *
 * @param onDismissRequest Đóng bảng chọn nguồn trước khi mở tác vụ khác.
 * @param onNavigateToOnlineLibrary Mở kho truyện online.
 * @param onPickLocalEpub Chọn file từ thiết bị.
 * @param onUploadEpubToServer Chọn file để gửi lên backend.
 * @param onPickDriveBook Mở hướng dẫn chọn truyện từ Google Drive.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddBookBottomSheet(
    onDismissRequest: () -> Unit,
    onNavigateToOnlineLibrary: () -> Unit,
    onPickLocalEpub: () -> Unit,
    onUploadEpubToServer: () -> Unit,
    onPickDriveBook: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(
                text = stringResource(R.string.add_book_source_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 20.dp)
            )

            AddBookOptionItem(
                icon = Icons.Default.CloudDownload,
                title = stringResource(R.string.add_book_online),
                subtitle = stringResource(R.string.add_book_online_desc),
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                onClick = {
                    onDismissRequest()
                    onNavigateToOnlineLibrary()
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            AddBookOptionItem(
                icon = Icons.Default.FolderOpen,
                title = stringResource(R.string.add_book_local),
                subtitle = stringResource(R.string.add_book_local_desc),
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                iconTint = MaterialTheme.colorScheme.onSecondaryContainer,
                onClick = {
                    onDismissRequest()
                    onPickLocalEpub()
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            AddBookOptionItem(
                icon = Icons.Default.CloudDownload,
                title = stringResource(R.string.add_book_drive),
                subtitle = stringResource(R.string.add_book_drive_desc),
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                onClick = {
                    onDismissRequest()
                    onPickDriveBook()
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            AddBookOptionItem(
                icon = Icons.Default.CloudUpload,
                title = stringResource(R.string.add_book_upload),
                subtitle = stringResource(R.string.add_book_upload_desc),
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                iconTint = MaterialTheme.colorScheme.onTertiaryContainer,
                onClick = {
                    onDismissRequest()
                    onUploadEpubToServer()
                }
            )
        }
    }
}

@Composable
private fun AddBookOptionItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    containerColor: androidx.compose.ui.graphics.Color,
    iconTint: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    color = containerColor,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = iconTint,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
