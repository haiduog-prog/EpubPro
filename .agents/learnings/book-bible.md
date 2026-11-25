# Book Bible (Tiến trình nhân vật & Dòng thời gian)

> Tổng hợp kiến thức về hệ thống Chapter-Aware Character Profile Book Bible (Tiến trình hồ sơ nhân vật theo chương không spoiler) trong dự án.
> Cập nhật lần cuối: 2026-11-25

---

## Architecture

### Hệ thống Snapshot chống Spoiler theo mốc chương (Chapter-Aware Projection)
- **Ngày**: 2026-08-19
- **Chi tiết**: Phân hệ Book Bible không hiển thị toàn bộ hồ sơ nhân vật tới chương cuối mà sử dụng mô hình snapshot theo mốc chương (`local_chapter` / `canonical_chapter`). Server tính toán projection dựa trên các `CharacterEvent` phát sinh từ chương 1 đến chương hiện tại để ngăn chặn spoiler hoàn toàn cho độc giả.
- **Files liên quan**: `domain/src/main/java/com/epubpro/domain/model/BookBibleModels.kt`, `core/storage/src/main/java/com/epubpro/core/storage/bookbible/BookBibleRepositoryImpl.kt`

### Luồng Ingestion Bất đồng bộ với WorkManager & Chống trùng lặp (Idempotency)
- **Ngày**: 2026-08-19
- **Chi tiết**: Do quá trình LLM trích xuất diễn biến chương diễn ra bất đồng bộ, client gửi nội dung chương qua `BookBibleWorker` với header `X-Idempotency-Key` (SHA-256 nội dung chương). Server trả về HTTP 202 Accepted với `submission_id`. Client lưu trạng thái vào Room SQLite và lắng nghe/polling tiến độ cho đến khi `completed`.
- **Files liên quan**: `core/storage/src/main/java/com/epubpro/core/storage/bookbible/BookBibleWorker.kt`, `core/storage/src/main/java/com/epubpro/core/storage/network/BookBibleApiService.kt`

### Cache đa tầng & Thuật toán dọn dẹp LRU
- **Ngày**: 2026-08-19
- **Chi tiết**: Tích hợp cơ chế Cache 2 tầng: Room SQLite lưu snapshot & timeline metadata, Disk Cache lưu payload JSON. Sử dụng cơ chế giới hạn dung lượng mềm (50 MiB) kèm thuật toán xóa bản ghi ít truy cập nhất (LRU pruning) dựa trên `lastAccessedAt`.
- **Files liên quan**: `core/database/src/main/java/com/epubpro/core/database/dao/BookBibleDao.kt`, `core/storage/src/main/java/com/epubpro/core/storage/bookbible/BookBibleRepositoryImpl.kt`

### Thiết kế giao diện phân tầng dọc (Vertical Hierarchy Layout)
- **Ngày**: 2026-08-19
- **Chi tiết**: Trường `role` từ backend là câu mô tả dài (không phải nhãn ngắn), nếu đặt ngang dạng badge sẽ gây vỡ layout và tràn chữ. Sử dụng layout dọc 3 dòng (Tên + Tên gốc $\to$ Vai trò/Mô tả $\to$ Cảnh giới/Thế lực) trên thẻ danh sách full-width kết hợp Hero Card nổi bật cho nhân vật chính.
- **Files liên quan**: `feature/bookbible/src/main/java/com/epubpro/feature/bookbible/BookBibleScreen.kt`

### Màn Tiến trình truyện ưu tiên Local/Cache
- **Ngày**: 2026-08-21
- **Chi tiết**: Màn cấp ứng dụng đọc `BookRepository` và `BookBibleRepository` trước để luôn có fallback local/cache. Khi vào tab, màn gọi riêng `GET /api/v1/book-bible/books` để lấy số event pending phục vụ duyệt tiến trình; tuyệt đối không gọi `GET /api/v1/library/novels`, vì catalog online thuộc `OnlineLibraryViewModel` và tab Duyệt.
- **Files liên quan**: `feature/bookbible/src/main/java/com/epubpro/feature/bookbible/StoryProgressViewModel.kt`, `feature/bookbible/src/main/java/com/epubpro/feature/bookbible/StoryProgressScreen.kt`

