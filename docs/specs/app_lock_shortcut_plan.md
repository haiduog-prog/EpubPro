# Kế hoạch: Lối tắt sách ra màn hình chính và khoá app bằng vân tay

## Mục tiêu

Hai tính năng tham khảo từ Moon+ Reader Pro, phục vụ dùng cá nhân:

| # | Tính năng | Công sức | Rủi ro regression | Dependency mới |
|---|---|---|---|---|
| A | Lối tắt sách ra màn hình chính (pinned + dynamic shortcut) | 1–2 ngày | Thấp | Không |
| B | Khoá app bằng vân tay / PIN máy, có công tắc bật/tắt, nằm trong module riêng `:core:security` | 2–3 ngày | Trung bình (chạm `MainActivity`) | `androidx.biometric:biometric:1.1.0` (chỉ trong `:core:security`) |

Thứ tự: **Phase 0 → A → B**. Phase 0 chỉ thêm cửa vào nội bộ mang `bookId` cho shortcut (Reader đã tự mở theo tiến độ). B làm sau vì chạm vòng đời `MainActivity`.

Ngoài phạm vi: widget kệ sách (bỏ, chưa ưu tiên), ẩn nội dung widget/notification khi khoá (để sau).

## Tham khảo mã nguồn mở

- **Mihon** (`mihonapp/mihon`, Apache-2.0), `SecureActivityDelegate.kt`: khoá theo vòng đời process, cờ `requireUnlock` mặc định `true` khi process mới, tuỳ chọn khoá sau N phút, tuỳ chọn `FLAG_SECURE`, tự tắt khoá khi máy không còn hỗ trợ xác thực. EpubPro lấy ý tưởng, không chép code.
- Khác Mihon: Mihon mở `UnlockActivity` riêng; EpubPro chỉ có một Activity Compose nên dùng **lớp phủ Compose trong `MainActivity`**, không thêm Activity mới (bớt một cửa vào phải bảo mật).

## Hiện trạng liên quan

- `MainActivity` kế thừa `ComponentActivity`, `launchMode="singleTop"`, xử lý intent qua `dispatchOpenBookRequest`, `dispatchOpenLibraryRequest`, `checkAndAutoResumeLastBook`.
- Cửa vào nội bộ không export: `activity-alias .InternalEntryActivity`; `TtsOpenBookContract.isInternalEntry()` chỉ tin action nội bộ khi Intent đi qua alias này.
- Reader đã tự mở theo tiến độ đã lưu khi route không kèm chương (chi tiết ở Phase 0). Nhưng `TtsOpenBookContract.configureIntent` bắt buộc `chapterIndex >= 0`, nên chưa có Intent nội bộ nào chỉ mang `bookId`.
- Mở CBZ đi qua kênh riêng `openComicRequests` (`comicRouteFor` phân biệt comic local/online).
- Convention plugin có sẵn: `epubpro.android.library`, `epubpro.android.hilt`, `epubpro.android.compose`. `androidx.lifecycle:lifecycle-process` đã có trong version catalog.
- minSdk 26, targetSdk 34.

---

## Phase 0: Cửa vào "mở sách theo bookId" cho shortcut

**Đã có sẵn, không làm lại:** mở sách theo tiến độ đã lưu.
- EPUB/MOBI/AZW3: `Screen.Reader.createRoute(bookId)` không kèm chương → nav arg `chapterIndex = NO_CHAPTER_OVERRIDE` → `ReaderViewModel` (`resolveRequestedChapterIndex` trả `null`) tự lấy `savedProgress.chapterIndex` và khôi phục đúng trang khi cùng chương. `LibraryScreen.onBookClick` đang dùng đúng đường này.
- CBZ: `ComicReaderViewModel` tự đọc `getReadingProgressDirect(bookId)`; truyện online (`COMIC_BOOK_ID_PREFIX`) đi qua `comicRouteFor` với `resumeProgress = true`.
- Kênh `openComicRequests` của `MainIntentViewModel` đã gọi `comicRouteFor`.

**Chỉ còn thiếu:** một action nội bộ mang `bookId` (không mang chương), vì `TtsOpenBookContract.configureIntent`/`parse` bắt buộc `chapterIndex >= 0`.

- [ ] Thêm `BookEntryContract` cạnh `TtsOpenBookContract`: `ACTION_OPEN_BOOK_ENTRY`, `EXTRA_BOOK_ID`, `buildIntent(context, bookId)` trả Intent explicit tới `internalEntryComponent(context)`, `parse(intent)` có `runCatching` như `TtsOpenBookContract.parse`.
- [ ] `MainIntentViewModel.openBookEntry(bookId)`: `getBookById` → không còn thì notice `R.string.book_entry_missing`; CBZ hoặc ID truyện online → `dispatchOpenComic(bookId)`; còn lại → `dispatch(TtsOpenBookRequest(bookId, chapterIndex = NO_CHAPTER_OVERRIDE, openTtsPlayer = false))` để Reader tự khôi phục tiến độ. Không đọc `ReadingProgress` ở đây.
- [ ] `MainActivity.dispatchBookEntryRequest(intent)`: chỉ nhận khi `isInternalEntry(intent)`, xoá action/extra sau khi dùng; thêm vào chuỗi điều kiện ở `onCreate` và `onNewIntent` để huỷ auto-resume.
- Kiểm chứng: unit test `BookEntryContract.parse` (thiếu extra, extra hỏng, bookId rỗng); unit test `openBookEntry` cho nhánh EPUB, CBZ, sách đã xoá.

