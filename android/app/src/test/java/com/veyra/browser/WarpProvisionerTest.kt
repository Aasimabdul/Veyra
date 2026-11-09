package com.veyra.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class WarpProvisionerTest {
    private val reply = """
        {"id":"x","token":"secret-account-token","config":{
          "peers":[{"public_key":"PEERKEY=","endpoint":{"host":"engage.cloudflareclient.com:2408","v4":"162.159.192.1:0"}}],
          "interface":{"addresses":{"v4":"172.16.0.2","v6":"2606:4700:110:8a36::2"}}}}
    """.trimIndent()

    @Test fun buildsAFullTunnelConfig() {
        val conf = WarpProvisioner.buildConfig("PRIVKEY=", reply)
        val s = VpnConfigSummary.of(conf)
        assertEquals(listOf("engage.cloudflareclient.com:2408"), s.endpoints)
        assertEquals(listOf("172.16.0.2/32", "2606:4700:110:8a36::2/128"), s.addresses)
        assertEquals(listOf("1.1.1.1", "1.0.0.1"), s.dns)
        assertTrue(s.routesAllTraffic)
        assertTrue(s.hasPrivateKey)
        assertTrue(conf.contains("PrivateKey = PRIVKEY="))
    }

    @Test fun neverCopiesTheAccountToken() {
        assertFalse(WarpProvisioner.buildConfig("PRIVKEY=", reply).contains("secret-account-token"))
    }

    @Test fun addsDefaultPortWhenMissing() {
        val noPort = reply.replace("engage.cloudflareclient.com:2408", "engage.cloudflareclient.com")
        assertTrue(WarpProvisioner.buildConfig("K=", noPort).contains("Endpoint = engage.cloudflareclient.com:2408"))
    }

    @Test fun rejectsIncompleteReplies() {
        for (bad in listOf("not json", "{}", """{"config":{"peers":[]}}""")) {
            try {
                WarpProvisioner.buildConfig("K=", bad)
                fail("expected IOException for $bad")
            } catch (_: IOException) { }
        }
    }
}
