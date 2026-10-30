# P2 Advanced Reader (CBZ/Manga, Trình duyệt web novel, Convert Hán-Việt, OCR dịch truyện tranh)

> Tổng hợp kiến thức về nhóm tính năng P2: đọc truyện tranh CBZ, trình duyệt bóc tách truyện web và lưu EPUB, Convert Hán-Việt offline cho EPUB tiếng Trung, OCR + dịch bóng thoại truyện tranh.
> Cập nhật lần cuối: 2026-10-30

---

## Architecture

### CBZ giữ nguyên archive, Reader riêng
- **Ngày**: 2026-10-29
- **Chi tiết**: CBZ không chuyển sang EPUB. `:core:comic` (đọc ZIP, natural sort, `ComicInfo.xml`, giải mã ảnh) + `:feature:comic` (Compose Reader: lật trái→phải, phải→trái, webtoon). `BookSourceFormat.CBZ` + `isComic`. Tiến độ lưu **trang vào `chapterIndex`**, `currentCfi = "cbz:page=N"`, `totalChapters = pageCount` để Library ("đang đọc/đã xong") và ledger đồng bộ Drive dùng chung schema, không cần migration Room.
- **Files liên quan**: `core/comic/`, `feature/comic/`, `domain/.../ComicModels.kt`

### Định tuyến Reader theo định dạng ở mọi lối vào
- **Ngày**: 2026-10-29
- **Chi tiết**: Không thêm guard trong `ReaderViewModel` (file lớn, có contract chuyển chương). Thay vào đó mọi lối vào chọn route theo `sourceFormat`: `LibraryScreen.onBookClick(bookId, format)`, import từ app ngoài (đọc `KEY_SOURCE_FORMAT` của worker), auto-resume sách gần nhất → kênh `openComicRequests` trong `MainIntentViewModel`. Widget TTS chỉ dành cho sách chữ.
- **Files liên quan**: `app/.../AppNavigation.kt`, `app/.../MainActivity.kt`

### Tự giải mã ảnh trang thay vì Coil
- **Ngày**: 2026-10-29
- **Chi tiết**: `ComicPageDecoder`: downsample theo chiều rộng đích, giới hạn 16 MP, RGB_565 khi > 4 MP, cắt ảnh cao thành dải 2048 px bằng `BitmapRegionDecoder` để tránh "too large bitmap"/vượt texture GPU. Cache `LruCache` 1/6 heap, `Semaphore(2)` giới hạn giải mã song song. GIF không hỗ trợ giải mã theo vùng → fallback decode cả ảnh.
- **Files liên quan**: `core/comic/.../ComicPageDecoder.kt`, `feature/comic/.../ComicReaderViewModel.kt`

### Bóc tách web bằng jsoup thay Readability.js
- **Ngày**: 2026-10-29
- **Chi tiết**: `ArticleExtractor` chấm điểm văn bản trực tiếp (text node + inline + `<p>` con), trừ mật độ link, cộng/trừ theo class/id → bắt được kiểu `<div>` + `<br>` của site truyện mà Readability gốc bỏ sót. Không inject JS vào trang lạ, thuần JVM nên unit test bằng fixture. `ChapterLinkDetector` phải chạy **trước** khi dọn nav/footer (nút chuyển chương nằm ở đó).
- **Files liên quan**: `core/webnovel/.../ArticleExtractor.kt`, `ChapterLinkDetector.kt`

### Lưu truyện web = EPUB, HTTP client riêng chống SSRF và hỗ trợ tải nối tiếp
- **Ngày**: 2026-10-30
- **Chi tiết**: `WebNovelDownloadWorker` dùng `WebNovelCrawler` + `EpubWriter` tạo EPUB chuẩn. Dùng `OkHttpClient` riêng (không gắn auth interceptor của server EpubPro), lọc địa chỉ qua `PublicAddressPolicy` ở cả `Dns` và network interceptor (kiểm tra socket IP thực tế sau redirect) để chặn SSRF vào localhost/LAN/CGNAT/ULA. `WebNovelSourceStore` lưu `startUrl`, `lastChapterUrl`, `userAgent` (khớp WebView để giữ cookie Cloudflare) phục vụ tải thêm chương mới và cập nhật qua `LocalBookReplacement(..., keepAiCache = true)`.
- **Files liên quan**: `core/storage/.../worker/WebNovelDownloadWorker.kt`, `core/webnovel/.../PublicAddressPolicy.kt`, `core/storage/.../WebNovelSourceStore.kt`, `core/storage/.../worker/LocalBookReplacement.kt`

