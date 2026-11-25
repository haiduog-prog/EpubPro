# Online Backend & Library Integration

> Tổng hợp kiến thức về hệ thống kết nối Backend API, Kho Truyện Online, Retrofit, Dynamic Base URL, Cloudflare Workers/R2/Render, quản lý Coroutine/Flow, WorkManager ngầm và chuẩn hóa quy định dự án theo AGENTS.md.
> Cập nhật lần cuối: 2026-11-25

---

## Architecture

### Dynamic Base URL Multi-Environment Architecture
- **Ngày**: 2026-08-17
- **Chi tiết**: Sử dụng `DynamicBaseUrlInterceptor` trong OkHttp để định tuyến lại URL động lúc runtime dựa trên `ServerPreferencesManager`. Bóc tách path segments tương đối sau tiền tố `/api/v1` để tránh nhân đôi path segments khi chuyển đổi giữa Cloud (Render/API), Emulator (`10.0.2.2`), Localhost (`127.0.0.1`) và LAN IP.
- **Files liên quan**: `core/storage/src/main/java/com/epubpro/core/storage/network/DynamicBaseUrlInterceptor.kt`, `core/storage/src/main/java/com/epubpro/core/storage/ServerPreferencesManager.kt`

### Background EPUB Import & Realtime Notification Architecture
- **Ngày**: 2026-08-19
- **Chi tiết**: Sử dụng `EpubImportWorker` (kế thừa `CoroutineWorker`) kết hợp `WorkManager` và `NotificationCompat.Builder` với kiểu `FOREGROUND_SERVICE_TYPE_DATA_SYNC`. Tách biệt vòng đời xử lý khỏi `viewModelScope` để tiến trình upload và polling không bao giờ bị dừng khi người dùng tắt app hoặc khóa màn hình. `LibraryViewModel` lắng nghe `getWorkInfosForUniqueWorkFlow` để đồng bộ UI.
- **Files liên quan**: `core/storage/src/main/java/com/epubpro/core/storage/worker/EpubImportWorker.kt`, `feature/library/src/main/java/com/epubpro/feature/library/LibraryViewModel.kt`

### Two-Tier Book Bible & Online Novel ID Resolution Architecture
- **Ngày**: 2026-08-19
- **Chi tiết**: Phân tách rõ giữa Client Local Source Key (`LOCAL_EPUB:<id>`, `ONLINE_NOVEL:<slug>`) và Backend Cloud ID (`backendBookId`, `backendEditionId`). Sách offline được resolve qua `POST /books/resolve` để lấy ID máy chủ trước khi submit, tránh nhầm lẫn ID giữa các thiết bị.
- **Files liên quan**: `core/storage/src/main/java/com/epubpro/core/storage/bookbible/BookBibleWorker.kt`, `core/storage/src/main/java/com/epubpro/core/storage/bookbible/BookBibleRepositoryImpl.kt`

### Production Render Cloud Deployment Integration
- **Ngày**: 2026-08-18
- **Chi tiết**: Cấu hình `DEFAULT_BASE_URL` sang máy chủ cố định `https://epubbackend.onrender.com/api/v1/` và tự động làm sạch các URL thử nghiệm tạm thời (`trycloudflare.com`, `workers.dev`, `r2.dev`) trong `readBaseUrl()` để người dùng cập nhật phiên bản mới luôn kết nối tới backend chính thức mà không bị dính cache SharedPreferences cũ.
- **Files liên quan**: `core/storage/src/main/java/com/epubpro/core/storage/ServerPreferencesManager.kt`, `feature/profile/src/main/java/com/epubpro/feature/profile/ServerSettingsDialog.kt`

### 1-Touch Stream & Offline Storage Pipeline
- **Ngày**: 2026-08-17
- **Chi tiết**: Tải nhị phân streaming (`@Streaming` Retrofit `ResponseBody`) file `.epub` từ `/export/epub` -> ghi trực tiếp vào `EpubStorageManager.importDownloadedEpub` -> tự động bóc tách metadata bằng `EpubEngine` -> lưu Room DB -> lập chỉ mục FTS5 ngầm. Không giữ toàn bộ file trong RAM.
- **Files liên quan**: `core/storage/src/main/java/com/epubpro/core/storage/network/OnlineNovelRepositoryImpl.kt`, `feature/library/src/main/java/com/epubpro/feature/library/online/OnlineLibraryViewModel.kt`

