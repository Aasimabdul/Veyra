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
    }

    @Test fun emptyInputIsSafe() {
        val s = VpnConfigSummary.of("")
        assertEquals(0, s.peers)
        assertFalse(s.hasInterface)
    }
}