### Convert Hán-Việt: từ điển nhị phân mmap, đồng bộ Sanitize & TTS nền
- **Ngày**: 2026-10-30
- **Chi tiết**: `:core:convert` thuần JVM. `PhraseDictionary`: khóa trong `CharArray` liền khối + offset `IntArray`, nghĩa UTF-8 trong vùng mmap (~20 MB heap cho ~1,5 triệu dòng). Tầng ưu tiên: Name truyện → Names → VietPhrase → Phiên âm. Trong `ReaderViewModel`, `convertChapterHtml` trả kèm bản đã `EpubHtmlSanitizer.sanitize` (`preSanitizedDisplayedHtml` và `sanitized*ChapterHtml`) để key commit chương kề trong `EpubProWebView` khớp với key nạp chương mới. `TtsChapterPlaybackCoordinator` cũng gọi `HtmlConverter.convert` khi tự chuyển chương dưới nền.
- **Files liên quan**: `core/convert/`, `core/storage/.../ConvertDictionaryStore.kt`, `feature/reader/.../ReaderViewModel.kt`, `core/playback/.../TtsChapterPlaybackCoordinator.kt`

### OCR dịch truyện tranh bằng ML Kit qua Play services
- **Ngày**: 2026-10-29
- **Chi tiết**: Dùng bản `play-services-mlkit-text-recognition*` (model do Play services tải, APK không phình) + `com.google.mlkit:translate` (gói ~30 MB/ngôn ngữ, **chỉ tải sau khi người dùng xác nhận**). OCR chạy trên bản giải mã riêng (rộng 1280 px) theo từng dải, tọa độ chuẩn hóa 0..1 → overlay đúng vị trí ở mọi mức zoom/chế độ. `BubbleGrouper` gom dòng bằng union-find. Engine Hán-Việt tái dùng `ConvertDictionaryStore`. Cache JSON theo (sách, trang, ngôn ngữ, engine).
- **Files liên quan**: `core/ocr/`, `core/storage/.../ComicTranslationStore.kt`, `feature/comic/.../ComicTranslationUi.kt`

---

## Bugs & Solutions

### CBZ mở nhầm vào Reader EPUB sau import/auto-resume
- **Ngày**: 2026-10-29
- **Vấn đề**: Sau khi import từ app ngoài, `MainActivity` dispatch `TtsOpenBookRequest` → luôn vào `Screen.Reader` (EPUB).
- **Root cause**: Các luồng mở sách giả định mọi sách là EPUB.
- **Fix**: Kiểm tra `KEY_SOURCE_FORMAT` / `book.sourceFormat`, CBZ đi kênh `openComicRequests` → `Screen.ComicReader`.
- **Files liên quan**: `app/.../MainActivity.kt`

### Code duyệt toàn bộ sách giả định EPUB
- **Ngày**: 2026-10-29
- **Vấn đề**: `ensureMissingCoversExtracted` gọi `epubEngine.extractCoverImage` trên CBZ (exception làm dừng vòng lặp cho sách khác); danh sách Book Bible liệt kê cả truyện tranh.
- **Root cause**: Vòng lặp `getAllBooks()` không lọc định dạng.
- **Fix**: Lọc `!book.sourceFormat.isComic`. Khi thêm định dạng mới, grep `getAllBooks()` và `epubEngine.` để rà.
- **Files liên quan**: `feature/library/.../LibraryViewModel.kt`, `feature/bookbible/.../StoryProgressViewModel.kt`

### Convert HTML: dấu câu không đổi và chữ dính nhau qua thẻ inline
- **Ngày**: 2026-10-29
- **Vấn đề**: `<p>我是<b>你</b>。</p>` ra `Ta lànhĩ。`.
- **Root cause**: Text node chỉ có dấu câu bị bỏ qua (chỉ xét chữ Hán); mỗi text node convert độc lập nên không biết node trước kết thúc bằng từ.
- **Fix**: `needsConversion` xét cả dấu câu toàn góc; truyền `afterWord`/`sentenceStart` xuyên text node, reset tại thẻ khối.
- **Files liên quan**: `core/convert/.../HtmlConverter.kt`, `HanVietConverter.kt`

