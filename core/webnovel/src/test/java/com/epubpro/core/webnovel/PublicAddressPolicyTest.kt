package com.epubpro.core.webnovel

import java.net.InetAddress
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Kiểm thử chặn địa chỉ nội bộ cho trình tải truyện. */
class PublicAddressPolicyTest {

    /** Loopback, mạng nội bộ, link-local, CGNAT và IPv6 ULA/mapped bị chặn. */
    @Test
    fun blocksPrivateAddresses() {
        listOf(
            "127.0.0.1", "0.0.0.0", "10.1.2.3", "172.16.0.1", "192.168.1.1", "169.254.169.254",
            "100.64.0.1", "::1", "fe80::1", "fd00::1", "::ffff:192.168.1.1", "224.0.0.1"
        ).forEach { ip ->
            assertFalse(ip, PublicAddressPolicy.isPublic(InetAddress.getByName(ip)))
        }
    }

    /** Địa chỉ Internet công khai được phép. */
    @Test
    fun allowsPublicAddresses() {
        listOf("1.1.1.1", "8.8.8.8", "100.128.0.1", "2606:4700:4700::1111").forEach { ip ->
            assertTrue(ip, PublicAddressPolicy.isPublic(InetAddress.getByName(ip)))
        }
    }
}
