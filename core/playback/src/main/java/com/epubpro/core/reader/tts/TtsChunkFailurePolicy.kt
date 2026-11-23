package com.epubpro.core.reader.tts

/** Loại lỗi khi phát một đoạn TTS. */
internal enum class TtsChunkFailureKind {
    /** Engine báo lỗi qua callback `onError` (tổng hợp lỗi, audio rỗng, khởi tạo lỗi...). */
    ERROR,

    /** Đoạn không hoàn tất trong thời hạn của watchdog (engine treo hoặc quá chậm). */
    TIMEOUT,
}

/** Hành động `TtsService` cần thực hiện sau khi một đoạn TTS phát lỗi. */
internal enum class TtsChunkFailureDecision {
    /** Phát lại chính đoạn đó bằng engine hiện tại. */
    RETRY_SAME_ENGINE,

    /** Đọc riêng đoạn đó bằng giọng hệ thống, đoạn sau quay lại engine AI. */
    NATIVE_FOR_THIS_CHUNK,

    /** Dùng giọng hệ thống cho phần còn lại của phiên phát. */
    NATIVE_FOR_SESSION,

    /** Bỏ qua đoạn lỗi và phát đoạn kế tiếp. */
    SKIP_CHUNK,

    /** Dừng phiên phát và hiển thị lỗi. */
    STOP_WITH_ERROR,
}

/**
 * Chính sách chịu lỗi khi phát từng đoạn TTS, tách khỏi `TtsService` để kiểm thử độc lập.
 *
 * Quy tắc:
 * - Lỗi thường ([TtsChunkFailureKind.ERROR]) được thử lại một lần trên cùng engine. Timeout không
 *   thử lại để tránh người nghe phải chờ thêm một chu kỳ watchdog.
 * - Với engine AI: hết lượt thử lại thì đọc riêng đoạn đó bằng giọng hệ thống. Khi đủ
 *   [maxConsecutiveAiFailures] đoạn AI hỏng liên tiếp thì chuyển hẳn sang giọng hệ thống cho phiên.
 * - Với giọng hệ thống: hết lượt thử lại thì bỏ qua đoạn. Khi đủ
 *   [maxConsecutiveNativeFailures] đoạn hỏng liên tiếp thì dừng phiên và báo lỗi.
 *
 * Class không an toàn đa luồng; `TtsService` chỉ gọi trên main thread.
 *
 * @param maxRetriesPerChunk Số lần thử lại tối đa cho một đoạn khi engine báo lỗi.
 * @param maxConsecutiveAiFailures Số đoạn AI hỏng liên tiếp trước khi chuyển hẳn sang giọng hệ thống.
 * @param maxConsecutiveNativeFailures Số đoạn hỏng liên tiếp trên giọng hệ thống trước khi dừng phiên.
 */