### Convert Hán-Việt làm WebView reload khi vuốt chương kề và TTS ngầm đọc chữ Hán gốc
- **Ngày**: 2026-10-30
- **Vấn đề**: Khi bật Convert, vuốt qua biên chương bị reload lại `WebView`; khi tắt màn hình nghe TTS, sang chương mới TTS đọc chữ Hán gốc.
- **Root cause**: `EpubProWebView` tính `locallyCommittedHtmlKey` từ HTML chưa sanitize trong khi `newHtmlKey` tính từ bản đã sanitize; `TtsChapterPlaybackCoordinator` chưa tích hợp `HtmlConverter`.
- **Fix**: Sanitize ngay trong `convertChapterHtml` (`ConvertedChapter(html, sanitized)`), truyền vào `preSanitizedDisplayedHtml` + `sanitized*ChapterHtml`, và thêm `loadConvertedHtml` trong `TtsChapterPlaybackCoordinator`.
- **Files liên quan**: `feature/reader/.../ReaderViewModel.kt`, `feature/reader/.../ReaderScreen.kt`, `core/playback/.../TtsChapterPlaybackCoordinator.kt`

### Race condition đóng `ComicArchive` và giữ zoom ở trang liền kề
- **Ngày**: 2026-10-30
- **Vấn đề**: Thoát nhanh màn hình đọc truyện tranh khi đang giải mã/OCR có thể rò `ZipFile` hoặc ném `IllegalStateException`; lật sang trang kế rồi quay lại trang cũ vẫn bị phóng to.
- **Root cause**: `openArchive()` gán `archive` ngoài khóa đồng bộ với `onCleared()`; `HorizontalPager` giữ trang liền kề trong composition (`beyondBoundsPageCount = 1`).
- **Fix**: Gắn `attachArchive` dưới `archiveLock` ngay trong khối I/O, gói mọi lỗi đọc `ZipFile` đã đóng về `ComicArchiveException`, và dùng `LaunchedEffect(isSettledPage)` để reset `scale`/`offset` khi rời trang.
- **Files liên quan**: `feature/comic/.../ComicReaderViewModel.kt`, `core/comic/.../ComicArchive.kt`, `feature/comic/.../ComicPages.kt`

### File `.zip` ebook chữ vượt rào 100 MiB và báo sai giới hạn dung lượng
- **Ngày**: 2026-10-30
- **Vấn đề**: File EPUB gửi kèm MIME `application/zip` được hưởng ngưỡng 1 GiB của CBZ; file CBZ > 1 GiB lại không khớp chuỗi lỗi `"100 MiB"`.
- **Root cause**: Kiểm tra dung lượng theo phần mở rộng trước khi sniff nội dung, và so khớp chuỗi lỗi cứng.
- **Fix**: Dùng `BookFileTooLargeException(limitBytes)` mang theo `limitMiB`, và kiểm tra lại `sourceFile.length() <= MAX_INPUT_BYTES` trong `LocalBookImportScheduler.enqueue` sau khi `BookFormatSniffer.sniff` xác định không phải `CBZ`.
- **Files liên quan**: `core/storage/.../BookFileTooLargeException.kt`, `core/storage/.../EpubStorageManager.kt`, `core/storage/.../worker/LocalBookImportScheduler.kt`, `app/.../MainActivity.kt`

### ML Kit báo UNAVAILABLE lần đầu dùng
- **Ngày**: 2026-10-29
- **Vấn đề**: OCR thất bại ngay lần đầu chọn ngôn ngữ CJK.
- **Root cause**: Play services còn đang tải model OCR (`MlKitException.UNAVAILABLE`).
- **Fix**: Bọc thành `OcrModelUnavailableException`, VM hiện thông báo "thử lại sau ít phút" thay vì lỗi chung.
- **Files liên quan**: `core/ocr/.../ComicTextRecognizer.kt`

### Phím âm lượng ngừng lật trang sau khi đổi chế độ đọc
- **Ngày**: 2026-10-29
- **Vấn đề**: Sau khi đổi mode (bọc trong `key(mode)`), focus mất.
- **Root cause**: Node `focusRequester` được tạo lại trong `key`, nhưng `requestFocus` chỉ chạy một lần bên ngoài.
- **Fix**: Đặt `LaunchedEffect(focusRequester, showControls)` bên trong block `key`.
- **Files liên quan**: `feature/comic/.../ComicReaderScreen.kt`

### TopAppBar bị đệm thanh trạng thái hai lần
- **Ngày**: 2026-10-29
- **Vấn đề**: Thanh trên của Reader truyện tranh thừa khoảng trống.
- **Root cause**: `TopAppBar` M3 đã tự áp `windowInsets` statusBars; thêm `Modifier.statusBarsPadding()` là thừa.
- **Fix**: Bỏ `statusBarsPadding()`, giữ `navigationBarsPadding()` cho thanh dưới (giống Reader EPUB).
- **Files liên quan**: `feature/comic/.../ComicReaderScreen.kt`

