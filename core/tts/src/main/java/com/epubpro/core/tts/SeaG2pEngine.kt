package com.epubpro.core.tts

import java.io.Closeable
import java.io.File

/**
 * Giao diện chuẩn hoá văn bản và chuyển đổi chữ viết sang âm vị (G2P).
 */
interface TextG2p : Closeable {
    /**
     * Chuẩn hoá văn bản tiếng Việt / song ngữ sang dạng đọc được (số, ngày tháng, đơn vị).
     *
     * @param text Văn bản đầu vào.
     * @param puncNorm Có áp dụng quy tắc chốt dấu câu cuối theo `sea-g2p` hay không.
     * @return Văn bản đã chuẩn hoá.
     */
    fun normalize(text: String, puncNorm: Boolean = false): String

    /**
     * Chuyển đổi văn bản sang chuỗi âm vị IPA tương thích với VieNeu-TTS.
     *
     * @param text Văn bản đầu vào.
     * @param puncNorm Có áp dụng quy tắc chốt dấu câu cuối hay không.
     * @return Chuỗi âm vị IPA.
     */
    fun phonemize(text: String, puncNorm: Boolean = true): String

    /**
     * Áp dụng quy tắc chốt dấu câu cuối cho một chunk văn bản hoặc âm vị.
     *
     * @param text Chuỗi đầu vào.
     * @return Chuỗi đã chuẩn hoá dấu câu cuối.
     */
    fun puncNorm(text: String): String
}

/**
 * Cầu nối JNI trực tiếp tới thư viện C ABI của `sea-g2p` (chỉ nạp trên thiết bị `arm64-v8a`).
 */
internal object SeaG2pNative {
    init {
        System.loadLibrary("sea_g2p_rs")
        System.loadLibrary("sea_g2p_jni")
    }

    /**
     * Lấy phiên bản C ABI mà `libsea_g2p_rs.so` đang cung cấp.
     *
     * @return Mã phiên bản ABI.
     */
    external fun abiVersion(): Int

    /**
     * Mở file từ điển `sea_g2p.bin` và khởi tạo handle native.
     *
     * @param dictPathUtf8 Đường dẫn file `sea_g2p.bin` mã hoá UTF-8 chuẩn.
     * @return Con trỏ handle native.
     */
    external fun open(dictPathUtf8: ByteArray): Long

    /**
     * Giải phóng handle native đã mở từ [open].
     *
     * @param handlePtr Con trỏ handle native.
     */
    external fun close(handlePtr: Long)

    /**
     * Chuẩn hoá và chuyển văn bản UTF-8 sang âm vị IPA bằng handle native.
     *
     * @param handlePtr Con trỏ handle native.
     * @param textUtf8 Văn bản UTF-8 chuẩn.
     * @param puncNorm Có chốt dấu câu cuối hay không.
     * @return Chuỗi âm vị UTF-8 chuẩn.
     */
    external fun phonemize(handlePtr: Long, textUtf8: ByteArray, puncNorm: Boolean): ByteArray

    /**
     * Chuẩn hoá văn bản UTF-8 (chưa G2P) bằng handle native.
     *
     * @param handlePtr Con trỏ handle native.
     * @param textUtf8 Văn bản UTF-8 chuẩn.
     * @param puncNorm Có chốt dấu câu cuối hay không.
     * @return Chuỗi đã chuẩn hoá UTF-8 chuẩn.
     */
    external fun normalize(handlePtr: Long, textUtf8: ByteArray, puncNorm: Boolean): ByteArray

    /**
     * Áp dụng quy tắc dấu câu cuối thuần chuỗi bằng `sea_g2p_punc_norm`.
     *
     * @param textUtf8 Chuỗi đầu vào UTF-8 chuẩn.
     * @return Chuỗi kết quả UTF-8 chuẩn.
     */
    external fun puncNorm(textUtf8: ByteArray): ByteArray
}

/**
 * Wrapper an toàn luồng quanh handle `sea_g2p` để chạy offline trên Android `arm64-v8a`.
 *
 * @param dictFile Đường dẫn tới file `sea_g2p.bin`.
 * @throws IllegalArgumentException Nếu file từ điển không tồn tại.
 * @throws IllegalStateException Nếu thiết bị không phải `arm64-v8a`, ABI không khớp hoặc không mở được từ điển.
 */
class SeaG2pEngine(dictFile: File) : TextG2p {
    private val lock = Any()

    @Volatile
    private var handlePtr: Long = 0L

