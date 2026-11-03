# P2 Advanced Reader (CBZ/Manga, Trình duyệt web novel, Convert Hán-Việt, OCR dịch truyện tranh)

> Tổng hợp kiến thức về nhóm tính năng P2: đọc truyện tranh CBZ, trình duyệt bóc tách truyện web và lưu EPUB, Convert Hán-Việt offline cho EPUB tiếng Trung, OCR + dịch bóng thoại truyện tranh.
> Cập nhật lần cuối: 2026-11-03

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
- **Ngày**: 2026-11-03
- **Chi tiết**: `WebNovelDownloadWorker` dùng `WebNovelCrawler` + `EpubWriter` tạo EPUB chuẩn. Dùng `OkHttpClient` riêng (không gắn auth interceptor của server EpubPro), lọc địa chỉ qua `PublicAddressPolicy` ở cả `Dns` và network interceptor (kiểm tra socket IP thực tế sau redirect) để chặn SSRF vào localhost/LAN/CGNAT/ULA. `WebNovelSourceStore` lưu `startUrl`, `lastChapterUrl`, `userAgent` (khớp WebView để giữ cookie Cloudflare) phục vụ tải thêm chương mới và cập nhật qua `LocalBookReplacement(..., keepAiCache = true)`. EPUB cũ sau khi nối **không xóa ngay** (Reader/TTS có thể đang đọc theo đường dẫn): `retireFile` ghi vào danh sách chờ, `purgeRetiredFiles` dọn ở lần nối sau hoặc khi xóa sách, bỏ qua đường dẫn hiện tại và giữ lại file xóa thất bại.
- **Files liên quan**: `core/storage/.../worker/WebNovelDownloadWorker.kt`, `core/webnovel/.../PublicAddressPolicy.kt`, `core/storage/.../WebNovelSourceStore.kt`, `core/storage/.../worker/LocalBookReplacement.kt`

### Convert Hán-Việt: từ điển nhị phân mmap, đồng bộ Sanitize & TTS nền
- **Ngày**: 2026-10-30
- **Chi tiết**: `:core:convert` thuần JVM. `PhraseDictionary`: khóa trong `CharArray` liền khối + offset `IntArray`, nghĩa UTF-8 trong vùng mmap (~20 MB heap cho ~1,5 triệu dòng). Tầng ưu tiên: Name truyện → Names → VietPhrase → Phiên âm. Trong `ReaderViewModel`, `convertChapterHtml` trả kèm bản đã `EpubHtmlSanitizer.sanitize` (`preSanitizedDisplayedHtml` và `sanitized*ChapterHtml`) để key commit chương kề trong `EpubProWebView` khớp với key nạp chương mới. `TtsChapterPlaybackCoordinator` cũng gọi `HtmlConverter.convert` khi tự chuyển chương dưới nền.
- **Files liên quan**: `core/convert/`, `core/storage/.../ConvertDictionaryStore.kt`, `feature/reader/.../ReaderViewModel.kt`, `core/playback/.../TtsChapterPlaybackCoordinator.kt`

### OCR dịch truyện tranh bằng ML Kit qua Play services
- **Ngày**: 2026-10-29
- **Chi tiết**: Dùng bản `play-services-mlkit-text-recognition*` (model do Play services tải, APK không phình) + `com.google.mlkit:translate` (gói ~30 MB/ngôn ngữ, **chỉ tải sau khi người dùng xác nhận**). OCR chạy trên bản giải mã riêng (rộng 1280 px) theo từng dải, tọa độ chuẩn hóa 0..1 → overlay đúng vị trí ở mọi mức zoom/chế độ. `BubbleGrouper` gom dòng bằng union-find. Engine Hán-Việt tái dùng `ConvertDictionaryStore`. Cache JSON theo (sách, trang, ngôn ngữ, engine).
- **Files liên quan**: `core/ocr/`, `core/storage/.../ComicTranslationStore.kt`, `feature/comic/.../ComicTranslationUi.kt`

### Lưu chương đang đọc và khôi phục tiến độ truyện tranh online
- **Ngày**: 2026-10-30
- **Chi tiết**: Nhận diện sách web bằng `sourceUrl` của từng XHTML trong EPUB; sách cũ thiếu URL chỉ khớp khi nội dung chương trùng chính xác, còn URL đầu/cuối là mốc dự phòng. Chỉ nối chương khi link chương trước trùng URL chương cuối; trường hợp mơ hồ yêu cầu chọn sách. Truyện tranh online lưu chương/trang/chế độ theo hash của host và đường dẫn bộ truyện. Chỉ lối vào từ Kệ sách bật `resumeProgress`; mở trực tiếp giữ nguyên URL người dùng yêu cầu.
- **Files liên quan**: `core/storage/.../VisibleChapterMatching.kt`, `VisibleWebChapterSaver.kt`, `OnlineComicProgressStore.kt`, `app/.../AppNavigation.kt`

