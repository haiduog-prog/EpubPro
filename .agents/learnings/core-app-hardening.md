# Core & App Hardening

> Tổng hợp kiến thức về ổn định lõi `:app` và 15 module `:core:*` (R8 Release, Room/FTS4, ForegroundService Android 14–17, đồng bộ native TTS, Single-Flight Cache, WebView Sanitizer, Comic/WebNovel Parser và Kotlin Coroutines/Flow).
> Cập nhật lần cuối: 2026-11-23

---

## Architecture

### Generation Counter + Mutex Lifecycle cho Native TTS Engine
- **Ngày**: 2026-11-23
- **Chi tiết**: Khi bọc engine C++/ONNX (`SherpaTtsEngine`, `VieNeuRuntimeManager`) trong wrapper bất đồng bộ, không gọi `release()` đồng bộ trên Main thread khi coroutine `synthesize()`/`playPcm()` còn chạy trên `Dispatchers.IO`. Dùng `lifecycleMutex` để tuần tự hóa `initialize`/`release`, kết hợp `engineGeneration` (`AtomicLong`) và cờ `hasNativeEngineLoaded` gán trong `withLock`. Với runtime `@Singleton` dùng chung như `VieNeuRuntimeManager`, quản lý `pendingUnloadJob` qua `scheduleUnload()` và `awaitPendingUnload()` ngay trong manager để một instance wrapper mới vẫn đợi được lệnh unload của wrapper cũ.
- **Files liên quan**: `core/playback/src/main/java/com/epubpro/core/reader/tts/PiperTtsEngineWrapper.kt`, `core/tts/src/main/java/com/epubpro/core/tts/SherpaTtsEngine.kt`, `core/tts/src/main/java/com/epubpro/core/tts/VieNeuRuntimeManager.kt`

### Hợp đồng Foreground Service Android 14–17 cho TTS & Widget
- **Ngày**: 2026-11-23
- **Chi tiết**: Với `targetSdk = 34` trên Android 14–17, mọi `PendingIntent.getForegroundService` hoặc `ContextCompat.startForegroundService()` bắt buộc phải gọi `startForeground()` với `serviceTypes != 0` trước khi `stopSelf()`. Khi nâng cấp `foregroundServiceType` lúc service đã ở foreground (`RESTART_FOREGROUND`), phải gọi trực tiếp `startForeground(notification, serviceTypes)` mà không gọi `stopForeground(STOP_FOREGROUND_DETACH)` trước để tránh `ForegroundServiceStartNotAllowedException` khi app đang ở background.
- **Files liên quan**: `core/playback/src/main/java/com/epubpro/core/reader/tts/TtsService.kt`, `core/playback/src/main/java/com/epubpro/core/reader/tts/notification/TtsPlaybackNotificationManager.kt`, `app/src/main/java/com/epubpro/app/widget/TtsAudioWidgetProvider.kt`, `app/src/main/java/com/epubpro/app/widget/TtsReadingWidgetProvider.kt`

### Single-Flight Chapter Memory Cache Chống Hủy Dây Chuyền & Ghi Đè Cũ
- **Ngày**: 2026-11-23
- **Chi tiết**: `EpubChapterMemoryCache.getOrLoad()` dùng `ConcurrentHashMap<String, CompletableDeferred<String>>` với `putIfAbsent` để gộp các yêu cầu nạp cùng một chương và `cacheGeneration: AtomicLong` tăng khi `evictBook`/`clear`. Để tránh trường hợp tác vụ prefetch bị hủy làm luồng đọc chính đang `await()` cũng nhận `CancellationException`, bọc trong vòng lặp `while (true)`: nếu `existingDeferred.await()` ném `CancellationException` mà coroutine hiện tại vẫn còn active (`currentCoroutineContext().ensureActive()`), gọi `yield()` rồi `continue` để tự thực hiện nạp chương.
- **Files liên quan**: `core/epub/src/main/java/com/epubpro/core/reader/engine/EpubChapterMemoryCache.kt`

### Gỡ Bỏ Tính Năng Có `CoroutineWorker` Đã Lập Lịch Trong WorkManager
- **Ngày**: 2026-11-23
- **Chi tiết**: Khi xóa một tính năng dùng WorkManager (như Google Drive Sync `DriveSyncWorker`), cơ sở dữ liệu SQLite nội bộ của WorkManager trên máy người dùng có thể vẫn còn giữ bản ghi công việc `ENQUEUED`. Cần gọi `WorkManager.getInstance(this).cancelUniqueWork("epub-sync")` và dọn thư mục/DataStore cũ (`sync-rollback`, `epub_sync_state.preferences_pb`) một lần trong `Application.onCreate()`.
- **Files liên quan**: `app/src/main/java/com/epubpro/app/EpubApplication.kt`