### Smart cast lỗi với property từ module khác
- **Ngày**: 2026-10-29
- **Vấn đề**: `Smart cast to 'Int' is impossible, because 'info.entryCount' is a public API property declared in different module`.
- **Root cause**: Kotlin không smart-cast property public của module khác.
- **Fix**: Dùng `info.entryCount?.let { ... } ?: ...`.
- **Files liên quan**: `feature/profile/.../convert/ConvertDictionaryScreen.kt`

---

## How-To

### Build dự án trên máy này
- **Ngày**: 2026-10-29
- **Bước thực hiện**:
  1. JDK mặc định (25) làm Kotlin 1.9.23 lỗi; dùng JBR 17: `export JAVA_HOME="/c/Users/haidu/.jdks/jbr-17.0.11"`.
  2. `./gradlew :app:assembleDebug` (chạy nền vì mất ~3 phút lần đầu).
  3. Đếm kết quả test bằng cách đọc `*/build/test-results/test*/*.xml` (Gradle log không in số test).
  4. Cài máy thật: `adb install -r app/build/outputs/apk/debug/app-debug.apk`; log lỗi: `adb logcat -d -t 500 *:E`.
- **Files liên quan**: `build-logic/`, `gradle/libs.versions.toml`

### Thêm một định dạng sách mới
- **Ngày**: 2026-10-29
- **Bước thực hiện**:
  1. Thêm giá trị vào `BookSourceFormat` (cột String, không cần migration; bản app cũ fallback về EPUB).
  2. `BookFormatSniffer`: nhận theo extension và theo nội dung (ZIP có OPF/HTML là EPUB).
  3. `EpubStorageManager.importLocalBookSource`: mime → extension, giới hạn dung lượng riêng.
  4. `LocalBookImportScheduler`: chọn file đích; `LocalBookImportWorker`: nhánh import riêng, idempotent khi process death.
  5. Intent-filter + `ExternalBookImportHandler`; nhãn trong `LibraryScreen` (`when` exhaustive).
  6. Route Reader + mọi lối vào mở sách; lọc các vòng lặp `getAllBooks()` giả định EPUB.
  7. Dọn prefs/cache theo sách trong `LibraryViewModel.deleteBook`.
- **Files liên quan**: `core/book-converter/`, `core/storage/.../worker/`, `app/`

### Thêm module Gradle mới
- **Ngày**: 2026-10-29
- **Bước thực hiện**:
  1. `include(":core:xxx")` trong `settings.gradle.kts`.
  2. Plugin convention: `epubpro.android.library` (+ `.compose`, `.hilt`) hoặc `epubpro.kotlin.jvm.library` cho module thuần JVM (source ở `src/main/java`).
  3. Khai `namespace`; không cần `AndroidManifest.xml` cho library.
  4. Thêm dependency vào module dùng; dùng `api(...)` nếu kiểu dữ liệu lộ ra public API (ví dụ `core:storage` → `core:convert`).
- **Files liên quan**: `settings.gradle.kts`, `build-logic/src/main/kotlin/`

### Thêm một "phiên bản nội dung" chương vào Reader EPUB
- **Ngày**: 2026-10-30
- **Bước thực hiện**:
  1. Thêm field HTML + field `SanitizedEpubHtml` tương ứng vào `ReaderUiState`, cập nhật `displayedChapterHtml` và `preSanitizedDisplayedHtml`.
  2. Tính bản mới kèm `EpubHtmlSanitizer.sanitize` **trước** `_uiState.update` trong `loadBookData` và `onChapterSelected` để tránh nháy.
  3. Preview chương kề: biến đổi trong `adjacentPreloadJob` và gán `sanitized*ChapterHtml` bằng bản sanitize của chương đã biến đổi để giữ DOM khi vuốt qua ranh giới chương.
  4. Đổi nội dung mà muốn giữ trang: set `initialPageRequest = currentPageInChapter`.
  5. Tích hợp vào `TtsChapterPlaybackCoordinator.loadChapter` để TTS chạy nền đồng bộ nội dung.
  6. Chạy test `feature:reader`, `core:reader-renderer`, `core:playback` (có contract test chuyển chương).
- **Files liên quan**: `feature/reader/.../ReaderViewModel.kt`, `ReaderScreen.kt`, `core/playback/.../TtsChapterPlaybackCoordinator.kt`

---

## Patterns