### Hybrid Cloudflare Architecture (API Server + R2 Zero Egress CDN)
- **Ngày**: 2026-08-18
- **Chi tiết**: Tách biệt luồng xử lý: FastAPI/Worker backend xử lý logic tính toán và metadata (`/novels`, `/translate`). Cloudflare R2 (`pub-*.r2.dev`) đóng vai trò lưu trữ tĩnh file EPUB và Cover Images với ưu điểm Zero Egress Fee (miễn phí 100% băng thông tải về). App gọi API lấy metadata nhưng tải trực tiếp binary EPUB và ảnh từ CDN R2.
- **Files liên quan**: `core/storage/src/main/java/com/epubpro/core/storage/network/OnlineNovelApiService.kt`

### Single Job Coroutine Collector Management in ViewModels
- **Ngày**: 2026-08-18
- **Chi tiết**: Khi ViewModel cần kích hoạt lại việc thu thập Flow từ Repository dựa trên event (ví dụ kiểm tra sách đã tải sau khi fetch API chi tiết), luôn quản lý collector bằng một biến `private var collectJob: Job? = null` và gọi `collectJob?.cancel()` trước khi `launch` mới. Tránh rò rỉ và chồng chéo nhiều coroutine collectors chạy song song.
- **Files liên quan**: `feature/library/src/main/java/com/epubpro/feature/library/online/OnlineNovelDetailViewModel.kt`

### Tách Scheduler và Worker cho Upload EPUB
- **Ngày**: 2026-08-20
- **Chi tiết**: Dùng `EpubImportScheduler` để sao lưu URI ở `Dispatchers.IO`, chống enqueue trùng bằng `Mutex`, `ExistingWorkPolicy.KEEP` và ràng buộc mạng `CONNECTED`. `EpubImportWorker` chỉ chịu trách nhiệm upload, polling, thông báo và dọn file tạm.
- **Files liên quan**: `core/storage/src/main/java/com/epubpro/core/storage/worker/EpubImportScheduler.kt`, `core/storage/src/main/java/com/epubpro/core/storage/worker/EpubImportWorker.kt`

### Polling thưa cho Backend Free Tier
- **Ngày**: 2026-08-20
- **Chi tiết**: Với Render Free, polling trạng thái import mỗi 10 giây giảm tải xuống khoảng 6 request/phút nhưng vẫn đủ để hiển thị tiến độ. Không nhầm HTTP 200 của endpoint status với trạng thái job thành công; phải đọc trường `status` trong JSON.
- **Files liên quan**: `core/storage/src/main/java/com/epubpro/core/storage/worker/EpubImportWorker.kt`

### Persistent Online Novel ID Mapping in Local Database
- **Ngày**: 2026-08-21
- **Chi tiết**: Để đồng bộ và nhận diện chính xác trạng thái đã tải về giữa Kho Truyện Online và Tủ Sách Local (tránh so khớp lỏng lẻo bằng title), bổ sung trường nullable `onlineNovelId: String?` vào `BookEntity` thông qua Room `MIGRATION_5_6`. Khi download từ kho truyện, book metadata được copy gắn kèm `onlineNovelId = novel.novelId`, giúp truy vấn và hiển thị badge "Đã tải" tức thì.
- **Files liên quan**: `core/database/src/main/java/com/epubpro/core/database/Migrations.kt`, `core/database/src/main/java/com/epubpro/core/database/entity/Entities.kt`, `feature/library/src/main/java/com/epubpro/feature/library/online/OnlineLibraryViewModel.kt`

---

## Bugs & Solutions

### Lỗi Notification Channel bị khóa IMPORTANCE_LOW trên Android 14+ / Samsung One UI
- **Ngày**: 2026-08-24
- **Vấn đề**: Dù người dùng đã cấp quyền runtime `POST_NOTIFICATIONS`, tiến trình tải truyện chạy nhưng không hiện icon trên status bar đỉnh máy hay thông báo nổi.
- **Root cause**: Channel được khởi tạo lần đầu với `IMPORTANCE_LOW` (mức 2). Android OS lưu cache vĩnh viễn mức ưu tiên này, gom thông báo vào khu vực "im lặng" và không cho phép code tự nâng độ ưu tiên nếu giữ nguyên Channel ID cũ.
- **Fix**: Nâng cấp Channel ID sang `online_novel_downloads_v2` với `NotificationManager.IMPORTANCE_DEFAULT` (hoặc `HIGH`), đồng thời gọi `deleteNotificationChannel("online_novel_downloads")` để dọn kênh cũ.
- **Files liên quan**: `core/storage/src/main/java/com/epubpro/core/storage/worker/OnlineNovelDownloadWorker.kt`

