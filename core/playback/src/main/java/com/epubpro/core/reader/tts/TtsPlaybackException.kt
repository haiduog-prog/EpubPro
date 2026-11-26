package com.epubpro.core.reader.tts

import androidx.annotation.StringRes

/**
 * Lỗi nghiệp vụ của phiên phát TTS mang sẵn thông báo cho người dùng dưới dạng string resource.
 *
 * `TtsService` hiển thị [messageRes] thay vì `message` thô của exception, nhờ đó lỗi từ Room, IO
 * hay thư viện không lộ ra notification, bubble hoặc Reader.
 *
 * @property messageRes ID chuỗi trong `strings.xml` mô tả lỗi cho người dùng.
 */
class TtsPlaybackException(
    @StringRes val messageRes: Int
) : IllegalStateException()
