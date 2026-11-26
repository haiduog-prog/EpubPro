# Library Screen & Book Management

> Tổng hợp kiến thức về giao diện Thư viện, trích xuất ảnh bìa EPUB và quản lý danh mục sách theo chuẩn Stitch UI.
> Cập nhật lần cuối: 2026-11-26

---

## Architecture

### Trích xuất ảnh bìa EPUB chuẩn đa phiên bản
- **Ngày**: 2026-08-27
- **Chi tiết**: `EpubPackageStructureParser` tự động nhận diện `coverEntry` theo 4 cấp độ: (1) EPUB 3 manifest item có `properties="cover-image"`, (2) EPUB 2 thẻ `<meta name="cover" content="{id}">`, (3) Manifest item có media-type hình ảnh chứa từ khóa `cover`, (4) Quét tệp nén tìm `cover.*` / `frontcover.*`. Ảnh bìa sau đó được `EpubEngine` giải nén an toàn vào thư mục app-private `context.filesDir/covers/` để cấp phát `coverPath`.
- **Files liên quan**: `core/epub/src/main/java/com/epubpro/core/reader/engine/EpubPackageStructureParser.kt`, `core/epub/src/main/java/com/epubpro/core/reader/engine/EpubEngine.kt`

### Luồng ưu tiên ảnh bìa cho truyện Online & Chuyển đổi
- **Ngày**: 2026-08-27
- **Chi tiết**: Khi hoàn tất tải hoặc chuyển đổi sách, `OnlineNovelDownloadWorker` và import workers áp dụng thứ tự ưu tiên gán `coverPath`: Ảnh bìa trích xuất từ file EPUB nội bộ > Ảnh bìa từ bản ghi trước đó trong Room > URL ảnh bìa danh mục online (`KEY_COVER_URL`).
- **Files liên quan**: `core/storage/src/main/java/com/epubpro/core/storage/worker/OnlineNovelDownloadWorker.kt`, `core/storage/src/main/java/com/epubpro/core/storage/worker/OnlineNovelDownloadScheduler.kt`

### Kiến trúc giao diện Thư viện chuẩn Stitch (Editorial List View)
- **Ngày**: 2026-08-27
- **Chi tiết**: Màn hình Thư viện sử dụng cấu trúc: TopAppBar Serif mang phong cách tạp chí biên tập, ô tìm kiếm có nút xóa nhanh, thanh Filter Chips ngang tính toán số lượng theo thời gian thực (Tất cả, Đang đọc, Chưa đọc, Đã đọc xong), Sync Progress Widget hiển thị tác vụ nền và danh sách `LazyColumn` với Horizontal Book Card hiển thị tiến độ đọc chi tiết.
- **Files liên quan**: `feature/library/src/main/java/com/epubpro/feature/library/LibraryScreen.kt`, `feature/library/src/main/java/com/epubpro/feature/library/LibraryViewModel.kt`

### Lối tắt sách trên launcher (ghim + động) qua alias nội bộ
- **Ngày**: 2026-11-26
- **Chi tiết**: Feature chỉ biết `BookShortcutPublisher` (domain); cài đặt `BookShortcutManager` nằm ở `:app` vì Intent phải trỏ tới alias `InternalEntryActivity` (`exported="false"`). Intent chỉ mang `bookId` (`BookEntryContract.ACTION_OPEN_BOOK_ENTRY`); `MainIntentViewModel.openBookEntry` gửi `TtsOpenBookRequest(chapterIndex = NO_CHAPTER_OVERRIDE)` để `ReaderViewModel` tự khôi phục chương/trang đã lưu, còn CBZ/`ONLINE_COMIC_` đi kênh `openComicRequests`. Một collector `getAllBooks()` trong Application scope (bỏ `lastReadAt` khỏi state + `distinctUntilChanged`) đồng bộ 4 lối tắt động, vô hiệu hoá lối tắt ghim của sách đã xoá (app không tự gỡ được lối tắt ghim) và vẽ lại icon khi bìa đổi.
- **Files liên quan**: `app/src/main/java/com/epubpro/app/shortcut/BookShortcutManager.kt`, `app/src/main/java/com/epubpro/app/shortcut/BookEntryContract.kt`, `app/src/main/java/com/epubpro/app/MainIntentViewModel.kt`