### Hàng đợi duyệt Character Events trong app
- **Ngày**: 2026-08-21
- **Chi tiết**: Tab Tiến trình hiển thị danh sách sách từ Book Bible backend, click dòng sách mở route `story_review/{bookId}`. Màn review gọi `GET /book-bible/events?status=pending&book_id=...`, cho phép sửa value/evidence/confidence, duyệt, từ chối hoặc duyệt tất cả. Nút Book Bible riêng trên dòng sách vẫn giữ entry point xem snapshot trực tiếp.
- **Files liên quan**: `core/storage/src/main/java/com/epubpro/core/storage/network/BookBibleApiService.kt`, `core/storage/src/main/java/com/epubpro/core/storage/bookbible/BookBibleRepositoryImpl.kt`, `feature/bookbible/src/main/java/com/epubpro/feature/bookbible/StoryReviewScreen.kt`, `app/src/main/java/com/epubpro/app/navigation/AppNavigation.kt`

### Canonical Book Identity & Trách nhiệm Chống trùng lặp (Single Source of Truth)
- **Ngày**: 2026-08-21
- **Chi tiết**: Quản lý danh mục tác phẩm và định danh duy nhất (`canonical_book_id`) thuộc trách nhiệm của Server backend để tránh phân mảnh sự kiện và ấn bản. Client giữ vai trò Thin Client: map `book.onlineNovelId` từ sách local với `book_id` của server để gắn kết trạng thái đọc offline và sự kiện review. Backend chịu trách nhiệm chuẩn hóa metadata khi import, xử lý alias và gộp bản ghi duplicate.
- **Files liên quan**: `feature/bookbible/src/main/java/com/epubpro/feature/bookbible/StoryProgressViewModel.kt`, `core/storage/src/main/java/com/epubpro/core/storage/network/BookBibleApiService.kt`

---

### Công tắc gửi nội dung theo từng sách (mặc định tắt cho EPUB cục bộ)
- **Ngày**: 2026-11-25
- **Chi tiết**: Sách EPUB cá nhân chỉ gửi nội dung chương lên server khi người dùng bật trong màn Book Bible (`BookBibleConsentStore`, prefs `book_bible_consent`). Truyện online luôn được phép (`BookBibleSource.requiresSubmissionConsent`). Chặn ở ba tầng: repository (`enqueueChapterSubmission` bỏ qua; `refreshSnapshot`/`getCharacterTimeline`/`retrySubmission` ném `BookBibleSubmissionDisabledException` để không gửi cả metadata), `BookBibleWorker` kiểm tra lại trước khi gửi (xóa payload + submission nếu đã tắt), và ViewModel không làm mới khi tắt. Reader gửi chương đang mở ngay khi công tắc chuyển sang bật.
- **Files liên quan**: `core/storage/src/main/java/com/epubpro/core/storage/bookbible/BookBibleConsentStore.kt`, `core/storage/src/main/java/com/epubpro/core/storage/bookbible/BookBibleRepositoryImpl.kt`, `core/storage/src/main/java/com/epubpro/core/storage/bookbible/BookBibleWorker.kt`, `feature/bookbible/src/main/java/com/epubpro/feature/bookbible/BookBibleScreen.kt`

## Bugs & Solutions

