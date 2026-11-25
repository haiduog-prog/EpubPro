package com.epubpro.core.storage

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Chuẩn hóa địa chỉ Base URL do người dùng nhập: bỏ khoảng trắng, thêm scheme nếu thiếu và đảm bảo kết thúc
 * bằng `/`. Khi thiếu scheme, mặc định `https://` để Bearer token và API key không bị gửi dạng thô qua mạng;
 * chỉ địa chỉ máy cục bộ/mạng LAN (server phát triển) mới mặc định `http://`.
 *
 * @param url Chuỗi URL người dùng nhập hoặc chọn từ preset.
 * @return URL đầy đủ scheme, kết thúc bằng `/`; chuỗi rỗng trả về [ServerPreferencesManager.DEFAULT_BASE_URL].
 */
internal fun normalizeServerBaseUrl(url: String): String {
    var clean = url.trim()
    if (clean.isBlank()) {
        clean = ServerPreferencesManager.DEFAULT_BASE_URL
    }
    val hasScheme = clean.startsWith("http://", ignoreCase = true) ||
        clean.startsWith("https://", ignoreCase = true)
    if (!hasScheme) {
        val scheme = if (isLocalDevelopmentHost(clean)) "http" else "https"
        clean = "$scheme://$clean"
    }
    if (!clean.endsWith("/")) {
        clean = "$clean/"
    }
    return clean
}

/**
 * Kiểm tra phần host của địa chỉ (chưa có scheme) có phải máy cục bộ hoặc mạng nội bộ hay không: `localhost`,
 * tên miền `.local`/`.localhost`, loopback `127.0.0.0/8`, IPv6 `::1` và các dải IPv4 private
 * (`10.0.0.0/8` gồm `10.0.2.2` của emulator, `172.16.0.0/12`, `192.168.0.0/16`, link-local `169.254.0.0/16`).
 *
 * @param addressWithoutScheme Địa chỉ dạng `host[:port][/path]` chưa có scheme.
 * @return `true` nếu là host phát triển cục bộ/LAN, nơi thường không có chứng chỉ HTTPS.
 */
internal fun isLocalDevelopmentHost(addressWithoutScheme: String): Boolean {
    val authority = addressWithoutScheme.substringBefore('/').substringAfterLast('@')
    val host = if (authority.startsWith("[")) {
        authority.substringAfter('[').substringBefore(']')
    } else {
        authority.substringBefore(':')
    }.lowercase()
    if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local") || host == "::1") {
        return true
    }
    val octets = host.split('.').map { it.toIntOrNull() ?: return false }
    if (octets.size != 4 || octets.any { it !in 0..255 }) return false
    val (first, second) = octets
    return first == 127 ||
        first == 10 ||
        (first == 172 && second in 16..31) ||
        (first == 192 && second == 168) ||
        (first == 169 && second == 254)
}

/**
 * Trình quản lý lưu trữ cài đặt cấu hình địa chỉ Server Backend vào SharedPreferences.
 *
 * Cho phép người dùng chuyển đổi địa chỉ máy chủ API linh hoạt giữa các môi trường:
 * Cloud Server (Render), Máy ảo (10.0.2.2), Localhost (127.0.0.1) hoặc IP mạng LAN.
 */
