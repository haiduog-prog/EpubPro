package com.epubpro.core.reader.tts

/**
 * [TtsMessageResolver] giả cho unit test JVM: trả về chuỗi định danh theo resource ID để test kiểm tra
 * đúng loại thông báo mà không cần Android `Resources`.
 */
object TestTtsMessages : TtsMessageResolver {
    /**
     * Tạo chuỗi định danh cho resource ID và tham số.
     *
     * @param resId ID chuỗi.
     * @param formatArgs Tham số định dạng (được nối vào chuỗi để test so sánh được).
     * @return Chuỗi dạng `res:<id>` kèm tham số nếu có.
     */
    override fun getString(resId: Int, vararg formatArgs: Any): String = text(resId, *formatArgs)

    /**
     * Chuỗi kỳ vọng tương ứng với [getString], dùng trong assert.
     *
     * @param resId ID chuỗi.
     * @param formatArgs Tham số định dạng.
     * @return Chuỗi định danh giống hệt [getString].
     */
    fun text(resId: Int, vararg formatArgs: Any): String =
        if (formatArgs.isEmpty()) "res:$resId" else "res:$resId:${formatArgs.joinToString(",")}"
}
