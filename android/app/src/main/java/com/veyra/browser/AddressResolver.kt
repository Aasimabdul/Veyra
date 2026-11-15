package com.veyra.browser

import java.net.URI
import java.net.URLEncoder

/**
 * Resolves address-bar input without allowing unsupported URI schemes to be loaded directly.
 * Only valid HTTP(S) URLs are returned as navigation targets; all other input becomes a
 * search query sent to the user's chosen [SearchEngine] (default: DuckDuckGo).
 *
 * Google is intentionally not the default: Google tracks search queries across sessions
 * and builds advertising profiles. DuckDuckGo and the other privacy engines in
 * [SearchEngine.all] do not.
 */
internal object AddressResolver {
    private val hostInput = Regex("^[A-Za-z0-9.-]+(?::\\d+)?(?:[/?#].*)?$")

    /**
     * Resolves [raw] to a fully-qualified HTTPS URL.
     *
     * @param raw       The raw address-bar text typed by the user.
     * @param engine    The [SearchEngine.Engine] to use for plain-text queries.
     *                  Defaults to [SearchEngine.default] (DuckDuckGo).
     */
    fun resolve(raw: String, engine: SearchEngine.Engine = SearchEngine.default): String? {
        val input = raw.trim()
        if (input.isEmpty()) return null

        if (input.startsWith("http://", ignoreCase = true) ||
            input.startsWith("https://", ignoreCase = true)
        ) {
            val uri = runCatching { URI(input) }.getOrNull()
            if (uri != null && isValidWebUri(uri)) {
                return uri.normalize().toASCIIString()
            }
            return engine.buildUrl(input)
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
        return engine.buildUrl(input)
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
}
