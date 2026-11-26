package com.epubpro.core.designsystem.cover

import kotlin.math.abs

/**
 * Bảng màu gradient cho bìa sách tự sinh khi sách không có ảnh bìa.
 *
 * Dùng chung cho bìa Compose ở Kệ sách và icon lối tắt vẽ bằng Canvas, để cùng một cuốn sách
 * luôn có cùng màu ở mọi nơi. Màu được chọn ổn định theo tiêu đề.
 */
object GeneratedCoverPalette {
    private val gradients: List<Pair<Long, Long>> = listOf(
        0xFF8E2DE2 to 0xFF4A00E0, // Deep Violet
        0xFFD4145A to 0xFFFBB03B, // Warm Sunset
        0xFF009245 to 0xFF11998E, // Emerald Leaf
        0xFF667EEA to 0xFF764BA2, // Purple Blue
        0xFF13547A to 0xFF80D0C7, // Ocean Teal
        0xFF2B5876 to 0xFF4E4376, // Slate Midnight
        0xFFE0C3FC to 0xFF8EC5FC, // Lavender Ice
        0xFFF093FB to 0xFFF5576C  // Bright Coral
    )

    /**
     * Chọn cặp màu gradient (trên, dưới) cho một tiêu đề.
     *
     * @param title Tiêu đề sách.
     * @return Cặp màu ARGB dạng `Long` (trên, dưới), dùng được cho `Color(Long)` hoặc `.toInt()` cho Canvas.
     */
    fun gradientFor(title: String): Pair<Long, Long> =
        gradients[abs(title.hashCode() % gradients.size)]
}