### Lỗi không nhận diện được Nhân vật chính khi `attributes["profile"]` là JsonArray
- **Ngày**: 2026-08-27
- **Vấn đề**: Nhân vật chính (Đỗ Phong) không xuất hiện ở Hero Card "Nhân vật chính ⭐" mà bị đẩy vào danh sách chung dưới tên gốc tiếng Hán `杜风` và không có vai trò.
- **Root cause**: Backend tích lũy profile qua nhiều chương thành JsonArray `[...]` chứa nhiều object và string thay vì một JsonObject `{...}` đơn lẻ. Parser client cũ chỉ kiểm tra `profileElem.isJsonObject` nên `profileObj` bị `null`, mất `vi_name`, `role`, `isMain`.
- **Fix**: Nâng cấp parser trong `BookBibleRepositoryImpl` duyệt qua mọi phần tử trong JsonArray/JsonObject, gom `vi_name`, `role`, `aliases`, `realm`, và mở rộng hàm `isProtagonist`/`isAntagonist` trong `BookBibleModels` với từ điển đa ngôn ngữ (tiếng Việt, tiếng Anh *"Main character"*, tiếng Trung *"主角", "男主"*).
- **Files liên quan**: `core/storage/src/main/java/com/epubpro/core/storage/bookbible/BookBibleRepositoryImpl.kt`, `domain/src/main/java/com/epubpro/domain/model/BookBibleModels.kt`

### Lỗi Card bị phình to 500-700dp tạo khoảng trắng khổng lồ do `FlowChips` dùng `Row`
- **Ngày**: 2026-08-27
- **Vấn đề**: Màn hình chi tiết hồ sơ nhân vật xuất hiện khoảng trống trắng khổng lồ giữa các Card, các chip chữ dài bị bóp dẹt theo chiều dọc thành 20-30 dòng.
- **Root cause**: `FlowChips` dùng `Row` đơn hàng ngang. Khi có 3-5 chip tràn màn hình, chip tràn phải bị bóp width chỉ còn ~10dp khiến văn bản dài wrap thành 20-30 dòng dọc. Trong Compose, `Row` tính chiều cao theo phần tử con cao nhất $\to$ Card phình to 500-700dp.
- **Fix**: Chuyển `FlowChips` và `addressTerms` sang `FlowRow(horizontalArrangement = spacedBy(8.dp), verticalArrangement = spacedBy(8.dp))` để tự động bọc dòng mượt mà.
- **Files liên quan**: `feature/bookbible/src/main/java/com/epubpro/feature/bookbible/BookBibleScreen.kt`

### Lỗi tiêu đề đối tượng giao tiếp trong Xưng hô & Giao tiếp hiển thị chữ Hán
- **Ngày**: 2026-08-27
- **Vấn đề**: Tiêu đề người đối thoại trong thẻ "Xưng hô & Giao tiếp" và "Mối quan hệ" hiển thị tên chữ Hán (`司徒微微`, `吴小蝶`, `二王爷`, `陆萝`) dù ngữ cảnh bên dưới đã dịch tiếng Việt.
- **Root cause**: Backend lưu trường `with`, `counterpart_text`, `counterpart_original_name` bằng tên gốc tiếng Hán. Mapper client lấy trực tiếp chuỗi này làm `targetName` mà không đối chiếu với danh mục nhân vật trong snapshot.
- **Fix**: Xây dựng `buildCharacterNameMap` trích xuất ánh xạ `originalName / ID / Name -> viName` từ snapshot và hàm `resolveVietnameseCharacterName` (tra cứu map + quét tìm tên tiếng Việt trong ngữ cảnh nếu chứa Hanzi) để chuẩn hóa toàn bộ tiêu đề đối tượng sang tiếng Việt.
- **Files liên quan**: `core/storage/src/main/java/com/epubpro/core/storage/bookbible/BookBibleRepositoryImpl.kt`

### Lỗi hiển thị `null` và Raw JSON trong Dòng thời gian (Timeline Events)
- **Ngày**: 2026-08-19
- **Vấn đề**: Các sự kiện kỹ năng/trang bị hiển thị chữ `"null"` thay vì tên kỹ năng, còn sự kiện xưng hô hiển thị chuỗi JSON thô `{"with":"Thẩm Dập",...}`.
- **Root cause**: Backend trả về tên kỹ năng/trang bị trong trường `attribute_key` trong khi trường `value` và `display_value` là `null`. Mapper cũ fallback lấy `value.toString()` dẫn đến chữ `"null"`.
- **Fix**: Trong `mapDtoToTimelineEvent`, bổ sung logic phân giải fallback từ `attribute_key` kết hợp `operation` (ví dụ: *"Lĩnh ngộ: Hoàng Kim Long Thể"*), đồng thời bóc tách object JSON của `address_terms` thành văn bản tiếng Việt tự nhiên *"Xưng hô với X (Tự xưng: Y • Gọi: Z)"*.
- **Files liên quan**: `core/storage/src/main/java/com/epubpro/core/storage/bookbible/BookBibleRepositoryImpl.kt`

