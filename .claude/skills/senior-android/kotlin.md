# Kotlin Engineering Rules

## Readability

Ưu tiên code có intent rõ ràng.

Không biến code thành chuỗi scope function khó đọc.

Ví dụ tránh:

```kotlin
foo?.let { a ->
    bar(a)?.also {
        ...
    }?.run {
        ...
    }
}
```

nếu early return hoặc local variable rõ hơn.

## Nullability

Không dùng `!!` trừ khi invariant được đảm bảo rõ và documented.

Phân biệt:

- null là valid domain state;
- null là not initialized;
- null là missing data.

Không dùng nullable để biểu diễn nhiều state nếu sealed class phù hợp hơn.

## Collections

Cẩn trọng allocation khi chain nhiều operation trên collection lớn.

Không tối ưu sớm với Sequence nếu data nhỏ và readability giảm.

## Equality

Dùng structural equality đúng ngữ nghĩa.

Cẩn trọng với:

- String case normalization;
- locale-sensitive operation;
- floating-point equality.

## Enum

Không persist ordinal.

Nếu persist enum, dùng stable value/string.

## Data class

Không đưa mutable collections vào immutable state nếu chúng có thể bị thay đổi ngoài ý muốn.

## Scope Functions

- `let`: transform/null scope.
- `also`: side effect.
- `apply`: object configuration.
- `run`: compute với receiver.
- `with`: grouped calls.

Không dùng chỉ để code nhìn "Kotlin hơn".
