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
            if (uri != null &&
                (uri.scheme.equals("http", ignoreCase = true) ||
                    uri.scheme.equals("https", ignoreCase = true)) &&
                !uri.host.isNullOrBlank()
            ) {
                return uri.normalize().toASCIIString()
            }
            return search(input)
        }

        if (hostInput.matches(input) &&
            (input.contains('.') || input.equals("localhost", ignoreCase = true))
        ) {
            val uri = runCatching { URI("https://$input") }.getOrNull()
            if (uri != null && !uri.host.isNullOrBlank()) {
                return uri.normalize().toASCIIString()
            }
        }

        // Treat file:, javascript:, data:, and other unsupported schemes as search text.
        return search(input)
    }

    private fun search(query: String): String =
        "https://www.google.com/search?q=" + URLEncoder.encode(query, "UTF-8")
}
