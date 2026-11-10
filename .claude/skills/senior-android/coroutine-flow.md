# Coroutine & Flow Rules

## Scope

Coroutine phải sống đúng lifetime của owner.

Ưu tiên:

- viewModelScope;
- lifecycleScope;
- repeatOnLifecycle;
- application scope được DI rõ ràng cho app-wide work.

Không dùng GlobalScope.

## Dispatcher

- UI: Main.
- blocking I/O: IO.
- CPU-heavy: Default.

Không wrap suspend network/Room API bằng `withContext(IO)` nếu library đã xử lý dispatcher và không có lý do cần thiết.

## Cancellation

Không catch `Exception` rồi nuốt CancellationException.

Nếu catch broad exception:

rethrow cancellation.

## Flow

Kiểm tra:

- collector lifecycle;
- duplicated collector;
- replay;
- SharingStarted;
- stateIn/shareIn lifetime;
- initial value;
- repeated side effect.

## Race Condition

Cẩn trọng pattern:

```kotlin
if (!isLoading) {
    isLoading = true
    load()
}
```

nếu nhiều coroutine có thể chạy đồng thời.

Cần mutex/atomic/single-flight hoặc architecture đảm bảo serialized processing.

## One-shot Event

SharedFlow/Channel/UI event phải được chọn dựa trên behavior cần thiết.

Không dùng StateFlow cho event nếu replay event cũ gây sai behavior.