@Singleton
class ServerPreferencesManager @Inject constructor(
    @ApplicationContext private val appContext: Context
) {
    private val preferences =
        appContext.getSharedPreferences("server_settings_prefs", Context.MODE_PRIVATE)

    private val _baseUrl = MutableStateFlow(readBaseUrl())
    val baseUrlFlow: StateFlow<String> = _baseUrl.asStateFlow()

    /**
     * Lấy giá trị Base URL hiện tại đang được cấu hình.
     *
     * @return Chuỗi URL API đang hoạt động (ví dụ: `https://epubbackend.onrender.com/api/v1/`).
     */
    fun getBaseUrl(): String = _baseUrl.value

    /**
     * Lưu trữ và phát ra địa chỉ Base URL mới cho toàn ứng dụng.
     *
     * @param url Chuỗi URL mới do người dùng nhập hoặc chọn từ preset.
     */
    fun saveBaseUrl(url: String) {
        val normalized = normalizeUrl(url)
        preferences.edit().putString(KEY_BASE_URL, normalized).apply()
        _baseUrl.value = normalized
    }

    /**
     * Đọc giá trị Base URL đã lưu từ bộ nhớ SharedPreferences, tự động làm sạch các URL cũ không còn hợp lệ.
     *
     * @return Chuỗi Base URL đã được chuẩn hóa.
     */
    private fun readBaseUrl(): String {
        val stored = preferences.getString(KEY_BASE_URL, null)
        if (stored == null || stored.contains("r2.dev") || stored.contains("trycloudflare.com") || stored.contains("workers.dev")) {
            preferences.edit().putString(KEY_BASE_URL, DEFAULT_BASE_URL).apply()
            return DEFAULT_BASE_URL
        }
        return normalizeUrl(stored)
    }

    /**
     * Chuẩn hóa định dạng chuỗi URL: loại bỏ khoảng trắng, bổ sung `http://` nếu thiếu và đảm bảo kết thúc bằng dấu gạch chéo `/`.
     *
     * @param url Chuỗi URL đầu vào cần chuẩn hóa.
     * @return Chuỗi URL hoàn chỉnh và hợp lệ.
     */
    internal fun normalizeUrl(url: String): String = normalizeServerBaseUrl(url)

    // Hai bí mật được mã hóa bằng Android Keystore thay vì lưu thô trong SharedPreferences (vốn nằm trong bản
    // sao lưu). Khởi tạo lười để class vẫn dựng được trong unit test JVM.
    private val clientKeyStore by lazy {
        KeystoreSecretStore(appContext, CLIENT_KEY_FILE_NAME, CLIENT_KEY_ALIAS)
    }
    private val llmApiKeyStore by lazy {
        KeystoreSecretStore(appContext, LLM_API_KEY_FILE_NAME, LLM_API_KEY_ALIAS)
    }
    private val secretLock = Any()
    private val secretCache = HashMap<String, String?>()

    /**
     * Lấy khóa xác thực Book Bible Client Key nếu có.
     *
     * @return Chuỗi token hoặc null nếu chưa thiết lập.
     */
    fun getBookBibleClientKey(): String? = readSecret(KEY_BOOK_BIBLE_CLIENT_KEY, clientKeyStore)

    /**
     * Lưu trữ khóa xác thực Book Bible Client Key (mã hóa bằng Android Keystore).
     *
     * @param key Khóa token do quản trị viên cung cấp hoặc null để xóa.
     */
    fun saveBookBibleClientKey(key: String?) {
        writeSecret(KEY_BOOK_BIBLE_CLIENT_KEY, clientKeyStore, key)
    }

    /**
     * Lấy khóa LLM API Key (Gemini/Claude) cá nhân nếu có.
     *
     * @return Chuỗi API Key hoặc null.
     */
    fun getLlmApiKey(): String? = readSecret(KEY_LLM_API_KEY, llmApiKeyStore)

    /**
     * Lưu trữ khóa LLM API Key cá nhân (mã hóa bằng Android Keystore).
     *
     * @param key Chuỗi API Key hoặc null để xóa.
     */
    fun saveLlmApiKey(key: String?) {
        writeSecret(KEY_LLM_API_KEY, llmApiKeyStore, key)
    }

    /**
     * Đọc bí mật từ bộ nhớ đệm hoặc từ [store]. Lần đầu, nếu còn giá trị thô từ phiên bản cũ trong
     * SharedPreferences thì chuyển sang [store] rồi xóa bản thô. Được gọi từ interceptor OkHttp cho mỗi request
     * Book Bible nên kết quả được giữ trong bộ nhớ để không giải mã lặp lại.
     *
     * @param legacyKey Khóa SharedPreferences từng lưu bí mật dạng thô.
     * @param store Kho bí mật mã hóa tương ứng.
     * @return Bí mật, hoặc null nếu chưa thiết lập.
     */
    private fun readSecret(legacyKey: String, store: KeystoreSecretStore): String? = synchronized(secretLock) {
        if (secretCache.containsKey(legacyKey)) return@synchronized secretCache[legacyKey]
        val legacy = preferences.getString(legacyKey, null)?.trim()?.takeIf { it.isNotBlank() }
        val value = if (legacy != null) {
            // Chỉ xóa bản thô khi đã mã hóa thành công, tránh mất key nếu Keystore lỗi.
            runCatching { store.save(legacy) }
                .onSuccess { preferences.edit().remove(legacyKey).commit() }
            legacy
        } else {
            store.load()
        }
        secretCache[legacyKey] = value
        value
    }

    /**
     * Ghi hoặc xóa bí mật trong [store], đồng thời xóa mọi bản thô cũ còn sót trong SharedPreferences.
     *
     * @param legacyKey Khóa SharedPreferences từng lưu bí mật dạng thô.
     * @param store Kho bí mật mã hóa tương ứng.
     * @param secret Giá trị mới; null hoặc rỗng để xóa.
     */
    private fun writeSecret(legacyKey: String, store: KeystoreSecretStore, secret: String?) = synchronized(secretLock) {
        val value = secret?.trim()?.takeIf { it.isNotBlank() }
        if (value == null) store.clear() else store.save(value)
        preferences.edit().remove(legacyKey).apply()
        secretCache[legacyKey] = value
    }

    /**
     * Lấy tên model LLM tùy chỉnh.
     *
     * @return Tên model (ví dụ: "gemini-2.5-flash") hoặc null nếu dùng mặc định.
     */
    fun getLlmModel(): String? = preferences.getString(KEY_LLM_MODEL, null)?.takeIf { it.isNotBlank() }

    /**
     * Lưu trữ tên model LLM tùy chỉnh.
     *
     * @param model Tên model hoặc null để dùng mặc định của backend.
     */
    fun saveLlmModel(model: String?) {
        preferences.edit().putString(KEY_LLM_MODEL, model?.trim()).apply()
    }

    companion object {
        /** Địa chỉ máy chủ Cloud Production mặc định */
        const val DEFAULT_BASE_URL = "https://epubbackend.onrender.com/api/v1/"

        /** Preset cấu hình cho Cloud Server (Render) */
        const val PRESET_RENDER = "https://epubbackend.onrender.com/api/v1/"

        /** Preset cấu hình cho Android Emulator trỏ về máy tính host */
        const val PRESET_EMULATOR = "http://10.0.2.2:8000/api/v1/"

        /** Preset cấu hình cho Localhost / adb reverse */
        const val PRESET_LOCALHOST = "http://127.0.0.1:8000/api/v1/"

        private const val KEY_BASE_URL = "server_base_url"
        private const val KEY_BOOK_BIBLE_CLIENT_KEY = "book_bible_client_key"
        private const val KEY_LLM_API_KEY = "book_bible_llm_api_key"
        private const val KEY_LLM_MODEL = "book_bible_llm_model"
        private const val CLIENT_KEY_FILE_NAME = "book_bible_client_key"
        private const val CLIENT_KEY_ALIAS = "epubpro_book_bible_client_key"
        private const val LLM_API_KEY_FILE_NAME = "book_bible_llm_api_key"
        private const val LLM_API_KEY_ALIAS = "epubpro_book_bible_llm_api_key"
    }
}
