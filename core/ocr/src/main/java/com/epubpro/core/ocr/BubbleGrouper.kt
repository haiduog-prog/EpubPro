package com.epubpro.core.ocr

/**
 * Một dòng/khối chữ OCR với tọa độ chuẩn hóa 0..1 theo trang.
 *
 * @property text Văn bản nhận diện.
 * @property left Cạnh trái.
 * @property top Cạnh trên.
 * @property right Cạnh phải.
 * @property bottom Cạnh dưới.
 */
data class OcrTextBlock(
    val text: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    /** Chiều rộng khối. */
    val width: Float get() = right - left

    /** Chiều cao khối. */
    val height: Float get() = bottom - top
}

/**
 * Gom các dòng OCR nằm sát nhau thành bóng thoại và sắp theo thứ tự đọc.
 */
object BubbleGrouper {
    /** Hệ số nới khung theo cạnh ngắn của khối khi xét hai khối có thuộc cùng bóng thoại. */
    private const val MERGE_MARGIN_FACTOR = 0.6f

    /** Hai bóng có cạnh trên lệch nhau ít hơn tỉ lệ này (theo chiều cao trang) được coi cùng hàng. */
    private const val SAME_ROW_TOLERANCE = 0.04f

    /**
     * Gom khối thành bóng thoại.
     *
     * @param blocks Các khối OCR.
     * @param rightToLeft true với manga: trong cùng hàng, bóng bên phải được đọc trước.
     * @param joinWithSpace true với chữ Latin/Hàn: nối dòng bằng dấu cách; false với chữ Hán/Nhật.
     * @return Bóng thoại theo thứ tự đọc, mỗi bóng là một [OcrTextBlock] bao trọn các dòng.
     */
    fun group(blocks: List<OcrTextBlock>, rightToLeft: Boolean, joinWithSpace: Boolean): List<OcrTextBlock> {
        val valid = blocks.filter { it.text.isNotBlank() && it.width > 0f && it.height > 0f }
        if (valid.isEmpty()) return emptyList()

        val parent = IntArray(valid.size) { it }
        fun find(index: Int): Int {
            var current = index
            while (parent[current] != current) {
                parent[current] = parent[parent[current]]
                current = parent[current]
            }
            return current
        }
        for (i in valid.indices) {
            for (j in i + 1 until valid.size) {
                if (isNear(valid[i], valid[j])) parent[find(i)] = find(j)
            }
        }

        val groups = valid.indices.groupBy(::find).values.map { members -> members.map(valid::get) }
        val bubbles = groups.map { members -> mergeGroup(members, rightToLeft, joinWithSpace) }
        return sortReadingOrder(bubbles, rightToLeft)
    }

    /**
     * Kiểm tra hai khối có đủ gần để thuộc cùng bóng thoại.
     *
     * @param a Khối thứ nhất.
     * @param b Khối thứ hai.
     * @return true nếu khung đã nới của hai khối giao nhau.
     */
    private fun isNear(a: OcrTextBlock, b: OcrTextBlock): Boolean {
        val margin = minOf(minOf(a.width, a.height), minOf(b.width, b.height)) * MERGE_MARGIN_FACTOR
        return a.left - margin <= b.right && b.left - margin <= a.right &&
            a.top - margin <= b.bottom && b.top - margin <= a.bottom
    }

    /**
     * Gộp các dòng của một bóng: khung bao chung và văn bản theo thứ tự dòng.
     *
     * Chữ dọc (khối cao hơn rộng) đọc theo cột từ phải sang trái nếu là manga; chữ ngang đọc từ trên xuống.
     *
     * @param members Các dòng của bóng.
     * @param rightToLeft Đọc phải sang trái.
     * @param joinWithSpace Nối dòng bằng dấu cách.
     * @return Khối bao trọn bóng thoại.
     */
    private fun mergeGroup(members: List<OcrTextBlock>, rightToLeft: Boolean, joinWithSpace: Boolean): OcrTextBlock {
        val vertical = members.all { it.height > it.width * 1.5f } && members.size > 1
        val ordered = if (vertical) {
            if (rightToLeft) members.sortedByDescending { it.right } else members.sortedBy { it.left }
        } else {
            members.sortedWith(compareBy<OcrTextBlock> { it.top }.thenBy { it.left })
        }
        return OcrTextBlock(
            text = ordered.joinToString(if (joinWithSpace) " " else "") { it.text.trim() },
            left = members.minOf { it.left },
            top = members.minOf { it.top },
            right = members.maxOf { it.right },
            bottom = members.maxOf { it.bottom }
        )
    }

    /**
     * Sắp bóng thoại theo hàng từ trên xuống; trong hàng theo hướng đọc.
     *
     * @param bubbles Các bóng thoại.
     * @param rightToLeft Đọc phải sang trái.
     * @return Danh sách đã sắp xếp.
     */
    private fun sortReadingOrder(bubbles: List<OcrTextBlock>, rightToLeft: Boolean): List<OcrTextBlock> {
        val rows = mutableListOf<MutableList<OcrTextBlock>>()
        bubbles.sortedBy { it.top }.forEach { bubble ->
            val row = rows.lastOrNull()
            if (row != null && bubble.top - row.first().top < SAME_ROW_TOLERANCE) row += bubble else rows += mutableListOf(bubble)
        }
        return rows.flatMap { row -> if (rightToLeft) row.sortedByDescending { it.right } else row.sortedBy { it.left } }
    }
}
