package com.veyra.browser

import java.net.URI
import java.net.URLEncoder

/**
 * Resolves address-bar input without allowing unsupported URI schemes to be loaded directly.
 * Only valid HTTP(S) URLs are returned as navigation targets; other input becomes a search.
 */
internal object AddressResolver {
    private val hostInput = Regex("^[A-Za-z0-9.-]+(?::\\d+)?(?:[/?#].*)?$")

    fun resolve(raw: String): String? {
        val input = raw.trim()
        if (input.isEmpty()) return null

        if (input.startsWith("http://", ignoreCase = true) ||
            input.startsWith("https://", ignoreCase = true)
        ) {
            val uri = runCatching { URI(input) }.getOrNull()
            if (uri != null && isValidWebUri(uri)) {
                return uri.normalize().toASCIIString()
            }
            return search(input)
        }

        if (hostInput.matches(input)) {
            val uri = runCatching { URI("https://$input") }.getOrNull()
            val host = uri?.host
            if (uri != null && isValidWebUri(uri) &&
                !host.isNullOrBlank() &&
                (host.contains('.') || host.equals("localhost", ignoreCase = true))
            ) {
                return uri.normalize().toASCIIString()
            }
        }

        // Treat file:, javascript:, data:, and other unsupported schemes as search text.
        return search(input)
    }

    private fun isValidWebUri(uri: URI): Boolean {
        val scheme = uri.scheme
        if (!scheme.equals("http", ignoreCase = true) &&
            !scheme.equals("https", ignoreCase = true)
        ) {
            return false
        }

        // Avoid silently navigating to URLs that embed credentials or use invalid TCP ports.
        if (uri.host.isNullOrBlank() || uri.rawUserInfo != null) return false
        val port = uri.port
        return port == -1 || port in 1..65535
    }

    private fun search(query: String): String =
        "https://www.google.com/search?q=" + URLEncoder.encode(query, "UTF-8")
}
