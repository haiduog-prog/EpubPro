package com.epubpro.feature.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.epubpro.core.designsystem.R

/**
 * Bảng Convert Hán-Việt trong Reader: bật/tắt convert cho sách và quản lý Name riêng của truyện.
 *
 * @param enabled Convert đang bật cho sách.
 * @param hasDictionary Đã nhập từ điển nền (VietPhrase hoặc Phiên âm).
 * @param names Danh sách Name của truyện (chữ Hán, tên tiếng Việt).
 * @param onDismiss Callback đóng bảng.
 * @param onToggle Callback bật/tắt convert.
 * @param onSaveName Callback thêm/sửa Name.
 * @param onDeleteName Callback xóa Name theo chữ Hán.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConvertBottomSheet(
    enabled: Boolean,
    hasDictionary: Boolean,
    names: List<Pair<String, String>>,
    onDismiss: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onSaveName: (String, String) -> Unit,
    onDeleteName: (String) -> Unit
) {
    var source by rememberSaveable { mutableStateOf("") }
    var target by rememberSaveable { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.convert_sheet_title), style = MaterialTheme.typography.titleLarge)
                    Text(
                        text = stringResource(
                            if (hasDictionary) R.string.convert_sheet_subtitle else R.string.convert_no_dictionary
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = enabled, onCheckedChange = onToggle)
            }

            HorizontalDivider()
            Text(stringResource(R.string.convert_names_title), style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = source,
                    onValueChange = { source = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.convert_name_source)) },
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = target,
                    onValueChange = { target = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.convert_name_target)) },
                    modifier = Modifier.weight(1f)
                )
            }
            Button(
                onClick = {
                    onSaveName(source, target)
                    source = ""
                    target = ""
                },
                enabled = source.isNotBlank() && target.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.convert_name_save))
            }

            if (names.isEmpty()) {
                Text(
                    text = stringResource(R.string.convert_names_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 280.dp)) {
                    items(names, key = { it.first }) { (zh, vi) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(R.string.convert_name_entry, zh, vi),
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            IconButton(onClick = { onDeleteName(zh) }) {
                                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.action_delete))
                            }
                        }
                    }
                }
            }
        }
    }
}
