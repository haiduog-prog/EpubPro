# Local Book Import

> Tổng hợp kiến thức về luồng nạp EPUB, PRC, MOBI, AZW3 và quét sách giấy (OCR) vào thư viện dùng chung reader.
> Cập nhật lần cuối: 2026-11-10

---

## Architecture

### Pipeline quét sách giấy (ML Kit + VietOCR ONNX) thành EPUB
- **Ngày**: 2026-11-10
- **Chi tiết**: Quét trang bằng ML Kit Document Scanner (`SCANNER_MODE_FULL`) hoặc chọn ảnh từ Photo Picker, sao lưu vào `files/scanned_pages/<jobId>` rồi chạy `ScannedBookImportWorker` (chia sẻ tag và progress keys với `LocalBookImportWorker`). ML Kit Text Recognition v2 định vị khung dòng chữ, sau đó `VietOcrEngine` (ONNX `vgg_transformer` trong `assets/vietocr`, chép ra `noBackupFilesDir/vietocr/<version>`) đọc lại từng dòng để lấy dấu tiếng Việt chuẩn. `ScannedPageTextAssembler` lọc số trang/running header, nối dòng thành đoạn văn và tách chương để `EpubWriter` ghi thành EPUB, trích ảnh trang đầu làm cover và index FTS.
- **Files liên quan**: `core/ocr/src/main/java/com/epubpro/core/ocr/BookPageTextRecognizer.kt`, `core/ocr/src/main/java/com/epubpro/core/ocr/VietOcrEngine.kt`, `core/ocr/src/main/java/com/epubpro/core/ocr/ScannedPageTextAssembler.kt`, `core/storage/src/main/java/com/epubpro/core/storage/worker/ScannedBookImportWorker.kt`

### Pipeline chuyển đổi local dùng chung EPUB reader
- **Ngày**: 2026-08-27
- **Chi tiết**: URI từ Storage Access Framework được sao lưu vào app-private storage, giới hạn 100 MiB, sau đó `WorkManager` tiếp nhận tác vụ. `LocalBookImportWorker` gọi `MobiEpubConverter`; native libmobi dựng resource, Kotlin đóng gói EPUB tạm, kiểm tra container rồi rename atomically. Chỉ sau khi EPUB hoàn tất mới parse metadata, lưu Room và index FTS qua `EpubEngine`.
- **Files liên quan**: `core/storage/src/main/java/com/epubpro/core/storage/EpubStorageManager.kt`, `core/storage/src/main/java/com/epubpro/core/storage/worker/LocalBookImportScheduler.kt`, `core/storage/src/main/java/com/epubpro/core/storage/worker/LocalBookImportWorker.kt`, `core/book-converter/src/main/java/com/epubpro/core/bookconverter/MobiEpubConverter.kt`

### Hợp đồng tên resource giữa libmobi và OPF
- **Ngày**: 2026-08-27
- **Chi tiết**: `content.opf` là resource duy nhất được đổi tên cố định. NCX và mọi resource khác phải giữ mẫu `resource%05zu.%s`, vì `opf.c` tham chiếu cùng `uid` trong `manifest` và `spine`. Đổi NCX thành `toc.ncx` làm entry trong ZIP không khớp manifest, khiến reader mất TOC.
- **Files liên quan**: `core/book-converter/src/main/cpp/mobi_jni.c`, `core/book-converter/src/main/cpp/libmobi/src/opf.c`, `core/book-converter/src/main/java/com/epubpro/core/bookconverter/MobiEpubConverter.kt`

### Phân loại format Palm Database
- **Ngày**: 2026-08-27
- **Chi tiết**: `BOOKMOBI` bao quát MOBI/AZW3 ở mức header, còn `TEXtREAd` là PalmDOC/PRC. `BookSourceFormat` chỉ cần lưu phân loại nguồn rộng; native libmobi vẫn chịu trách nhiệm xác thực cấu trúc, DRM, replica và khả năng chuyển đổi thực tế.
- **Files liên quan**: `core/book-converter/src/main/java/com/epubpro/core/bookconverter/BookFormatSniffer.kt`, `domain/src/main/java/com/epubpro/domain/model/Models.kt`, `core/book-converter/src/main/cpp/mobi_jni.c`

---

## Bugs & Solutions