### Sửa file CRLF bằng script Python giữ kiểu xuống dòng
- **Ngày**: 2026-10-29
- **Chi tiết**: Phần lớn file trong repo là CRLF. Dùng helper `edit(path, [(old, new)])` tự nhận `\r\n`, assert mỗi `old` xuất hiện đúng 1 lần, ghi lại với `newline=''`; `add_strings()` chèn `<string>` trước `</resources>` và chặn key trùng. Heredoc Python dài trong Bash dễ lỗi quote → ghi script ra file rồi chạy. Sau khi sửa, kiểm tra không lẫn LF bằng `grep -c $'\r$'`.

### Tách logic thuần khỏi Composable/ViewModel để unit test
- **Ngày**: 2026-10-29
- **Chi tiết**: Vùng chạm, tiến độ, trang mở đầu, chuẩn hóa địa chỉ, gom bóng thoại, cỡ chữ bóng thoại... đặt ở hàm `internal` top-level (`ComicReaderLogic.kt`, `BrowserAddress.kt`, `BubbleGrouper`) → test JVM không cần Robolectric. Logic đọc file/HTML đặt ở module thuần JVM khi có thể (`core:convert`, `core:webnovel` chỉ dùng jsoup).
- **Files liên quan**: `feature/comic/.../ComicReaderLogic.kt`, `feature/browser/.../BrowserAddress.kt`

### Lỗi kỹ thuật → string resource ở ViewModel
- **Ngày**: 2026-10-29
- **Chi tiết**: Exception trong core mang message kỹ thuật (không hiển thị). ViewModel bắt và map sang `@StringRes` trong state (`errorRes`, `messageRes`), UI hiển thị rồi gọi `consumeMessage()`. Nhãn enum domain (`ComicReadingMode`, `ComicSourceLanguage`...) map sang string ở UI bằng extension `labelRes()`, không đặt `displayName` tiếng Việt trong domain.

### Dữ liệu riêng theo sách: băm ID + dọn khi xóa
- **Ngày**: 2026-10-29
- **Chi tiết**: Cache/Name/bản dịch lưu dưới `filesDir/<feature>/<sha256(bookId) 32 ký tự>`; prefs theo sách dùng key `prefix_<bookId>`. Mỗi store có `clear/clearBook(bookId)` và được gọi trong `LibraryViewModel.deleteBook`. Ghi file luôn qua `*.tmp` rồi `renameTo`.
- **Files liên quan**: `core/storage/.../ComicPreferencesStore.kt`, `ConvertDictionaryStore.kt`, `ComicTranslationStore.kt`

### Chờ `Task` của Play services trong coroutine
- **Ngày**: 2026-10-29
- **Chi tiết**: Dự án không có `kotlinx-coroutines-play-services`; dùng extension nội bộ thay vì thêm dependency.
- **Ví dụ code**:
  ```kotlin
  internal suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { c ->
      addOnSuccessListener { c.resume(it) }
      addOnFailureListener { c.resumeWithException(it) }
      addOnCanceledListener { c.cancel() }
  }
  ```
- **Files liên quan**: `core/ocr/.../ComicTextRecognizer.kt`

### Overlay vẽ đè đúng khung trang ở mọi chế độ
- **Ngày**: 2026-10-29
- **Chi tiết**: `PageTiles` nhận slot `ComicPageOverlay = @Composable BoxScope.(pageIndex) -> Unit`; Box bao ảnh có đúng kích thước trang và nằm trong `graphicsLayer` zoom, overlay dùng `matchParentSize` + tọa độ chuẩn hóa 0..1 → tự khớp khi zoom và ở webtoon. Overlay con `clickable` tiêu thụ tap nên không kích hoạt vùng chạm lật trang.
- **Files liên quan**: `feature/comic/.../ComicPages.kt`, `ComicTranslationUi.kt`

### WebView an toàn cho trang bên thứ ba
- **Ngày**: 2026-10-29
- **Chi tiết**: Bật JavaScript (site cần) nhưng **không** `addJavascriptInterface`; `allowFileAccess/allowContentAccess = false`; `MIXED_CONTENT_NEVER_ALLOW`; chặn mọi scheme ngoài http(s) trong `shouldOverrideUrlLoading`; ô địa chỉ chuyển `javascript:`/`file:` thành tìm kiếm. Lấy HTML bằng `evaluateJavascript("document.documentElement.outerHTML")` và giải chuỗi JSON qua `JSONArray("[$value]")`.
- **Files liên quan**: `feature/browser/.../BrowserScreen.kt`