### Lỗi kẹt tiến độ 0% và âm thầm retry vô tận khi gặp HTTP 502 / Timeout
- **Ngày**: 2026-08-24
- **Vấn đề**: Khi backend Render bị OOM/timeout (HTTP 502/SocketTimeoutException), app không báo lỗi mà kẹt ở 0% và âm thầm retry ngầm.
- **Root cause**: Code cũ đánh dấu `isRetryable = true` cho mọi lỗi >= 500, khiến WorkManager gọi `Result.retry()` rơi vào trạng thái chờ ngầm vô tận.
- **Fix**: Đánh dấu `isRetryable = false` khi gặp lỗi kết nối/502/timeout; gọi `showErrorNotification()` ngay lập tức; trả `Result.failure()` kèm message tiếng Việt cụ thể (`"Máy chủ đang bận xử lý hoặc không thể xuất EPUB (HTTP 502)"`); dọn file tạm `.part`.
- **Files liên quan**: `core/storage/src/main/java/com/epubpro/core/storage/network/OnlineNovelRepositoryImpl.kt`, `core/storage/src/main/java/com/epubpro/core/storage/worker/OnlineNovelDownloadWorker.kt`

### Lỗi Dialog tự mở lại liên tục khi người dùng nhấn "Chạy ngầm"
- **Ngày**: 2026-08-19
- **Vấn đề**: Sau khi bấm nút "Chạy ngầm" để đóng AlertDialog tiến trình upload, dialog bị tự động bật lại ngay lập tức.
- **Root cause**: `WorkManager.getWorkInfosForUniqueWorkFlow` liên tục emit state `RUNNING` mỗi khi có cập nhật % tiến độ mới, gán đè lại `_uploadJobState.value = ImportJobStatus(...)`.
- **Fix**: Thêm cờ `isDialogDismissedByUser = true` khi người dùng bấm ẩn; `observeImportWorkerProgress` kiểm tra cờ này trước khi gán lại state dialog. Reset về `false` khi có upload mới hoặc job kết thúc.
- **Files liên quan**: `feature/library/src/main/java/com/epubpro/feature/library/LibraryViewModel.kt`

### Lỗi Polling chạy vô tận khi gặp HTTP 404 / 4xx từ Server
- **Ngày**: 2026-08-19
- **Vấn đề**: Khi Server trả về 404 (`{"detail":"Không tìm thấy tiến trình upload này."}`), app vẫn tiếp tục polling vô tận và không báo lỗi cho người dùng.
- **Root cause**: Khối `onFailure` trong vòng lặp polling chỉ in stack trace và tiếp tục `delay(2500)` cho vòng lặp kế tiếp.
- **Fix**: Bắt `HttpException`, bóc tách `JSONObject.optString("detail")`, ngắt vòng lặp ngay lập tức (`isRunning = false`), hiển thị Error Notification và cập nhật UI thất bại. Thêm giới hạn tối đa 12 lần lỗi mạng liên tiếp trước khi ngắt.
- **Files liên quan**: `core/storage/src/main/java/com/epubpro/core/storage/worker/EpubImportWorker.kt`

### Lỗi Notification không hiển thị trên Android 13+ (API 33+)
- **Ngày**: 2026-08-19
- **Vấn đề**: Tiến trình upload chạy nhưng thanh thông báo trạng thái không xuất hiện trên đỉnh màn hình điện thoại.
- **Root cause**: Android 13+ yêu cầu runtime permission `POST_NOTIFICATIONS` và Android 14 yêu cầu khai báo `FOREGROUND_SERVICE_DATA_SYNC` / `SystemForegroundService`.
- **Fix**: Thêm launcher xin quyền `POST_NOTIFICATIONS` trong `LibraryScreen.kt` trước khi mở picker; khai báo `FOREGROUND_SERVICE_DATA_SYNC` trong `AndroidManifest.xml`; gọi `setForeground(createForegroundInfo(...))` trong Worker.
- **Files liên quan**: `app/src/main/AndroidManifest.xml`, `feature/library/src/main/java/com/epubpro/feature/library/LibraryScreen.kt`, `core/storage/src/main/java/com/epubpro/core/storage/worker/EpubImportWorker.kt`