### VietOCR thêm từ rác ở mép dòng, mất gạch hội thoại hoặc sai dấu khi lượng tử hóa
- **Ngày**: 2026-11-10
- **Vấn đề**: Khung dòng ML Kit sát chữ làm VietOCR sinh thêm từ ở đầu/cuối hoặc mất dấu gạch lời thoại; lượng tử hóa động Conv hoặc lớp `fc` của decoder làm tăng lỗi dấu tiếng Việt ("ấ" thành "t").
- **Root cause**: Khung cắt thiếu lề giấy; decoder chạy lại cả chuỗi tốn bộ nhớ/thời gian; các lớp nhạy cảm bị ép về int8 động.
- **Fix**: Nới khung dòng (`padX = 0.5 * height`, `padY = 0.15 * height`), co ảnh bằng box filter (`VietOcrPreprocessor`), đối chiếu skeleton với chữ ML Kit trong `chooseLineText` để cắt từ thừa ở mép và khôi phục gạch đầu dòng. Khi export ONNX (`export_vietocr.py`), dùng step decoder có KV-cache (`past_i`), lượng tử tĩnh QDQ per-channel cho Conv và giữ fp32 cho lớp `/fc/` của decoder.
- **Files liên quan**: `core/ocr/src/main/java/com/epubpro/core/ocr/VietOcrEngine.kt`, `core/ocr/src/main/java/com/epubpro/core/ocr/VietOcrLineText.kt`, `core/ocr/src/main/java/com/epubpro/core/ocr/VietOcrPreprocessor.kt`, `tools/vietocr/export_vietocr.py`

### PalmDOC bị từ chối ở JNI
- **Ngày**: 2026-08-27
- **Vấn đề**: JNI chỉ gọi `mobi_is_mobipocket`, nên file có `type=TEXt` và `creator=REAd` bị trả về unsupported dù pipeline công bố hỗ trợ PRC.
- **Root cause**: Điều kiện native bỏ qua `mobi_is_textread`; prototype của hàm cũng chưa có trong `mobi.h`.
- **Fix**: Cho phép `mobi_is_mobipocket || mobi_is_textread`, vẫn loại `mobi_is_replica`, và thêm khai báo API vào header.
- **Files liên quan**: `core/book-converter/src/main/cpp/mobi_jni.c`, `core/book-converter/src/main/cpp/libmobi/src/mobi.h`

### Mất Snackbar khi import hoàn thành quá nhanh
- **Ngày**: 2026-08-27
- **Vấn đề**: Flow WorkManager có thể emit `SUCCEEDED` trước `ENQUEUED/RUNNING`, khiến UI không phát Snackbar thành công.
- **Root cause**: `hasActiveLocalImportSession` chỉ được bật trong observer.
- **Fix**: Bật cờ ngay trước `localBookImportScheduler.enqueue`; reset cờ trong các nhánh cancellation và exception để không giữ trạng thái giả.
- **Files liên quan**: `feature/library/src/main/java/com/epubpro/feature/library/LibraryViewModel.kt`

### File không có extension bị từ chối
- **Ngày**: 2026-08-27
- **Vấn đề**: Một số provider trả URI/tên file dạng `.bin` hoặc không có extension dù nội dung là PDB ebook hợp lệ.
- **Root cause**: Sniffer chỉ xét `file.extension`; hàm kiểm tra Palm Database header không được dùng.
- **Fix**: Fallback đọc 8 byte tại offset 60: `BOOKMOBI` → MOBI, `TEXtREAd` → PRC. Native vẫn xác thực sâu hơn.
- **Files liên quan**: `core/book-converter/src/main/java/com/epubpro/core/bookconverter/BookFormatSniffer.kt`, `core/book-converter/src/test/java/com/epubpro/core/bookconverter/BookFormatSnifferTest.kt`

---

## How-To

### Export và cập nhật model VietOCR ONNX
- **Ngày**: 2026-11-10
- **Bước thực hiện**:
  1. Chạy `python tools/vietocr/export_vietocr.py <out_dir> <calib_pages_dir>` để xuất `vietocr_encoder.onnx`, `vietocr_decoder.onnx` và `vietocr_vocab.txt`.
  2. Chép 3 file vào `core/ocr/src/main/assets/vietocr/` và tăng hằng số `MODEL_VERSION` trong `VietOcrEngine.kt` để thiết bị xóa bản chép cũ trong `noBackupFilesDir`.
  3. Chạy `./gradlew :core:ocr:testDebugUnitTest` để kiểm tra `VietOcrTest` và `ScannedPageTextAssemblerTest`.