### Lỗi trùng lặp thẻ Xưng hô và gộp nhầm người cùng họ
- **Ngày**: 2026-08-19
- **Vấn đề**: Giao diện hiển thị nhiều thẻ xưng hô bị lặp cho cùng 1 nhân vật (ví dụ "Thẩm Dập" và "Thẩm lão sư"), hoặc gộp nhầm 2 nhân vật khác nhau có cùng họ.
- **Root cause**: Logic first-word matching so sánh họ (`words1.first() == words2.first()`), và regex `\b` không hỗ trợ Unicode tiếng Việt.
- **Fix**: Xây dựng mảng `TITLE_WORDS` bóc tách danh xưng honorifics an toàn, loại bỏ first-word matching, so khớp sau khi đã strip danh xưng và gộp hợp nhất `selfTerm`, `otherTerm`, `contexts`.
- **Files liên quan**: `core/storage/src/main/java/com/epubpro/core/storage/bookbible/BookBibleRepositoryImpl.kt`

### Lỗi cờ "Tiến triển mới" hiển thị liên tục (Spam Changed Badge)
- **Ngày**: 2026-08-19
- **Vấn đề**: Lần nào mở lại Book Bible cũng thấy xuất hiện huy hiệu "Tiến triển mới" trên các nhân vật dù không có cập nhật mới ở chương đó.
- **Root cause**: Mapper cũ fallback gán `last_changed_chapter = entity.chapterNumber` khi trường này bị thiếu trong JSON.
- **Fix**: Bóc tách chính xác `last_changed_chapter` từ `attributes["profile"]` hoặc root DTO, chỉ kích hoạt cờ `changedInCurrentChapter` khi `last_changed_chapter == chapterNumber` và `chapterNumber > 1`.
- **Files liên quan**: `core/storage/src/main/java/com/epubpro/core/storage/bookbible/BookBibleRepositoryImpl.kt`

### Lỗi Locale-unsafe khi gọi `lowercase()` không chỉ định Locale
- **Ngày**: 2026-08-19
- **Vấn đề**: Khi chạy trên thiết bị cài đặt ngôn ngữ Thổ Nhĩ Kỳ (`tr-TR`), các phép so sánh chuỗi enum/status/category như `"SKILL".lowercase()` thành `"skıll"` (không khớp nhánh `when`).
- **Root cause**: Dùng `.lowercase()` mặc định của JVM thay vì `Locale.ROOT`.
- **Fix**: Chuẩn hóa toàn bộ bằng `.lowercase(Locale.ROOT)` và `titlecase(Locale.ROOT)` trên toàn bộ codebase.
- **Files liên quan**: `core/storage/src/main/java/com/epubpro/core/storage/bookbible/BookBibleRepositoryImpl.kt`, `domain/src/main/java/com/epubpro/domain/model/BookBibleModels.kt`

### Lỗi 502 do gọi catalog online ngoài ngữ cảnh
- **Ngày**: 2026-08-21
- **Vấn đề**: Mở tab Tiến trình phát sinh `GET /library/novels` và nhận HTTP 502 khi Render không có deployment hoạt động (`x-render-routing: no-deploy`).
- **Root cause**: `StoryProgressViewModel` gọi `OnlineNovelRepository.getNovels()` trong `init`, dù màn chỉ cần dữ liệu local và Book Bible cache.
- **Fix**: Bỏ dependency/call online khỏi ViewModel Tiến trình; giữ catalog online ở `OnlineLibraryViewModel`, còn dữ liệu online đã cache vẫn được giữ qua Room.
- **Files liên quan**: `feature/bookbible/src/main/java/com/epubpro/feature/bookbible/StoryProgressViewModel.kt`, `feature/library/src/main/java/com/epubpro/feature/library/online/OnlineLibraryViewModel.kt`

