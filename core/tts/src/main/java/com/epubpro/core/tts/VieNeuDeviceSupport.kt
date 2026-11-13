package com.epubpro.core.tts

import android.os.Build
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tiện ích kiểm tra khả năng hỗ trợ phần cứng cho VieNeu-TTS v3-Nano (`arm64-v8a`).
 */
object VieNeuDeviceSupport {
    const val REQUIRED_ABI = "arm64-v8a"

    /**
     * Kiểm tra thiết bị hiện tại có hỗ trợ ABI 64-bit (`arm64-v8a`) để chạy thư viện `sea-g2p` và ONNX VieNeu hay không.
     *
     * @return `true` nếu thiết bị hỗ trợ `arm64-v8a`, ngược lại trả về `false`.
     */
    fun isSupportedDevice(): Boolean = runCatching {
        Build.SUPPORTED_ABIS?.any { it.equals(REQUIRED_ABI, ignoreCase = true) } == true
    }.getOrDefault(false)
}

/**
 * Thành phần injectable giúp kiểm tra hỗ trợ ABI của thiết bị và dễ dàng giả lập trong JVM unit test.
 */
@Singleton
open class VieNeuDeviceSupportChecker @Inject constructor() {

    /**
     * Trả về trạng thái thiết bị có hỗ trợ chạy VieNeu-TTS v3-Nano hay không.
     *
     * @return `true` trên thiết bị `arm64-v8a`.
     */
    open fun isSupported(): Boolean = VieNeuDeviceSupport.isSupportedDevice()
}