### Bìa chuẩn cho truyện web tự đóng gói EPUB
- **Ngày**: 2026-11-26
- **Chi tiết**: EPUB do `EpubWriter` tạo không chứa ảnh, nên bìa lấy từ trang giới thiệu truyện: `WebNovelCoverFinder.findLandingUrl` chọn link cùng host là thư mục cha gần nhất của URL chương (breadcrumb), `findCoverUrl` ưu tiên `og:image` → `twitter:image` → `itemprop=image` → JSON-LD `Book` → `link[rel=image_src]`, bỏ tên file giống logo. `WebNovelCoverFetcher` tải bằng HTTP client public-only của `WebNovelDownloadWorker`, gửi `Referer`, loại ảnh ngang/nhỏ (logo, banner), thu nhỏ ≤ 900 px rồi lưu JPEG. Truyện tải mới lấy bìa ngay; truyện cũ và truyện lưu từ trình duyệt do `WebNovelCoverBackfillWorker` xử lý, hẹn thử lại 1 ngày khi lỗi mạng và 7 ngày khi site không có bìa. Chỉ xét sách có `WebNovelSourceStore` hoặc EPUB đúng cấu trúc `OEBPS/text/chapter_*` để không gửi request cho EPUB người dùng tự nhập có `dc:source`.
- **Files liên quan**: `core/webnovel/src/main/java/com/epubpro/core/webnovel/WebNovelCoverFinder.kt`, `core/storage/src/main/java/com/epubpro/core/storage/worker/WebNovelCoverFetcher.kt`, `core/storage/src/main/java/com/epubpro/core/storage/worker/WebNovelCoverBackfillWorker.kt`

---

## Bugs & Solutions

### Mất ảnh bìa sách trong toàn bộ Tab Thư viện
- **Ngày**: 2026-08-27
- **Vấn đề**: Toàn bộ sách trong thư viện đều không tải được ảnh bìa, chỉ hiển thị bìa chữ gradient mặc định.
- **Root cause**: `BookCard` trong `LibraryScreen` gọi cứng `GeneratedBookCover` mà không dùng `AsyncImage`; `EpubEngine` gán cứng `coverPath = null` khi parse metadata; và sách cũ trong Room bị thiếu đường dẫn ảnh bìa.
- **Fix**: Tích hợp `AsyncImage` Coil trong `BookCard`, nâng cấp bộ parser cấu trúc OPF để trích xuất ảnh bìa vào đĩa, đồng thời thêm hàm `ensureMissingCoversExtracted()` chạy nền trong `LibraryViewModel.init` để tự động bù ảnh bìa cho sách cũ.
- **Files liên quan**: `feature/library/src/main/java/com/epubpro/feature/library/LibraryScreen.kt`, `core/epub/src/main/java/com/epubpro/core/reader/engine/EpubEngine.kt`, `feature/library/src/main/java/com/epubpro/feature/library/LibraryViewModel.kt`

### Mất ảnh bìa catalog khi tải truyện online không có cover nội bộ
- **Ngày**: 2026-08-27
- **Vấn đề**: Khi tải truyện online về máy mà file EPUB từ server không nhúng ảnh bìa chuẩn bên trong, sách import vào Room bị mất trắng ảnh thumbnail từ danh mục.
- **Root cause**: `OnlineNovelDownloadWorker` không nhận `coverUrl` qua WorkData input nên không có fallback.
- **Fix**: Bổ sung `KEY_COVER_URL` vào Scheduler và Worker, gán `finalCoverPath = parsedBook.coverPath ?: previousBook?.coverPath ?: coverUrl`.
- **Files liên quan**: `core/storage/src/main/java/com/epubpro/core/storage/worker/OnlineNovelDownloadWorker.kt`, `core/storage/src/main/java/com/epubpro/core/storage/worker/OnlineNovelDownloadScheduler.kt`

### Nối chương truyện web xoá mất ảnh bìa đã tải
- **Ngày**: 2026-11-26
- **Vấn đề**: Sau "Tải thêm chương" hoặc lưu chương kế tiếp từ trình duyệt, truyện web mất ảnh bìa vừa lấy từ trang nguồn.
- **Root cause**: `LocalBookReplacement.replace` gán `coverPath = parsed.coverPath` từ EPUB mới; EPUB truyện web không có ảnh nên `parsed.coverPath` là `null`.
- **Fix**: Thêm tham số `keepExistingCover` (`parsed.coverPath ?: current.coverPath`), chỉ bật ở hai chỗ nối chương truyện web; nạp lại sách local giữ hành vi cũ.
- **Files liên quan**: `core/storage/src/main/java/com/epubpro/core/storage/worker/LocalBookReplacement.kt`

### Gắn bìa thất bại khi `filePath` của sách đổi trong lúc tải
- **Ngày**: 2026-11-26
- **Vấn đề**: Bìa đã lưu vào `covers/` nhưng không gắn vào sách nếu người dùng nối chương đúng lúc đang tải bìa.
- **Root cause**: `updateCoverPathIfMissing` có điều kiện `filePath = :filePath`; nối chương đổi sang file EPUB mới nên snapshot `Book` cũ không còn khớp.
- **Fix**: Đọc lại sách bằng `getBookById` ngay trước khi tải; nếu cập nhật thất bại mà `filePath` đã đổi và sách vẫn chưa có bìa thì gắn lại theo `filePath` hiện tại. Không giữ `WebNovelMutationLock` trong lúc gọi mạng để không chặn thao tác lưu chương từ trình duyệt.
- **Files liên quan**: `core/storage/src/main/java/com/epubpro/core/storage/worker/WebNovelCoverFetcher.kt`