---

## Bugs & Solutions

### R8 Release Obfuscate/Strip Method `@JavascriptInterface` của `ReaderJsBridge`
- **Ngày**: 2026-11-23
- **Vấn đề**: Khi bật `isMinifyEnabled = true`, R8 đổi tên hoặc xóa các method `@JavascriptInterface` không có caller phía Kotlin, làm gãy hoàn toàn lật trang/chọn chữ trong WebView EPUB Reader trên bản Release.
- **Root cause**: `app/proguard-rules.pro` thiếu rule giữ method gắn `@android.webkit.JavascriptInterface`.
- **Fix**: Thêm `-keepclassmembers class * { @android.webkit.JavascriptInterface <methods>; }` và kiểm chứng qua `app/build/outputs/mapping/release/mapping.txt`.
- **Files liên quan**: `app/proguard-rules.pro`

### `BookDao.insertBook` dùng `REPLACE` kích hoạt `ON DELETE CASCADE` xóa dữ liệu AI
- **Ngày**: 2026-11-23
- **Vấn đề**: Cập nhật sách online/comic bằng `insertBook` làm mất toàn bộ `ai_rules` và `ai_chapter_cache` của cuốn sách đó.
- **Root cause**: SQLite `INSERT OR REPLACE` xóa bản ghi cũ rồi chèn mới, kích hoạt `ForeignKey.CASCADE` trên các bảng con.
- **Fix**: Đổi `@Insert(onConflict = OnConflictStrategy.REPLACE)` sang `@Upsert` (Room 2.5+), đồng thời gom `deleteBook` trong `@Transaction` xóa sạch `reading_progress`, `bookmarks`, `highlights`.
- **Files liên quan**: `core/database/src/main/java/com/epubpro/core/database/dao/Daos.kt`

### SQLite FTS4 Crash với `*$query*` và Cắt Rời Tiếng Việt NFD
- **Ngày**: 2026-11-23
- **Vấn đề**: Tìm kiếm trong sách ném `SQLiteException: malformed MATCH expression` hoặc `no such column`, và cắt rời dấu tiếng Việt khi dán chuỗi NFD.
- **Root cause**: FTS4 không hỗ trợ leading wildcard `*token*`, không sanitize toán tử (`:`, `"`, `-`, `AND`/`OR`/`NOT`/`NEAR`), còn tokenizer `simple` coi `_` là dấu tách và không tự chuẩn hóa NFD→NFC hay gấp chữ hoa có dấu tiếng Việt.
- **Fix**: Chuẩn hóa `Normalizer.Form.NFC`, tách token bằng `Regex("[\\p{L}\\p{M}\\p{N}]+")`, chỉ hạ thường 4 toán tử dự trữ, gắn hậu tố `*` và trả rỗng khi không có token.
- **Files liên quan**: `core/database/src/main/java/com/epubpro/core/database/repository/RepositoryImpls.kt`

### Vòng Lặp Vô Tận Room `Flow` trong `BookBibleRepositoryImpl.observeSnapshot`
- **Ngày**: 2026-11-23
- **Vấn đề**: Mở Book Bible khi có snapshot cache khiến DB bị `UPDATE` liên tục và UI recompose vô hạn.
- **Root cause**: `.map` của Room `Flow` trên bảng `book_bible_snapshots` gọi `updateSnapshotLastAccessed()` cập nhật chính bảng đó, kích hoạt `InvalidationTracker` emit lại không dừng.
- **Fix**: Dùng `distinctUntilChangedBy` (loại trừ `lastAccessedAt`) bên trong `flow { ... }` với biến cục bộ theo từng collector để chỉ cập nhật `lastAccessedAt` tối đa 1 lần cho mỗi snapshot ID.
- **Files liên quan**: `core/storage/src/main/java/com/epubpro/core/storage/bookbible/BookBibleRepositoryImpl.kt`

