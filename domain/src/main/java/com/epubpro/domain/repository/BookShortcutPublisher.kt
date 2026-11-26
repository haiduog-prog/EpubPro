package com.epubpro.domain.repository

import com.epubpro.domain.model.Book

/**
 * Kết quả khi yêu cầu ghim lối tắt một cuốn sách ra màn hình chính.
 */
enum class PinShortcutResult {
    /** Đã gửi yêu cầu; launcher sẽ hiện hộp thoại xác nhận của hệ thống. */
    REQUESTED,

    /** Launcher hiện tại không hỗ trợ ghim lối tắt. */
    UNSUPPORTED,

    /** Hệ thống từ chối yêu cầu (ví dụ app không ở foreground). */
    FAILED
}

/**
 * Cổng tạo lối tắt mở sách trên màn hình chính.
 *
 * Phần cài đặt nằm ở module `:app` vì lối tắt phải trỏ tới cửa vào nội bộ của `MainActivity`;
 * các module feature chỉ biết interface này.
 */
interface BookShortcutPublisher {
    /**
     * Yêu cầu launcher ghim lối tắt mở thẳng cuốn sách, tiếp tục từ tiến độ đã lưu.
     *
     * Contract: chỉ gọi khi app đang ở foreground (do người dùng bấm); không ném exception,
     * mọi lỗi được trả về dưới dạng [PinShortcutResult].
     *
     * @param book Cuốn sách cần tạo lối tắt.
     * @return [PinShortcutResult.REQUESTED] nếu hệ thống đã nhận yêu cầu, ngược lại là lý do thất bại.
     */
    suspend fun requestPin(book: Book): PinShortcutResult
}
