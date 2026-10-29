---
name: android-bug-investigation
description: Điều tra bug/regression Android dựa trên bằng chứng (evidence), xác định nguyên nhân gốc rễ trước khi sửa code. Luôn xuất kết quả bằng tiếng Việt.
argument-hint: "<mô tả bug, log, crash hoặc bước reproduce>"
user-invocable: true
language: vi
---

# Android Bug Investigation

## Vai trò

Điều tra bug/regression dựa trên evidence.

Không sửa mò.

Không thay đổi code trước khi xác định được nguyên nhân hoặc có giả thuyết mạnh kèm evidence.

---

# Investigation Workflow

## 1. Reproduce

Thu thập:

- behavior mong muốn;
- behavior thực tế;
- thiết bị;
- Android version;
- app version;
- environment;
- bước reproduce;
- frequency.

Nếu thông tin không có, sử dụng code/history để điều tra trước thay vì dừng ngay.

---

## 2. Identify Regression Window

Nếu user cung cấp commit tốt cuối cùng hoặc range:

dùng:

```bash
git log
git diff
git show
git blame
```

So sánh commit tốt với HEAD.

Tập trung file liên quan execution path.

---

## 3. Search Execution Path

Trace:

```text
UI
→ event
→ ViewModel
→ UseCase
→ Repository
→ storage/network
→ callback/state
→ UI
```

Với bug UI:

trace cả state và lifecycle.

Với bug data:

trace cả write và read.

---

## 4. Hypothesis

Mỗi hypothesis cần:

- evidence;
- execution path;
- expected symptom;
- cách validate.

Ưu tiên hypothesis có khả năng giải thích toàn bộ symptom.

---

## 5. Common Android Regression Areas

### Lifecycle

- callback sau destroy;
- duplicated observer;
- Flow collect sai lifecycle;
- saved state;
- stale Fragment reference.

### Coroutine

- race condition;
- cancellation;
- concurrent requests;
- stale response override data mới.

### RecyclerView

- recycled state;
- listener duplicate;
- wrong position;
- mutable item.

### Compose

- wrong effect key;
- stale lambda;
- unstable state;
- repeated side effect.

### Cache

- invalidation thiếu;
- key thay đổi;
- default value thay đổi;
- cache object mutable.

### WebView

- CSS injection;
- JS timing;
- page reload;
- scroll restoration;
- DOM mutation.

---

## 6. Fix Strategy

Fix phải:

- sửa root cause;
- thay đổi nhỏ;
- không che symptom;
- không tạo behavior mới ngoài scope.

Nếu có nhiều cách:

ưu tiên cách ít regression risk nhất.

---

# Regression Test

Sau fix:

1. Reproduce case cũ.
2. Verify bug không còn.
3. Verify behavior trước regression.
4. Verify neighboring flows.
5. Verify lifecycle/reopen/retry nếu liên quan.

---

# Output

```text
## Root cause
...

## Evidence
...

## Fix
...

## Files affected
...

## Regression risk
...

## Validation
...
```

Nếu chưa đủ evidence để khẳng định root cause:

ghi rõ `Most likely cause`, không nói chắc chắn.
