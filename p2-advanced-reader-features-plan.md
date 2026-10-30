# Kế hoạch tính năng P2: Manga, Trình duyệt bóc tách, Convert Hán-Việt, OCR dịch truyện tranh, Lật trang 3D

## Mục tiêu

Mở rộng EpubPro sang truyện tranh, truyện mạng từ web bất kỳ và truyện chữ tiếng Trung, đồng thời thêm hiệu ứng lật trang 3D, mà không phá các contract hiện có của Reader EPUB (pagination ngang, chapter-transition snapshot, TTS, highlight/CFI, Book Bible, đồng bộ Drive).

Tham khảo danh sách tính năng từ `readexa_architecture_and_reverse_engineering.md`, nhưng **giữ kiến trúc Kotlin/Compose/Room/Hilt hiện tại** và **không dùng lại tài sản của Readexa** (`reader.onnx`, `extraction_rules.json`, giọng đọc `yncmobile`, endpoint `*.readexa.uk`).

## Thứ tự và phụ thuộc

| # | Tính năng | Phụ thuộc | Công sức (1 dev) | Rủi ro regression |
|---|---|---|---|---|
| 0 | Nền tảng chung: dispatch Reader theo định dạng | — | 3–4 ngày | Thấp |
| 1 | Đọc CBZ/Manga | 0 | 2–3 tuần | Thấp (module mới) |
| 2 | Trình duyệt + bóc tách web (Readability) | — | 3 tuần | Thấp |
| 3 | Convert Hán-Việt offline | — | 3–4 tuần | Trung bình (chạm pipeline chương) |
| 4 | OCR dịch truyện tranh | 1 (và tùy chọn 3) | 3 tuần | Thấp |
| 5 | Lật trang 3D | — | 2–3 tuần | **Cao** (chạm `EpubProWebView`) |

Các mục 1, 2, 3, 5 độc lập về logic nhưng cùng chạm `AppNavigation.kt` và `core/designsystem/src/main/res/values/strings.xml`. Nếu làm song song, mỗi nhánh chỉ thêm dòng mới vào hai file này để merge dễ. Mục 5 làm cuối vì rủi ro cao nhất với Reader.

Khuyến nghị nâng toolchain (Kotlin `1.9.23` → 2.x, AGP `8.3.2` → mới hơn, `compileSdk`/`targetSdk` 34 → 35) trước khi bắt đầu, vì build hiện không chạy được với JDK 25 và các thư viện mới (ML Kit, Coil 3) có thể yêu cầu Kotlin/AGP mới hơn.

## Quyết định cần chốt trước khi làm

- [x] **CBR (RAR):** đề xuất v1 chỉ hỗ trợ CBZ/ZIP và thư mục ảnh; CBR để sau vì vướng license unrar.
- [x] **Engine OCR/dịch:** đề xuất v1 dùng Google ML Kit Text Recognition v2 + ML Kit Translate (on-device, miễn phí). Đây là dependency mới (Google Play services, tăng dung lượng APK) nên cần xác nhận theo quy định `AGENTS.md`. Phương án thay thế: PaddleOCR PP-OCRv5 qua ONNX Runtime, nhưng `libonnxruntime.so` có thể trùng/xung đột với bản đóng gói trong AAR `sherpa-onnx` — phải thử nghiệm trước. — *Đã chốt và làm: ML Kit qua Google Play services (model OCR do Play services tải, không làm phình APK).*
- [x] **Dữ liệu từ điển Hán-Việt:** đề xuất app chỉ đóng gói bảng âm Hán-Việt từng chữ (nguồn Unihan `kVietnamese`, license Unicode) và cho người dùng **tự nhập** file VietPhrase/Names theo định dạng QuickTranslator, vì nguồn gốc license của các bộ VietPhrase cộng đồng không rõ ràng. — *Đã chốt và làm: chưa đóng gói sẵn bảng Unihan; người dùng tự nhập cả Phiên âm. Muốn kèm sẵn bảng Unihan cần tải dữ liệu từ unicode.org.*
- [x] **Lưu truyện bóc tách từ web:** đề xuất đóng gói thành EPUB cục bộ để tái dùng toàn bộ Reader/TTS/highlight/AI/Book Bible, thay vì thêm định dạng lưu trữ mới. — *Đã chốt và làm.*
- [x] **Đồng bộ Drive cho CBZ:** `LocalSnapshotDataSource` đang tải lên `storage/novels` và `storage/uploads`. File CBZ thường 50–300 MB, nên đề xuất mặc định **không** đồng bộ file CBZ, chỉ đồng bộ tiến độ.

