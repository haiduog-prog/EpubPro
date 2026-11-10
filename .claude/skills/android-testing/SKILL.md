---
name: android-testing
description: Thiết kế và đánh giá test Android theo kim tự tháp test dựa trên risk (Unit, Integration, UI test). Luôn xuất kết quả bằng tiếng Việt.
argument-hint: "<mục tiêu test, function, repository hoặc flow cần test>"
user-invocable: true
language: vi
---

# Android Testing Skill

## Mục tiêu

Thiết kế test dựa trên risk, không dựa trên coverage số lượng.

---

# Test Pyramid

Ưu tiên:

1. Unit tests cho business logic.
2. Integration tests cho data/repository.
3. UI tests chỉ cho critical flow hoặc behavior UI cần xác nhận.

Không biến mọi thứ thành instrumentation test.

---

# Khi thay đổi code

Tạo test matrix:

## Happy path

Requirement chính.

## Boundary

- empty;
- null;
- zero;
- max/min;
- first/last item.

## Error

- network error;
- timeout;
- malformed response;
- DB error nếu phù hợp.

## Lifecycle

Nếu liên quan UI/state:

- recreate;
- background/foreground;
- navigation back;
- duplicate event.

## Concurrency

Nếu async:

- double tap;
- two requests;
- old response về sau new response;
- cancellation.

## Compatibility

Nếu model/storage thay đổi:

- old data;
- missing field;
- default value;
- migration.

---

# Unit Test Principles

Test behavior, không test implementation detail.

Tên test nên mô tả:

```text
given_when_then
```

hoặc câu mô tả rõ behavior.

Không mock mọi object nếu fake đơn giản hơn.

---

# Coroutine Tests

Sử dụng test dispatcher phù hợp.

Không phụ thuộc real delay.

Kiểm tra state emission theo order khi order có ý nghĩa.

---

# Flow Tests

Kiểm tra:

- initial state;
- emitted state;
- no duplicate emission nếu requirement;
- event replay behavior;
- cancellation.

---

# Regression Test

Mỗi bug fix nên có ít nhất một test chứng minh bug cũ nếu có thể viết tự động.

Test phải fail trước fix và pass sau fix.

---

# Output

```text
## Test cases
1. ...
2. ...

## Automated tests
- ...

## Manual verification
- ...

## Areas not covered
- ...
```
