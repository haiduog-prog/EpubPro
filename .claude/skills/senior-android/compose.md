# Jetpack Compose Rules

## State

State owner phải rõ ràng.

Hoist state nếu parent cần điều khiển.

Dùng immutable UI state.

## remember

Không dùng `remember` để giữ state phải survive configuration/process recreation.

Dùng `rememberSaveable` khi dữ liệu phù hợp SavedState.

## Effect

- `LaunchedEffect`: coroutine side effect.
- `DisposableEffect`: resource cần cleanup.
- `SideEffect`: publish state ra non-Compose object.
- `rememberUpdatedState`: giữ callback/value mới trong long-lived effect.

Kiểm tra key cẩn thận.

Sai key có thể gây:

- effect không restart khi cần;
- effect restart liên tục;
- duplicate network request.

## Recomposition

Không thực hiện:

- network;
- DB write;
- analytics;
- navigation;

trực tiếp trong composable body.

## LazyList

Dùng stable key khi item có identity ổn định.

Không dùng index làm key nếu list có insert/remove/reorder.