### Lỗi IllegalArgumentException: 25.0.2 khi build Gradle với Java 25
- **Ngày**: 2026-08-17
- **Vấn đề**: `gradlew assembleDebug` bị crash ngay khi khởi động vì `JavaVersion.parse` của Kotlin Compiler 1.9 / Gradle 8.7 không nhận diện được chuỗi phiên bản `25.0.2` của JDK Android Studio mới.
- **Root cause**: Java 25 quá mới so với Kotlin compiler 1.9.23.
- **Fix**: Trỏ biến môi trường `JAVA_HOME` sang JDK 17 LTS chuẩn (`C:\Users\haidu\.jdks\jbr-17.0.11` hoặc `Java 17`).
- **Files liên quan**: Build environment configuration

### Lỗi UnknownHostException trên Android với Cloudflare Tunnel (*.trycloudflare.com)
- **Ngày**: 2026-08-18
- **Vấn đề**: Trình duyệt/curl trên PC phân giải được domain tunnel nhưng Android App ném `java.net.UnknownHostException: No address associated with hostname`.
- **Root cause**: DNS mặc định của Android Emulator hoặc ISP mạng di động không phân giải được tên miền tunnel sinh tự động hoặc ưu tiên IPv6 không có route.
- **Fix**: Cài đặt `FallbackDns` (sử dụng DNS-over-HTTPS tới `1.1.1.1` hoặc `8.8.8.8`) trong `OkHttpClient.Builder().dns(fallbackDns)`.
- **Files liên quan**: `core/storage/src/main/java/com/epubpro/core/storage/network/FallbackDns.kt`, `core/storage/src/main/java/com/epubpro/core/storage/network/NetworkModule.kt`

### Lỗi 404 Not Found khi gọi API vào Cloudflare Worker chỉ phục vụ HTML
- **Ngày**: 2026-08-18
- **Vấn đề**: `GET https://*.workers.dev/api/v1/library/novels` trả về HTTP 404 (0-byte body).
- **Root cause**: Cloudflare Worker chỉ có route xử lý `GET /` (serve HTML Dashboard), chưa có handler định tuyến cho các API path `/api/v1/...` hoặc chưa proxy về origin backend.
- **Fix**: Viết thêm API router (`Hono` / `itty-router`) kết nối D1/KV/R2 trên Worker HOẶC deploy FastAPI backend lên Render/Fly.io và trỏ Base URL của App vào đúng server API.
- **Files liên quan**: `core/storage/src/main/java/com/epubpro/core/storage/ServerPreferencesManager.kt`

### Lỗi Smart Cast không hợp lệ cho thuộc tính public module khác
- **Ngày**: 2026-08-17
- **Vấn đề**: Thuộc tính `val description: String?` khai báo trong `:domain` khi kiểm tra `if (!detail.description.isNullOrBlank())` trong `:feature:library` báo lỗi compilation `Smart cast to String is impossible`.
- **Root cause**: Kotlin không thể đảm bảo tính bất biến của public property từ module khác tại thời điểm runtime.
- **Fix**: Gán vào biến cục bộ trước khi kiểm tra: `val desc = detail.description; if (!desc.isNullOrBlank()) { ... }`.
- **Files liên quan**: `feature/library/src/main/java/com/epubpro/feature/library/online/OnlineNovelDetailScreen.kt`

### Thông báo thành công cũ xuất hiện lại sau khi mở app
- **Ngày**: 2026-08-20
- **Vấn đề**: WorkManager giữ `WorkInfo` ở trạng thái `SUCCEEDED`; ViewModel mới đọc lại và phát Snackbar cũ.
- **Root cause**: Collector xử lý mọi trạng thái terminal giống một sự kiện mới.
- **Fix**: Theo dõi `hasActiveUploadSession`; chỉ thông báo terminal khi phiên upload đang hoạt động trong process hiện tại, sau đó reset cờ.
- **Files liên quan**: `feature/library/src/main/java/com/epubpro/feature/library/LibraryViewModel.kt`