---

## Phase 0: Nền tảng chung

- [x] Thêm giá trị `CBZ` (và `WEB_NOVEL` nếu cần đánh dấu nguồn) vào `BookSourceFormat`. Cột `sourceFormat` là `String` nên không cần migration Room, nhưng bản app cũ sẽ fallback về `EPUB` khi gặp giá trị lạ (`runCatching { valueOf } getOrDefault EPUB`). Kiểm chứng: Reader EPUB từ chối mở file không phải EPUB với lỗi rõ ràng thay vì crash, để bản cũ nhận dữ liệu đồng bộ từ bản mới vẫn an toàn. — *Đã làm: thêm `BookSourceFormat.CBZ` + `isComic`. Chưa thêm chốt chặn trong Reader EPUB; thay vào đó mọi lối vào (Library, import từ app ngoài, tự mở sách gần nhất) đều định tuyến theo định dạng.*
- [x] Tách điểm vào Reader trong `AppNavigation.kt`: route `Screen.Reader` tra `sourceFormat` rồi điều hướng sang `ReaderScreen` (EPUB, MOBI, PRC, AZW3) hoặc `ComicReaderScreen` (CBZ). Kiểm chứng: mở sách từ Library, widget TTS và `ExternalBookImportHandler` đều vào đúng Reader. — *Đã làm: route `Screen.ComicReader`, `LibraryScreen.onBookClick` trả kèm định dạng, kênh `openComicRequests` trong `MainActivity`.*
- [x] Ẩn các tính năng không áp dụng cho truyện tranh (TTS, AI tiếng Việt, Book Bible, tìm FTS, highlight văn bản) ở Library và menu Reader. Kiểm chứng: không có lối vào tính năng chữ khi mở CBZ. — *Đã làm: Library không có menu tính năng chữ; danh sách Book Bible và quét bìa EPUB bỏ qua CBZ.*

## Phase 1: Đọc CBZ/Manga

**Module mới:** `:core:comic` (đọc archive, metadata, không phụ thuộc Android UI) và `:feature:comic` (UI Reader).

