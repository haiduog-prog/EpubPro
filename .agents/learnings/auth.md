# Authentication & Supabase Integration

> Tổng hợp kiến thức về hệ thống xác thực tài khoản, tích hợp Supabase Auth, Dynamic URL routing và quản lý phiên trong dự án EpubPro.
> Cập nhật lần cuối: 2026-11-25

---

## Architecture

### Backend Config kết hợp Supabase Direct Auth
- **Ngày**: 2026-08-24
- **Chi tiết**: Hệ thống áp dụng mô hình 2 tầng: (1) Client gọi `GET /api/auth/config` từ Backend để lấy thông số môi trường động (`supabase_url`, `supabase_publishable_key`, `mode`, `auth_required`); (2) Client gọi trực tiếp các endpoint Supabase Auth (`/auth/v1/token`, `/auth/v1/logout`, `/auth/v1/recover`) bằng Supabase anon key; (3) Access Token (JWT) nhận được từ Supabase sẽ được lưu vào SharedPreferences và tự động gắn vào header `Authorization: Bearer <token>` cho tất cả các API backend nghiệp vụ (`/api/v1/library/...`).
- **Files liên quan**: `core/storage/src/main/java/com/epubpro/core/storage/network/AuthRepositoryImpl.kt`, `core/storage/src/main/java/com/epubpro/core/storage/network/NetworkModule.kt`

### Hai tầng Token Refresh (Proactive Interceptor + Reactive Authenticator)
- **Ngày**: 2026-08-24
- **Chi tiết**: Xử lý vòng đời JWT token (thời hạn 1 giờ của Supabase) bằng 2 tầng phòng thủ: (1) `AuthInterceptor` kiểm tra `expiresAt`, nếu token đã hết hạn hoặc sắp hết hạn (< 60s) thì chủ động gọi API `refresh_token` trước khi gửi request; (2) `okhttp3.Authenticator` bắt mã lỗi HTTP 401 khi backend từ chối, tự động làm mới token và retry request liền mạch (Seamless Retry).
- **Files liên quan**: `core/storage/src/main/java/com/epubpro/core/storage/network/TokenRefreshManager.kt`, `core/storage/src/main/java/com/epubpro/core/storage/network/NetworkModule.kt`

---

### Google Sign-In kết hợp quyền Google Drive tối thiểu
- **Ngày**: 2026-09-04
- **Chi tiết**: Màn hình đăng nhập dùng `rememberLauncherForActivityResult` để mở Google Sign-In, nhận `GoogleSignInAccount` rồi chuyển kết quả vào ViewModel. Flow yêu cầu `email`, `profile` và scope `drive.file`; scope được khai báo tập trung trong `GoogleDriveAuthManager` để đồng nhất với `AccountManager` lấy access token Drive.
- **Files liên quan**: `feature/profile/src/main/java/com/epubpro/feature/profile/auth/LoginScreen.kt`, `feature/profile/src/main/java/com/epubpro/feature/profile/auth/AuthViewModel.kt`, `core/storage/src/main/java/com/epubpro/core/storage/sync/GoogleDriveAuthManager.kt`

## Bugs & Solutions

### Lỗi 401 Unauthorized khi phiên đăng nhập vượt quá 1 giờ
- **Ngày**: 2026-08-24
- **Vấn đề**: Người dùng đăng nhập thành công nhưng sau 1 giờ sử dụng thì mọi request (tải truyện, lấy metadata) đều bị từ chối với mã lỗi HTTP 401.
- **Root cause**: Access Token của Supabase Auth hết hạn sau 3600 giây. Ứng dụng có API `refreshToken` nhưng chưa được gắn vào tầng mạng tự động của OkHttp.
- **Fix**: Xây dựng `TokenRefreshManager` an toàn đa luồng (`synchronized`) kết nối trực tiếp endpoint `{supabase_url}/auth/v1/token?grant_type=refresh_token`, tích hợp vào `authInterceptor` và `tokenAuthenticator` trong `NetworkModule`.
- **Files liên quan**: `core/storage/src/main/java/com/epubpro/core/storage/network/TokenRefreshManager.kt`, `core/storage/src/main/java/com/epubpro/core/storage/network/NetworkModule.kt`

### Lỗi 404 do DynamicBaseUrlInterceptor tự động chèn /v1/ vào /api/auth/config
- **Ngày**: 2026-08-24
- **Vấn đề**: Request `GET /api/auth/config` bị biến đổi thành `GET /api/v1/auth/config` và trả về lỗi HTTP 404.
- **Root cause**: `DynamicBaseUrlInterceptor` bóc tách `api` và ghép nối với pathSegments của base URL mặc định (`["api", "v1"]`).
- **Fix**: Thêm bộ lọc trong interceptor: nếu path bắt đầu bằng `/api/auth/` hoặc có header `SKIP_DYNAMIC_BASE_URL_HEADER`, chỉ tái cấu trúc scheme/host/port và giữ nguyên toàn bộ path gốc.
- **Files liên quan**: `core/storage/src/main/java/com/epubpro/core/storage/network/DynamicBaseUrlInterceptor.kt`, `core/storage/src/main/java/com/epubpro/core/storage/network/AuthApiService.kt`

