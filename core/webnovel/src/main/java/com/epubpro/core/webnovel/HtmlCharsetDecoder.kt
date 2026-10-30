package com.epubpro.core.webnovel

import java.nio.charset.Charset

/**
 * Giải mã byte HTML theo charset khai báo, cần cho site truyện Trung dùng GBK/GB2312.
 */
object HtmlCharsetDecoder {
    private val headerCharset = Regex("charset=([\\w.:-]+)", RegexOption.IGNORE_CASE)
    private val metaCharset = Regex("<meta[^>]+charset\\s*=\\s*[\"']?([\\w.:-]+)", RegexOption.IGNORE_CASE)
    private const val SNIFF_BYTES = 4096

    /**
     * Giải mã HTML: ưu tiên charset trong header `Content-Type`, sau đó thẻ `<meta>`, cuối cùng UTF-8.
     *
     * @param bytes Nội dung phản hồi.
     * @param contentType Giá trị header `Content-Type`, có thể null.
     * @return Chuỗi HTML.
     */
    fun decode(bytes: ByteArray, contentType: String?): String {
        val declared = contentType?.let { headerCharset.find(it)?.groupValues?.get(1) }
            ?: metaCharset.find(String(bytes, 0, minOf(bytes.size, SNIFF_BYTES), Charsets.ISO_8859_1))?.groupValues?.get(1)
        val charset = declared?.let { name ->
            // GB2312 là tập con của GBK; giải mã bằng GBK tránh mất ký tự hiếm.
            val normalized = if (name.equals("gb2312", ignoreCase = true)) "GBK" else name
            runCatching { Charset.forName(normalized) }.getOrNull()
        } ?: Charsets.UTF_8
        return String(bytes, charset)
    }
}
