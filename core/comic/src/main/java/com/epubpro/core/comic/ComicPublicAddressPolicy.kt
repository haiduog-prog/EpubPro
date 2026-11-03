package com.epubpro.core.comic

import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import okhttp3.Dns
import okhttp3.OkHttpClient

/**
 * Chính sách chỉ cho phép tải truyện tranh online từ địa chỉ Internet công khai.
 *
 * Trang truyện là nội dung bên ngoài: link chương, URL ảnh hoặc redirect có thể trỏ tới localhost/mạng LAN.
 * Chặn loopback, mạng nội bộ, link-local, CGNAT, multicast và IPv6 ULA để ứng dụng không bị dùng làm cầu
 * đọc dữ liệu từ dịch vụ trên thiết bị hay mạng nội bộ (SSRF), nhất là khi tải hàng loạt ở nền.
 */
internal object ComicPublicAddressPolicy {

    /**
     * Kiểm tra địa chỉ có phải địa chỉ công khai được phép kết nối.
     *
     * @param address Địa chỉ đã phân giải.
     * @return true nếu là địa chỉ Internet công khai.
     */
    fun isPublic(address: InetAddress): Boolean {
        if (address.isLoopbackAddress || address.isAnyLocalAddress ||
            address.isLinkLocalAddress || address.isSiteLocalAddress ||
            address.isMulticastAddress
        ) return false

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
                    ipv4Mapped -> isPublic(InetAddress.getByAddress(bytes.copyOfRange(12, 16)))
                    else -> true
                }
            }
            else -> false
        }
    }

    /**
     * Gắn kiểm tra địa chỉ công khai vào OkHttp client: DNS loại địa chỉ nội bộ, network interceptor kiểm tra
     * địa chỉ socket thực tế của mọi request (kể cả sau redirect và URL dạng IP không qua DNS).
     *
     * @param builder Builder của client cần bảo vệ.
     * @return Chính [builder] để nối tiếp cấu hình.
     */
    fun applyTo(builder: OkHttpClient.Builder): OkHttpClient.Builder = builder
        .dns(PublicOnlyDns)
        .addNetworkInterceptor { chain ->
            val remote = chain.connection()?.socket()?.inetAddress
            if (remote != null && !isPublic(remote)) throw IOException(BLOCKED_MESSAGE)
            chain.proceed(chain.request())
        }

    /** DNS chỉ trả về địa chỉ công khai; host chỉ phân giải ra địa chỉ nội bộ bị coi như không tồn tại. */
    private object PublicOnlyDns : Dns {
        /**
         * Phân giải host và loại địa chỉ nội bộ.
         *
         * @param hostname Tên host.
         * @return Danh sách địa chỉ công khai.
         * @throws UnknownHostException Nếu không còn địa chỉ công khai nào.
         */
        override fun lookup(hostname: String): List<InetAddress> {
            val addresses = Dns.SYSTEM.lookup(hostname).filter(::isPublic)
            if (addresses.isEmpty()) throw UnknownHostException(BLOCKED_MESSAGE)
            return addresses
        }
    }

    private const val BLOCKED_MESSAGE = "Địa chỉ nội bộ không được phép tải"
}
