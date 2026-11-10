---
name: cb-implement-with-subagents
description: Implement ticket qua subagent tuần tự có handoff/verification. Luôn xuất kết quả bằng tiếng Việt.
argument-hint: "<mục tiêu, diff, file hoặc lỗi cần xử lý>"
user-invocable: true
source-repo: chrisbanes/skills
upstream-skill: implement-with-subagents
upstream-path: skills/implement-with-subagents
language: vi
---

# cb-implement-with-subagents

Nguồn gốc: chuyển thể standalone từ `chrisbanes/skills` / `implement-with-subagents`.

## Quy tắc output

- Luôn trả lời bằng **tiếng Việt**.
- Giữ nguyên code identifiers, API names, Gradle tasks, XML tags, package/class/function names và log/error text.
- Khi đưa code, dùng Kotlin/Gradle/XML đúng ngữ cảnh project.
- Không tuyên bố đã chạy test/build nếu chưa có bằng chứng.

## Khi dùng

Dùng skill này khi task liên quan đến: **Implement ticket qua subagent tuần tự có handoff/verification.**

## Quy trình

1. Phân loại vấn đề: state/effect/API/layout/performance/concurrency/test/workflow.
2. Đọc code gọi và code được gọi để giữ đúng ownership và lifecycle.
3. Chọn Kotlin/Compose idiomatic, ưu tiên API nhỏ và rõ boundary.
4. Nêu trade-off, regression risk và validation bằng tiếng Việt.

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