### Phân biệt API catalog với API review
- **Ngày**: 2026-08-21
- **Vấn đề**: Sau khi thêm hàng đợi duyệt, dễ nhầm việc lấy danh sách sách review với catalog `/library/novels`, dẫn đến phụ thuộc endpoint đang lỗi hoặc hiển thị sai nguồn dữ liệu.
- **Root cause**: Catalog online phục vụ duyệt/truy cập truyện, còn Book Bible `/books` phục vụ số lượng event và định danh sách cần kiểm duyệt.
- **Fix**: `StoryProgressViewModel` chỉ gọi `BookBibleRepository.getReviewBooks()`; `OnlineLibraryViewModel` giữ trách nhiệm gọi `OnlineNovelRepository.getNovels()`. Các thao tác event dùng riêng namespace `/book-bible/events`.
- **Files liên quan**: `feature/bookbible/src/main/java/com/epubpro/feature/bookbible/StoryProgressViewModel.kt`, `core/storage/src/main/java/com/epubpro/core/storage/network/BookBibleApiService.kt`

### Lỗi trùng lặp đầu sách do backend tồn tại nhiều book_id cho cùng tác phẩm
- **Ngày**: 2026-08-21
- **Vấn đề**: Màn hình Tiến trình truyện hiển thị đồng thời 2 thẻ cho cùng 1 bộ truyện (ví dụ: *"Ta Tại Bệnh Viện Tâm Thần Học Trảm Thần [AI]"* và *"Trảm Thần: Ta Học Trảm Thần Ở Bệnh Viện Tâm Thần"* của cùng tác giả Tam Cửu Âm Vực).
- **Root cause**: Database backend lưu 2 bản ghi `book_id` khác nhau cho cùng tác phẩm (một từ online novel catalog, một từ EPUB import/resolve trước đó). Client lọc theo `distinctBy { it.backendBookId ?: it.source.sourceId }` nên không thể nhận diện nếu ID khác nhau.
- **Fix**: Chuẩn hóa và gộp bản ghi trùng tại server database, cập nhật các bảng con (`editions`, `character_events`, `submissions`) trỏ về `book_id` chuẩn; phía Android map `backendBookId` với `book.onlineNovelId` của local book để hợp nhất thẻ hiển thị.
- **Files liên quan**: `feature/bookbible/src/main/java/com/epubpro/feature/bookbible/StoryProgressViewModel.kt`, `core/storage/src/main/java/com/epubpro/core/storage/network/BookBibleApiService.kt`

### Lỗi tràn text thô không dấu và trùng lặp thuộc tính trong card "Thông tin khác"
- **Ngày**: 2026-08-21
- **Vấn đề**: Màn hình chi tiết nhân vật (`CharacterDetailSheet`) hiển thị hàng loạt text thô không dấu (ví dụ: `Hon luc level`, `Vo hun`, `Hoc sinh su lai khac`, `blacksmith_rank`) và bị trùng lặp nhiều lần (ví dụ: cả `vo_hun` và `Võ Hồn`, cả `Đoán Tạo Sư` và `blacksmith_rank`, cả `weapon`, `Học viện`, `Loạn Phi Phong Chùy Pháp`).
- **Root cause**: Backend tích lũy sự kiện qua các chương với key không đồng nhất (`vo_hun`, `Võ Hồn`, `hon_luc_level`, `Hồn Lực`, `Hồn lực`, `weapon`, `membership`). Client mapper cũ chỉ lọc một số `standardKeys` cố định và hiển thị toàn bộ phần còn lại vào `extraAttributes` mà không gộp trùng hay chuyển mục.
- **Fix**:
  1. Tự động bóc tách và phân loại chuyên biệt (Category Promotion): chuyển các key vũ khí/pháp bảo (`weapon`, `vũ_khí`, `trang_bị`) vào `items`; chuyển trường học viện/hội nhóm (`Học viện`, `membership`, `tông_môn`) vào `affiliations`; chuyển công pháp (`Loạn Phi Phong Chùy Pháp`) vào `techniques`.
  2. Mở rộng `ATTRIBUTE_LABEL_TRANSLATIONS` với từ điển thể loại tiên hiệp/huyền huyễn/kiếm hiệp đầy đủ.
  3. Xây dựng thuật toán `extractDeduplicatedExtraAttributes`: gộp nhóm các key trùng ngữ nghĩa (bỏ dấu/lowercase), giữ lại nhãn tiếng Việt có dấu đẹp nhất và chọn giá trị mới nhất/đầy đủ nhất.
