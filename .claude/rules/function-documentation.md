# Function Documentation Rule (Quy tắc tài liệu hóa hàm)

## Khi nào áp dụng
Áp dụng **bắt buộc** mỗi khi viết, tạo mới hoặc refactor một function/method trong toàn bộ mã nguồn của dự án (Kotlin, Java, hoặc các ngôn ngữ khác).

## Quy định chi tiết

### 1. Ngôn ngữ tài liệu
- **BẮT BUỘC** viết doc (KDoc / Javadoc) bằng **tiếng Việt**.
- Câu từ rõ ràng, ngắn gọn, súc tích, chuẩn kỹ thuật.

### 2. Cấu trúc chuẩn của một hàm (KDoc format)
Mỗi function mới khi được khai báo phải có block comment `/** ... */` đặt ngay phía trên khai báo hàm, bao gồm:

1. **Mô tả tổng quan (Summary)**: Giải thích ngắn gọn chức năng của hàm và ngữ cảnh sử dụng nếu cần.
2. **`@param <tên_tham_số>`**: Ý nghĩa và ràng buộc của từng tham số truyền vào (nếu có tham số).
3. **`@return`**: Giải thích giá trị trả về, ý nghĩa của các trường hợp đặc biệt như `null`, `empty`, `true/false` (nếu hàm trả về giá trị khác `Unit`/`void`).
4. **`@throws <loại_ngoại_lệ>`**: Giải thích ngoại lệ nào có thể bị ném ra và trong điều kiện nào (nếu có).

### 3. Ví dụ mẫu (Kotlin KDoc)

```kotlin
/**
 * Trích xuất và phân tích cú pháp nội dung chương từ file EPUB theo đường dẫn chỉ định.
 *
 * @param bookPath Đường dẫn tuyệt đối đến file sách EPUB trên bộ nhớ thiết bị.
 * @param chapterIndex Chỉ số thứ tự của chương cần tải (bắt đầu từ 0).
 * @return Đối tượng [ChapterContent] chứa tiêu đề, nội dung HTML và danh sách tài nguyên,
 *         hoặc `null` nếu không tìm thấy chương hoặc file lỗi.
 * @throws FileNotFoundException Ném ra khi đường dẫn file sách không tồn tại.
 */
suspend fun extractChapter(
    bookPath: String,
    chapterIndex: Int
): ChapterContent? {
    // ...
}
```

```kotlin
/**
 * Chuyển đổi văn bản thành giọng nói (TTS) và phát trực tiếp qua audio output.
 *
 * @param text Đoạn văn bản tiếng Việt cần đọc.
 * @param speechRate Tốc độ đọc (1.0 là tốc độ bình thường).
 */
fun speakText(text: String, speechRate: Float = 1.0f) {
    // ...
}
```

### 4. Quy tắc bổ sung
- Không bỏ trống doc comment hoặc chỉ ghi tên hàm lặp lại.
- Đối với interface/abstract method, doc phải mô tả kỳ vọng (contract) mà các implementation cần tuân thủ.
- Đối với override method (`override fun`), nếu hành vi thay đổi đặc thù so với hàm cha hoặc bổ sung logic quan trọng, nên cập nhật hoặc ghi rõ doc tiếng Việt.