### Reader truyện tranh online dùng parser nguồn và tải ảnh riêng
- **Ngày**: 2026-10-30
- **Chi tiết**: `NetTruyenChapterParser` đọc ảnh lazy-load và URL server thay thế từ HTML; `OnlineComicImageLoader` dùng OkHttp không cookie phiên, Referer cho hotlink, giới hạn ảnh 64 MiB, giải mã tối đa hai ảnh đồng thời và cache RAM. Màn hình tái dùng `ComicPageDecoder`/các composable đọc CBZ thay vì nhân đôi pipeline ảnh.
- **Files liên quan**: `core/comic/.../NetTruyenChapterParser.kt`, `OnlineComicImageLoader.kt`, `feature/comic/.../OnlineComicReaderScreen.kt`

### Tải truyện tranh online về máy = một file CBZ cho cả bộ, nối chương bằng ghi lại
- **Ngày**: 2026-11-03
- **Chi tiết**: `OnlineComicDownloadWorker` (unique work `online_comic_download_<comicKey>`, KEEP, cần mạng) đi theo link "chương sau", tải ảnh từng trang (thử server thay thế, kiểm tra magic bytes) ra `cacheDir` rồi mới ghi CBZ để không giữ cả chương trong RAM. `ComicCbzWriter` chép entry cũ + thêm thư mục `%04d Tên chương/`, một `ComicInfo.xml` duy nhất, `NO_COMPRESSION`. Nối chương: ghi file mới, `insertBook` giữ ID, tính lại tiến độ, rồi xóa file cũ. `OfflineComicStore` lưu `comicKey` + danh sách URL chương để biết tải tiếp từ đâu và chặn trùng chương. Ngân sách 95% `ComicReadLimits` để archive luôn mở được.
- **Files liên quan**: `core/storage/.../worker/OnlineComicDownloadWorker.kt`, `OnlineComicDownloadScheduler.kt`, `core/storage/.../OfflineComicStore.kt`, `core/comic/.../ComicCbzWriter.kt`

### Xuất sách ra bộ nhớ ngoài qua SAF
- **Ngày**: 2026-11-03
- **Chi tiết**: Sách nằm trong `filesDir/books/` (người dùng không truy cập được). Xuất bằng `ACTION_CREATE_DOCUMENT` → không cần quyền bộ nhớ; ViewModel chép file ở IO qua `contentResolver.openOutputStream`, lỗi thì `DocumentsContract.deleteDocument` để không để lại file rỗng. `bookExportFormat` trả null cho sách có `filePath` là URL (truyện online). Tên file bỏ ký tự cấm, tối đa 100 ký tự.
- **Files liên quan**: `feature/library/.../BookExport.kt`, `LibraryViewModel.kt`, `LibraryScreen.kt`

### Truyện tranh online cũng chặn địa chỉ nội bộ ở tầng HTTP
- **Ngày**: 2026-11-03
- **Chi tiết**: Lọc URL ảnh lúc parse là chưa đủ (redirect, link chương, DNS rebinding). `ComicPublicAddressPolicy.applyTo(builder)` gắn `Dns` chỉ trả IP công khai + network interceptor kiểm tra IP socket thực tế, dùng cho `OnlineComicFetcher` và `OnlineComicImageLoader`; parser dùng chung `isPublic`. `core:comic` không phụ thuộc `core:webnovel` nên có bản policy riêng — giữ logic giống `PublicAddressPolicy` khi sửa.
- **Files liên quan**: `core/comic/.../ComicPublicAddressPolicy.kt`, `OnlineComicFetcher.kt`, `OnlineComicImageLoader.kt`

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

### URL trang giới thiệu truyện không thể suy ra URL chương đầu
- **Ngày**: 2026-10-30
- **Vấn đề**: Tự ghép `/chuong-1` hoặc `/chapter-1` làm sai với nguồn dùng quy ước khác; trang giới thiệu cũng có thể bị parser chương coi là chương rỗng.
- **Root cause**: Dạng URL không thống nhất giữa truyện và site.
- **Fix**: Nhận diện trang giới thiệu từ tiêu đề/metadata và lấy link chương số 1 cùng host trực tiếp từ DOM; không tự dựng suffix. Khi site không cung cấp link, báo không nhận diện được thay vì tải nhầm.
- **Files liên quan**: `core/webnovel/.../NovelLandingDetector.kt`, `WebNovelCrawler.kt`