### Upload HTTP thành công nhưng EPUB bị server từ chối
- **Ngày**: 2026-08-20
- **Vấn đề**: POST upload và các GET polling đều trả HTTP 200, nhưng job kết thúc `failed` với `Không tìm thấy nội dung chương hợp lệ trong file EPUB`, `current_chapter=0`.
- **Root cause**: Lỗi parser/cấu trúc nội dung EPUB phía server, không phải lỗi truyền file hoặc WorkManager.
- **Fix**: Kiểm tra JSON trạng thái job; cần validate EPUB trước upload hoặc bổ sung hỗ trợ cấu trúc EPUB ở backend.
- **Files liên quan**: `core/storage/src/main/java/com/epubpro/core/storage/worker/EpubImportWorker.kt`, `core/storage/src/main/java/com/epubpro/core/storage/network/OnlineNovelApiService.kt`

### Hiển thị nhầm hai hệ số chương trong tiến độ
- **Ngày**: 2026-08-20
- **Vấn đề**: Chuỗi `Đang nạp chương 35 (35/135): Chương 647` khiến số thứ tự trong file bị hiểu là số chương gốc.
- **Root cause**: UI hiển thị nguyên `current_step` từ backend.
- **Fix**: Chỉ hiển thị phần sau `): `, giữ lại tên chương gốc như `Chương 647: ...`.
- **Files liên quan**: `feature/library/src/main/java/com/epubpro/feature/library/LibraryScreen.kt`

### Ping kiểm tra Server làm thay đổi Base URL đã lưu
- **Ngày**: 2026-08-21
- **Vấn đề**: Khi bấm nút "Kiểm tra kết nối" với một URL thử nghiệm trong dialog cài đặt, cấu hình Base URL bị lưu đè vào SharedPreferences ngay cả khi người dùng không bấm "Lưu" hoặc test thất bại.
- **Root cause**: Hàm `testServerConnection()` cũ gọi `saveBaseUrl(urlText)` trước khi thực hiện request ping.
- **Fix**: Sử dụng header nội bộ `BASE_URL_OVERRIDE_HEADER` truyền vào request ping để `DynamicBaseUrlInterceptor` định tuyến riêng lẻ mà không làm biến đổi SharedPreferences.
- **Files liên quan**: `core/storage/src/main/java/com/epubpro/core/storage/network/DynamicBaseUrlInterceptor.kt`, `core/storage/src/main/java/com/epubpro/core/storage/network/OnlineNovelRepositoryImpl.kt`, `feature/profile/src/main/java/com/epubpro/feature/profile/ServerSettingsDialog.kt`

---

### Upload EPUB mất file sau khi mạng rớt và có thể tạo truyện trùng
- **Ngày**: 2026-11-25
- **Vấn đề**: Mất mạng giữa lúc polling, khi có mạng lại worker báo "File không tồn tại"; nếu giữ file thì lần chạy lại upload thêm một bản.
- **Root cause**: `finally { file.delete() }` chạy cả khi WorkManager dừng worker do mất `NetworkType.CONNECTED`; `jobId` không được lưu nên lần chạy lại không biết đã upload.
- **Fix**: Chỉ xóa file ở kết quả cuối; lưu `jobId` vào file đánh dấu; file chờ upload nằm trong `files/epub_upload_staging/` với giới hạn 100 MiB và tự xóa file dở khi lỗi; UI báo `BookFileTooLargeException` bằng thông báo riêng.
- **Files liên quan**: `core/storage/src/main/java/com/epubpro/core/storage/worker/EpubImportWorker.kt`, `core/storage/src/main/java/com/epubpro/core/storage/EpubStorageManager.kt`

## How-To

### Cách tích hợp WorkManager kèm Foreground Notification cho tác vụ upload dài
- **Ngày**: 2026-08-19
- **Bước thực hiện**:
  1. Khai báo permission `FOREGROUND_SERVICE_DATA_SYNC` và `SystemForegroundService` trong `AndroidManifest.xml`.
  2. Tạo `@HiltWorker CoroutineWorker` gọi `setForeground(createForegroundInfo(...))` với Channel và Ongoing Notification.
  3. Bắn tiến độ bằng `setProgress(workDataOf(...))` và cập nhật Notification tương ứng.
  4. Lắng nghe `workManager.getWorkInfosForUniqueWorkFlow(UNIQUE_NAME)` trong ViewModel.
- **Files liên quan**: `core/storage/src/main/java/com/epubpro/core/storage/worker/EpubImportWorker.kt`, `feature/library/src/main/java/com/epubpro/feature/library/LibraryViewModel.kt`