### Lỗi 401 Unauthorized do Fallback ngầm tài khoản Local Dev
- **Ngày**: 2026-08-24
- **Vấn đề**: Người dùng nhập tài khoản thật nhưng app tự nhận diện là "Thành viên Local Dev", gửi token giả `local_jwt_...` lên backend và bị từ chối 401 khi truy cập thư viện sách.
- **Root cause**: Khi `fetchAuthConfig()` bị lỗi 404, logic cũ rơi vào nhánh fallback ngầm tạo tài khoản offline thay vì báo lỗi.
- **Fix**: Loại bỏ hoàn toàn fallback ngầm. Nếu không lấy được cấu hình hoặc Supabase từ chối (sai mật khẩu, lỗi mạng), ném `Result.failure` và hiển thị thông báo lỗi cụ thể lên UI.
- **Files liên quan**: `core/storage/src/main/java/com/epubpro/core/storage/network/AuthRepositoryImpl.kt`

---

### Google Sign-In chưa thể xác thực production nếu thiếu OAuth client ID
- **Ngày**: 2026-09-04
- **Vấn đề**: Có thể mở account chooser nhưng không nhận được ID token/backend session hợp lệ khi project chưa có OAuth Web Client ID và `google-services.json`.
- **Root cause**: `GoogleSignInOptions` chỉ có thể gọi `requestIdToken` khi đã có client ID hợp lệ; repository hiện còn fallback token cục bộ cho Google.
- **Fix**: Đã nối UI, ViewModel và scope Drive tối thiểu; cần bổ sung OAuth client ID, SHA-1/SHA-256 đúng application ID và endpoint exchange ID token trước khi bật production.
- **Files liên quan**: `feature/profile/src/main/java/com/epubpro/feature/profile/auth/LoginScreen.kt`, `core/storage/src/main/java/com/epubpro/core/storage/network/AuthRepositoryImpl.kt`

### Token "sống lại" sau khi đăng xuất hoặc đổi tài khoản
- **Ngày**: 2026-11-25
- **Vấn đề**: Đăng xuất đúng lúc `TokenRefreshManager` đang làm mới token thì token cũ được ghi lại vào prefs; interceptor tiếp tục gắn Bearer của người dùng cũ.
- **Root cause**: `saveTokens()` ghi thẳng `KEY_AUTH_TOKEN`/`KEY_REFRESH_TOKEN` từ luồng OkHttp, không đồng bộ với `clearSession()` và không kiểm tra phiên còn đúng.
- **Fix**: Mọi thao tác phiên trong `AuthPreferencesManager` dùng chung `sessionLock`; `saveTokens()` bỏ qua khi đã đăng xuất; kết quả refresh ghi qua `replaceTokensIfCurrent(expectedRefreshToken, ...)`, chỉ nhận nếu phiên vẫn dùng đúng refresh token đã gửi.
- **Files liên quan**: `core/storage/src/main/java/com/epubpro/core/storage/AuthPreferencesManager.kt`, `core/storage/src/main/java/com/epubpro/core/storage/network/TokenRefreshManager.kt`

### Email và tiền tố token lọt vào Logcat bản release
- **Ngày**: 2026-11-25
- **Vấn đề**: Log đăng nhập ghi email, interceptor ghi `token.take(8)` và đường dẫn mọi request bằng `Log.d` ở cả release.
- **Fix**: Bỏ email/token khỏi message; log chẩn đoán xác thực chỉ ghi khi `BuildConfig.DEBUG`.
- **Files liên quan**: `core/storage/src/main/java/com/epubpro/core/storage/network/AuthRepositoryImpl.kt`, `core/storage/src/main/java/com/epubpro/core/storage/network/NetworkModule.kt`

## How-To

### Quy trình tích hợp và kiểm tra luồng xác thực Supabase
- **Ngày**: 2026-08-24
- **Bước thực hiện**:
  1. Lấy root URL từ `ServerPreferencesManager.getBaseUrl()` và tạo endpoint tuyệt đối `$rootUrl/api/auth/config`.
  2. Định nghĩa Retrofit interface với `@GET suspend fun getAuthConfigFromUrl(@Url url: String, @Header(...) skipDynamic: String)` để tránh bị rewrite URL.
  3. Gửi payload đăng nhập tới `{supabase_url}/auth/v1/token?grant_type=password` kèm header `apikey: {supabase_publishable_key}`.
  4. Lưu `accessToken`, `refreshToken` và `expiresAt` vào `AuthPreferencesManager`.
  5. Đăng ký `TokenRefreshManager` trong `NetworkModule` để chủ động làm mới token và retry tự động khi gặp 401.