### Mở sách truyện tranh online bị quay về tiến độ cũ ngoài ý muốn
- **Ngày**: 2026-10-30
- **Vấn đề**: Khi mở URL chương từ trình duyệt, tiến độ đã lưu có thể thay URL người dùng vừa chọn.
- **Root cause**: Khôi phục tiến độ được áp dụng cho mọi lần điều hướng.
- **Fix**: Truyền `resumeProgress=true` riêng khi mở từ Kệ sách; route trực tiếp mặc định giữ chương được yêu cầu. Nút thử lại dùng lại URL chương đang lỗi.
- **Files liên quan**: `app/.../AppNavigation.kt`, `feature/comic/.../OnlineComicReaderViewModel.kt`

### Nối chương vào CBZ nhưng Kệ sách vẫn hiện số trang cũ và "Đã hoàn thành"
- **Ngày**: 2026-11-03
- **Vấn đề**: Sau khi tải thêm chương, cuốn đã đọc hết vẫn hiện tổng trang cũ, kẹt ở tab "Đã hoàn thành".
- **Root cause**: Chỉ cập nhật `Book.totalChapters`; Kệ sách ưu tiên `ReadingProgress.totalChapters` và lọc theo `progressPercentage >= 1f`.
- **Fix**: Mọi luồng thay file sách phải tính lại `totalChapters`/`progressPercentage` trong `reading_progress` và tăng `updatedAt` (+1, chống tràn `Long.MAX_VALUE`) như `LocalBookReplacement`. Truyện tranh lấy trang từ `parseComicLocator(currentCfi) ?: chapterIndex`. Hạn chế còn lại: Reader đang mở bản cũ sẽ ghi lại số trang cũ khi lật trang, tới lần mở sau mới đúng.
- **Files liên quan**: `core/storage/.../worker/OnlineComicDownloadWorker.kt`

### `setForeground` ném lỗi làm hỏng tác vụ tải nền
- **Ngày**: 2026-11-03
- **Vấn đề**: Android 12+ có thể ném `ForegroundServiceStartNotAllowedException` khi app ở nền; gọi trong vòng tải làm nhảy vào `catch` chung, xóa file đang tải và `Result.failure()`.
- **Root cause**: Gọi `setForeground` trực tiếp, không bắt lỗi.
- **Fix**: `try { setForeground(info) } catch (_: IllegalStateException) { notificationManager.notify(id, notification) }` — tác vụ vẫn chạy như worker thường. Bắt `IllegalStateException` thay vì `runCatching` để không nuốt `CancellationException`.
- **Files liên quan**: `OnlineComicDownloadWorker.kt`, `WebNovelDownloadWorker.kt`, `EpubImportWorker.kt`

### Mở lại màn hình mất trạng thái tác vụ WorkManager đang chạy
- **Ngày**: 2026-11-03
- **Vấn đề**: Thoát rồi mở lại Reader truyện tranh online khi đang tải: nút không hiện tiến trình, không báo kết quả.
- **Root cause**: `isDownloading` chỉ bật khi bấm tải trong phiên ViewModel hiện tại.
- **Fix**: Scheduler có `findActive(comicKey)` (unique work chưa kết thúc, gọi ở IO); ViewModel gọi trong `init` rồi dùng chung `awaitDownload(workId)` với luồng bấm tải. Bỏ qua nếu người dùng đã bấm tải trong lúc tìm.
- **Files liên quan**: `OnlineComicDownloadScheduler.kt`, `feature/comic/.../OnlineComicReaderViewModel.kt`

### Tự tải thêm chương truyện web làm mới hai lần và bật thông báo lỗi
- **Ngày**: 2026-11-03
- **Vấn đề**: Khi đọc gần hết, tự tải thêm chương gọi refresh thủ công trùng với luồng collect, báo "chờ mạng"/lỗi nhiều lần.
- **Root cause**: Luồng `combine(getAllBooks, filePath)` đã tự nạp lại khi `filePath` đổi; nhánh tự động dùng chung thông báo với nhánh người dùng bấm.
- **Fix**: Bỏ `refreshAppendedChapters()` thừa; chỉ báo chờ mạng một lần; với `auto` thì im lặng khi lỗi và chỉ báo "đã thêm chương".
- **Files liên quan**: `feature/reader/.../ReaderViewModel.kt`

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

