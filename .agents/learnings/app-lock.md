# App Lock (Khoá ứng dụng bằng vân tay / PIN)

> Tổng hợp kiến thức về module `:core:security`: khoá app bằng `BiometricPrompt` (vân tay/khuôn mặt hoặc PIN màn hình khoá), lớp phủ khoá Compose và cài đặt ở tab Cá nhân.
> Cập nhật lần cuối: 2026-11-26

---

## Architecture

### Module riêng `:core:security`, khoá theo vòng đời process
- **Ngày**: 2026-11-26
- **Chi tiết**: Chỉ `:core:security` khai báo `androidx.biometric:biometric:1.1.0` (dạng `api` để module gọi thấy `FragmentActivity`). `AppLockManager` (`@Singleton`) giữ `locked: StateFlow`, khởi tạo `true` khi process mới nếu khoá bật, quan sát `ProcessLifecycleOwner` (`install()` trong `Application.onCreate`) và đo thời gian bằng `SystemClock.elapsedRealtime` để chỉnh giờ hệ thống không mở khoá được. Logic quyết định tách vào `AppLockPolicy` (thuần Kotlin, unit test). Cấu hình lưu SharedPreferences + `StateFlow` (theo convention dự án, đọc đồng bộ để khung hình đầu đã đúng trạng thái khoá). Mặc định: tắt, khoá lại sau 1 phút.
- **Files liên quan**: `core/security/src/main/java/com/epubpro/core/security/AppLockManager.kt`, `core/security/src/main/java/com/epubpro/core/security/AppLockPolicy.kt`, `app/src/main/java/com/epubpro/app/EpubApplication.kt`

### Lớp phủ khoá giữ nguyên nội dung bên dưới
- **Ngày**: 2026-11-26
- **Chi tiết**: `AppLockGate` bọc `AppNavHost` và phủ `AppLockScreen` lên trên khi khoá, thay vì bỏ nội dung khỏi composition (Reader/WebView sẽ phải nạp lại). Nội dung bị `clearAndSetSemantics`, overlay consume mọi pointer event, `BackHandler` gọi `moveTaskToBack`. Intent từ lối tắt/notification vẫn điều hướng phía dưới, xác thực xong là thấy đúng màn hình. `MainActivity` phải kế thừa `FragmentActivity`.
- **Files liên quan**: `core/security/src/main/java/com/epubpro/core/security/AppLockGate.kt`, `app/src/main/java/com/epubpro/app/MainActivity.kt`

---

## Bugs & Solutions

### Màn nhập PIN là Activity riêng làm app "vào nền"
- **Ngày**: 2026-11-26
- **Vấn đề**: Với `DEVICE_CREDENTIAL`, màn nhập PIN của hệ thống làm Activity của app `onStop` → `ProcessLifecycleOwner` phát ON_STOP/ON_START. Nếu tính như rời app thì khoá "ngay" gây vòng lặp khoá; nếu bỏ qua hoàn toàn thì "Tắt khoá → màn PIN → Home → đi lâu → quay lại" lộ nội dung mà không bị khoá.
- **Root cause**: Không phân biệt lần rời app do hộp thoại xác thực với rời app thật.
- **Fix**: Luôn ghi thời điểm vào nền; nếu quay lại khi đang xác thực thì giữ `(backgroundAt, foregroundAt)` và chỉ xét khi xác thực kết thúc: thành công thì bỏ qua, huỷ/lỗi thì khoá nếu rời app ≥ `max(delay, 30s)` (`AppLockPolicy.shouldLockAfterAuthTrip`).
- **Files liên quan**: `core/security/src/main/java/com/epubpro/core/security/AppLockManager.kt`, `core/security/src/main/java/com/epubpro/core/security/AppLockPolicy.kt`

### Phím âm lượng lật trang Reader ngay dưới màn khoá
- **Ngày**: 2026-11-26
- **Vấn đề**: Khi đang khoá, bấm phím âm lượng/bàn phím vẫn lật trang EPUB ở dưới lớp phủ.
- **Root cause**: WebView (trong `AndroidView`) giữ View focus; `AndroidComposeView` chuyển phím thẳng cho View con nên `onPreviewKeyEvent` của Compose không chặn được.
- **Fix**: `MainActivity.dispatchKeyEvent` khi `locked` gọi `event.dispatch(this, window.decorView.keyDispatcherState, this)` để bỏ qua cây View; Back vẫn tới `OnBackPressedDispatcher`, phím âm lượng vẫn do `PhoneWindow` chỉnh âm lượng hệ thống.
- **Files liên quan**: `app/src/main/java/com/epubpro/app/MainActivity.kt`

### Hộp thoại mở khoá không tự hiện lại khi quay về app
- **Ngày**: 2026-11-26
- **Vấn đề**: Rời app khi hộp thoại đang mở (hệ thống tự đóng nó), quay lại chỉ còn màn khoá.
- **Root cause**: `LaunchedEffect(Unit)` chỉ tự mở hộp thoại một lần.
- **Fix**: `AppLockManager.foregroundCount` tăng mỗi lần về foreground (không tính lần quay về từ màn PIN); `LaunchedEffect(foregroundCount)` chờ `RESUMED` rồi mở hộp thoại, có cờ chống mở trùng.
- **Files liên quan**: `core/security/src/main/java/com/epubpro/core/security/AppLockGate.kt`

---

## Patterns

### Không gắn coroutine BiometricPrompt vào vòng đời RESUMED
- **Ngày**: 2026-11-26
- **Chi tiết**: `authenticate()` dùng `suspendCancellableCoroutine` + `invokeOnCancellation { cancelAuthentication() }`. Nếu chạy trong `repeatOnLifecycle(RESUMED)`, màn PIN (Activity riêng) làm app pause → coroutine bị huỷ → đóng luôn màn PIN. Chạy trong scope của composition, chỉ chờ `currentStateFlow.first { RESUMED }` trước khi gọi.
- **Files liên quan**: `core/security/src/main/java/com/epubpro/core/security/AppLockGate.kt`

### Tổ hợp authenticator chạy được từ API 26
- **Ngày**: 2026-11-26
- **Chi tiết**: Dùng `BIOMETRIC_WEAK or DEVICE_CREDENTIAL` (`BIOMETRIC_STRONG or DEVICE_CREDENTIAL` không hỗ trợ trên API 28–29); có `DEVICE_CREDENTIAL` thì không được `setNegativeButtonText`. Máy không đăng ký vân tay/khuôn mặt nhưng có PIN vẫn trả `BIOMETRIC_SUCCESS` và hiện màn PIN (đã kiểm chứng trên Samsung One UI). `screencap` chụp hộp thoại hệ thống ra màu đen; kiểm tra bằng `dumpsys window | grep mCurrentFocus` (`BiometricPromptRoot`).
- **Files liên quan**: `core/security/src/main/java/com/epubpro/core/security/AppLockManager.kt`
