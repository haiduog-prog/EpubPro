package com.epubpro.app.intent

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns

/**
 * Yêu cầu nạp file sách từ ứng dụng bên ngoài sau khi đã được thẩm định an toàn.
 *
 * @property uri URI trích xuất từ Intent chứa dữ liệu sách.
 * @property displayName Tên hiển thị gốc của file sách nếu truy vấn được.
 */
data class ExternalBookImportRequest(
    val uri: Uri,
    val displayName: String?
)

/**
 * Tiện ích thẩm định và trích xuất thông tin an toàn từ Intent mở hoặc chia sẻ file sách.
 */
object ExternalBookImportHandler {

    private val SUPPORTED_MIME_TYPES = setOf(
        "application/epub+zip",
        "application/epub",
        "application/x-zip-compressed-epub",
        "application/x-mobipocket-ebook",
        "application/vnd.amazon.mobi8-ebook",
        "application/x-palm-database",
        "application/vnd.comicbook+zip",
        "application/x-cbz"
    )

    private val SUPPORTED_EXTENSIONS = setOf(
        "epub",
        "prc",
        "mobi",
        "azw3",
        "cbz"
    )

    /**
     * Phân tích Intent từ hệ thống hoặc ứng dụng khác để tạo yêu cầu nạp sách.
     *
     * Phương thức thẩm định scheme (chỉ chấp nhận content hoặc file), lấy tên hiển thị an toàn
     * và xác thực định dạng trước khi chuyển tiếp cho pipeline xử lý.
     *
     * @param intent Intent nhận được từ onCreate hoặc onNewIntent.
     * @param contentResolver ContentResolver dùng để truy vấn tên hiển thị của URI.
     * @return [ExternalBookImportRequest] nếu Intent hợp lệ và là file sách được hỗ trợ, ngược lại trả về null.
     */
    fun parse(intent: Intent?, contentResolver: ContentResolver): ExternalBookImportRequest? {
        if (intent == null) return null
        val action = intent.action ?: return null
        if (action != Intent.ACTION_VIEW && action != Intent.ACTION_SEND) return null

        val uri = extractUri(intent, action) ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != ContentResolver.SCHEME_CONTENT && scheme != ContentResolver.SCHEME_FILE) {
            return null
        }

        val displayName = resolveDisplayName(contentResolver, uri)
        val mimeType = normalizeMimeType(intent.type ?: resolveContentMimeType(contentResolver, uri))

        if (!isSupportedEbook(displayName, uri, mimeType)) {
            return null
        }

        return ExternalBookImportRequest(
            uri = uri,
            displayName = displayName
        )
    }

    /**
     * Trích xuất URI nguồn từ Intent tùy theo Action mở hoặc chia sẻ.
     *
     * @param intent Intent cần trích xuất URI.
     * @param action Action của Intent.
     * @return URI của file hoặc null nếu không tìm thấy.
     */
    private fun extractUri(intent: Intent, action: String): Uri? {
        return when (action) {
            Intent.ACTION_VIEW -> {
                intent.data ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
            }
            Intent.ACTION_SEND -> {
                val streamUri = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                streamUri ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
            }
            else -> null
        }
    }

    /**
     * Truy vấn tên hiển thị của file từ ContentResolver hoặc trích xuất từ URI path.
     *
     * @param contentResolver ContentResolver dùng để query cột DISPLAY_NAME.
     * @param uri URI của file.
     * @return Tên hiển thị của file hoặc null nếu không xác định được.
     */
    private fun resolveDisplayName(contentResolver: ContentResolver, uri: Uri): String? {
        if (uri.scheme.equals(ContentResolver.SCHEME_CONTENT, ignoreCase = true)) {
            try {
                contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index != -1) {
                            val name = cursor.getString(index)
                            if (!name.isNullOrBlank()) return name
                        }
                    }
                }
            } catch (_: Exception) {
                // Tiếp tục fallback bên dưới nếu không truy vấn được metadata
            }
        }

        return uri.lastPathSegment?.let { segment ->
            safeUrlDecode(segment)?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        }
    }

    /**
     * Lấy MIME type từ ContentResolver an toàn không ném ngoại lệ.
     *
     * @param contentResolver ContentResolver để kiểm tra MIME.
     * @param uri URI cần kiểm tra.
     * @return MIME type nếu xác định được hoặc null.
     */
    private fun resolveContentMimeType(contentResolver: ContentResolver, uri: Uri): String? {
        if (!uri.scheme.equals(ContentResolver.SCHEME_CONTENT, ignoreCase = true)) return null
        return runCatching { contentResolver.getType(uri) }.getOrNull()
    }

    /**
     * Chuẩn hóa chuỗi MIME type về chữ thường và loại bỏ các tham số phụ.
     *
     * @param rawMimeType Chuỗi MIME thô ban đầu.
     * @return Chuỗi MIME đã chuẩn hóa hoặc null.
     */
    private fun normalizeMimeType(rawMimeType: String?): String? {
        return rawMimeType?.substringBefore(';')?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
    }

    /**
     * Giải mã URL an toàn bằng chuẩn UTF-8, không gây lỗi unmocked method trên môi trường kiểm thử đơn vị.
     *
     * @param value Chuỗi ký tự cần giải mã.
     * @return Chuỗi sau giải mã hoặc chuỗi gốc nếu giải mã thất bại.
     */
    private fun safeUrlDecode(value: String?): String? {
        if (value == null) return null
        return runCatching { java.net.URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
    }

    /**
     * Kiểm tra xem file hoặc URI có khớp định dạng sách điện tử được hỗ trợ hay không.
     *
     * @param displayName Tên hiển thị của file.
     * @param uri URI nguồn.
     * @param mimeType MIME type của Intent hoặc ContentResolver.
     * @return true nếu thỏa mãn định dạng ebook hỗ trợ, ngược lại false.
     */
    private fun isSupportedEbook(displayName: String?, uri: Uri, mimeType: String?): Boolean {
        if (mimeType != null && SUPPORTED_MIME_TYPES.contains(mimeType)) {
            return true
        }

        val extensionFromDisplayName = displayName
            ?.substringAfterLast('.', "")
            ?.lowercase()
            ?.takeIf { it.isNotBlank() }

        if (extensionFromDisplayName != null && SUPPORTED_EXTENSIONS.contains(extensionFromDisplayName)) {
            return true
        }

        val pathSegment = uri.lastPathSegment?.let { safeUrlDecode(it) }
        val extensionFromPath = pathSegment
            ?.substringAfterLast('.', "")
            ?.lowercase()
            ?.takeIf { it.isNotBlank() }

        if (extensionFromPath != null && SUPPORTED_EXTENSIONS.contains(extensionFromPath)) {
            return true
        }

        return false
    }
}
