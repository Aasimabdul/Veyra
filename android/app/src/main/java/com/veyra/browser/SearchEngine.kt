package com.veyra.browser

import java.net.URLEncoder

/**
 * Privacy-respecting search engine registry.
 *
 * DuckDuckGo is the default: no user tracking, no search history sold to advertisers,
 * works without an account. Google is intentionally excluded as the default because it
 * fingerprints queries to build user profiles. Users who prefer Google may still navigate
 * directly to google.com.
 *
 * All search URLs use HTTPS. Query parameters are URL-encoded to prevent injection.
 */
object SearchEngine {

    data class Engine(
        val key: String,
        val name: String,
        val host: String,
        val searchUrl: String,   // Must contain "%s" which is replaced with the encoded query
        val privacyNote: String
    ) {
        fun buildUrl(query: String): String =
            searchUrl.replace("%s", URLEncoder.encode(query, "UTF-8"))
    }

    /** Ordered list – first entry is the default. */
    val all: List<Engine> = listOf(
        Engine(
            key = "duckduckgo",
            name = "DuckDuckGo",
            host = "duckduckgo.com",
            searchUrl = "https://duckduckgo.com/?q=%s",
            privacyNote = "No tracking. No user profiles. No search history sold."
        ),
        Engine(
            key = "brave",
            name = "Brave Search",
            host = "search.brave.com",
            searchUrl = "https://search.brave.com/search?q=%s",
            privacyNote = "Independent index. No Google or Bing tracking."
        ),
        Engine(
            key = "startpage",
            name = "Startpage",
            host = "www.startpage.com",
            searchUrl = "https://www.startpage.com/search?q=%s",
            privacyNote = "Google results, without Google tracking."
        ),
        Engine(
            key = "ecosia",
            name = "Ecosia",
            host = "www.ecosia.org",
            searchUrl = "https://www.ecosia.org/search?q=%s",
            privacyNote = "Plants trees with ad revenue. Privacy-friendly."
        ),
        Engine(
            key = "wikipedia",
            name = "Wikipedia",
            host = "en.wikipedia.org",
            searchUrl = "https://en.wikipedia.org/w/index.php?search=%s",
            privacyNote = "Non-profit. No ads. No tracking."
        )
    )

    val default: Engine get() = all.first()

    fun byKey(key: String): Engine = all.firstOrNull { it.key == key } ?: default
}