    @Volatile
    private var closed: Boolean = false

    /**
     * Phiên bản C ABI được báo cáo bởi thư viện `libsea_g2p_rs.so`.
     */
    val abiVersion: Int

    init {
        check(VieNeuDeviceSupport.isSupportedDevice()) {
            "Thiết bị hiện tại không hỗ trợ ABI 64-bit arm64-v8a cho sea-g2p"
        }
        require(dictFile.isFile) { "Không tìm thấy từ điển sea_g2p.bin tại: ${dictFile.absolutePath}" }
        val reportedAbi = SeaG2pNative.abiVersion()
        check(reportedAbi == EXPECTED_ABI_VERSION) {
            "Phiên bản sea_g2p ABI không tương thích: $reportedAbi (kỳ vọng $EXPECTED_ABI_VERSION)"
        }
        abiVersion = reportedAbi
        val pathBytes = dictFile.absolutePath.toByteArray(Charsets.UTF_8)
        val ptr = SeaG2pNative.open(pathBytes)
        check(ptr != 0L) { "Không thể mở sea_g2p từ điển tại: ${dictFile.absolutePath}" }
        handlePtr = ptr
    }

    /**
     * Chuẩn hoá văn bản tiếng Việt / song ngữ sang dạng phát âm được.
     *
     * @param text Văn bản đầu vào.
     * @param puncNorm Có áp dụng quy tắc dấu câu cuối hay không.
     * @return Văn bản đã chuẩn hoá.
     * @throws IllegalStateException Nếu engine đã đóng hoặc gọi native thất bại.
     */
    override fun normalize(text: String, puncNorm: Boolean): String {
        if (text.isEmpty()) return ""
        return synchronized(lock) {
            val ptr = activeHandle()
            val outBytes = SeaG2pNative.normalize(ptr, text.toByteArray(Charsets.UTF_8), puncNorm)
            String(outBytes, Charsets.UTF_8)
        }
    }

    /**
     * Chuẩn hoá và chuyển đổi văn bản sang âm vị IPA.
     *
     * @param text Văn bản đầu vào.
     * @param puncNorm Có áp dụng quy tắc dấu câu cuối hay không.
     * @return Chuỗi âm vị IPA.
     * @throws IllegalStateException Nếu engine đã đóng hoặc gọi native thất bại.
     */
    override fun phonemize(text: String, puncNorm: Boolean): String {
        if (text.isEmpty()) return ""
        return synchronized(lock) {
            val ptr = activeHandle()
            val outBytes = SeaG2pNative.phonemize(ptr, text.toByteArray(Charsets.UTF_8), puncNorm)
            String(outBytes, Charsets.UTF_8)
        }
    }

    /**
     * Áp dụng quy tắc dấu câu cuối của `sea-g2p` cho một chunk.
     *
     * @param text Chuỗi chunk cần chốt dấu câu cuối.
     * @return Chuỗi đã chốt dấu câu cuối.
     */
    override fun puncNorm(text: String): String = Companion.puncNorm(text)

    /**
     * Đóng handle native và giải phóng bộ nhớ ánh xạ `sea_g2p.bin`.
     */
    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            val ptr = handlePtr
            handlePtr = 0L
            if (ptr != 0L) {
                SeaG2pNative.close(ptr)
            }
        }
    }

    /**
     * Trả về con trỏ handle đang mở hoặc ném ngoại lệ nếu engine đã đóng.
     *
     * @return Con trỏ handle `sea_g2p` hợp lệ.
     */
    private fun activeHandle(): Long {
        check(!closed && handlePtr != 0L) { "SeaG2pEngine đã được đóng" }
        return handlePtr
    }

    companion object {
        const val EXPECTED_ABI_VERSION = 1

        /**
         * Gọi hàm `sea_g2p_punc_norm` thuần chuỗi không cần mở handle từ điển.
         *
         * @param text Chuỗi cần chốt dấu câu cuối.
         * @return Chuỗi sau khi áp dụng quy tắc dấu câu cuối.
         */
        fun puncNorm(text: String): String {
            if (text.isEmpty()) return ""
            check(VieNeuDeviceSupport.isSupportedDevice()) {
                "Thiết bị hiện tại không hỗ trợ ABI 64-bit arm64-v8a cho sea-g2p"
            }
            val outBytes = SeaG2pNative.puncNorm(text.toByteArray(Charsets.UTF_8))
            return String(outBytes, Charsets.UTF_8)
        }
    }
}
