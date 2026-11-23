# Core & App Hardening

> Tổng hợp kiến thức về ổn định lõi `:app`, `:core:database`, `:core:storage`, `:core:playback`, `:core:tts` (R8 Release, Room/FTS4, ForegroundService Android 14–17, đồng bộ native TTS và Kotlin Flow).
> Cập nhật lần cuối: 2026-11-23

---

## Architecture

### Generation Counter + Mutex Lifecycle cho Native TTS Engine
- **Ngày**: 2026-11-23
- **Chi tiết**: Khi bọc engine C++/ONNX (`SherpaTtsEngine`) trong wrapper bất đồng bộ (`PiperTtsEngineWrapper`), không được gọi `release()` đồng bộ trên Main thread khi coroutine `synthesize()`/`playPcm()` còn đang chạy trên `Dispatchers.IO`. Dùng `lifecycleMutex` để tuần tự hóa `initialize`/`release`, kết hợp `engineGeneration` (`AtomicLong`) và cờ `hasNativeEngineLoaded` gán ngay bên trong `withLock` để đảm bảo coroutine release cũ (kể cả khi đổi giọng liên tiếp `A → B → C`) không bao giờ giải phóng nhầm engine mới vừa khởi tạo.
- **Files liên quan**: `core/playback/src/main/java/com/epubpro/core/reader/tts/PiperTtsEngineWrapper.kt`, `core/tts/src/main/java/com/epubpro/core/tts/SherpaTtsEngine.kt`

### Hợp đồng Foreground Service Android 14–17 cho TTS & Widget
- **Ngày**: 2026-11-23
- **Chi tiết**: Với `targetSdk = 34` trên Android 14–17, mọi `PendingIntent.getForegroundService` hoặc `ContextCompat.startForegroundService()` bắt buộc phải dẫn tới `startForeground()` với `serviceTypes != 0` trước khi `stopSelf()`. Widget khi `!state.hasSnapshot` phải gắn `PendingIntent.getActivity` mở app thay vì gọi FGS; phía `TtsService` luôn cung cấp `FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK` dự phòng cho nhánh bắt buộc promote (như Reading Widget lúc `Idle`), hoàn tất `startForeground()` trước khi `stopSelf()`, và bắt `IllegalStateException` (`ForegroundServiceStartNotAllowedException`).
- **Files liên quan**: `core/playback/src/main/java/com/epubpro/core/reader/tts/TtsService.kt`, `app/src/main/java/com/epubpro/app/widget/TtsAudioWidgetProvider.kt`, `app/src/main/java/com/epubpro/app/widget/TtsReadingWidgetProvider.kt`

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
- **Fix**: Đổi `@Insert(onConflict = OnConflictStrategy.REPLACE)` sang `@Upsert` (Room 2.5+), chỉ chạy `UPDATE` khi trùng khóa chính mà không phát sinh `DELETE`.
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

### `SherpaTtsEngine` Native Use-After-Free & Lỗi Suy Diễn Kiểu Trả Về `initialize()`
- **Ngày**: 2026-11-23
- **Vấn đề**: Đổi giọng/tắt TTS lúc đang chạy `OfflineTts.generate()` hoặc `AudioTrack.write()` có thể gây SIGSEGV; `modelConfig.debug = true` ở production; `sampleRate <= 0` gây leak native `tts`.
- **Root cause**: `release()` không đồng bộ với `generate()`/`write()`, và hàm expression-body `initialize() = withContext { ... Log.d(...) }` bị suy diễn trả về `Int` thay vì `Unit` khi bỏ `return@withContext`.
- **Fix**: Khai báo rõ `: Unit`, dùng `ttsLock` (`ReentrantLock`), `audioWriteLock` và `playbackEpoch` (`AtomicLong`), tắt `debug = false`, ném lỗi kèm `release()` khi `sampleRate <= 0`.
- **Files liên quan**: `core/tts/src/main/java/com/epubpro/core/tts/SherpaTtsEngine.kt`

