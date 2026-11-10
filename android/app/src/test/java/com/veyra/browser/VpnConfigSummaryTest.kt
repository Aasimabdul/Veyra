package com.veyra.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnConfigSummaryTest {
    private val conf = """
        [Interface]
        PrivateKey = SECRETSECRETSECRETSECRETSECRETSECRETSECRET1=
        Address = 10.2.0.2/32, fd00::2/128
        DNS = 10.2.0.1   # provider DNS

        [Peer]
        PublicKey = PUBPUBPUB=
        PresharedKey = PSKPSKPSK=
        AllowedIPs = 0.0.0.0/0, ::/0
        Endpoint = vpn.example.com:51820
    """.trimIndent()

    @Test fun extractsDisplayFields() {
        val s = VpnConfigSummary.of(conf)
        assertEquals(listOf("vpn.example.com:51820"), s.endpoints)
        assertEquals(listOf("10.2.0.2/32", "fd00::2/128"), s.addresses)
        assertEquals(listOf("10.2.0.1"), s.dns)
        assertEquals(1, s.peers)
        assertTrue(s.hasInterface)
        assertTrue(s.hasPrivateKey)
        assertTrue(s.routesAllTraffic)
    }

    @Test fun neverContainsKeys() {
        val text = VpnConfigSummary.of(conf).toString()
        assertFalse(text.contains("SECRET"))
        assertFalse(text.contains("PSK"))
        assertFalse(text.contains("PUB"))
    }

    @Test fun splitRoutesAreNotAllTraffic() {
        val s = VpnConfigSummary.of("[Peer]\nAllowedIPs = 10.0.0.0/8\nEndpoint = a:1")
        assertFalse(s.routesAllTraffic)
        assertFalse(s.routesAllIpv4)
        assertFalse(s.routesAllIpv6)
    }

    @Test fun oneIpFamilyDefaultRouteDoesNotClaimAllTraffic() {
        val ipv4Only = VpnConfigSummary.of("[Peer]\nAllowedIPs = 0.0.0.0/0\nEndpoint = a:1")
        assertTrue(ipv4Only.routesAllIpv4)
        assertFalse(ipv4Only.routesAllIpv6)
        assertFalse(ipv4Only.routesAllTraffic)

        val ipv6Only = VpnConfigSummary.of("[Peer]\nAllowedIPs = ::/0\nEndpoint = a:1")
        assertFalse(ipv6Only.routesAllIpv4)
        assertTrue(ipv6Only.routesAllIpv6)
        assertFalse(ipv6Only.routesAllTraffic)
    }

    @Test fun emptyInputIsSafe() {
        val s = VpnConfigSummary.of("")
        assertEquals(0, s.peers)
        assertFalse(s.hasInterface)
    }
}