- [x] `ComicArchive` trong `:core:comic`: mở ZIP bằng `java.util.zip.ZipFile`, lọc entry ảnh (`jpg/jpeg/png/webp/gif/avif`), sắp xếp **natural sort** (`2.jpg` trước `10.jpg`), bỏ `__MACOSX`/file ẩn, đọc `ComicInfo.xml` (Title, Series, Number, Writer, `Manga=YesAndRightToLeft`). Kiểm chứng: unit test với ZIP có thư mục lồng, tên số lẫn chữ, thiếu `ComicInfo.xml`, entry hỏng.
- [x] Mở rộng `BookFormatSniffer`: nhận `.cbz` và ZIP chỉ chứa ảnh, không có `mimetype`/`container.xml` (khác `detectEpubFormat`). Kiểm chứng: test EPUB vẫn được nhận là EPUB và ZIP ảnh được nhận là CBZ.
- [x] Nhánh import CBZ trong `LocalBookImportWorker`: **không chuyển đổi**, chỉ sao chép file, trích ảnh bìa từ trang đầu (downsample), `totalChapters = 1`, bỏ bước index FTS. Cập nhật intent-filter `VIEW`/`SEND` cho `application/vnd.comicbook+zip` và `application/zip`. Kiểm chứng: import qua Add Book và qua "Mở bằng" từ trình quản lý file. — *Đã làm; giới hạn import CBZ/ZIP là 1 GiB, file được move (không copy) sang đích.*
- [x] Nạp ảnh: `Fetcher` của Coil (`coil-compose` 2.6.0 đã có) đọc thẳng entry trong ZIP, downsample theo kích thước màn hình, cache bộ nhớ có giới hạn. Ảnh webtoon rất cao (> 4096 px) cắt bằng `BitmapRegionDecoder` để tránh `Canvas: trying to draw too large bitmap`. Kiểm chứng: không OOM với chương webtoon 80 ảnh × 800×12000 px trên máy 3 GB RAM. — *Đã làm theo cách khác: không dùng Coil mà tự giải mã (`ComicPageDecoder`) với downsample, giới hạn 16 MP, RGB_565 cho ảnh lớn và chia dải 2048 px bằng `BitmapRegionDecoder`; cache LruCache 1/6 heap. Chưa đo OOM trên máy thật.*
- [x] `ComicReaderScreen` + `ComicReaderViewModel`, ba chế độ: trang đơn trái→phải, trang đơn phải→trái (RTL, mặc định khi `ComicInfo` báo manga) bằng `HorizontalPager`, và cuộn dọc webtoon bằng `LazyColumn` (`key` theo tên entry, `contentType` cố định). Có pinch-zoom, double-tap zoom, vùng chạm trái/phải/giữa và phím âm lượng (tái dùng `TapZoneLayout`/`TapZoneAction` hiện có). Kiểm chứng: Compose UI test chuyển trang ở cả ba chế độ, xoay màn hình giữ đúng trang. — *Đã làm 3 chế độ, pinch/double-tap zoom ở chế độ lật trang, vùng chạm cố định 30/40/30 (chưa dùng `TapZoneLayout`), phím âm lượng theo cài đặt Reader chung. Webtoon chưa có zoom. Chưa có Compose UI test.*
- [x] Tiến độ: lưu vào `ReadingProgress` với `chapterIndex = 0`, `pageIndex` là số trang ảnh, `currentCfi = "cbz:page=<n>"`, và `progressPercentage`. Ghi vào `ReadingHistoryRecord` để đồng bộ Drive như sách thường. Kiểm chứng: thoát app, kill process rồi mở lại đúng trang; thiết bị thứ hai nhận đúng trang. — *Đã làm nhưng lưu trang vào `chapterIndex` (không phải 0) để Library hiển thị đúng "đang đọc/đã xong".*
- [ ] Tùy chọn cho từng sách: hướng đọc, chế độ fit (vừa chiều rộng, vừa chiều cao, ảnh gốc), cắt viền trắng tự động (quét pixel biên, tính một lần rồi cache theo trang), nền đen/trắng. Kiểm chứng: cài đặt của sách A không ảnh hưởng sách B. — *Mới có hướng đọc theo từng sách; fit mode, cắt viền trắng, nền trắng chưa làm.*
- [ ] Library: nhóm sách theo `Series` + `Number` lấy từ `ComicInfo.xml`, nếu không có thì đoán từ tên file (`Tập 01`, `Vol.1`, `Ch.012`). Kiểm chứng: unit test parser tên file. — *Chưa làm.*

## Phase 2: Trình duyệt tích hợp + bóc tách truyện (Readability)

**Module mới:** `:feature:browser`. Thư viện JS: Mozilla `Readability.js` (Apache-2.0), đặt trong `assets/js/`, ghi rõ license.