### `TtsService.resume()` bị khóa bởi `pausedBySystem` & VieNeu treo khi Prefetch Lỗi
- **Ngày**: 2026-11-23
- **Vấn đề**: Sau khi mất AudioFocus tạm thời, bấm Play thủ công không có tác dụng; ở VieNeu nếu `prefetch()` lỗi sẽ treo vĩnh viễn ở trạng thái `Preparing`.
- **Root cause**: `resume()` có `if (pausedBySystem) return` biến `pausedBySystem = false` thành dead code; `VieNeuTtsEngineWrapper.speak()` bắt nhầm `CancellationException` từ `cachedEntry.result.await()` như thể chính `speakJob` bị hủy.
- **Fix**: Xóa `if (pausedBySystem) return`; bọc riêng `cachedEntry.result.await()` chỉ rethrow `CancellationException` khi `isCancelled()` là `true`, ngược lại fallback tổng hợp mới.
- **Files liên quan**: `core/playback/src/main/java/com/epubpro/core/reader/tts/TtsService.kt`, `core/playback/src/main/java/com/epubpro/core/reader/tts/VieNeuTtsEngineWrapper.kt`

### `AndroidNativeTtsEngine` Gọi `onInit(ERROR)` Đồng Bộ Ngay Trong Constructor `TextToSpeech`
- **Ngày**: 2026-11-23
- **Vấn đề**: Khi thiết bị không có engine TTS, constructor `TextToSpeech(context, this)` gọi `onInit(ERROR)` đồng bộ trước khi constructor trả về (lúc biến `tts` vẫn còn `null`). Nếu guard `val activeTts = tts ?: return` đặt đầu `onInit`, lỗi bị nuốt mất; đồng thời sau khi constructor trả về, `tts` lại được gán instance hỏng khiến các lần `initialize()` sau không thử khởi tạo lại.
- **Root cause**: `onInit(ERROR)` chạy bên trong thân constructor trước phép gán `tts = TextToSpeech(...)`.
- **Fix**: Xử lý nhánh `status != TextToSpeech.SUCCESS` trước guard `tts ?: return`, bật cờ `initFailed = true`, và ngay sau `tts = TextToSpeech(...)` nếu `initFailed` bật thì gọi `tts?.shutdown()` và đặt `tts = null`.
- **Files liên quan**: `core/playback/src/main/java/com/epubpro/core/reader/tts/AndroidNativeTtsEngine.kt`

### `java.net.URI.host` Trả Về `null` Khi Host CDN Chứa Dấu Gạch Dưới `_`
- **Ngày**: 2026-11-23
- **Vấn đề**: Các trang truyện tranh dùng CDN có dấu `_` trong subdomain (ví dụ `https://img_cdn_1.example.com/001.jpg`) bị loại hết ảnh và báo lỗi "Không tìm thấy ảnh trong chương".
- **Root cause**: `java.net.URI` tuân thủ chặt RFC 2396 nên trả `URI.host == null` khi tên miền chứa `_`, khiến `isPublicUrl()` fail-closed loại bỏ URL.
- **Fix**: Dùng `url.toHttpUrlOrNull()?.host?.lowercase()` của OkHttp (hỗ trợ chuẩn WHATWG URL và dấu `_` trong host) thay cho `URI(url).host`.
- **Files liên quan**: `core/comic/src/main/java/com/epubpro/core/comic/NetTruyenChapterParser.kt`

### Hủy Request `HttpURLConnection` Trong `GeminiClient` Bị Treo Tới Khi Hết Read Timeout
- **Ngày**: 2026-11-23
- **Vấn đề**: Khi người dùng đóng màn hình hoặc hủy tác vụ AI, kết nối `HttpURLConnection` vẫn bị treo tối đa 120 giây.
- **Root cause**: `coroutineContext[Job]?.invokeOnCompletion` chỉ chạy sau khi coroutine body kết thúc, nhưng thread của coroutine đang bị block trong `connection.responseCode` / `inputStream.readText()`.
- **Fix**: Dùng `suspendCancellableCoroutine`, chạy blocking I/O trên daemon thread pool riêng và gọi `connection.disconnect()` + `future.cancel(true)` trong `continuation.invokeOnCancellation`.
- **Files liên quan**: `core/ai/src/main/java/com/epubpro/core/ai/GeminiClient.kt`

### `HtmlNormalizer` Phá Vỡ Thẻ HTML Nhiều Dòng & `EpubHtmlSanitizer` Lọt SVG Script/DOM Clobbering
- **Ngày**: 2026-11-23
- **Vấn đề**: Chương EPUB không có `<p>`/`<br>` nhưng có thẻ `<img>`/`<div>` viết xuống dòng bị chèn `<br>` vào giữa thẻ làm vỡ DOM; `data:image/svg+xml` hoặc `id="ReaderJsBridge"` trong EPUB có thể thực thi script hoặc ghi đè bridge trong WebView.
- **Root cause**: `HtmlNormalizer` thay thế `\r?\n` trên chuỗi HTML thô trước khi parse DOM; `EpubHtmlSanitizer` cho phép mọi `data:image/*` và chưa lọc `id`/`name` trùng tên bridge.
- **Fix**: Parse bằng `Jsoup.parse(htmlContent)` trước và chỉ thay `\r?\n` trong `TextNode` thuộc `body` (bỏ qua `pre`, `code`, `script`, `style`); trong `EpubHtmlSanitizer`, chỉ cho phép `data:image/(png|jpeg|jpg|gif|webp)`, chặn thẻ animation SVG (`set`, `animate`, `foreignObject`) và xóa `id`/`name` trùng `ReaderJsBridge` hoặc `epubpro-*`.
- **Files liên quan**: `core/epub/src/main/java/com/epubpro/core/reader/engine/HtmlNormalizer.kt`, `core/reader-renderer/src/main/java/com/epubpro/core/reader/filter/EpubHtmlSanitizer.kt`

