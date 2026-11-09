package com.veyra.browser

/**
 * Display-only summary of a WireGuard `.conf`. It never exposes PrivateKey or PresharedKey, so
 * it is safe to show on screen. The real parsing and validation is done by the WireGuard
 * library when the config is imported and connected.
 */
data class VpnConfigSummary(
    val endpoints: List<String>,
    val addresses: List<String>,
    val dns: List<String>,
    val allowedIps: List<String>,
    val peers: Int,
    val hasInterface: Boolean,
    val hasPrivateKey: Boolean
) {
    /** True when all traffic is routed through the tunnel (0.0.0.0/0 or ::/0). */
    val routesAllTraffic: Boolean
        get() = allowedIps.any { it == "0.0.0.0/0" || it == "::/0" }

    companion object {
        fun of(text: String): VpnConfigSummary {
            val endpoints = ArrayList<String>()
            val addresses = ArrayList<String>()
            val dns = ArrayList<String>()
            val allowed = ArrayList<String>()
            var peers = 0
            var hasInterface = false
            var hasPrivateKey = false
            for (raw in text.lineSequence()) {
                val line = raw.substringBefore('#').trim()
                if (line.isEmpty()) continue
                if (line.startsWith("[")) {
                    when (line.lowercase()) {
                        "[interface]" -> hasInterface = true
                        "[peer]" -> peers++
                    }
                    continue
                }
                val key = line.substringBefore('=').trim().lowercase()
                val value = line.substringAfter('=', "").trim()
                fun items() = value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                when (key) {
                    "privatekey" -> hasPrivateKey = value.isNotEmpty()
                    "endpoint" -> if (value.isNotEmpty()) endpoints.add(value)
                    "address" -> addresses.addAll(items())
                    "dns" -> dns.addAll(items())
                    "allowedips" -> allowed.addAll(items())
                }
            }
            return VpnConfigSummary(endpoints, addresses, dns, allowed, peers, hasInterface, hasPrivateKey)
        }
    }
}
