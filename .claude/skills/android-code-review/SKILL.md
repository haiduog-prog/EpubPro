---
name: android-code-review
description: Review code Android toàn diện như Senior Engineer (functional bug, regression, security, lifecycle, concurrency, crash, performance). Luôn xuất kết quả bằng tiếng Việt.
argument-hint: "<diff, PR, file hoặc chức năng cần review>"
user-invocable: true
language: vi
---

# Android Code Review

## Mục tiêu

Review code như Senior Android Engineer.

Không tập trung vào style nhỏ nếu không ảnh hưởng maintainability.

Ưu tiên tìm:

1. Functional bug.
2. Regression.
3. Data loss.
4. Security.
5. Lifecycle.
6. Concurrency.
7. Crash.
8. Compatibility.
9. Performance.
10. Maintainability.

---

# Review Process

## 1. Understand Diff

Đọc toàn bộ diff.

Xác định requirement nếu có.

Tìm code context trước và sau phần sửa.

Không review chỉ dựa trên vài dòng diff nếu logic phụ thuộc code xung quanh.

## 2. Trace Impact

Tìm:

- callers;
- subclasses/implementations;
- usages;
- persisted data;
- API contracts;
- UI state;
- navigation;
- events;
- tests.

## 3. Regression Review

Hỏi:

> Thay đổi này có làm behavior cũ thay đổi ngoài requirement không?

Đặc biệt chú ý:

- default values;
- reordered conditions;
- null handling;
- enum mapping;
- cache invalidation;
- early return;
- changed lifecycle timing;
- changed coroutine scope.

## 4. Android-specific Review

Kiểm tra:

- Activity/Fragment lifecycle;
- ViewBinding cleanup;
- coroutine cancellation;
- Flow collection;
- RecyclerView recycling;
- Compose side effects;
- Room migration;
- WorkManager duplication;
- exported component;
- PendingIntent flags;
- API level.

---

# Severity

## CRITICAL

- security vulnerability nghiêm trọng;
- data loss diện rộng;
- crash/blocker production;
- authentication/authorization bypass.

## HIGH

- functional bug lớn;
- race condition đáng tin cậy;
- lifecycle crash;
- regression chính;
- migration lỗi.

## MEDIUM

- edge case thực tế;
- maintainability issue có impact;
- performance issue đáng kể.

## LOW

- code smell nhỏ;
- readability;
- minor optimization.

## INFO

- suggestion;
- optional improvement.

---

# Confidence

Mỗi finding nên có:

- HIGH: bằng chứng rõ từ code.
- MEDIUM: có khả năng cao nhưng phụ thuộc runtime/context.
- LOW: giả thuyết cần xác minh.

Không tạo finding LOW chỉ để tăng số lượng review comment.

---

# Evidence Rule

Mỗi finding phải trả lời được:

- Code nào gây vấn đề?
- Điều kiện nào kích hoạt?
- Hậu quả cụ thể?
- Vì sao behavior hiện tại sai?

Không report theoretical issue không có execution path hợp lý.

---

# Output

Mỗi finding:

```text
[SEVERITY][CONFIDENCE] Tiêu đề

File/line:
...

Vấn đề:
...

Execution scenario:
...

Impact:
...

Đề xuất:
...
```

Cuối cùng:

```text
Regression risk: LOW | MEDIUM | HIGH
Recommendation: APPROVE | APPROVE_WITH_COMMENTS | REQUEST_CHANGES
```