- [ ] `BrowserScreen`: `WebView` có thanh địa chỉ, back/forward, tải lại, lịch sử và bookmark trang web (Room bảng mới, migration v8→v9). Bảo mật: `setAllowFileAccess(false)`, không gắn `ReaderJsBridge` lên trang web lạ, chỉ chạy JS bóc tách qua `evaluateJavascript`, chặn scheme lạ (`intent:`, `file:`). Áp dụng skill `android-intent-security`. Kiểm chứng: trang độc hại không gọi được bridge native. — *Đã làm: thanh địa chỉ, lùi/tiến/tải lại, chặn scheme ngoài http(s), không gắn JS bridge, không truy cập file. Chưa làm: lịch sử/bookmark trang web (cần migration Room).*
- [x] Bóc tách nội dung: inject `Readability.js` vào DOM clone, nhận `{title, content, byline}`. Sanitize bằng `EpubHtmlSanitizer` hiện có trước khi hiển thị. Kiểm chứng: test với HTML mẫu đã lưu (fixture) của 5–10 site truyện phổ biến. — *Làm khác plan: không inject Readability.js mà viết `ArticleExtractor` bằng jsoup (chạy ngoài trang, không tải thư viện bên ngoài, unit test được, xử lý được kiểu `<div>` + `<br>` của site truyện).*
- [x] Nhận diện chương trước/sau/mục lục bằng heuristic JS riêng: `<link rel="next|prev">`, text link khớp từ điển đa ngôn ngữ (`Chương sau`, `Next`, `下一章`, `下一页`, `다음`), vị trí gần cuối nội dung, cùng host, URL có số tăng dần. Có thể bổ sung **bộ quy tắc CSS selector theo domain** do mình tự viết (JSON đóng gói sẵn, sau này cho phép tải từ server EpubPro). Kiểm chứng: unit test chấm điểm link với fixture. — *Đã làm `ChapterLinkDetector` (rel, chữ trên nút đa ngôn ngữ, id/class, số tăng dần trong URL). Chưa có bộ quy tắc CSS theo domain.*
- [ ] Chế độ đọc (Reader Mode): nút một chạm hiển thị nội dung đã bóc tách bằng theme/font/cỡ chữ của Reader. Tải trước chương sau trong nền bằng OkHttp + jsoup + Readability chạy trong `WebView` ẩn (một instance, có hàng đợi). Kiểm chứng: chuyển chương không trắng màn; huỷ tải khi rời màn hình. — *Đã làm chế độ đọc + nút chương trước/mục lục/chương sau. Chưa có tải trước chương sau, chưa dùng theme/font của Reader (đang dùng theme app).*
- [ ] "Lưu thành truyện": tải tuần tự N chương (WorkManager, giới hạn tốc độ, tôn trọng lỗi `429`), lưu HTML từng chương vào thư mục tạm, rồi `EpubWriter` mới (jsoup + `ZipOutputStream`, EPUB 3 tối giản có nav) đóng gói thành EPUB và import qua luồng hiện có. Cập nhật chương mới thì đóng gói lại và thay file **giữ nguyên `bookId` và tiến độ**. Kiểm chứng: EPUB sinh ra qua được `EpubEngine.parseEpubMetadataStrict`; cập nhật thêm chương không mất tiến độ, highlight hay cache AI của chương cũ. — *Đã làm `WebNovelDownloadWorker` (OkHttp riêng, gửi cookie WebView, nghỉ 0,8 s giữa các trang, lùi khi 429/5xx, nhận charset GBK) + `EpubWriter`. Chưa làm cập nhật chương mới vào truyện đã lưu (giữ bookId/tiến độ) và chưa checkpoint khi process bị kill.*
- [ ] Nhận link từ app khác: thêm `SEND` `text/plain` (URL) và `PROCESS_TEXT` ("Mở bằng EpubPro") mở thẳng trình duyệt. Kiểm chứng: chia sẻ link từ Chrome mở đúng trang. — *Đã làm `SEND text/plain` (trích URL). Chưa làm `PROCESS_TEXT`.*
- [x] Lưu ý pháp lý trong UI: chỉ phục vụ đọc cá nhân; không phân phối lại nội dung. — *Đã có ghi chú trong hộp thoại lưu truyện.*

## Phase 3: Convert Hán-Việt offline cho EPUB tiếng Trung

**Module mới:** `:core:convert`, thuần Kotlin/JVM (không phụ thuộc Android) để unit test nhanh.

