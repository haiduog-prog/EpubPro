---
name: compose-perf-visualizing-recomposition-cascades
description: Nhìn cascade/heatmap recomposition để biết change lan tới đâu. Luôn xuất kết quả bằng tiếng Việt.
argument-hint: "<mục tiêu, diff, file hoặc lỗi cần xử lý>"
user-invocable: true
source-repo: skydoves/compose-performance-skills
upstream-skill: visualizing-recomposition-cascades
upstream-path: stability/visualizing-recomposition-cascades
language: vi
---

# compose-perf-visualizing-recomposition-cascades

Nguồn gốc: chuyển thể standalone từ `skydoves/compose-performance-skills` / `visualizing-recomposition-cascades`.

## Quy tắc output

- Luôn trả lời bằng **tiếng Việt**.
- Giữ nguyên code identifiers, API names, Gradle tasks, XML tags, package/class/function names và log/error text.
- Khi đưa code, dùng Kotlin/Gradle/XML đúng ngữ cảnh project.
- Không tuyên bố đã chạy test/build nếu chưa có bằng chứng.

## Khi dùng

Dùng skill này khi task liên quan đến: **Nhìn cascade/heatmap recomposition để biết change lan tới đâu.**

## Quy trình

1. Bắt đầu bằng bằng chứng đo được: compiler report, trace, recomposition count, benchmark hoặc symptom cụ thể.
2. Không tối ưu mò; xác định nguyên nhân trước khi sửa.
3. Áp dụng fix nhỏ, đúng hot path, tránh đổi architecture rộng.
4. Đo/kiểm lại bằng release mode hoặc tool phù hợp khi performance là mục tiêu.
5. Báo cáo before/after, giới hạn bằng chứng và bước tiếp theo.

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
