---
name: android-migrate-xml-views-to-compose
description: Migrate màn hình XML/View sang Jetpack Compose theo lát nhỏ. Luôn xuất kết quả bằng tiếng Việt.
argument-hint: "<mục tiêu, diff, file hoặc lỗi cần xử lý>"
user-invocable: true
source-repo: android/skills
upstream-skill: migrate-xml-views-to-jetpack-compose
upstream-path: jetpack-compose/migration/migrate-xml-views-to-jetpack-compose
language: vi
---

# android-migrate-xml-views-to-compose

Nguồn gốc: chuyển thể standalone từ `android/skills` / `migrate-xml-views-to-jetpack-compose`.

## Quy tắc output

- Luôn trả lời bằng **tiếng Việt**.
- Giữ nguyên code identifiers, API names, Gradle tasks, XML tags, package/class/function names và log/error text.
- Khi đưa code, dùng Kotlin/Gradle/XML đúng ngữ cảnh project.
- Không tuyên bố đã chạy test/build nếu chưa có bằng chứng.

## Khi dùng

Dùng skill này khi task liên quan đến: **Migrate màn hình XML/View sang Jetpack Compose theo lát nhỏ.**

## Quy trình

1. Xác định module/file và yêu cầu cụ thể trước khi sửa.
2. Đọc cấu hình/code liên quan, không chỉ file đang mở.
3. Áp dụng thay đổi nhỏ nhất theo Android best practice và pattern hiện có.
4. Kiểm tra lifecycle, permission, compatibility, performance hoặc policy nếu liên quan.
5. Đề xuất command build/test/lint hoặc thao tác thiết bị cần chạy.

## Checklist

- Output trả lời bằng tiếng Việt.
- Không bịa version hoặc API chưa kiểm chứng trong repo hiện tại.
- Không sửa lan man ngoài phạm vi task.
- Luôn ghi rõ validation đã chạy hoặc cần chạy.

## Format output bắt buộc

```markdown
Mục tiêu:
- ...

Phạm vi đã kiểm tra:
- ...

Nhận định:
- ...

Thay đổi/đề xuất:
- ...

Validation:
- Đã chạy: ...
- Cần chạy: ...

Rủi ro còn lại:
- ...
```