- [x] Định dạng từ điển: hỗ trợ file QuickTranslator `ChinesePhienAmWords.txt` (Hán-Việt từng chữ), `VietPhrase.txt` và `Names.txt` (dạng `中文=nghĩa1/nghĩa2`). Biên dịch một lần sang file nhị phân nén (khoá sắp xếp + mảng offset, đọc bằng `MappedByteBuffer`) trong Worker. Không nạp nguyên trie vào heap. Kiểm chứng: từ điển 1,5 triệu dòng mở trong < 300 ms, heap tăng < 30 MB. — *Đã làm `DictionaryBuilder` (mảng nguyên thủy, merge sort ổn định, dòng sau ghi đè) + file nhị phân mmap. Chưa đo trên máy thật với VietPhrase 1,5 triệu dòng.*
- [x] Engine `HanVietConverter`: ghép **longest-match từ trái sang** với thứ tự ưu tiên Names theo sách → Names chung của người dùng → VietPhrase → Hán-Việt từng chữ → giữ nguyên ký tự. Chuyển dấu câu (`，。！？“”「」` → `, . ! ? " "`), chèn khoảng trắng, viết hoa đầu câu và danh từ riêng từ Names. Kiểm chứng: golden test input/output; benchmark 10.000 chữ Hán < 150 ms. — *Đã làm, có golden test. Chưa benchmark trên thiết bị.*
- [ ] Luật nhân v1 (mẫu cố định, ví dụ `X的Y` → `Y của X` khi X là Name) dùng bảng luật nhập được. Nhận diện tên riêng tự động để v2. Kiểm chứng: test cho từng luật. — *Chưa làm.*
- [x] Tích hợp Reader: thêm bước biến đổi chương trong `ReaderViewModel` **sau khi sanitize, cùng tầng với cache AI** (`aiVietnameseService.loadCachedChapter`). Duyệt text node bằng jsoup, giữ nguyên markup. Cache theo `(fingerprint, entryName, dictionaryRevision, glossaryRevision)`. Thêm nút chuyển "Bản gốc / Convert" trên thanh Reader. Kiểm chứng: test `ReaderViewModel` đổi chế độ không làm sai `chapterIndex`/page; chạy đủ bộ test bắt buộc trong `feature/reader/AGENTS.md`. — *Đã làm theo cùng đường với bản AI (`displayedChapterHtml`); preview chương trước/sau cũng convert và bỏ HTML sanitize sẵn. Không đụng `EpubProWebView`/`CssInjector`; test contract của Reader vẫn pass.*
- [ ] Tự phát hiện sách tiếng Trung (tỉ lệ ký tự CJK trong 3 chương đầu > 30%) và gợi ý bật Convert. Kiểm chứng: unit test với văn bản Việt, Trung, Nhật (kana không bị nhận là Trung). — *Đã làm nhận diện (bỏ qua kana) + Toast gợi ý khi mở sách; chỉ xét chương đang mở, không phải 3 chương đầu.*
- [ ] Sửa Name trong lúc đọc: bôi đen → "Thêm/Sửa Name" → bảng Room `convert_glossary(bookId nullable, source, target, updatedAt)` (migration). Lưu xong thì chỉ convert lại chương hiện tại. Có màn quản lý glossary theo sách, nhập/xuất file `Names.txt`. Kiểm chứng: sửa Name áp dụng ngay, không reload toàn sách. — *Đã làm bảng Name trong menu Reader (thêm/xóa, áp dụng ngay, giữ trang). Chưa làm thêm Name từ vùng bôi đen, chưa nhập/xuất Names.txt theo từng truyện.*
- [x] Ràng buộc với tính năng khác: — *Highlight vẫn hoạt động (Reader hiện chỉ lưu văn bản + vị trí chương); TTS đọc bản convert; Book Bible và cache AI vẫn nhận bản gốc.*
  - Highlight/bookmark tạo ở chế độ Convert lưu thêm `textMode` để không lệch CFI với bản gốc. Nếu chưa xử lý được, v1 khoá highlight khi đang Convert.
  - TTS đọc bản Convert bằng giọng tiếng Việt.
  - **Book Bible chỉ gửi bản gốc**, đúng quy định trong `book-bible-feature-plan.md`.
  - Lệnh "AI tiếng Việt" có thể nhận bản Convert làm đầu vào để trau chuốt.