---

## How-To

### Tích hợp màn hình Stitch vào Jetpack Compose chuẩn dự án
- **Ngày**: 2026-08-27
- **Bước thực hiện**:
  1. Dùng `StitchMCP` / share link lấy mã nguồn HTML, design tokens (màu sắc, typography, spacing, component hierarchy).
  2. Map màu sắc vào Design System (`#D97757` Terracotta -> `MaterialTheme.colorScheme.primary`, nền `#F8F9FA` -> `background`, Card `#FFFFFF` -> `surface`).
  3. Định nghĩa toàn bộ UI string mới vào `core/designsystem/src/main/res/values/strings.xml`, không hardcode text.
  4. Giữ nguyên Architecture (ViewModel, Repository, Room Entities) và chuyển đổi giao diện sang Compose Composable chuẩn (State hoisting, KDoc tiếng Việt, `key` cho List items).
  5. Chạy `./gradlew testDebugUnitTest` xác thực toàn bộ test cases pass 100%.
- **Files liên quan**: `feature/library/src/main/java/com/epubpro/feature/library/LibraryScreen.kt`, `core/designsystem/src/main/res/values/strings.xml`

---

## Patterns

### Coil AsyncImage kèm Fallback Composable an toàn
- **Ngày**: 2026-08-27
- **Chi tiết**: Trong Compose, khi hiển thị ảnh từ file local hoặc URL, sử dụng `remember(model) { mutableStateOf(false) }` với callback `onError = { coverLoadFailed = true }` để fallback mượt sang `GeneratedBookCover` mà không bị nhấp nháy hoặc hiển thị ô trống.
- **Ví dụ code**:
  ```kotlin
  var coverLoadFailed by remember(item.book.coverPath) { mutableStateOf(false) }
  if (!item.book.coverPath.isNullOrBlank() && !coverLoadFailed) {
      AsyncImage(
          model = item.book.coverPath,
          contentDescription = item.book.title,
          contentScale = ContentScale.Crop,
          modifier = Modifier.fillMaxSize(),
          onError = { coverLoadFailed = true }
      )
  } else {
      GeneratedBookCover(
          title = item.book.title,
          author = item.book.author,
          modifier = Modifier.fillMaxSize()
      )
  }
  ```
- **Files liên quan**: `feature/library/src/main/java/com/epubpro/feature/library/LibraryScreen.kt`

### Lazy Data Migration trong ViewModel Background Scope
- **Ngày**: 2026-08-27
- **Chi tiết**: Khi thêm một trường dữ liệu phái sinh (như `coverPath`) cho các bản ghi cũ trong Room Database, thực hiện quét ngầm qua `viewModelScope.launch(Dispatchers.IO)` ngay trong `init` để tự cập nhật database mà không làm chậm luồng khởi động chính của UI.
- **Files liên quan**: `feature/library/src/main/java/com/epubpro/feature/library/LibraryViewModel.kt`

### Bảng màu bìa tự sinh dùng chung Compose và Canvas
- **Ngày**: 2026-11-26
- **Chi tiết**: `GeneratedCoverPalette.gradientFor(title)` (core:designsystem) trả cặp ARGB `Long`; Compose dùng `Color(long)`, icon lối tắt dùng `LinearGradient(top.toInt(), bottom.toInt())`. Giữ công thức `abs(hash % size)` để màu không đổi so với bản cũ `abs(hash) % size` (kể cả `Int.MIN_VALUE`).
- **Files liên quan**: `core/designsystem/src/main/java/com/epubpro/core/designsystem/cover/GeneratedCoverPalette.kt`, `feature/library/src/main/java/com/epubpro/feature/library/components/GeneratedBookCover.kt`

### Launcher mở được lối tắt trỏ tới activity-alias không export
- **Ngày**: 2026-11-26
- **Chi tiết**: Lối tắt ghim/động được hệ thống khởi chạy bằng danh tính app phát hành (log `START ... cmp=.../.InternalEntryActivity ... from uid <app>`), nên alias `exported="false"` vẫn mở được; đã kiểm chứng trên Samsung One UI. Không cần export alias cho lối tắt. Hệ thống tự thêm `FLAG_ACTIVITY_NEW_TASK`; Intent ở `onCreate` hoặc `onNewIntent` tuỳ app đang chạy.
- **Files liên quan**: `app/src/main/AndroidManifest.xml`, `app/src/main/java/com/epubpro/app/MainActivity.kt`
