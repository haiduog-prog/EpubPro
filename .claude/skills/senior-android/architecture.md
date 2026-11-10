# Android Architecture Rules

## Quy tắc số 1

Architecture hiện có của project là source of truth.

Trước khi tạo class mới:

- tìm implementation tương tự;
- xem dependency direction;
- xem naming;
- xem cách project truyền state/event;
- xem cách DI được sử dụng.

## Layering

Không cho UI layer truy cập trực tiếp storage/network nếu project có domain/data abstraction.

Không tạo UseCase chỉ để wrap một dòng code nếu project không có convention này.

Không để ViewModel chứa Android View.

Hạn chế Context trong ViewModel.

Nếu cần Application Context, dùng abstraction/DI phù hợp.

## State

State phải có owner rõ ràng.

Tránh hai nguồn state cùng đại diện một dữ liệu.

Khi dùng MVI:

- state là source of truth;
- event one-shot không nên nhét vào persistent state nếu gây replay sai;
- reducer phải predictable.

Khi dùng MVVM:

- tránh expose MutableLiveData/MutableStateFlow ra UI;
- expose immutable type.

## Dependency Direction

Feature không được phụ thuộc ngược vào app layer nếu architecture không cho phép.

Không tạo circular dependency.

## Public Contract

Cẩn trọng khi thay:

- Intent extras;
- Deep Link params;
- Parcelable model;
- SDK interface;
- module public API;
- callback;
- event bus event.

Các contract này có thể được sử dụng ngoài phạm vi search trực tiếp.