## Phase 4: OCR dịch truyện tranh

**Module mới:** `:core:ocr` (interface `TextRecognizer` và `Translator` để thay engine được). Phụ thuộc Phase 1.

- [x] Engine v1 (sau khi chốt quyết định): ML Kit Text Recognition v2 cho Latin/Chinese/Japanese/Korean, tải model theo nhu cầu, và ML Kit Translate on-device (zh/ja/ko/en → vi, tải model ~30 MB/ngôn ngữ, hỏi trước khi tải qua dữ liệu di động). Kiểm chứng: hoạt động khi bật chế độ máy bay sau khi đã tải model. — *Đã làm: hỏi trước khi tải gói dịch (tùy chọn chỉ Wi-Fi); báo riêng khi Play services còn đang tải model OCR.*
- [x] Gom dòng chữ thành bóng thoại: gộp các `TextBlock` gần nhau theo khoảng cách và chiều chữ; sắp xếp thứ tự đọc theo hướng manga (phải→trái, trên→dưới). Lưu ý: chữ dọc tiếng Nhật ML Kit nhận kém — ghi nhận là giới hạn v1, đánh giá PaddleOCR ở v2. Kiểm chứng: test hình học với dữ liệu box giả. — *Đã làm `BubbleGrouper` (union-find, chữ dọc đọc theo cột, sắp hàng theo hướng đọc). Chưa đánh giá chất lượng với manga chữ dọc thật.*
- [ ] Bộ dịch: ưu tiên (1) Hán-Việt Convert của Phase 3 cho tiếng Trung, (2) ML Kit Translate offline, (3) Gemini qua `core/ai` (key người dùng) cho chất lượng cao. Lựa chọn (3) gửi ảnh/văn bản ra ngoài nên phải có opt-in rõ ràng. Kiểm chứng: tắt mạng thì tự rơi về engine offline. — *Đã làm (1) Hán-Việt và (2) ML Kit. Chưa làm (3) Gemini.*
- [x] UI trong `ComicReaderScreen`: nút "Dịch trang này" và tuỳ chọn "Tự dịch các trang kế tiếp". Overlay: khung nền phủ bóng thoại với chữ dịch tự co cỡ, hoặc chạm vào khung để xem bottom sheet (bản gốc, bản dịch, sao chép). Kiểm chứng: overlay đúng vị trí khi zoom/pan và cả ở chế độ webtoon. — *Đã làm: nút Dịch trên thanh trên, bảng chọn ngôn ngữ/cách dịch, tự dịch khi lật trang, lớp phủ theo zoom ở cả chế độ lật trang và webtoon, chạm bóng để xem bản gốc.*
- [x] Cache kết quả theo `(bookId, entryName, ocrEngine, targetLang)` dưới dạng JSON trong `filesDir`, xoá khi xoá sách. OCR chạy trên `Dispatchers.Default`, tối đa 1 trang song song, huỷ khi rời trang. Kiểm chứng: lật lại trang đã dịch hiển thị ngay, không OCR lại. — *Đã làm theo (bookId, trang, ngôn ngữ, cách dịch); xóa cùng sách.*

## Phase 5: Hiệu ứng lật trang 3D

Chỉ áp dụng cho `ReadingMode.FLIP` (phân trang ngang). Thêm setting `PageTurnAnimation { SLIDE (mặc định, hiện tại), CURL, NONE }` trong `ReaderPreferencesManager` và màn `PageTurnControlScreen`.

**Bắt buộc đọc trước:** `feature/reader/AGENTS.md`, `docs/reader-chapter-transition-snapshot-design.md`, `docs/reader-horizontal-last-page-geometry-fix.md`.

