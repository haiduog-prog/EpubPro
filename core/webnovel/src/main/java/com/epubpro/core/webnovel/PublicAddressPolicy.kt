package com.epubpro.core.webnovel

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Chính sách chỉ cho phép trình tải truyện kết nối tới địa chỉ Internet công khai.
 *
 * Chặn loopback, mạng nội bộ, link-local, CGNAT, multicast và IPv6 ULA để một trang web độc hại không thể
 * dùng redirect hoặc link chương khiến ứng dụng đọc dữ liệu từ dịch vụ trên thiết bị hay mạng LAN (SSRF).
 */
object PublicAddressPolicy {

    /**
     * Kiểm tra địa chỉ có phải địa chỉ công khai được phép kết nối.
     *
     * @param address Địa chỉ đã phân giải.
     * @return true nếu là địa chỉ Internet công khai.
     */
    fun isPublic(address: InetAddress): Boolean {
        if (address.isLoopbackAddress || address.isAnyLocalAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress
        ) {
            return false
        }
        val bytes = address.address
        return when (address) {
            is Inet4Address -> {
                val first = bytes[0].toInt() and 0xFF
                val second = bytes[1].toInt() and 0xFF
                // 100.64.0.0/10 (CGNAT) và 0.0.0.0/8 không phải đích Internet hợp lệ.
                !(first == 100 && second in 64..127) && first != 0
            }
            is Inet6Address -> {
                val first = bytes[0].toInt() and 0xFF
                val ipv4Mapped = bytes.copyOfRange(0, 10).all { it.toInt() == 0 } &&
                    (bytes[10].toInt() and 0xFF) == 0xFF && (bytes[11].toInt() and 0xFF) == 0xFF
                when {
                    // fc00::/7 (Unique Local Address).
                    first and 0xFE == 0xFC -> false
                    // ::ffff:a.b.c.d phải được xét như địa chỉ IPv4 tương ứng.
                    ipv4Mapped -> isPublic(InetAddress.getByAddress(bytes.copyOfRange(12, 16)))
                    else -> true
                }
            }
            else -> false
        }
    }
}