internal class TtsChunkFailurePolicy(
    private val maxRetriesPerChunk: Int = DEFAULT_MAX_RETRIES_PER_CHUNK,
    private val maxConsecutiveAiFailures: Int = DEFAULT_MAX_CONSECUTIVE_AI_FAILURES,
    private val maxConsecutiveNativeFailures: Int = DEFAULT_MAX_CONSECUTIVE_NATIVE_FAILURES,
) {
    private var retryChunkKey: Any? = null
    private var retriesForChunk: Int = 0
    private var consecutiveAiFailures: Int = 0
    private var consecutiveNativeFailures: Int = 0

    /**
     * Quyết định hành động tiếp theo khi một đoạn phát lỗi.
     *
     * @param chunkKey Khoá định danh đoạn (ví dụ cặp chương và chỉ số đoạn); đổi khoá sẽ reset lượt thử lại.
     * @param kind Loại lỗi xảy ra.
     * @param engineIsAi `true` nếu đoạn đang phát bằng engine AI (VieNeu/Sherpa), `false` nếu là giọng hệ thống.
     * @return Hành động mà `TtsService` cần thực hiện.
     */
    fun onChunkFailed(
        chunkKey: Any,
        kind: TtsChunkFailureKind,
        engineIsAi: Boolean,
    ): TtsChunkFailureDecision {
        if (chunkKey != retryChunkKey) {
            retryChunkKey = chunkKey
            retriesForChunk = 0
        }
        if (kind == TtsChunkFailureKind.ERROR && retriesForChunk < maxRetriesPerChunk) {
            retriesForChunk++
            return TtsChunkFailureDecision.RETRY_SAME_ENGINE
        }
        // Hết lượt thử lại: đoạn sau (hoặc engine khác cho cùng đoạn) được thử lại từ đầu.
        retriesForChunk = 0
        retryChunkKey = null

        return if (engineIsAi) {
            consecutiveAiFailures++
            if (consecutiveAiFailures >= maxConsecutiveAiFailures) {
                consecutiveAiFailures = 0
                TtsChunkFailureDecision.NATIVE_FOR_SESSION
            } else {
                TtsChunkFailureDecision.NATIVE_FOR_THIS_CHUNK
            }
        } else {
            consecutiveNativeFailures++
            if (consecutiveNativeFailures >= maxConsecutiveNativeFailures) {
                consecutiveNativeFailures = 0
                TtsChunkFailureDecision.STOP_WITH_ERROR
            } else {
                TtsChunkFailureDecision.SKIP_CHUNK
            }
        }
    }

    /**
     * Ghi nhận một đoạn đã phát xong để reset các bộ đếm lỗi liên tiếp tương ứng.
     *
     * Đoạn do giọng hệ thống đọc thay không reset bộ đếm lỗi AI, để các đoạn AI hỏng xen kẽ vẫn
     * được tính là liên tiếp.
     *
     * @param engineIsAi `true` nếu đoạn vừa hoàn tất được phát bằng engine AI.
     */
    fun onChunkSucceeded(engineIsAi: Boolean) {
        retryChunkKey = null
        retriesForChunk = 0
        consecutiveNativeFailures = 0
        if (engineIsAi) consecutiveAiFailures = 0
    }

    /** Xoá toàn bộ trạng thái, dùng khi bắt đầu nội dung mới, đổi cấu hình hoặc dừng phiên. */
    fun reset() {
        retryChunkKey = null
        retriesForChunk = 0
        consecutiveAiFailures = 0
        consecutiveNativeFailures = 0
    }

    companion object {
        const val DEFAULT_MAX_RETRIES_PER_CHUNK = 1
        const val DEFAULT_MAX_CONSECUTIVE_AI_FAILURES = 3
        const val DEFAULT_MAX_CONSECUTIVE_NATIVE_FAILURES = 3

        private const val AI_TIMEOUT_BASE_MS = 45_000L
        private const val NATIVE_TIMEOUT_BASE_MS = 20_000L
        private const val TIMEOUT_DURATION_MULTIPLIER = 3L

        /**
         * Tính thời hạn watchdog cho một đoạn, gồm thời gian tổng hợp và thời gian phát.
         *
         * Thời hạn rộng có chủ đích: chỉ nhằm bắt engine treo hoặc chậm bất thường, không thay
         * thế việc engine tự báo lỗi.
         *
         * @param estimatedChunkDurationMs Thời lượng phát ước lượng của đoạn (đã tính tốc độ đọc).
         * @param engineIsAi `true` nếu đoạn phát bằng engine AI (cần thêm thời gian nạp model và tổng hợp).
         * @return Thời hạn tính bằng mili-giây.
         */
        fun timeoutMs(estimatedChunkDurationMs: Long, engineIsAi: Boolean): Long {
            val base = if (engineIsAi) AI_TIMEOUT_BASE_MS else NATIVE_TIMEOUT_BASE_MS
            return base + estimatedChunkDurationMs.coerceAtLeast(0L) * TIMEOUT_DURATION_MULTIPLIER
        }
    }
}
