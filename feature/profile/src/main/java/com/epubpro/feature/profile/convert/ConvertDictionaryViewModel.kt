package com.epubpro.feature.profile.convert

import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.epubpro.core.designsystem.R
import com.epubpro.core.storage.ConvertDictionaryInfo
import com.epubpro.core.storage.ConvertDictionaryKind
import com.epubpro.core.storage.ConvertDictionaryStore
import com.epubpro.core.storage.DictionaryTooLargeException
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Trạng thái màn hình quản lý từ điển Convert.
 *
 * @property dictionaries Thông tin từng loại từ điển.
 * @property importingKind Loại đang nhập, null nếu rảnh.
 * @property importedLines Số dòng đã đọc của lần nhập hiện tại.
 * @property messageRes Thông báo kết quả gần nhất.
 * @property messageArg Tham số số mục cho thông báo thành công.
 */
data class ConvertDictionaryUiState(
    val dictionaries: List<ConvertDictionaryInfo> = emptyList(),
    val importingKind: ConvertDictionaryKind? = null,
    val importedLines: Int = 0,
    @StringRes val messageRes: Int? = null,
    val messageArg: Int = 0
)

/**
 * ViewModel nhập/xóa từ điển Convert Hán-Việt.
 */
@HiltViewModel
class ConvertDictionaryViewModel @Inject constructor(
    private val store: ConvertDictionaryStore
) : ViewModel() {
    private val _uiState = MutableStateFlow(ConvertDictionaryUiState())
    val uiState: StateFlow<ConvertDictionaryUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    /**
     * Nhập file từ điển người dùng chọn.
     *
     * @param kind Loại từ điển.
     * @param uri URI file văn bản.
     */
    fun import(kind: ConvertDictionaryKind, uri: Uri) {
        if (_uiState.value.importingKind != null) return
        _uiState.update { it.copy(importingKind = kind, importedLines = 0, messageRes = null) }
        viewModelScope.launch {
            try {
                val count = store.importDictionary(kind, uri) { lines ->
                    _uiState.update { it.copy(importedLines = lines) }
                }
                _uiState.update { it.copy(messageRes = R.string.convert_import_success, messageArg = count) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: DictionaryTooLargeException) {
                _uiState.update {
                    it.copy(
                        messageRes = R.string.convert_import_too_large,
                        messageArg = (error.limitBytes / (1024 * 1024)).toInt()
                    )
                }
            } catch (_: Exception) {
                _uiState.update { it.copy(messageRes = R.string.convert_import_failed) }
            } finally {
                _uiState.update { it.copy(importingKind = null) }
                refresh()
            }
        }
    }

    /**
     * Xóa một từ điển.
     *
     * @param kind Loại từ điển.
     */
    fun delete(kind: ConvertDictionaryKind) {
        viewModelScope.launch {
            store.deleteDictionary(kind)
            refresh()
        }
    }

    /** Xóa thông báo đã hiển thị. */
    fun consumeMessage() {
        _uiState.update { it.copy(messageRes = null) }
    }

    /** Đọc lại trạng thái các từ điển. */
    private fun refresh() {
        viewModelScope.launch {
            val info = store.dictionaryInfo()
            _uiState.update { it.copy(dictionaries = info) }
        }
    }
}