## Phase A: Lối tắt sách

- [ ] `BookShortcutManager` (`:app`, `@Singleton`):
  - `requestPin(book)`: kiểm tra `ShortcutManagerCompat.isRequestPinShortcutSupported`, tạo `ShortcutInfoCompat` id `book_<bookId>`, nhãn ngắn = tiêu đề cắt 24 ký tự, intent từ `BookEntryContract.buildIntent`.
  - Icon: đọc `coverPath` trên `Dispatchers.IO`, decode bằng `inSampleSize` về ~192 px, vẽ vào canvas adaptive 108 dp (bìa nằm trong vùng an toàn 66 dp) → `IconCompat.createWithAdaptiveBitmap`. Không có bìa → icon mặc định của app.
  - `updateDynamicShortcuts(recentBooks)`: 3–4 sách đọc gần nhất trong menu nhấn giữ icon app (`pushDynamicShortcut` có `rank`), gọi khi rời Reader.
  - `onBookDeleted(bookId)`: `disableShortcuts(listOf(id), message)` (app không xoá được shortcut đã ghim) + `removeDynamicShortcuts`.
- [ ] Lối vào UI: menu nhấn giữ sách ở `LibraryScreen` và menu tràn ở `ReaderScreen` → "Thêm ra màn hình chính". Ẩn mục khi launcher không hỗ trợ.
- [ ] Chuỗi mới trong `core/designsystem/src/main/res/values/strings.xml`: `book_shortcut_add`, `book_shortcut_unsupported`, `book_shortcut_disabled_deleted`, `book_entry_missing`.
- **Thử trên máy thật trước khi làm UI:** shortcut ghim trỏ tới alias `exported="false"`. Launcher khởi chạy shortcut bằng danh tính app phát hành nên alias không export vẫn mở được trên Pixel Launcher; cần xác nhận trên launcher bạn đang dùng (One UI/MIUI). Nếu không được thì xem lại theo `android-intent-security`, không export alias khi chưa phân tích.
- Kiểm chứng: ghim 2 sách (1 EPUB, 1 CBZ) → mở đúng Reader và đúng trang khi app đã tắt và khi app đang mở ở màn khác; xoá sách → shortcut xám, bấm vào báo lỗi rõ ràng.

## Phase B: Khoá app trong module `:core:security`

### B1. Module mới

- [ ] `settings.gradle.kts`: `include(":core:security")`.
- [ ] `core/security/build.gradle.kts`: plugin `epubpro.android.library`, `epubpro.android.hilt`, `epubpro.android.compose`; namespace `com.epubpro.core.security`. Dependency: `:core:designsystem` (theme), `androidx.biometric:biometric:1.1.0`, `androidx.lifecycle:lifecycle-process`, `androidx.datastore:datastore-preferences`, coroutines.
- [ ] Thêm `biometric = "1.1.0"` và alias `androidx-biometric` vào `gradle/libs.versions.toml`. **Chỉ** `:core:security` khai báo dependency này; `:app` và `:feature:profile` chỉ phụ thuộc `project(":core:security")`.
- [ ] Chuỗi riêng của tính năng đặt trong `core/security/src/main/res/values/strings.xml` (theo rule string resources cho chuỗi đặc thù module).
- Impact dependency: thư viện AndroidX nhỏ, kéo `androidx.fragment` (đã có qua appcompat), không cần Google Play services.

### B2. API public của module (giữ nhỏ)

| Thành phần | Vai trò |
|---|---|
| `AppLockSettings` (data class) + `AppLockPreferencesRepository` | `enabled` (**mặc định `false`**), `lockDelay` (`IMMEDIATE`, `ONE_MINUTE`, `FIVE_MINUTES`, `FIFTEEN_MINUTES`), `secureScreen`. DataStore riêng `app_lock.preferences_pb`. |
| `BiometricAvailability` | `check(context)` → `Available`, `NoneEnrolled`, `NoHardware`, `Unavailable` (bọc `BiometricManager.canAuthenticate(BIOMETRIC_WEAK or DEVICE_CREDENTIAL)`). |
| `BiometricAuthenticator` | `suspend fun authenticate(activity: FragmentActivity, title, subtitle): AuthResult` (`Success`, `Cancelled`, `Error(code)`), bọc `BiometricPrompt` bằng `suspendCancellableCoroutine`. |
| `AppLockManager` (`@Singleton`) | `locked: StateFlow<Boolean>`, `unlock()`, `setAuthenticating(Boolean)`; quan sát `ProcessLifecycleOwner`. |
| `AppLockGate(activity) { content() }` (Composable) | Vẽ `content`, phủ `AppLockScreen` lên trên khi `locked`, tự gọi xác thực khi vào foreground. |
| `AppLockSettingsSection()` (Composable) | Khối cài đặt dùng trong `ProfileScreen`. |

