---
name: android-intent-security
description: Audit Intent, DeepLink, exported component, PendingIntent và URI permission. Luôn xuất kết quả bằng tiếng Việt.
argument-hint: "<mục tiêu, diff, file hoặc lỗi cần xử lý>"
user-invocable: true
source-repo: android/skills
upstream-skill: android-intent-security
upstream-path: security/android-intent-security
language: vi
---

# android-intent-security

Nguồn gốc: chuyển thể standalone từ `android/skills` / `android-intent-security`.

## Quy tắc output

- Luôn trả lời bằng **tiếng Việt**.
- Giữ nguyên code identifiers, API names, Gradle tasks, XML tags, package/class/function names và log/error text.
- Khi đưa code, dùng Kotlin/Gradle/XML đúng ngữ cảnh project.
- Không tuyên bố đã chạy test/build nếu chưa có bằng chứng.

## Khi dùng

Dùng skill này khi task liên quan đến: **Audit Intent, DeepLink, exported component, PendingIntent và URI permission.**

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
