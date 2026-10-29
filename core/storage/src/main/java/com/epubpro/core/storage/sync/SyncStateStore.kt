package com.epubpro.core.storage.sync

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.epubpro.domain.sync.SyncManifest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Dữ liệu baseline và cấu hình Drive không chứa access token. */
data class PersistedSyncState(
    val driveFolderId: String? = null,
    val baseline: SyncManifest? = null,
    val lastSyncAt: Long? = null,
    val lastSyncId: String? = null
)

/**
 * Lưu state sync bằng DataStore để tránh đưa token hoặc trạng thái phiên lên Drive.
 */
@Singleton
class SyncStateStore @Inject constructor(
    @ApplicationContext context: Context,
    private val manifestCodec: ManifestJsonCodec,
    private val gson: Gson
) {
    private val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create {
        context.preferencesDataStoreFile(FILE_NAME)
    }

    /** Flow state bền vững dùng cho coordinator. */
    val state: Flow<PersistedSyncState> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(androidx.datastore.preferences.core.emptyPreferences())
            else throw error
        }
        .map { preferences -> preferences.toDomain() }

    /**
     * Đọc state hiện tại một lần.
     *
     * @return State đã lưu hoặc state mặc định.
     */
    suspend fun read(): PersistedSyncState = state.first()

    /**
     * Lưu ID folder Drive đã được người dùng chọn hoặc app tạo.
     *
     * @param folderId ID folder Drive, null để xóa liên kết.
     */
    suspend fun saveDriveFolderId(folderId: String?) {
        dataStore.edit { preferences ->
            if (folderId.isNullOrBlank()) preferences.remove(KEY_FOLDER_ID)
            else preferences[KEY_FOLDER_ID] = folderId
        }
    }

    /**
     * Lấy hoặc tạo UUID bền vững dùng làm chủ sở hữu ledger của thiết bị này.
     *
     * @return UUID ổn định qua các lần mở app.
     */
    suspend fun getOrCreateReadingHistoryDeviceId(): String {
        val preferences = dataStore.edit { values ->
            if (values[KEY_READING_HISTORY_DEVICE_ID].isNullOrBlank()) {
                values[KEY_READING_HISTORY_DEVICE_ID] = UUID.randomUUID().toString()
            }
        }
        return checkNotNull(preferences[KEY_READING_HISTORY_DEVICE_ID])
    }

    /**
     * Đọc cache fingerprint sách local để tránh băm lại file chưa thay đổi.
     *
     * @return Cache ánh xạ book ID nội bộ sang fingerprint và metadata file.
     */
    suspend fun readBookFingerprintCache(): Map<String, BookFingerprintCache> {
        val json = dataStore.data.first()[KEY_BOOK_FINGERPRINT_CACHE] ?: return emptyMap()
        return runCatching {
            gson.fromJson<Map<String, BookFingerprintCache>>(json, FINGERPRINT_MAP_TYPE).orEmpty()
        }.getOrDefault(emptyMap())
    }

    /**
     * Ghi cache fingerprint sau khi đã tính SHA-256 của file sách.
     *
     * @param cache Cache mới, bao gồm các fingerprint còn dùng được.
     */
    suspend fun saveBookFingerprintCache(cache: Map<String, BookFingerprintCache>) {
        dataStore.edit { values ->
            values[KEY_BOOK_FINGERPRINT_CACHE] = gson.toJson(cache)
        }
    }

    /**
     * Lưu baseline sau khi manifest đã được xác nhận thành công.
     *
     * @param manifest Manifest đã sync xong.
     * @param syncId ID phiên sync.
     * @param timestamp Thời điểm hoàn tất theo epoch milliseconds.
     */
    suspend fun saveBaseline(manifest: SyncManifest, syncId: String, timestamp: Long) {
        dataStore.edit { preferences ->
            preferences[KEY_BASELINE] = manifestCodec.encode(manifest)
            preferences[KEY_LAST_SYNC_ID] = syncId
            preferences[KEY_LAST_SYNC_AT] = timestamp
        }
    }

    private fun Preferences.toDomain() = PersistedSyncState(
        driveFolderId = this[KEY_FOLDER_ID],
        baseline = this[KEY_BASELINE]?.let { runCatching { manifestCodec.decode(it) }.getOrNull() },
        lastSyncAt = this[KEY_LAST_SYNC_AT],
        lastSyncId = this[KEY_LAST_SYNC_ID]
    )

    companion object {
        private const val FILE_NAME = "epub_sync_state"
        private val KEY_FOLDER_ID = stringPreferencesKey("drive_folder_id")
        private val KEY_BASELINE = stringPreferencesKey("manifest_baseline")
        private val KEY_LAST_SYNC_ID = stringPreferencesKey("last_sync_id")
        private val KEY_LAST_SYNC_AT = longPreferencesKey("last_sync_at")
        private val KEY_READING_HISTORY_DEVICE_ID = stringPreferencesKey("reading_history_device_id")
        private val KEY_BOOK_FINGERPRINT_CACHE = stringPreferencesKey("reading_history_fingerprints")
        private val FINGERPRINT_MAP_TYPE = object : TypeToken<Map<String, BookFingerprintCache>>() {}.type
    }
}

/**
 * Metadata local dùng để kiểm tra cache SHA-256 của một quyển sách.
 *
 * @property filePath Đường dẫn canonical lúc tính fingerprint.
 * @property fileLength Kích thước file tại thời điểm băm.
 * @property lastModified Thời gian sửa file tại thời điểm băm.
 * @property sha256 SHA-256 nội dung file.
 */
data class BookFingerprintCache(
    val filePath: String,
    val fileLength: Long,
    val lastModified: Long,
    val sha256: String
)
