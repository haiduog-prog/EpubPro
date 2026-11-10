# Android Performance Rules

## Main Thread

Không chạy:

- file I/O lớn;
- DB blocking;
- bitmap decode lớn;
- JSON parse lớn;
- crypto nặng;

trên Main Thread.

## Memory

Kiểm tra:

- Activity retained bởi singleton;
- Fragment binding leak;
- listener không remove;
- bitmap lớn;
- WebView;
- static callback;
- long-lived coroutine giữ reference UI.

## RecyclerView

Tránh:

- bind tạo object nặng;
- notifyDataSetChanged khi DiffUtil phù hợp;
- listener bị attach lặp;
- nested layout quá sâu.

## Compose

Tránh:

- unstable object được tạo mỗi recomposition;
- expensive calculation trong composable;
- state scope quá rộng;
- list thiếu key.

## Images

Tôn trọng target size.

Không load full-resolution image khi chỉ hiển thị thumbnail.