- [ ] Hướng kỹ thuật: lật bằng **lớp phủ native**, không biến đổi DOM. Khi lật trang tới:
  1. chụp trang hiện tại bằng `PixelCopy` (tái dùng hạ tầng snapshot sẵn có);
  2. đặt bitmap làm cover;
  3. gọi `scrollToPage(target, animate = false)` trong `CssInjector`;
  4. chờ `postVisualStateCallback`;
  5. chạy animation cuộn góc trên bitmap bằng `Canvas.drawBitmapMesh` (chạy từ API 26) kèm gradient bóng đổ;
  6. gỡ cover.

  Lật lùi chụp cả trang hiện tại lẫn trang trước, cuộn trang trước từ trái vào. AGSL `RuntimeShader` (API 33+) chỉ cân nhắc nếu mesh không đủ đẹp. Kiểm chứng: không frame trắng/chỉ màu theme ở cả hai chiều.
- [ ] Cử chỉ: v1 chỉ phát animation khi tap, phím âm lượng hoặc vuốt đã được nhận diện (không bám ngón tay). Long-press chọn chữ và tap zone giữ nguyên hành vi. v2 mới làm curl bám ngón tay, kéo gesture từ JS sang `pointerInput` của Compose. Kiểm chứng: chọn chữ, highlight và menu vẫn hoạt động khi bật CURL.
- [ ] Ranh giới chương: curl tới trang preview của chương kế (đã có sẵn trong document), sau đó **giao lại đúng luồng chapter-transition snapshot hiện có**. Không tạo luồng reload riêng, không dùng delay cố định, tôn trọng generation, timeout và cleanup theo 7 invariant. Kiểm chứng: `ReaderChapterTransitionContractTest` vẫn pass mà không cần sửa test; chạy checklist thiết bị thật hai chiều trong `feature/reader/AGENTS.md`.
- [ ] Bộ nhớ: tối đa 2 bitmap `ARGB_8888` (~10 MB mỗi cái ở 1080×2400), tái dùng giữa các lần lật, giải phóng khi dispose. Lật dồn dập thì bỏ qua animation cũ và nhảy thẳng tới đích. Có fallback về SLIDE khi cấp phát bitmap lỗi. Kiểm chứng: lật 200 trang liên tục không tăng heap; profiler không thấy jank > 16 ms trên máy tầm trung.
- [ ] Tự tắt CURL khi bật "Giảm chuyển động" trong hệ thống (`Settings.Global.ANIMATOR_DURATION_SCALE == 0`). Kiểm chứng: thủ công.

---

## Quy định áp dụng cho mọi phase

- Function Kotlin mới có KDoc tiếng Việt; mọi chuỗi hiển thị nằm trong `core/designsystem/src/main/res/values/strings.xml`. Không thêm `displayName`/`label` tiếng Việt cứng vào enum domain mới.
- Mỗi dependency mới (ML Kit, Readability.js, có thể cả thư viện zoom ảnh) phải ghi lý do, dung lượng tăng thêm và license trong mô tả PR.
- Migration Room gom vào một lần nâng v8→v9 nếu các phase làm gần nhau, nếu không thì mỗi phase một migration có test.
- Không log nội dung chương, URL đầy đủ có token, ảnh trang hay văn bản OCR.

## Hoàn thành khi

- [ ] Mở và đọc được CBZ ở 3 chế độ, tiến độ đồng bộ được, không OOM với webtoon dài.
- [ ] Mở trang web bất kỳ, bật Reader Mode, chuyển chương và lưu thành EPUB đọc được bằng Reader hiện có.
- [ ] EPUB tiếng Trung chuyển được Bản gốc/Convert, sửa Name áp dụng ngay, Book Bible vẫn chỉ nhận bản gốc.
- [ ] Dịch được trang truyện tranh offline với overlay đúng vị trí.
- [ ] Lật trang 3D không vi phạm contract chapter-transition, test kiến trúc và checklist thiết bị đều đạt.
- [ ] Bộ test bắt buộc pass: `.\gradlew.bat :domain:test :core:epub:testDebugUnitTest :core:reader-renderer:testDebugUnitTest :core:playback:testDebugUnitTest :feature:reader:testDebugUnitTest :feature:library:testDebugUnitTest :app:assembleDebug`, cùng test của các module mới (`:core:comic`, `:feature:comic`, `:feature:browser`, `:core:convert`, `:core:ocr`).