### Thêm nguồn truyện tranh online
- **Ngày**: 2026-10-30
- **Bước thực hiện**:
  1. Tạo parser JVM thuần: trích URL ảnh lazy-load, chuẩn hóa relative URL, lọc asset giao diện và nhận diện link chương trước/sau; hỗ trợ URL server thay thế theo cùng thứ tự trang.
  2. Bổ sung fixture HTML và test parser cho ảnh thiếu, ảnh trùng, URL tương đối và server thay thế.
  3. Tải ảnh bằng client riêng, gửi Referer khi nguồn chặn hotlink, giới hạn byte khi đọc stream, đóng response và giới hạn giải mã/cache.
  4. Kết nối ViewModel với UI đọc hiện có; giữ riêng state lỗi, thử lại, đổi server và điều hướng chương.
- **Files liên quan**: `core/comic/`, `feature/comic/`

### Thêm một định dạng xuất sách
- **Ngày**: 2026-11-03
- **Bước thực hiện**:
  1. Thêm giá trị vào `BookExportFormat(extension, mimeType)`.
  2. Cập nhật `bookExportFormat(filePath)` nhận diện phần mở rộng; giữ trả null cho URL.
  3. Thêm test trong `BookExportTest`; menu "Xuất %1$s" tự dùng `format.name`.
- **Files liên quan**: `feature/library/.../BookExport.kt`

### Kiểm chứng bản review từ AI khác trước khi sửa
- **Ngày**: 2026-11-03
- **Bước thực hiện**:
  1. Đọc đúng code được trích dẫn và cả code liên quan (ví dụ `ComicArchive` giữ `ZipFile` mở suốt phiên → claim "mở lại ZipFile mỗi trang, xóa file gây FileNotFound" là sai).
  2. Đối chiếu tên/hằng số được nêu (tên unique work trong review có thể sai dù lỗi có thật).
  3. Chỉ sửa phần đúng; báo rõ phần sai và hạn chế còn lại.

---

## Patterns

### Sửa file CRLF bằng script Python giữ kiểu xuống dòng
- **Ngày**: 2026-10-29
- **Chi tiết**: Phần lớn file trong repo là CRLF. Dùng helper `edit(path, [(old, new)])` tự nhận `\r\n`, assert mỗi `old` xuất hiện đúng 1 lần, ghi lại với `newline=''`; `add_strings()` chèn `<string>` trước `</resources>` và chặn key trùng. Heredoc Python dài trong Bash dễ lỗi quote → ghi script ra file rồi chạy. Sau khi sửa, kiểm tra không lẫn LF bằng Python (`grep` của Git Bash không thấy CR). Repo có `core.autocrlf=true`, index lưu LF: xem bằng `git ls-files --eol`; working copy LF chỉ gây cảnh báo "LF will be replaced by CRLF", không đổi nội dung commit — chuẩn hóa về CRLF cho đồng nhất. Heredoc chứa nhiều nháy trong Bash tool dễ lỗi → ghi script bằng Write rồi chạy.

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

### Thay đổi server ảnh phải giữ đồng bộ toàn bộ trang
- **Ngày**: 2026-10-30
- **Chi tiết**: Chỉ hiển thị server thay thế khi parser có đủ URL cho mọi trang; mỗi ảnh thay thế phải cùng chỉ số với ảnh chính. Khi đổi server, tạo lại danh sách trang và reset vị trí đọc phù hợp. Giới hạn kích thước cần kiểm tra cả `Content-Length` lẫn tổng byte đã đọc vì response có thể không khai báo độ dài.
- **Files liên quan**: `core/comic/.../NetTruyenChapterParser.kt`, `OnlineComicImageLoader.kt`, `feature/comic/.../OnlineComicReaderViewModel.kt`

### Xóa/thay file sách khi Reader có thể đang mở
- **Ngày**: 2026-11-03
- **Chi tiết**: Trên Android, file đang mở vẫn đọc được sau khi bị xóa. `ComicArchive` giữ `ZipFile` suốt phiên nên xóa CBZ cũ ngay là an toàn. Reader EPUB/TTS mở lại file theo đường dẫn nên phải hoãn xóa (`retireFile`/`purgeRetiredFiles`). Trước khi xóa, kiểm tra reader giữ file descriptor hay mở lại theo path.
- **Files liên quan**: `core/comic/.../ComicArchive.kt`, `core/storage/.../WebNovelSourceStore.kt`
