# String Resources Rule (Quy tắc không hardcode chuỗi)

## Khi nào áp dụng
Áp dụng **bắt buộc** trong toàn bộ quá trình phát triển mã nguồn (Jetpack Compose, XML layout, Activity, Fragment, ViewModel, Repository, Service, Dialog, Toast, Notification,...).

## Quy định chi tiết

### 1. Nguyên tắc cốt lõi
- **TUYỆT ĐỐI KHÔNG HARDCODE CHUỖI VĂN BẢN**: Mọi chuỗi hiển thị tới người dùng (UI label, title, button text, dialog message, toast, thông báo lỗi, placeholder,...) đều **phải** được khai báo trong tệp tài nguyên chuỗi (`strings.xml`).
- Không để text trực tiếp kiểu: `Text(text = "Đang tải...")` hoặc `Toast.makeText(context, "Thành công", ...)`.

### 2. Vị trí lưu trữ chuỗi tài nguyên
- **Chuỗi dùng chung**: Khai báo tại `core/designsystem/src/main/res/values/strings.xml` để các module tính năng khác (`feature:*`, `app`) có thể tái sử dụng dễ dàng.
- **Chuỗi đặc thù riêng của module**: Khai báo tại file `strings.xml` nằm trong module tương ứng nếu chuỗi đó chỉ phục vụ riêng tính năng đó.

### 3. Cách sử dụng chuẩn theo từng môi trường

#### A. Trong Jetpack Compose
- Dùng `stringResource(R.string.<id>)`:
  ```kotlin
  import androidx.compose.ui.res.stringResource
  import com.epubpro.core.designsystem.R

  Text(
      text = stringResource(R.string.btn_continue_reading),
      style = MaterialTheme.typography.labelLarge
  )
  ```

#### B. Trong Context / Activity / Fragment / Service
- Dùng `context.getString(R.string.<id>)`:
  ```kotlin
  val message = context.getString(R.string.error_book_not_found)
  ```

#### C. Trong Data Model / UI State / Enums
- Sử dụng annotation `@StringRes` và truyền resource ID (`Int`) thay vì truyền `String` trực tiếp:
  ```kotlin
  data class NavItem(
      val route: String,
      @StringRes val titleRes: Int,
      @DrawableRes val iconRes: Int
  )
  ```

### 4. Xử lý chuỗi động (Dynamic Strings / Format Args)
- Khai báo placeholder có chỉ mục trong `strings.xml`:
  ```xml
  <string name="reading_progress_format">Đã đọc %1$d%% · Chương %2$s</string>
  <string name="highlight_note_format">Ghi chú: %1$s</string>
  ```
- Sử dụng trong Compose:
  ```kotlin
  Text(text = stringResource(R.string.reading_progress_format, progressPercent, chapterTitle))
  ```
- Sử dụng trong Context:
  ```kotlin
  val text = context.getString(R.string.reading_progress_format, progressPercent, chapterTitle)
  ```

### 5. Lưu ý về Smart Cast khi dùng tham số từ Module khác
- Khi truyền property nullable của model từ module khác vào `stringResource`: gán ra biến cục bộ (`val note = item.note`) trước khi kiểm tra non-null để tránh lỗi trình biên dịch Smart Cast.