### Cách tích hợp thêm một API Endpoint Backend mới
- **Ngày**: 2026-08-17
- **Bước thực hiện**:
  1. Khai báo DTO và method trong `OnlineNovelApiService.kt` (dùng Retrofit annotations).
  2. Khai báo Model và method trong Domain (`OnlineModels.kt` & `OnlineNovelRepository.kt`).
  3. Triển khai ánh xạ DTO -> Domain Model trong `OnlineNovelRepositoryImpl.kt`.
  4. Gọi từ ViewModel tương ứng với xử lý lỗi `Result<T>` và hiển thị lên Compose UI.
- **Files liên quan**: `core/storage/src/main/java/com/epubpro/core/storage/network/OnlineNovelApiService.kt`, `domain/src/main/java/com/epubpro/domain/repository/OnlineNovelRepository.kt`

### Cách deploy FastAPI Backend lên Render.com kết hợp Cloudflare R2
- **Ngày**: 2026-08-18
- **Bước thực hiện**:
  1. Đẩy code Backend lên GitHub repo (có `requirements.txt`).
  2. Tạo Web Service trên Render.com (Runtime: Python 3, Build: `pip install -r requirements.txt`, Start: `uvicorn main:app --host 0.0.0.0 --port $PORT`).
  3. Điền Environment Variables (`R2_ACCOUNT_ID`, `R2_ACCESS_KEY_ID`, `R2_SECRET_KEY`, `R2_BUCKET_NAME`).
  4. Lấy link public `https://xxx.onrender.com/api/v1/` cấu hình vào App.
- **Files liên quan**: `core/storage/src/main/java/com/epubpro/core/storage/ServerPreferencesManager.kt`

### Điều tra một job upload bất đồng bộ bằng logcat
- **Ngày**: 2026-08-20
- **Bước thực hiện**:
  1. Lọc logcat theo PID `com.epubpro.app` và tìm POST upload, GET polling, `Worker result`.
  2. Lấy `job_id` từ URL polling hoặc response POST.
  3. Gọi `GET /library/import-jobs/{job_id}` để đọc `status`, `error_message`, `current_chapter` và `total_chapters`.
  4. Phân biệt HTTP transport thành công với job xử lý thất bại ở backend.
- **Files liên quan**: `core/storage/src/main/java/com/epubpro/core/storage/worker/EpubImportWorker.kt`, `core/storage/src/main/java/com/epubpro/core/storage/network/OnlineNovelApiService.kt`

---

## Patterns

### Smart Dialog Dismissal with Background Worker Progress Pattern
- **Ngày**: 2026-08-19
- **Chi tiết**: Khi Compose UI hiển thị Dialog theo dõi một background Worker, việc chỉ set `_state.value = null` sẽ bị ghi đè lại mỗi khi Worker emit progress mới. Áp dụng cờ `isDialogDismissedByUser = true` trong ViewModel khi bấm "Chạy ngầm", bỏ qua các progress updates tiếp theo trên UI nhưng vẫn giữ Worker chạy và cập nhật Notification. Reset cờ khi bắt đầu job mới hoặc khi job hoàn tất/hủy.
- **Files liên quan**: `feature/library/src/main/java/com/epubpro/feature/library/LibraryViewModel.kt`

### DNS-over-HTTPS (DoH) Fallback & Cache TTL Pattern
- **Ngày**: 2026-08-18
- **Chi tiết**: Cấu hình custom `okhttp3.Dns` với logic 2 tầng: tầng 1 gọi `Dns.SYSTEM` (lọc IPv4), tầng 2 fallback DoH Cloudflare (`1.1.1.1`) và Google (`8.8.8.8`). Sử dụng `CachedDnsEntry` kèm TTL (10 phút) và giải phóng `HttpURLConnection.disconnect()` trong khối `finally`.
- **Files liên quan**: `core/storage/src/main/java/com/epubpro/core/storage/network/FallbackDns.kt`

### Multi-Source Add Book BottomSheet Pattern
- **Ngày**: 2026-08-17
- **Chi tiết**: Thay vì mở trực tiếp File Picker khi bấm nút Add (`+`), mở một Material 3 `ModalBottomSheet` hiển thị danh sách các nguồn nhập sách (Kho truyện Online, File máy, Upload lên Server) giúp mở rộng tính năng mà không phá vỡ UX cũ.
- **Files liên quan**: `feature/library/src/main/java/com/epubpro/feature/library/AddBookBottomSheet.kt`