- **Files liên quan**: `core/storage/src/main/java/com/epubpro/core/storage/network/AuthApiService.kt`, `core/storage/src/main/java/com/epubpro/core/storage/network/NetworkModule.kt`, `core/storage/src/main/java/com/epubpro/core/storage/AuthPreferencesManager.kt`, `core/storage/src/main/java/com/epubpro/core/storage/network/TokenRefreshManager.kt`

---

### Quy trình thêm Google Sign-In vào Compose
- **Ngày**: 2026-09-04
- **Bước thực hiện**:
  1. Thêm `play-services-auth` qua version catalog.
  2. Tạo `GoogleSignInOptions` với `requestEmail`, `requestProfile` và scope cần thiết.
  3. Dùng `rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult())`.
  4. Bỏ qua cancel; với kết quả thành công, đọc account qua `ApiException` và gọi ViewModel.
  5. Hiển thị lỗi bằng string resource, không hardcode trong Compose.
  6. Chạy unit test ViewModel, assemble APK và cài lên thiết bị có Google Play Services.
- **Files liên quan**: `feature/profile/src/main/java/com/epubpro/feature/profile/auth/LoginScreen.kt`, `feature/profile/src/test/java/com/epubpro/feature/profile/auth/AuthViewModelTest.kt`

## Patterns

### Không lưu OAuth token Drive vào DataStore
- **Ngày**: 2026-09-04
- **Chi tiết**: `GoogleDriveAuthManager` chỉ lấy access token ngắn hạn từ `AccountManager` trong lúc gọi Drive, không ghi token vào DataStore, manifest hoặc log. Khi API trả 401, token cần được invalidate và phiên sync chuyển sang `AUTH_REQUIRED`.
- **Files liên quan**: `core/storage/src/main/java/com/epubpro/core/storage/sync/GoogleDriveAuthManager.kt`, `core/storage/src/main/java/com/epubpro/core/storage/sync/GoogleDriveDataSource.kt`
### Safe Logging trong Android Module thuần JVM Unit Tests
- **Ngày**: 2026-08-24
- **Chi tiết**: Trong module Android library (như `:core:storage`), gọi trực tiếp `android.util.Log` trong các class được test bằng JUnit 4 thuần JVM sẽ ném `RuntimeException: Method d in android.util.Log not mocked`.
- **Ví dụ code**:
  ```kotlin
  private fun logDebug(tag: String, msg: String) {
      try {
          android.util.Log.d(tag, msg)
      } catch (_: Throwable) {
          println("[$tag] $msg")
      }
  }
  ```
- **Files liên quan**: `core/storage/src/main/java/com/epubpro/core/storage/network/AuthRepositoryImpl.kt`

### Bí mật dùng Keystore, phiên đăng nhập không vào bản sao lưu
- **Ngày**: 2026-11-25
- **Chi tiết**: API key/client key lưu qua `KeystoreSecretStore` (AES-GCM, khóa trong Android Keystore, file trong `noBackupFilesDir`); lần đọc đầu chuyển giá trị thô cũ trong SharedPreferences sang kho mã hóa rồi xóa bản thô (chỉ xóa khi mã hóa thành công). `epubpro_auth_prefs` và `server_settings_prefs` bị loại khỏi sao lưu qua `data_extraction_rules.xml` (API 31+) và `backup_rules.xml`. Khi thêm file prefs chứa token/key mới phải bổ sung vào cả hai file rule.
- **Files liên quan**: `core/storage/src/main/java/com/epubpro/core/storage/KeystoreSecretStore.kt`, `core/storage/src/main/java/com/epubpro/core/storage/ServerPreferencesManager.kt`, `app/src/main/res/xml/data_extraction_rules.xml`

### Base URL thiếu scheme mặc định HTTPS
- **Ngày**: 2026-11-25
- **Chi tiết**: `normalizeServerBaseUrl()` thêm `https://` khi người dùng không nhập scheme; chỉ host cục bộ/LAN (`localhost`, `*.local`, `127/8`, `10/8` gồm `10.0.2.2`, `172.16/12`, `192.168/16`, `169.254/16`) mới dùng `http://`, tránh gửi Bearer token và API key dạng thô.
- **Files liên quan**: `core/storage/src/main/java/com/epubpro/core/storage/ServerPreferencesManager.kt`
