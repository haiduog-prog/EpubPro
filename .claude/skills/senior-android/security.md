# Android Security Review

## Exported Component

Mọi Activity/Service/Receiver/Provider exported phải có lý do.

Nếu exported:

- validate caller/input;
- kiểm tra permission nếu phù hợp;
- không tin Intent extras.

## Deep Link

Validate:

- scheme;
- host;
- path;
- query param;
- redirect target.

Không dùng URL input để mở tùy ý WebView/native intent mà không validate.

## PendingIntent

Dùng immutable khi có thể.

Mutable chỉ khi API thực sự yêu cầu.

## Logs

Không log dữ liệu nhạy cảm.

## Storage

Token/credential không lưu plaintext SharedPreferences nếu threat model yêu cầu bảo vệ.

Không hardcode secret có giá trị bảo mật trong APK.

## WebView

Không addJavascriptInterface cho untrusted content.

Không bật file access không cần thiết.

## Intent

Cẩn trọng implicit Intent có sensitive data.

Validate Parcelable/Serializable/extras từ external caller.

## Clipboard

Không tự động copy sensitive data nếu không cần thiết.

## Screenshots

Cân nhắc FLAG_SECURE cho màn hình cực kỳ nhạy cảm theo requirement/security policy.
