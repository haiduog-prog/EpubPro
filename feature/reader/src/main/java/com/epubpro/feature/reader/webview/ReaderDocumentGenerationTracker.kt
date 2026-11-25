package com.epubpro.feature.reader.webview

internal class ReaderDocumentGenerationTracker {
    private var currentLoadGeneration: Int = 0
    private var pendingDestinationGeneration: Int? = null

    /**
     * Cấp mã thế hệ cho document HTML sắp nạp. Nếu đang chờ đích của một lần chuyển chương mà có document
     * mới hơn được nạp (người dùng bấm chuyển chương tiếp khi màn che còn hiển thị), đích chờ được chuyển
     * sang document mới nhất để màn che chỉ gỡ khi chương cuối cùng đã vẽ xong, không lộ các lần nạp ở giữa.
     *
     * @return Mã thế hệ tăng dần của document mới.
     */
    fun nextLoadGeneration(): Int {
        currentLoadGeneration += 1
        val pending = pendingDestinationGeneration
        if (pending != null && currentLoadGeneration > pending) {
            pendingDestinationGeneration = currentLoadGeneration
        }
        return currentLoadGeneration
    }

    fun beginChapterTransition(): Int {
        return (currentLoadGeneration + 1).also { expectedGeneration ->
            pendingDestinationGeneration = expectedGeneration
        }
    }

    /**
     * Dời đích chờ của lần chuyển chương hiện tại sang lần nạp document kế tiếp. Gọi khi gộp thêm một yêu cầu
     * chuyển chương dưới màn che đang hiển thị: document đang chờ (hoặc đang layout) chưa phải chương cuối
     * cùng người dùng muốn tới, nên không được gỡ màn che.
     *
     * @return `true` nếu đang có lần chuyển chương chờ và đích đã được dời; `false` nếu không có.
     */
    fun extendPendingToNextLoad(): Boolean {
        val pending = pendingDestinationGeneration ?: return false
        pendingDestinationGeneration = maxOf(pending, currentLoadGeneration + 1)
        return true
    }

    /**
     * Kiểm tra [loadGeneration] có phải document đích đang được màn che chờ hay không.
     *
     * @param loadGeneration Mã thế hệ document vừa báo layout sẵn sàng.
     * @return `true` nếu đúng document đích đang chờ.
     */
    fun isPendingDestination(loadGeneration: Int): Boolean =
        pendingDestinationGeneration == loadGeneration

    fun completeIfExpected(loadGeneration: Int): Boolean {
        if (pendingDestinationGeneration != loadGeneration) return false
        pendingDestinationGeneration = null
        return true
    }

    fun cancelIfExpected(loadGeneration: Int): Boolean {
        if (pendingDestinationGeneration != loadGeneration) return false
        pendingDestinationGeneration = null
        return true
    }

    /**
     * Hủy lần chuyển chương đang chờ bất kể đích hiện tại (đích có thể đã được chuyển sang document mới
     * hơn), dùng khi hết thời gian chờ để không giữ màn che mãi.
     *
     * @return `true` nếu có lần chuyển chương đang chờ và đã bị hủy.
     */
    fun cancelPending(): Boolean {
        if (pendingDestinationGeneration == null) return false
        pendingDestinationGeneration = null
        return true
    }
}