---

## How-To

### Xác Minh Bản Release (R8) & ForegroundService Trên Thiết Bị Thật
- **Ngày**: 2026-11-23
- **Bước thực hiện**:
  1. Chạy toàn bộ unit test và build APK release: `.\gradlew.bat testDebugUnitTest :core:convert:test :domain:test :app:assembleRelease --continue`.
  2. Kiểm tra `app/build/outputs/mapping/release/mapping.txt` để xác nhận tên các method `@JavascriptInterface` không bị rút gọn.
  3. Cài đè `adb install -r app/build/outputs/apk/release/app-release.apk`, mở sách EPUB và vuốt lật trang xác nhận `ReaderJsBridge` hoạt động mượt.
  4. Kiểm tra `adb logcat -d *:E` xác nhận không có `FATAL EXCEPTION` hay lỗi `WorkManager` / `ForegroundService`.
- **Files liên quan**: `app/proguard-rules.pro`, `core/playback/src/main/java/com/epubpro/core/reader/tts/TtsService.kt`

---

## Patterns

### Per-Collector State trong Cold `Flow` kết hợp `distinctUntilChangedBy`
- **Ngày**: 2026-11-23
- **Chi tiết**: Không khai báo `var` mutable bên ngoài builder của một cold `Flow` vì nhiều collector hoặc re-collect (`WhileSubscribed`) sẽ dùng chung biến không đồng bộ. Khai báo biến ngay trong `flow { ... }` để cô lập hoàn toàn theo từng lượt collect.
- **Files liên quan**: `core/storage/src/main/java/com/epubpro/core/storage/bookbible/BookBibleRepositoryImpl.kt`

### Ghi File Nguyên Tử Bằng `File.createTempFile` + `Files.move`
- **Ngày**: 2026-11-23
- **Chi tiết**: Không dùng tên file tạm cố định (`"${target.name}.tmp"`) hoặc xóa file đích trước khi ghi khi `target == existing` (như `ComicCbzWriter`), vì hai luồng ghi song song hoặc crash giữa chừng sẽ làm hỏng/mất dữ liệu. Luôn tạo file tạm duy nhất bằng `File.createTempFile("${target.name}.", ".tmp", parentDir)`, di chuyển nguyên tử bằng `Files.move(..., REPLACE_EXISTING, ATOMIC_MOVE)` (fallback `REPLACE_EXISTING`), và xóa file tạm còn sót trong `finally`.
- **Files liên quan**: `core/storage/src/main/java/com/epubpro/core/storage/EpubStorageManager.kt`, `core/comic/src/main/java/com/epubpro/core/comic/ComicCbzWriter.kt`, `core/webnovel/src/main/java/com/epubpro/core/webnovel/EpubWriter.kt`

### An Toàn Hủy Coroutine, Ngắt Stream & Phân Biệt `Exception` với `Error`
- **Ngày**: 2026-11-23
- **Chi tiết**: Trong `suspend fun` hoặc vòng lặp tải file, dùng `currentCoroutineContext().ensureActive()` thay cho `Thread.currentThread().isInterrupted`; luôn rethrow `CancellationException` trước `catch (e: Exception)`; dùng `runCatchingCancellable` thay cho `runCatching` khi bọc hàm `suspend`; bọc `withContext(NonCancellable)` cho thao tác lấy `Mutex` trong `finally`; và dùng exception chuyên biệt (`TotalExtractedBudgetExceededException`) thay vì so khớp chuỗi `error.message`.
- **Files liên quan**: `core/tts/src/main/java/com/epubpro/core/tts/VoiceModelDownloader.kt`, `core/epub/src/main/java/com/epubpro/core/reader/engine/EpubEngine.kt`, `core/storage/src/main/java/com/epubpro/core/storage/bookbible/BookBibleRepositoryImpl.kt`