- **Files liên quan**: `core/storage/src/main/java/com/epubpro/core/storage/bookbible/BookBibleRepositoryImpl.kt`, `core/storage/src/test/java/com/epubpro/core/storage/bookbible/BookBibleRepositoryImplTest.kt`

---

## How-To

### Cách thêm thuộc tính hoặc phân mục mới vào Hồ sơ nhân vật (Book Bible)
- **Ngày**: 2026-08-19
- **Bước thực hiện**:
  1. Thêm model thuộc tính vào `domain/model/BookBibleModels.kt` (ví dụ: `CharacterPet`, `CharacterAddressTerm`).
  2. Khai báo field tương ứng trong `BookBibleDtos.kt` (`CharacterProfileDto`).
  3. Bổ sung parser bóc tách và lọc dữ liệu trong `BookBibleRepositoryImpl.kt` (`extractPetList`, `extractAddressTerms`, thêm key vào `standardKeys`).
  4. Khai báo chuỗi tiêu đề trong `strings.xml` (`core/designsystem`).
  5. Thêm `ProfileSectionCard` tương ứng trong `BookBibleScreen.kt`.
  6. Viết Unit Test trong `BookBibleRepositoryImplTest.kt` và `BookBibleViewModelTest.kt`.
- **Files liên quan**: `domain/src/main/java/com/epubpro/domain/model/BookBibleModels.kt`, `feature/bookbible/src/main/java/com/epubpro/feature/bookbible/BookBibleScreen.kt`

### Cách thêm entry point duyệt Book Bible cấp ứng dụng
- **Ngày**: 2026-08-21
- **Bước thực hiện**:
  1. Thêm projection tiến trình theo `BookBibleSource.uniqueKey` ở Domain/Room.
  2. Ghép danh sách `Book` với summary cache trong ViewModel và tạo summary mặc định cho truyện chưa có snapshot.
  3. Overlay danh sách sách backend review bằng `backendBookId`, `eventCount` và `pendingEventCount`.
  4. Điều hướng dòng sách tới `Screen.StoryReview`; giữ nút phụ điều hướng tới `Screen.BookBible` với source type, source ID và chương gần nhất.
  5. Có fallback local/cache khi API review lỗi, nhưng không thay API review bằng catalog `/library/novels`.
- **Files liên quan**: `core/database/src/main/java/com/epubpro/core/database/dao/BookBibleDao.kt`, `app/src/main/java/com/epubpro/app/navigation/AppNavigation.kt`

### Cách đóng khung Pretty Box Logger và Redact Header bảo mật
- **Ngày**: 2026-08-19
- **Bước thực hiện**:
  1. Trong `NetworkModule.kt`, tạo `HttpLoggingInterceptor.Logger` custom đóng khung đẹp với tag `API_HTTP`.
  2. Gọi `redactHeader("X-Book-Bible-Client-Key")`, `redactHeader("X-Api-Key")`, `redactHeader("Authorization")` để tránh lộ API key trên Logcat.
- **Files liên quan**: `core/storage/src/main/java/com/epubpro/core/storage/network/NetworkModule.kt`

---

## Patterns

### Pattern Avatar Gradient Palette theo Hash tên nhân vật
- **Ngày**: 2026-08-19
- **Chi tiết**: Sinh avatar màu sắc sống động tự động dựa trên hash code của tên nhân vật, lấy 1 trong 8 dải gradient màu nghệ thuật, giúp nhận diện thị giác tức thì mà không cần server trả về ảnh avatar.
- **Files liên quan**: `feature/bookbible/src/main/java/com/epubpro/feature/bookbible/BookBibleScreen.kt`