Các lớp còn lại (`AppLockScreen`, mapper lỗi, `ProcessLifecycleObserver`) để `internal`.

### B3. Hành vi bật/tắt

- **Tắt (mặc định):** không khoá, không gọi `BiometricPrompt`, không đặt `FLAG_SECURE`; `AppLockGate` chỉ vẽ `content`. Không có chi phí nào khác ngoài đọc một preference.
- **Bật:** chỉ bật được khi `BiometricAvailability` là `Available`, và phải xác thực thành công một lần để xác nhận. Nếu `NoneEnrolled` → báo cần cài khoá màn hình/vân tay trong cài đặt máy.
- **Tắt lại:** cũng phải xác thực, để người cầm máy không tắt được khoá.
- Khi máy không còn hỗ trợ (người dùng xoá hết vân tay và khoá màn hình) → tự tắt `enabled` và báo một lần, tránh tự khoá mình ngoài app.

### B4. Logic khoá

- [ ] Process mới + `enabled` → `locked = true`.
- [ ] `ProcessLifecycleOwner` `onStop` ghi `SystemClock.elapsedRealtime()`; `onStart` khoá nếu đã quá `lockDelay`. Dùng `elapsedRealtime` để đổi giờ hệ thống không mở khoá được.
- [ ] Không khoá lại khi chính `BiometricPrompt`, màn chọn file hệ thống (Add Book) hay hộp thoại quyền làm app vào nền: bọc các luồng đó bằng `setAuthenticating(true/false)` (giống cờ `isAuthenticating` của Mihon).
- [ ] `AppLockScreen`: nền đặc, logo, nút "Mở khoá"; `BackHandler` chặn back; huỷ hộp thoại vẫn ở màn khoá và bấm lại được.
- [ ] Authenticators: `BIOMETRIC_WEAK or DEVICE_CREDENTIAL` (chạy được trên API 26–29; `BIOMETRIC_STRONG or DEVICE_CREDENTIAL` thì không). Có PIN máy làm phương án dự phòng khi vân tay lỗi hoặc bị khoá tạm.
- [ ] `secureScreen` (chỉ có tác dụng khi khoá bật): `FLAG_SECURE` theo preference, collect lifecycle-aware; Android 13+ thêm `setRecentsScreenshotEnabled(false)`.

### B5. Tích hợp vào app

- [ ] `MainActivity` đổi từ `ComponentActivity` sang `FragmentActivity` (lớp con của `ComponentActivity`, `BiometricPrompt` bắt buộc). Kiểm tra `enableEdgeToEdge`, `viewModels`, TTS bubble (`onPostResume`) vẫn chạy như cũ.
- [ ] Bọc `AppNavHost` trong `AppLockGate(this)`. Intent đến lúc đang khoá (shortcut, file mở từ app khác, notification TTS) vẫn dispatch bình thường vì lớp phủ che phía trên; xác thực xong là thấy đúng màn hình.
- [ ] `:feature:profile`: thêm mục "Bảo mật" dùng `AppLockSettingsSection()` — công tắc khoá app, thời gian khoá, chặn chụp màn hình (hai mục sau chỉ hiện khi đã bật khoá).
- Kiểm chứng:
  - Khoá tắt: app chạy như hiện tại, không có hộp thoại nào.
  - Bật khoá → về nền 10 s → mở lại: delay "ngay" thì khoá, delay "1 phút" thì không.
  - Kill process → luôn khoá.
  - Xoay màn hình khi đang khoá không lộ nội dung.
  - Huỷ hộp thoại vân tay → vẫn ở màn khoá; dùng PIN máy mở được.
  - Mở từ shortcut khi đang khoá → xác thực xong vào đúng sách.
  - Thêm sách bằng màn chọn file khi đang bật khoá → quay về không bị khoá lại.
  - Tắt khoá đòi xác thực.

---

## Rủi ro và lưu ý

- **Đổi base class `MainActivity`**: nhỏ nhưng chạm điểm vào duy nhất của app; chạy lại luồng TTS bubble, auto-resume, import file ngoài.
- **Shortcut tới alias không export**: phụ thuộc launcher, phải thử trên máy thật trước khi làm UI.
- **Widget TTS hiện có** vẫn hiện nội dung trên màn hình chính khi app khoá (ngoài phạm vi lần này).
- Khoá app chỉ chống người cầm máy mở app, không bảo vệ dữ liệu trước root/ADB.

## Validation

```powershell
.\gradlew.bat :core:security:testDebugUnitTest :app:testDebugUnitTest
```

```powershell
.\gradlew.bat :app:assembleRelease
```

Sau đó cài bản release (R8 bật) lên máy thật để thử shortcut và `BiometricPrompt`, vì hai thứ này phụ thuộc launcher và phần cứng.
