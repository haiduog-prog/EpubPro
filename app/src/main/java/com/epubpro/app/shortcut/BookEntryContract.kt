package com.epubpro.app.shortcut

import android.content.Context
import android.content.Intent
import com.epubpro.core.reader.tts.TtsOpenBookContract

/**
 * Contract Intent mở một cuốn sách chỉ theo `bookId`, dùng cho lối tắt trên màn hình chính.
 *
 * Khác [TtsOpenBookContract], Intent này không mang chỉ số chương: Reader tự khôi phục chương và trang
 * từ tiến độ đã lưu. Intent luôn trỏ tới alias nội bộ không export nên app khác không giả được.
 */
object BookEntryContract {
    const val ACTION_OPEN_BOOK_ENTRY = "com.epubpro.app.action.OPEN_BOOK_ENTRY"
    const val EXTRA_BOOK_ID = "com.epubpro.app.extra.ENTRY_BOOK_ID"

    /**
     * Tạo Intent explicit tới alias nội bộ để mở một cuốn sách.
     *
     * @param context Context dùng để lấy package name.
     * @param bookId ID sách trong thư viện, không được rỗng.
     * @return Intent sẵn sàng gắn vào lối tắt.
     * @throws IllegalArgumentException Khi [bookId] rỗng.
     */
    fun buildIntent(context: Context, bookId: String): Intent {
        require(bookId.isNotBlank()) { "A book entry request requires a book id" }
        return Intent(ACTION_OPEN_BOOK_ENTRY)
            .setComponent(TtsOpenBookContract.internalEntryComponent(context))
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_BOOK_ID, bookId)
    }

    /**
     * Đọc `bookId` từ Intent mở sách theo lối tắt.
     *
     * Không kiểm tra nguồn gốc Intent; bên gọi phải kiểm tra [TtsOpenBookContract.isInternalEntry] trước.
     *
     * @param intent Intent Activity nhận được.
     * @return ID sách, hoặc `null` nếu không đúng action, thiếu extra hoặc extra hỏng.
     */
    fun parseBookId(intent: Intent?): String? {
        if (intent?.action != ACTION_OPEN_BOOK_ENTRY) return null
        // Extra hỏng có thể ném khi unparcel; không được làm crash Activity.
        return runCatching { intent.getStringExtra(EXTRA_BOOK_ID) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }
}