### Cleanup và Cancellation cho CoroutineWorker
- **Ngày**: 2026-11-25
- **Chi tiết**: Không xóa file tạm trong `finally`: worker có ràng buộc mạng bị WorkManager dừng khi mất mạng rồi chạy lại, lần chạy lại sẽ không còn file. Chỉ dọn ở kết quả cuối (success/failure); khi bị hủy thì ném lại `CancellationException` và giữ file. File của lần upload bị người dùng hủy được `EpubImportScheduler` dọn bằng `clearUploadStaging()` ở lần enqueue kế tiếp (khi không còn work chưa kết thúc). Upload xong ghi `jobId` ra `<file>.job` để lần chạy lại chỉ polling tiếp, không upload trùng.
- **Files liên quan**: `core/storage/src/main/java/com/epubpro/core/storage/worker/EpubImportWorker.kt`, `core/storage/src/main/java/com/epubpro/core/storage/worker/EpubImportScheduler.kt`, `core/storage/src/main/java/com/epubpro/core/storage/EpubStorageManager.kt`

### Stateless Candidate Connection Testing with Request-Scoped Base URL Override
- **Ngày**: 2026-08-21
- **Chi tiết**: Để kiểm tra kết nối server ứng viên trong `ServerSettingsDialog` mà không gây side-effect (không ghi đè SharedPreferences trước khi người dùng bấm Lưu), sử dụng header nội bộ `X-EpubPro-Base-Url-Override` qua `DynamicBaseUrlInterceptor`. Interceptor phát hiện header này, trỏ URL request tới server ứng viên và bóc xóa header trước khi phát ra mạng.
- **Files liên quan**: `core/storage/src/main/java/com/epubpro/core/storage/network/DynamicBaseUrlInterceptor.kt`, `core/storage/src/main/java/com/epubpro/core/storage/network/OnlineNovelApiService.kt`, `core/storage/src/main/java/com/epubpro/core/storage/network/OnlineNovelRepositoryImpl.kt`

### Server Settings Dialog UX & Accessibility Pattern
- **Ngày**: 2026-08-21
- **Chi tiết**: Trong `ServerSettingsDialog`, dùng `rememberSaveable` cho text URL tránh mất dữ liệu khi xoay màn hình; bọc bằng `verticalScroll` + `heightIn(max = 640.dp)` và `FlowRow` tránh vỡ layout trên màn hình nhỏ/landscape; gắn cờ `testInProgress` để disable inputs/buttons khi đang ping; gắn `semantics { liveRegion = LiveRegionMode.Polite }` cho thông báo trạng thái.
- **Files liên quan**: `feature/profile/src/main/java/com/epubpro/feature/profile/ServerSettingsDialog.kt`

### One-Shot UI Event Handling via Resource-Based UserMessage Channel
- **Ngày**: 2026-08-21
- **Chi tiết**: Thay vì lưu chuỗi thông báo hoặc error string trực tiếp trong UI state (dễ bị emit lại khi recompose hoặc config change, hoặc hardcode chuỗi), sử dụng data class `UserMessage(@StringRes val textRes: Int, val formatArgs: List<Any> = emptyList())` gửi qua `Channel<UserMessage>(Channel.BUFFERED)` trong ViewModel. Giao diện Compose thu thập qua `LaunchedEffect` và hiển thị Snackbar bằng `stringResource(msg.textRes, *msg.formatArgs.toTypedArray())`.
- **Files liên quan**: `feature/library/src/main/java/com/epubpro/feature/library/UserMessage.kt`, `feature/library/src/main/java/com/epubpro/feature/library/online/OnlineLibraryViewModel.kt`, `feature/library/src/main/java/com/epubpro/feature/library/online/OnlineLibraryScreen.kt`

### Robust Download & Temp File Cleanup with NonCancellable Dispatchers
- **Ngày**: 2026-08-21
- **Chi tiết**: Khi import truyện online đã tải về vào Room và FTS5, bọc thao tác trong try-catch bắt riêng `CancellationException` để rethrow, đồng thời thực hiện xóa file tạm trong khối catch và hủy bỏ bằng `withContext(NonCancellable + Dispatchers.IO) { file.delete() }`. Đảm bảo không rò rỉ dung lượng bộ nhớ tạm khi user thoát màn hình giữa chừng hoặc lỗi phân tích metadata.
- **Files liên quan**: `feature/library/src/main/java/com/epubpro/feature/library/online/OnlineLibraryViewModel.kt`

