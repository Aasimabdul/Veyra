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
}
