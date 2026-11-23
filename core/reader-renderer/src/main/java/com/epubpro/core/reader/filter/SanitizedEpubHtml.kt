package com.epubpro.core.reader.filter

import java.security.MessageDigest

/**
 * Kiểu dữ liệu đóng gói chuỗi HTML chương sách EPUB đã được kiểm tra và làm sạch an toàn qua [EpubHtmlSanitizer].
 *
 * Đảm bảo các tầng UI / WebView chỉ nhận dữ liệu đã qua khử độc (xóa active scripts, iframes, on* handlers, URL độc)
 * và không thể tùy ý khởi tạo dữ liệu không an toàn từ bên ngoài.
 *
 * @property rawHtml Chuỗi mã HTML đã được làm sạch an toàn.
 */
@JvmInline
value class SanitizedEpubHtml internal constructor(val rawHtml: String) {

    /**
     * Kiểm tra chuỗi HTML có rỗng hoặc chỉ chứa khoảng trắng hay không.
     */
    val isBlank: Boolean
        get() = rawHtml.isBlank()

    /**
     * Chiều dài ký tự của chuỗi HTML.
     */
    val length: Int
        get() = rawHtml.length

    companion object {
        /** Đối tượng rỗng đại diện cho chuỗi HTML rỗng an toàn. */
        val EMPTY = SanitizedEpubHtml("")

        /**
         * Khôi phục đối tượng [SanitizedEpubHtml] từ nguồn snapshot tin cậy nếu mã băm và phiên bản sanitizer khớp chính xác.
         *
         * @param html Chuỗi HTML đã làm sạch được lưu trong snapshot.
         * @param sourceHash Mã SHA-256 của HTML nguồn gốc trước khi làm sạch.
         * @param actualSourceHtml Chuỗi HTML nguồn gốc cần đối chiếu (nếu có).
         * @param sanitizerVersion Phiên bản của bộ làm sạch tại thời điểm tạo snapshot.
         * @return Đối tượng [SanitizedEpubHtml] nếu hợp lệ, ngược lại trả về `null`.
         */
        fun restoreFromSnapshot(
            html: String,
            sourceHash: String,
            actualSourceHtml: String? = null,
            sanitizerVersion: Int
        ): SanitizedEpubHtml? {
            if (sourceHash.isBlank()) return null
            if (sanitizerVersion != EpubHtmlSanitizer.CURRENT_SANITIZER_VERSION) return null
            if (actualSourceHtml != null) {
                val computedHash = MessageDigest.getInstance("SHA-256")
                    .digest(actualSourceHtml.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
                if (computedHash != sourceHash) return null
            }
            return SanitizedEpubHtml(html)
        }
    }
}
