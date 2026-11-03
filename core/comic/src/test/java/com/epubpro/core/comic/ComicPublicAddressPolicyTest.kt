package com.epubpro.core.comic

import java.net.InetAddress
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Kiểm tra chính sách chặn địa chỉ nội bộ khi tải truyện tranh online. */
class ComicPublicAddressPolicyTest {

    /** Loopback, LAN, link-local, CGNAT, 0.0.0.0/8, IPv6 ULA và IPv4-mapped nội bộ đều bị chặn. */
    @Test
    fun blocksPrivateAddresses() {
        listOf(
            byteArrayOf(127, 0, 0, 1),
            byteArrayOf(10, 1, 2, 3),
            byteArrayOf(192.toByte(), 168.toByte(), 1, 1),
            byteArrayOf(172.toByte(), 16, 0, 1),
            byteArrayOf(169.toByte(), 254.toByte(), 1, 1),
            byteArrayOf(100, 64, 0, 1),
            byteArrayOf(0, 0, 0, 0),
            ipv6(0xFC, 0x00),
            ByteArray(16).also { it[10] = 0xFF.toByte(); it[11] = 0xFF.toByte(); it[12] = 192.toByte(); it[13] = 168.toByte() }
        ).forEach { bytes ->
            assertFalse(bytes.joinToString(), ComicPublicAddressPolicy.isPublic(InetAddress.getByAddress(bytes)))
        }
    }

    /** Địa chỉ Internet công khai (IPv4, IPv6) được phép kết nối. */
    @Test
    fun allowsPublicAddresses() {
        assertTrue(ComicPublicAddressPolicy.isPublic(InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8))))
        assertTrue(ComicPublicAddressPolicy.isPublic(InetAddress.getByAddress(byteArrayOf(100, 128.toByte(), 0, 1))))
        assertTrue(ComicPublicAddressPolicy.isPublic(InetAddress.getByAddress(ipv6(0x26, 0x06))))
    }

    /**
     * Tạo địa chỉ IPv6 16 byte với hai byte đầu cho trước, phần còn lại là 0 trừ byte cuối.
     *
     * @param first Byte đầu.
     * @param second Byte thứ hai.
     * @return Mảng byte địa chỉ IPv6.
     */
    private fun ipv6(first: Int, second: Int): ByteArray =
        ByteArray(16).also { it[0] = first.toByte(); it[1] = second.toByte(); it[15] = 1 }
}