---

## How-To

### Xác Minh Bản Release (R8) & ForegroundService Trên Thiết Bị Thật
- **Ngày**: 2026-11-23
- **Bước thực hiện**:
  1. Build song song unit test và APK release: `.\gradlew.bat :app:assembleRelease`.
  2. Kiểm tra `app/build/outputs/mapping/release/mapping.txt` để xác nhận tên các method `@JavascriptInterface` không bị rút gọn.
  3. Cài đè `adb install -r app/build/outputs/apk/release/app-release.apk`, mở sách EPUB và vuốt lật trang xác nhận `ReaderJsBridge` cập nhật số trang.
  4. Bật TTS và kiểm tra `adb shell dumpsys activity services com.epubpro.app` xác nhận `isForeground=true` và `types=0x00000002` (`MEDIA_PLAYBACK`), không có exception trên logcat.
- **Files liên quan**: `app/proguard-rules.pro`, `core/playback/src/main/java/com/epubpro/core/reader/tts/TtsService.kt`

---

## Patterns

### Per-Collector State trong Cold `Flow` kết hợp `distinctUntilChangedBy`
- **Ngày**: 2026-11-23
- **Chi tiết**: Không khai báo `var` mutable bên ngoài builder của một cold `Flow` vì nhiều collector hoặc re-collect (`WhileSubscribed`) sẽ dùng chung biến không đồng bộ. Khai báo biến ngay trong `flow { ... }` để cô lập hoàn toàn theo từng lượt collect.
- **Ví dụ code**:
  ```kotlin
  fun observeSnapshot(...): Flow<BookBibleSnapshot?> = flow {
      var lastTouchedId: String? = null
      dao.observe(...)
          .distinctUntilChangedBy { /* bỏ qua cột chỉ để tracking như lastAccessedAt */ }
          .collect { entity ->
              emit(entity?.let {
                  if (lastTouchedId != it.id) {
                      lastTouchedId = it.id
                      dao.updateLastAccessed(it.id)
                  }
                  mapToDomain(it)
              })
          }
  }.flowOn(Dispatchers.IO)
  ```
- **Files liên quan**: `core/storage/src/main/java/com/epubpro/core/storage/bookbible/BookBibleRepositoryImpl.kt`

### Sanitize Query cho SQLite FTS4 `simple` Tokenizer Tiếng Việt
- **Ngày**: 2026-11-23
- **Chi tiết**: Khi dùng `@Fts4` mặc định (`simple` tokenizer), luôn chuẩn hóa `Normalizer.Form.NFC` trước khi tách token `[\p{L}\p{M}\p{N}]+` (không giữ `_`), giữ nguyên hoa/thường tiếng Việt có dấu, chỉ hạ thường `AND`/`OR`/`NOT`/`NEAR`, và dùng prefix `token*` thay vì `*token*`.
- **Files liên quan**: `core/database/src/main/java/com/epubpro/core/database/repository/RepositoryImpls.kt`

### An Toàn Hủy Coroutine & Phân Biệt `Exception` với `Error`
- **Ngày**: 2026-11-23
- **Chi tiết**: Trong `suspend fun` luôn rethrow `CancellationException` trước `catch (e: Exception)`; mọi bước dọn tài nguyên native dùng `suspend` (`Mutex.withLock`) bên trong `finally` phải bọc `withContext(NonCancellable)`; chỉ bắt `Exception` thay vì `Throwable` khi fallback retry để không che giấu `OutOfMemoryError` hoặc `Error` hệ thống.
- **Files liên quan**: `app/src/main/java/com/epubpro/app/MainActivity.kt`, `core/playback/src/main/java/com/epubpro/core/reader/tts/PiperTtsEngineWrapper.kt`, `core/playback/src/main/java/com/epubpro/core/reader/tts/VieNeuTtsEngineWrapper.kt`