- **Files liên quan**: `tools/vietocr/export_vietocr.py`, `core/ocr/src/main/java/com/epubpro/core/ocr/VietOcrEngine.kt`, `core/ocr/src/test/java/com/epubpro/core/ocr/VietOcrTest.kt`

### Thêm một nguồn ebook vào pipeline
- **Ngày**: 2026-08-27
- **Bước thực hiện**:
  1. Thêm extension và/hoặc header nhận diện trong `BookFormatSniffer`.
  2. Giữ file nguồn trong app-private storage và truyền format qua `WorkManager` input data.
  3. Đảm bảo native parser cho phép đúng loại header và resource output khớp `content.opf`.
  4. Đóng gói EPUB tạm, validate container, rồi commit atomically.
  5. Parse/index bằng cùng `EpubEngine`, bổ sung test sniffer và build JNI.
- **Files liên quan**: `BookFormatSniffer.kt`, `LocalBookImportScheduler.kt`, `LocalBookImportWorker.kt`, `MobiEpubConverter.kt`

### Xác minh thay đổi converter
- **Ngày**: 2026-08-27
- **Bước thực hiện**:
  1. Chạy `./gradlew :core:book-converter:testDebugUnitTest`.
  2. Chạy `./gradlew :core:book-converter:assembleDebug` để build CMake/JNI.
  3. Chạy test/compile `:feature:library` vì ViewModel và Compose permission nằm ở module này.
  4. Chạy `./gradlew :app:assembleDebug` để kiểm tra resource, manifest và packaging toàn app.
- **Files liên quan**: `core/book-converter/src/test/java/com/epubpro/core/bookconverter/BookFormatSnifferTest.kt`

---

## Patterns

### Hybrid Line Detection + Re-recognition với Fallback an toàn
- **Ngày**: 2026-11-10
- **Chi tiết**: Kết hợp bộ dò dòng nhanh/ổn định bố cục (ML Kit) với model chuyên biệt ngôn ngữ (VietOCR ONNX). Mở một `VietOcrSession` duy nhất cho cả cuốn sách và đóng trong `finally`. Nếu model lỗi hoặc độ dài chữ nhận được lệch ngoài ngưỡng `0.6..1.6` so với ML Kit, tự động fallback về chữ ML Kit của dòng đó mà không làm hỏng toàn bộ tác vụ quét.
- **Files liên quan**: `core/ocr/src/main/java/com/epubpro/core/ocr/BookPageTextRecognizer.kt`, `core/ocr/src/main/java/com/epubpro/core/ocr/VietOcrLineText.kt`

### Foreground notification cho tác vụ dài
- **Ngày**: 2026-08-27
- **Chi tiết**: Trước khi mở picker cho upload/import dài, kiểm tra `POST_NOTIFICATIONS` trên Android 13+ và xin quyền bằng Activity Result launcher. Worker vẫn chạy độc lập qua `WorkManager`; nếu người dùng từ chối, hệ điều hành có thể ẩn notification theo policy.
- **Files liên quan**: `feature/library/src/main/java/com/epubpro/feature/library/LibraryScreen.kt`, `feature/library/src/main/java/com/epubpro/feature/library/online/OnlineLibraryScreen.kt`, `core/storage/src/main/java/com/epubpro/core/storage/worker/LocalBookImportWorker.kt`

### Legacy native packaging là lựa chọn tương thích, không phải fix 16 KB
- **Ngày**: 2026-08-27
- **Chi tiết**: `useLegacyPackaging = true` làm native library được giải nén khi cài đặt, đổi lại tăng storage nhưng có thể hữu ích trong giai đoạn tương thích. Tùy chọn này không sửa ELF segment alignment cho cảnh báo page size 16 KB; muốn xử lý cảnh báo đó phải rebuild `.so` bằng toolchain/linker phù hợp.
- **Files liên quan**: `build-logic/src/main/kotlin/epubpro.android.application.gradle.kts`

### Test header bằng fixture tối thiểu
- **Ngày**: 2026-08-27
- **Chi tiết**: Unit test có thể tạo file tạm dài tối thiểu 78 byte, ghi identifier ASCII tại offset 60 và kiểm tra cả `sniff()` lẫn `hasPalmDatabaseHeader()`. Cách này không cần fixture ebook lớn nhưng vẫn bảo vệ fallback nhận diện extensionless.
- **Files liên quan**: `core/book-converter/src/test/java/com/epubpro/core/bookconverter/BookFormatSnifferTest.kt`