### Pattern chuẩn hóa thuật ngữ tu tiên / huyền huyễn (`ATTRIBUTE_LABEL_TRANSLATIONS`)
- **Ngày**: 2026-08-19
- **Chi tiết**: Khi backend trả về các key động từ LLM prompt (dạng snake_case hoặc tiếng Anh), sử dụng một `Map<String, String>` dịch tự động các thuật ngữ quen thuộc (như hồn lực, cảnh giới, võ hồn, điểm sát hạch) trước khi hiển thị cho người dùng.
- **Files liên quan**: `core/storage/src/main/java/com/epubpro/core/storage/bookbible/BookBibleRepositoryImpl.kt`

### Pattern Cache-First cho danh sách tiến trình
- **Ngày**: 2026-08-21
- **Chi tiết**: Cho phép mỗi truyện xuất hiện trước khi có snapshot bằng `BookBibleProgressSummary` mặc định (`latestChapterNumber = 0`), sau đó overlay status/chapter từ Room bằng `LOCAL_EPUB:<id>` hoặc `ONLINE_NOVEL:<id>`. Danh sách review từ backend được ưu tiên để có `pendingEventCount`; nếu request lỗi, local/cache vẫn giữ được entry point Book Bible.
- **Files liên quan**: `domain/src/main/java/com/epubpro/domain/model/BookBibleModels.kt`, `feature/bookbible/src/main/java/com/epubpro/feature/bookbible/StoryProgressViewModel.kt`

### Pattern giữ value JSON cho màn chỉnh sửa event
- **Ngày**: 2026-08-21
- **Chi tiết**: Model review lưu đồng thời `valueJson` và `displayValue`. UI hiển thị `displayValue`, còn dialog chỉnh sửa dùng `valueJson`; repository parse JSON hợp lệ và bọc văn bản thường thành `JsonPrimitive` trước khi gọi PATCH/approve.
- **Files liên quan**: `domain/src/main/java/com/epubpro/domain/model/BookBibleModels.kt`, `core/storage/src/main/java/com/epubpro/core/storage/bookbible/BookBibleRepositoryImpl.kt`, `feature/bookbible/src/main/java/com/epubpro/feature/bookbible/StoryReviewScreen.kt`

### Pattern sắp xếp ưu tiên Nhân vật chính (Protagonist Priority Ordering)
- **Ngày**: 2026-08-19
- **Chi tiết**: Trong ViewModel, sắp xếp danh sách nhân vật đa tiêu chí: Ưu tiên nhân vật chính (`isProtagonist`) $\to$ Nhân vật có tiến triển mới trong chương hiện tại (`changedInCurrentChapter`) $\to$ Theo thứ tự bảng chữ cái (`name`).
- **Files liên quan**: `feature/bookbible/src/main/java/com/epubpro/feature/bookbible/BookBibleViewModel.kt`, `domain/src/main/java/com/epubpro/domain/model/BookBibleModels.kt`

### Pattern tự động ánh xạ và giải quyết tên Hán sang tiếng Việt (Character Name Resolution & Context Cross-Referencing)
- **Ngày**: 2026-08-27
- **Chi tiết**: Khi API lưu đối tượng giao tiếp hoặc quan hệ dưới dạng tên gốc tiếng Hán (`with`, `counterpart_text`), sử dụng từ điển snapshot `buildCharacterNameMap` để ánh xạ `original_name / ID -> vi_name`. Nếu tên thô chứa chữ Hán (`containsHanCharacters`) mà chưa có trong map, tự động đối chiếu với đoạn văn bản ngữ cảnh tiếng Việt (`context`) để trích xuất tên tiếng Việt chuẩn.
- **Files liên quan**: `core/storage/src/main/java/com/epubpro/core/storage/bookbible/BookBibleRepositoryImpl.kt`
