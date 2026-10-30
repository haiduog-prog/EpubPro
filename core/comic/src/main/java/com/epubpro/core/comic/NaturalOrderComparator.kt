package com.epubpro.core.comic

/**
 * So sánh chuỗi theo thứ tự tự nhiên: cụm chữ số được so theo giá trị số, phần còn lại không phân biệt hoa thường.
 *
 * Dùng để sắp trang truyện tranh sao cho `2.jpg` đứng trước `10.jpg` và `Chap 2/` đứng trước `Chap 10/`,
 * điều mà sắp xếp từ điển thông thường làm sai.
 */
object NaturalOrderComparator : Comparator<String> {

    /**
     * So sánh hai chuỗi theo thứ tự tự nhiên.
     *
     * @param left Chuỗi bên trái.
     * @param right Chuỗi bên phải.
     * @return Số âm, 0 hoặc số dương theo quy ước của [Comparator].
     */
    override fun compare(left: String, right: String): Int {
        var i = 0
        var j = 0
        while (i < left.length && j < right.length) {
            val a = left[i]
            val b = right[j]
            if (a.isDigit() && b.isDigit()) {
                val endA = digitRunEnd(left, i)
                val endB = digitRunEnd(right, j)
                val numberOrder = compareDigitRuns(left.substring(i, endA), right.substring(j, endB))
                if (numberOrder != 0) return numberOrder
                i = endA
                j = endB
            } else {
                val charOrder = a.lowercaseChar().compareTo(b.lowercaseChar())
                if (charOrder != 0) return charOrder
                i++
                j++
            }
        }
        val lengthOrder = (left.length - i).compareTo(right.length - j)
        // Chuỗi chỉ khác hoa thường vẫn cần thứ tự ổn định để danh sách trang không thay đổi giữa các lần mở.
        return if (lengthOrder != 0) lengthOrder else left.compareTo(right)
    }

    /**
     * Tìm vị trí kết thúc của cụm chữ số bắt đầu tại [start].
     *
     * @param value Chuỗi nguồn.
     * @param start Vị trí ký tự số đầu tiên.
     * @return Chỉ số ngay sau ký tự số cuối cùng của cụm.
     */
    private fun digitRunEnd(value: String, start: Int): Int {
        var end = start
        while (end < value.length && value[end].isDigit()) end++
        return end
    }

    /**
     * So sánh hai cụm chữ số theo giá trị mà không chuyển sang Long, tránh tràn số với tên file dài.
     *
     * @param a Cụm chữ số thứ nhất.
     * @param b Cụm chữ số thứ hai.
     * @return Kết quả so sánh theo giá trị; cùng giá trị thì cụm ít số 0 đứng đầu hơn đứng trước.
     */
    private fun compareDigitRuns(a: String, b: String): Int {
        val trimmedA = a.trimStart('0')
        val trimmedB = b.trimStart('0')
        if (trimmedA.length != trimmedB.length) return trimmedA.length.compareTo(trimmedB.length)
        val valueOrder = trimmedA.compareTo(trimmedB)
        return if (valueOrder != 0) valueOrder else a.length.compareTo(b.length)
    }
}
