package com.veyra.browser

import org.mozilla.geckoview.GeckoSession

/** One browser tab: its own GeckoSession plus the UI state Veyra tracks for it. */
class Tab(val id: Int, val isPrivate: Boolean, val session: GeckoSession) {
    var url: String = ""
    var title: String = ""
    var canGoBack: Boolean = false
    var canGoForward: Boolean = false
    var desktop: Boolean = false
    var blocked: Int = 0
    /** Media files the page scanner found on the current page (reset on navigation). */
    var media: List<DetectedMedia> = emptyList()
    var unsupportedMedia: Int = 0
}
