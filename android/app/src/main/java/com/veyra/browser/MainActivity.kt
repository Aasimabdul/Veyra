package com.veyra.browser

import android.app.Dialog
import android.app.KeyguardManager
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONArray
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.StorageController
import org.mozilla.geckoview.WebExtension
import java.text.DateFormat
import java.util.Date

/**
 * Veyra Android browser shell. Uses GeckoView sessions for normal/private tabs, device-local
 * history/bookmarks, Gecko content blocking, the download center, WireGuard config import,
 * encrypted Private Space, theme-specific generated wallpapers and Media3 playback. Features
 * requiring a hosted provider/backend are kept explicitly separate from local functionality.
 */
class MainActivity : AppCompatActivity() {
    private data class HistoryEntry(val url: String, var title: String, val time: Long)

    private lateinit var runtime: GeckoRuntime
    private val tabs = ArrayList<Tab>()
    private var cur: Tab? = null
    private var nextTabId = 1
    private var tabsShowPrivate = false
    private val maxTabs = 8
    private var themeKey = Themes.SYSTEM
    private var blockedTotal = 0
    private var strictProtection = false
    private var cookieMode = "trackers"
    private var javascriptEnabled = true
    private var blockPopups = true
    private lateinit var geckoView: GeckoView
    private lateinit var root: LinearLayout
    private lateinit var contentArea: LinearLayout
    private var bottomBar: View? = null
    private var browserActionRow: View? = null
    private var dockHidden = false
    private var keyboardVisible = false
    private var lastTouchY = 0f
    private var systemInsetLeft = 0
    private var systemInsetTop = 0
    private var systemInsetRight = 0
    private var systemInsetBottom = 0
    private var imeInsetBottom = 0
    private var urlHost: TextView? = null
    private var backButton: View? = null
    private var forwardButton: View? = null
    private var progressFill: View? = null
    private var currentPage = "home"
    private val currentUrl: String get() = cur?.url ?: ""
    private val currentTitle: String get() = cur?.title ?: ""
    private val canGoBack: Boolean get() = cur?.canGoBack == true
    private val canGoForward: Boolean get() = cur?.canGoForward == true
    private var trackingProtectionEnabled = true
    private val history = ArrayList<HistoryEntry>()
    private val bookmarks = linkedSetOf<String>()

    private var appLockEnabled = false
    private var locked = false
    private var authInProgress = false
    private val unlockLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        authInProgress = false
        if (result.resultCode == RESULT_OK) {
            locked = false
            show(if (currentPage == "lock") "home" else currentPage)
        } else if (locked) {
            show("lock")
        }
    }

    private val privateTabAuth = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            if (newTab(true) != null) showSearch("")
        } else {
            Toast.makeText(this, "Private tab cancelled", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestPrivateTab() {
        val keyguard = getSystemService(KeyguardManager::class.java)
        val credentialIntent = keyguard?.createConfirmDeviceCredentialIntent(
            "Unlock private browsing", "Verify your screen lock to open a private tab"
        )
        if (credentialIntent == null) {
            Toast.makeText(this, "Set a PIN, pattern or password in Android Settings to protect private tabs.", Toast.LENGTH_LONG).show()
            try { startActivity(Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS)) } catch (_: Exception) { }
            return
        }
        privateTabAuth.launch(credentialIntent)
    }

    private lateinit var downloads: DownloadCenter
    private var vaultUnlocked = false
    private var pendingExportId: String? = null
    private val vaultUnlock = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) vaultUnlocked = true
        authInProgress = false
        if (currentPage == "private") show("private")
    }
    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importIntoVault(uri)
    }
    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val id = pendingExportId
        pendingExportId = null
        if (uri != null && id != null) exportFromVault(id, uri)
    }
    private val mediaPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) playMedia(uri, null)
    }
    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) VpnManager.get(this).connect()
        else Toast.makeText(this, "VPN permission was not granted", Toast.LENGTH_LONG).show()
    }
    private val vpnImport = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importVpnConfig(uri)
    }
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Notifications are optional; downloads work without them. */ }
    private val refreshHandler = android.os.Handler(android.os.Looper.getMainLooper())
    // The browser dock is intentionally stable and always visible while browsing.
    // It only yields to the keyboard so it never covers the search field.
    private fun revealBrowserChrome() {
        if (currentPage != "browse" || keyboardVisible) return
        bottomBar?.let { bar ->
            bar.visibility = View.VISIBLE
            bar.alpha = 1f
            bar.translationY = 0f
        }
    }

    private val prefs get() = getSharedPreferences("veyra_preferences", MODE_PRIVATE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        themeKey = prefs.getString("theme", Themes.SYSTEM) ?: Themes.SYSTEM
        strictProtection = prefs.getBoolean("protection_strict", false)
        cookieMode = prefs.getString("cookie_mode", "trackers") ?: "trackers"
        javascriptEnabled = prefs.getBoolean("javascript_enabled", true)
        blockPopups = prefs.getBoolean("block_popups", true)
        applyTheme()

        val contentBlockingSettings = ContentBlocking.Settings.Builder()
            .antiTracking(antiTrackingFlags())
            .cookieBehavior(cookieBehavior())
            .build()
        // GeckoView permits only one GeckoRuntime per app process. Reuse it when
        // Android recreates MainActivity (for example, after the emulator smoke test backgrounds it).
        runtime = sharedRuntime ?: GeckoRuntime.create(
            applicationContext,
            GeckoRuntimeSettings.Builder()
                .contentBlocking(contentBlockingSettings)
                .javaScriptEnabled(javascriptEnabled)
                .build()
        ).also { sharedRuntime = it }
        trackingProtectionEnabled = prefs.getBoolean("tracking_protection_enabled", true)
        installMediaScanner()
        geckoView = GeckoView(this)
        downloads = DownloadCenter(this) {
            if (currentPage == "downloads") refreshDownloads()
        }

        appLockEnabled = prefs.getBoolean("app_lock_enabled", false) && deviceIsSecure()
        applySecureFlag()

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.BG)
            clipChildren = false
            clipToPadding = false
        }
        contentArea = column()
        root.addView(contentArea, lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            systemInsetLeft = bars.left
            systemInsetTop = bars.top
            systemInsetRight = bars.right
            systemInsetBottom = bars.bottom
            imeInsetBottom = if (insets.isVisible(WindowInsetsCompat.Type.ime())) ime.bottom else 0
            keyboardVisible = imeInsetBottom > systemInsetBottom
            applyGutters()
            bottomBar?.let { bar ->
                bar.animate().cancel()
                bar.visibility = if (keyboardVisible && currentPage == "browse") View.GONE else View.VISIBLE
                bar.alpha = 1f
                bar.translationY = 0f
            }
            insets
        }
        ViewCompat.requestApplyInsets(root)
        applyGutters()
        loadBookmarks()
        loadHistory()

        if (appLockEnabled) {
            locked = true
            show("lock")
            authenticate()
        } else {
            show("home")
            openRequestedScreen()
        }
    }

    /** Debug builds only: lets CI open a screen directly to capture screenshots. */
    private fun openRequestedScreen() {
        val debuggable = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (!debuggable) return
        intent?.getStringExtra("veyra_theme")?.let { themeKey = it; applyTheme(); show("home") }
        intent?.getStringExtra("veyra_download")?.let { downloads.enqueueUrl(it, false); show("downloads"); return }
        if (intent?.getBooleanExtra("veyra_private", false) == true) {
            val tab = newTab(true)
            if (tab != null) intent?.getStringExtra("veyra_url")?.let { navigate(it) }
            return
        }
        intent?.getStringExtra("veyra_url")?.let { navigate(it); return }
        intent?.getStringExtra("veyra_page")?.let { show(it) }
    }

    // ---- lifecycle / lock -------------------------------------------------------------

    override fun onStop() {
        if (appLockEnabled && !authInProgress) locked = true
        super.onStop()
    }

    override fun onStart() {
        super.onStart()
        if (appLockEnabled && locked && !authInProgress && currentPage != "lock") {
            show("lock")
            authenticate()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyGutters()
        if (themeKey == Themes.SYSTEM) {
            applyTheme()
            if (currentPage != "browse") show(currentPage)
        }
    }

    override fun onDestroy() {
        VpnManager.get(this).listener = null
        tabs.forEach { it.session.close() }
        tabs.clear()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            currentPage == "lock" -> super.onBackPressed()
            currentPage == "browse" && canGoBack -> cur?.session?.goBack()
            currentPage == "browse" -> show("home")
            currentPage != "home" && !cur?.url.isNullOrBlank() -> show("browse")
            currentPage != "home" -> show("home")
            else -> super.onBackPressed()
        }
    }

    private fun deviceIsSecure(): Boolean =
        getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    private fun applySecureFlag() {
        val debuggable = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        // Debug builds skip the flag for private tabs only so CI can screenshot them.
        val privateSecure = tabs.any { it.isPrivate } && !debuggable
        if (appLockEnabled || privateSecure || currentPage == "private") window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    /** Uses the system credential screen (fingerprint / face / PIN / pattern set on the device). */
    @Suppress("DEPRECATION")
    private fun authenticate() {
        if (authInProgress) return
        val manager = getSystemService(KeyguardManager::class.java)
        if (manager == null || !manager.isDeviceSecure) {
            // The device credential was removed; do not lock the user out of their browser.
            appLockEnabled = false
            locked = false
            prefs.edit().putBoolean("app_lock_enabled", false).apply()
            applySecureFlag()
            show("home")
            return
        }
        val intent = manager.createConfirmDeviceCredentialIntent("Unlock Veyra", null)
        if (intent == null) {
            Toast.makeText(this, "Device unlock is unavailable", Toast.LENGTH_SHORT).show()
            return
        }
        authInProgress = true
        unlockLauncher.launch(intent)
    }

    // ---- theme & privacy settings -----------------------------------------------------

    private fun systemIsDark() =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private fun applyTheme() {
        val theme = Themes.resolve(themeKey, systemIsDark())
        Palette.apply(theme)
        window.statusBarColor = theme.bg
        window.navigationBarColor = theme.bg
        window.setBackgroundDrawable(ColorDrawable(theme.bg))
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = theme.light
            isAppearanceLightNavigationBars = theme.light
        }
        activeTheme = theme
        if (::root.isInitialized) applyRootBackground()
    }

    private var activeTheme: VeyraTheme = Themes.all.first()

    /** Home shows the theme's wallpaper; every other screen stays a plain theme colour. */
    private fun applyRootBackground() {
        if (currentPage == "home") root.background = WallpaperDrawable(activeTheme)
        else root.setBackgroundColor(activeTheme.bg)
    }

    private fun antiTrackingFlags() =
        if (strictProtection) ContentBlocking.AntiTracking.STRICT else ContentBlocking.AntiTracking.DEFAULT

    private fun cookieBehavior() = when (cookieMode) {
        "third_party" -> ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY
        "all" -> ContentBlocking.CookieBehavior.ACCEPT_NONE
        else -> ContentBlocking.CookieBehavior.ACCEPT_NON_TRACKERS
    }

    // ---- layout helpers ---------------------------------------------------------------

    /** 24dp gutter on phones; content is centred at a readable width on tablets. */
    private fun applyGutters() {
        if (!::root.isInitialized) return
        val width = resources.displayMetrics.widthPixels
        val gutter = maxOf(dp(24), (width - dp(640)) / 2)
        val contentGutter = if (currentPage == "browse") 0 else gutter
        val contentTop = if (currentPage == "browse") 0 else dp(16)
        root.setPadding(
            systemInsetLeft + contentGutter,
            systemInsetTop + contentTop,
            systemInsetRight + contentGutter,
            maxOf(systemInsetBottom, imeInsetBottom)
        )
    }

    private fun show(page: String) {
        when (page) {
            "home" -> showHome()
            "search" -> showSearch("")
            "browse" -> showBrowser()
            "tabs" -> showTabs()
            "bookmarks" -> showBookmarks()
            "history" -> showHistory()
            "downloads" -> showDownloads()
            "settings" -> showSettings()
            "appearance" -> showAppearance()
            "private" -> showPrivateSpace()
            "vpn" -> showVpn()
            "privacy" -> showPrivacy()
            "lock" -> showLock()
            else -> showHome()
        }
    }

    private fun resetContent(page: String) {
        dockHidden = false
        currentPage = page
        if (::root.isInitialized) { applyGutters(); applyRootBackground() }
        if (page != "private") vaultUnlocked = false
        applySecureFlag()
        urlHost = null
        backButton = null
        forwardButton = null
        progressFill = null
        browserActionRow = null
        hideKeyboard()
        contentArea.removeAllViews()
        bottomBar?.let { root.removeView(it) }
        bottomBar = null
    }

    private fun hideKeyboard() {
        currentFocus?.let {
            getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(it.windowToken, 0)
        }
    }

    /**
     * Scrolling screen body. The scroller spans the full screen width and the page re-adds the
     * gutter as padding so raised shadows (which reach ~19dp) are never clipped at the edges.
     */
    private fun scrollPage(): LinearLayout {
        val gutter = root.paddingLeft
        // clipChildren stays true: otherwise scrolled rows paint under the dock and system bars.
        val scroll = ScrollView(this).apply {
            clipToPadding = false
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
        }
        val page = column().apply { setPadding(gutter, dp(8), gutter, dp(28)) }
        scroll.addView(page, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        contentArea.addView(scroll, lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            leftMargin = -gutter
            rightMargin = -gutter
        })
        return page
    }

    private fun screenHeader(title: String, action: View? = null) {
        val header = row().apply { minimumHeight = dp(48) }
        header.addView(label(title, 22f, Palette.TEXT, 1), lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (action != null) header.addView(action)
        contentArea.addView(header, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun emptyState(paths: List<String>, title: String, message: String): View =
        column().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(8), dp(36), dp(8), 0)
            addView(well(icon(paths, Palette.ACCENT, 26), 72, 24))
            addView(label(title, 16f, Palette.TEXT, 1).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(22), 0, 0)
            })
            addView(label(message, 13f, Palette.MUTED).apply {
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(8), dp(12), 0)
            })
        }

    private fun spacer(heightDp: Int) = View(this).apply {
        layoutParams = lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(heightDp))
    }

    private fun gap(view: View, bottomDp: Int = 14): View {
        view.layoutParams = (view.layoutParams as? LinearLayout.LayoutParams
            ?: lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)).apply {
            bottomMargin = dp(bottomDp)
        }
        return view
    }

    private fun matchWrap() =
        lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    // ---- bottom bars ------------------------------------------------------------------

    private fun addMainNav(selected: String) {
        val nav = row().apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), 0, dp(8), 0)
            neu(this, 28, Depth.CARD)
        }
        data class NavItem(val key: String, val paths: List<String>, val description: String)
        listOf(
            NavItem("home", Icons.HOME, "Home"),
            NavItem("tabs", Icons.TABS, "Tabs"),
            NavItem("bookmarks", Icons.BOOKMARK, "Bookmarks"),
            NavItem("downloads", Icons.DOWNLOAD, "Downloads"),
            NavItem("settings", Icons.SETTINGS, "Settings")
        ).forEach { item ->
            val active = item.key == selected
            val slot = FrameLayout(this).apply {
                clipChildren = false
                contentDescription = item.description
                isClickable = true
                setOnClickListener { show(item.key) }
            }
            val well = LinearLayout(this).apply {
                gravity = Gravity.CENTER
                if (active) neu(this, 18, Depth.WELL, inset = true)
            }
            well.addView(icon(item.paths, if (active) Palette.ACCENT else Palette.TEXT, 22))
            slot.addView(well, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER))
            nav.addView(slot, lp(0, dp(72), 1f))
        }
        attachBottomBar(nav)
    }

    /**
     * Safari-style dock: one rounded capsule holding the address pill on top and the five browsing
     * actions below. While the page scrolls down the action row collapses so only the pill remains.
     */
    private fun addBrowserBar() {
        // Safari-inspired floating dock: a restrained accent glow that adapts to every theme.
        val surface = ThemeMath.mix(Palette.BG, Palette.TEXT, 0.055f)
        val accentSurface = ThemeMath.mix(Palette.BG, Palette.ACCENT, if (Palette.LIGHT_THEME) 0.09f else 0.16f)
        val outline = ThemeMath.mix(Palette.BG, Palette.ACCENT, if (Palette.LIGHT_THEME) 0.34f else 0.48f)
        val capsule = column().apply {
            setPadding(dp(10), dp(8), dp(10), dp(6))
            elevation = dpf(8f)
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(accentSurface, surface, ThemeMath.mix(Palette.BG, Palette.ACCENT, 0.10f))
            ).apply {
                cornerRadius = dpf(30f)
                setStroke(dp(1), outline)
            }
        }

        // Address pill: security state, host, tracker shield, reload.
        val pill = row().apply {
            setPadding(dp(14), 0, dp(4), 0)
            contentDescription = "Address. Tap to edit"
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dpf(22f)
                setColor(ThemeMath.mix(Palette.BG, Palette.ACCENT, if (Palette.LIGHT_THEME) 0.035f else 0.075f))
                setStroke(dp(1), ThemeMath.mix(Palette.BG, Palette.ACCENT, 0.18f))
            }
            setOnClickListener {
                if (dockHidden) setDockCollapsed(false) else showSearch(currentUrl)
            }
        }
        val secure = currentUrl.startsWith("https://", ignoreCase = true)
        pill.addView(icon(if (secure) Icons.LOCK else Icons.INFO, if (secure) Palette.ACCENT else Palette.MUTED, 16).apply {
            contentDescription = if (secure) "Secure connection" else "Connection is not encrypted"
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        })
        if (cur?.isPrivate == true) {
            pill.addView(label("PRIVATE", 10f, Palette.ACCENT, 1).apply {
                letterSpacing = 0.1f
                setPadding(dp(10), 0, 0, 0)
            })
        }
        urlHost = label(hostOf(currentUrl), 14f, Palette.TEXT, 1).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(10), 0, dp(6), 0)
        }
        pill.addView(urlHost, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val shield = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            contentDescription =
                if (trackingProtectionEnabled) "Tracker protection on, tap to turn off"
                else "Tracker protection off, tap to turn on"
            isClickable = true
            setOnClickListener { setTrackingProtection(!trackingProtectionEnabled); show("browse"); cur?.session?.reload() }
            addView(icon(
                if (trackingProtectionEnabled) Icons.SHIELD else Icons.SHIELD_OFF,
                if (trackingProtectionEnabled) Palette.ACCENT else Palette.MUTED, 20
            ))
        }
        pill.addView(shield, lp(dp(40), dp(40)))

        // One-tap device VPN badge beside the address/search field.
        val vpnManager = VpnManager.get(this)
        val vpnButton = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            contentDescription = if (vpnManager.status == VpnManager.Status.ON) "VPN connected. Tap to disconnect"
                else "VPN off. Tap to connect or configure"
            isClickable = true
            isFocusable = true
            setPadding(dp(4), 0, dp(4), 0)
            setOnClickListener {
                when (vpnManager.status) {
                    VpnManager.Status.ON -> vpnManager.disconnect()
                    VpnManager.Status.CONNECTING -> Toast.makeText(this@MainActivity, "VPN is connecting…", Toast.LENGTH_SHORT).show()
                    else -> if (vpnManager.hasConfig()) startVpn() else show("vpn")
                }
                postDelayed({
                    if (currentPage == "browse") showBrowser()
                }, 250)
            }
            val connected = vpnManager.status == VpnManager.Status.ON
            val badge = label(if (connected) "VPN ON" else "VPN", 10f,
                if (connected) Palette.BG else Palette.ACCENT, 1).apply {
                gravity = Gravity.CENTER
                setPadding(dp(7), dp(7), dp(7), dp(7))
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dpf(12f)
                    setColor(if (connected) Palette.ACCENT else ThemeMath.mix(Palette.BG, Palette.ACCENT, 0.14f))
                    setStroke(dp(1), ThemeMath.mix(Palette.BG, Palette.ACCENT, 0.52f))
                }
            }
            addView(badge)
        }
        pill.addView(vpnButton, lp(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)))

        val reload = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            contentDescription = "Reload page"
            isClickable = true
            setOnClickListener { cur?.session?.reload() }
            addView(icon(Icons.RELOAD, Palette.MUTED, 18))
        }
        pill.addView(reload, lp(dp(40), dp(40)))
        capsule.addView(pill, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))

        // Thin load-progress line between the pill and the action row.
        val track = FrameLayout(this).apply { visibility = View.INVISIBLE }
        val fill = View(this).apply {
            setBackgroundColor(Palette.ACCENT)
            pivotX = 0f
            scaleX = 0f
        }
        track.addView(fill, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2)))
        progressFill = fill
        capsule.addView(track, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(2)).apply { topMargin = dp(4) })

        // Action row.
        val actions = row().apply { gravity = Gravity.CENTER }
        fun cell(paths: List<String>, description: String, filled: Boolean = false, action: () -> Unit): View =
            LinearLayout(this).apply {
                gravity = Gravity.CENTER
                contentDescription = description
                isClickable = true
                setOnClickListener { action() }
                addView(icon(paths, Palette.TEXT, 22, filled))
            }
        val back = cell(Icons.BACK, "Back") { if (canGoBack) cur?.session?.goBack() else show("home") }
        val forward = cell(Icons.FORWARD, "Forward") { if (canGoForward) cur?.session?.goForward() }
            .apply { alpha = if (canGoForward) 1f else 0.4f }
        backButton = back
        forwardButton = forward
        val share = cell(Icons.SHARE, "Share page") {
            if (currentUrl.isNotBlank()) {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, currentUrl)
                }, "Share page"))
            }
        }
        val tabsButton = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            contentDescription = "Tabs, ${tabs.size} open"
            isClickable = true
            setOnClickListener { show("tabs") }
            addView(label(tabs.size.toString(), 12f, Palette.ACCENT, 1).apply {
                gravity = Gravity.CENTER
                neu(this, 8, Depth.KNOB, inset = true)
                layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
            })
        }
        val menu = cell(Icons.MENU, "Menu", filled = true) { showBrowserMenu() }
        listOf(back, forward, share, tabsButton, menu).forEach { actions.addView(it, lp(0, dp(44), 1f)) }
        capsule.addView(actions, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(2) })
        browserActionRow = actions

        attachBottomBar(capsule, matchWidth = true)
    }

    private fun setDockCollapsed(collapsed: Boolean) {
        if (dockHidden == collapsed) return
        dockHidden = collapsed
        browserActionRow?.visibility = if (collapsed) View.GONE else View.VISIBLE
    }

    private var lastScrollY = 0

    /** Collapses the dock while reading down a page and restores it on scroll up or at the top. */
    private fun onPageScrolled(scrollY: Int) {
        val delta = scrollY - lastScrollY
        lastScrollY = scrollY
        if (currentPage != "browse" || keyboardVisible) return
        val threshold = dp(14)
        if (scrollY <= 0 || delta < -threshold) setDockCollapsed(false)
        else if (delta > threshold && scrollY > dp(60)) setDockCollapsed(true)
    }

    private fun attachBottomBar(bar: View, matchWidth: Boolean = false) {
        bottomBar = bar
        dockHidden = false
        bar.alpha = 1f
        bar.translationY = 0f
        root.addView(bar, lp(
            if (matchWidth) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT,
            if (matchWidth) ViewGroup.LayoutParams.WRAP_CONTENT else dp(58)
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(if (matchWidth) 6 else 8)
            bottomMargin = dp(if (matchWidth) 8 else 10)
            leftMargin = dp(if (matchWidth) 12 else 20)
            rightMargin = dp(if (matchWidth) 12 else 20)
        })
    }

    // ---- screens ----------------------------------------------------------------------

    private fun showLock() {
        resetContent("lock")
        contentArea.gravity = Gravity.CENTER_HORIZONTAL
        contentArea.addView(label("Veyra", 22f, Palette.TEXT, 1).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, 0)
        }, matchWrap())
        val center = column().apply { gravity = Gravity.CENTER }
        val tile = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            contentDescription = "Unlock Veyra"
            setOnClickListener { authenticate() }
            neu(this, 56, Depth.CARD, pressable = true)
        }
        tile.addView(icon(Icons.FINGERPRINT, Palette.ACCENT, 56))
        center.addView(tile, lp(dp(132), dp(132)))
        center.addView(label("Touch to unlock", 18f, Palette.TEXT, 1).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(36), 0, 0)
        }, matchWrap())
        center.addView(label("Your tabs are protected. Uses your device fingerprint, PIN or pattern.", 13f, Palette.MUTED).apply {
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(8), dp(24), 0)
        }, matchWrap())
        contentArea.addView(center, lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun showHome() {
        resetContent("home")
        contentArea.gravity = Gravity.NO_GRAVITY
        val page = scrollPage()

        val header = row().apply { minimumHeight = dp(48) }
        header.addView(label("Veyra", 22f, Palette.TEXT, 1), lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(iconButton(Icons.LOCK, if (appLockEnabled) "Lock Veyra now" else "Set up app lock") {
            if (appLockEnabled) {
                locked = true
                show("lock")
                authenticate()
            } else {
                show("settings")
            }
        })
        page.addView(header, matchWrap())

        val search = row().apply {
            setPadding(dp(20), 0, dp(20), 0)
            contentDescription = "Search or enter address"
            setOnClickListener { showSearch("") }
            neu(this, 30, Depth.FIELD, inset = true)
        }
        search.addView(icon(Icons.SEARCH, Palette.ACCENT, 20))
        search.addView(label("Search or enter address", 15f, Palette.MUTED).apply {
            setPadding(dp(14), 0, 0, 0)
        })
        page.addView(search, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply {
            topMargin = dp(24); bottomMargin = dp(28)
        })

        page.addView(label("Quick access".uppercase(), 11f, Palette.MUTED, 1).apply {
            letterSpacing = 0.12f
            setPadding(dp(4), 0, 0, dp(14))
        }, matchWrap())
        val shortcuts = mutableListOf(
            Triple("Y", "YouTube", "https://m.youtube.com"),
            Triple("G", "Google", "https://google.com"),
            Triple("G", "GitHub", "https://github.com"),
            Triple("R", "Reddit", "https://reddit.com"),
            Triple("X", "X", "https://x.com")
        )
        shortcuts.addAll(loadQuickShortcuts().map { Triple(it[1].take(1), it[1], it[2]) })
        shortcuts.add(Triple("+", "Add", ""))
        shortcuts.chunked(4).forEach { group ->
            val line = row().apply { gravity = Gravity.TOP }
            repeat(4) { index ->
                val item = group.getOrNull(index)
                if (item == null) {
                    line.addView(View(this), lp(0, 1, 1f))
                    return@repeat
                }
                val tile = column().apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    contentDescription = if (item.third.isBlank()) "Add quick access" else item.second
                    setOnClickListener {
                        if (item.third.isNotBlank()) navigate(item.third) else showAddShortcutSheet()
                    }
                    isClickable = true
                }
                val face = LinearLayout(this).apply {
                    gravity = Gravity.CENTER
                    neu(this, 20, Depth.BUTTON, pressable = true)
                    isClickable = false
                    isDuplicateParentStateEnabled = true
                    if (item.third.isBlank()) addView(icon(Icons.PLUS, Palette.ACCENT, 22))
                    else addView(label(item.first.uppercase(), 18f, Palette.ACCENT, 1).apply { gravity = Gravity.CENTER })
                }
                tile.addView(face, lp(dp(60), dp(60)))
                tile.addView(label(item.second, 12f, Palette.MUTED).apply {
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(0, dp(10), 0, 0)
                }, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                line.addView(tile, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            page.addView(line, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(22)
            })
        }

        val hasPage = currentUrl.isNotBlank()
        val chevron = icon(Icons.CHEVRON, Palette.MUTED, 18)
        page.addView(
            listRow(
                well(icon(Icons.TABS, Palette.ACCENT, 22)),
                "Continue browsing",
                if (hasPage) (currentTitle.ifBlank { hostOf(currentUrl) }) else "No page open yet",
                chevron
            ) { if (hasPage) show("browse") else showSearch("") },
            lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) }
        )
        addMainNav("home")
    }

    private fun showSearch(prefill: String) {
        resetContent("search")
        val page = scrollPage()
        val header = row().apply { minimumHeight = dp(48) }
        header.addView(iconButton(Icons.BACK, "Back") { show(if (currentUrl.isBlank()) "home" else "browse") })
        header.addView(label("Search", 22f, Palette.TEXT, 1).apply { setPadding(dp(16), 0, 0, 0) })
        page.addView(header, matchWrap())

        val input = EditText(this).apply {
            hint = "Search or enter address"
            setHintTextColor(Palette.MUTED)
            setTextColor(Palette.TEXT)
            textSize = 15f
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_GO
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setPadding(dp(22), 0, dp(22), 0)
            setText(prefill)
            neu(this, 28, Depth.FIELD, inset = true)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_NULL) {
                    navigate(text.toString()); true
                } else false
            }
        }
        page.addView(input, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply {
            topMargin = dp(20); bottomMargin = dp(12)
        })

        // Suggestions are derived only from on-device history and bookmarks.
        val suggestions = column()
        page.addView(suggestions, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(18)
        })
        fun refreshSuggestions(raw: String) {
            suggestions.removeAllViews()
            val query = raw.trim().lowercase()
            val candidates = (history.asReversed().map { Triple(it.title.ifBlank { hostOf(it.url) }, hostOf(it.url), it.url) } +
                bookmarks.map { Triple(hostOf(it), hostOf(it), it) })
                .distinctBy { it.third }
                .filter { query.isBlank() || it.first.lowercase().contains(query) || it.second.lowercase().contains(query) || it.third.lowercase().contains(query) }
                .take(5)
            if (candidates.isEmpty()) return
            suggestions.addView(label(if (query.isBlank()) "RECENT PAGES" else "SUGGESTIONS", 10f, Palette.MUTED, 1).apply {
                letterSpacing = 0.12f
                setPadding(dp(4), dp(4), 0, dp(8))
            }, matchWrap())
            candidates.forEach { item ->
                suggestions.addView(
                    listRow(well(icon(Icons.CLOCK, Palette.ACCENT, 18)), item.first, item.second) {
                        navigate(item.third)
                    },
                    lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
                )
            }
        }
        refreshSuggestions(prefill)
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { refreshSuggestions(s?.toString().orEmpty()) }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        page.addView(label("QUICK SEARCH", 11f, Palette.MUTED, 1).apply {
            letterSpacing = 0.12f
            setPadding(dp(4), 0, 0, dp(14))
        }, matchWrap())
        listOf(
            Triple("Google", "google.com", "https://google.com"),
            Triple("DuckDuckGo", "duckduckgo.com", "https://duckduckgo.com"),
            Triple("Wikipedia", "wikipedia.org", "https://wikipedia.org")
        ).forEach { (name, host, url) ->
            page.addView(
                listRow(well(icon(Icons.SEARCH, Palette.ACCENT, 20)), name, host) { navigate(url) },
                lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(14)
                }
            )
        }
        addMainNav("home")
        input.requestFocus()
        if (prefill.isNotEmpty()) input.selectAll()
        getSystemService(InputMethodManager::class.java)?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun showBrowser() {
        resetContent("browse")
        contentArea.gravity = Gravity.NO_GRAVITY
        lastScrollY = 0
        (geckoView.parent as? ViewGroup)?.removeView(geckoView)
        cur?.let { tab ->
            if (geckoView.session !== tab.session) {
                if (geckoView.session != null) geckoView.releaseSession()
                geckoView.setSession(tab.session)
            }
        }
        // The page fills the whole content area; the address pill lives in the bottom dock.
        contentArea.addView(geckoView, lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        addBrowserBar()
        revealBrowserChrome()
    }

    private fun navigate(raw: String) {
        val trimmed = raw.trim()
        // Hand torrent magnets/files to an installed torrent client (for example Flud).
        // Veyra does not implement a torrent engine or pretend a torrent is a direct HTTP file.
        if (trimmed.startsWith("magnet:", true) ||
            (trimmed.startsWith("http", true) && trimmed.substringBefore('#').substringBefore('?').endsWith(".torrent", true))) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(trimmed)))
            } catch (_: Exception) {
                Toast.makeText(this, "Install a torrent client such as Flud to open this link", Toast.LENGTH_LONG).show()
            }
            return
        }
        val uri = AddressResolver.resolve(trimmed) ?: run {
            Toast.makeText(this, "Enter a valid address or search term", Toast.LENGTH_SHORT).show()
            return
        }
        val tab = cur ?: newTab(false) ?: return
        try {
            tab.url = uri
            tab.title = ""
            tab.session.loadUri(uri)
            show("browse")
        } catch (_: Exception) {
            Toast.makeText(this, "Could not open that address", Toast.LENGTH_SHORT).show()
        }
    }

    // ---- tabs -------------------------------------------------------------------------

    /** Creates a tab backed by its own GeckoSession; private tabs use Gecko's private mode. */
    private fun newTab(private: Boolean): Tab? {
        if (tabs.size >= maxTabs) {
            Toast.makeText(this, "Tab limit reached ($maxTabs). Close a tab first.", Toast.LENGTH_SHORT).show()
            return null
        }
        val settings = org.mozilla.geckoview.GeckoSessionSettings.Builder()
            .usePrivateMode(private)
            .useTrackingProtection(trackingProtectionEnabled)
            .build()
        val session = GeckoSession(settings)
        val tab = Tab(nextTabId++, private, session)
        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(
                session: GeckoSession,
                url: String?,
                perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean
            ) {
                if (url.isNullOrBlank() || url == "about:blank") return
                tab.url = url
                tab.title = ""
                tab.blocked = 0
                tab.media = emptyList()
                tab.unsupportedMedia = 0
                if (!tab.isPrivate && history.lastOrNull()?.url != url) {
                    history.add(HistoryEntry(url, "", System.currentTimeMillis()))
                    persistHistory()
                }
                if (tab === cur) {
                    urlHost?.text = hostOf(url)
                    progressFill?.let { fill ->
                        fill.animate().cancel()
                        fill.scaleX = 0f
                        fill.visibility = View.VISIBLE
                        (fill.parent as? View)?.visibility = View.VISIBLE
                    }
                }
            }

            override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
                if (blockPopups) {
                    runOnUiThread {
                        if (tab === cur && currentPage == "browse") {
                            Toast.makeText(this@MainActivity, "Blocked a pop-up window", Toast.LENGTH_SHORT).show()
                        }
                    }
                    return null
                }
                val popupTab = newTab(tab.isPrivate) ?: return null
                runOnUiThread {
                    cur = popupTab
                    if (uri.isNotBlank()) popupTab.session.loadUri(uri)
                    show("browse")
                }
                return GeckoResult.fromValue(popupTab.session)
            }

            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) { tab.canGoBack = canGoBack }

            override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
                tab.canGoForward = canGoForward
                if (tab === cur) forwardButton?.alpha = if (canGoForward) 1f else 0.4f
            }
        }
        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onExternalResponse(session: GeckoSession, response: org.mozilla.geckoview.WebResponse) {
                startDownload(response, tab.isPrivate)
            }

            override fun onTitleChange(session: GeckoSession, title: String?) {
                if (title.isNullOrBlank()) return
                tab.title = title
                if (!tab.isPrivate) {
                    history.lastOrNull { it.url == tab.url }?.title = title
                    persistHistory()
                }
            }
        }
        session.contentBlockingDelegate = object : ContentBlocking.Delegate {
            override fun onContentBlocked(session: GeckoSession, event: ContentBlocking.BlockEvent) {
                if (event.isBlocking) {
                    tab.blocked++
                    blockedTotal++
                }
            }
        }
        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onProgressChange(session: GeckoSession, progress: Int) {
                if (tab !== cur) return
                progressFill?.let { bar ->
                    bar.pivotX = 0f
                    (bar.parent as? View)?.visibility = View.VISIBLE
                    bar.visibility = View.VISIBLE
                    bar.animate().cancel()
                    bar.animate()
                        .scaleX(progress.coerceIn(0, 100) / 100f)
                        .setDuration(120L)
                        .withEndAction {
                            if (progress >= 100) bar.visibility = View.INVISIBLE
                        }
                        .start()
                }
            }
        }
        session.scrollDelegate = object : GeckoSession.ScrollDelegate {
            override fun onScrollChanged(session: GeckoSession, scrollX: Int, scrollY: Int) {
                if (tab === cur) onPageScrolled(scrollY)
            }
        }
        session.open(runtime)
        attachMediaScanner(tab)
        tabs.add(tab)
        cur = tab
        applySecureFlag()
        return tab
    }

    private fun closeTab(tab: Tab) {
        tab.session.close()
        tabs.remove(tab)
        if (cur === tab) {
            cur = tabs.lastOrNull { it.isPrivate == tab.isPrivate } ?: tabs.lastOrNull()
            if (geckoView.session != null) geckoView.releaseSession()
        }
        applySecureFlag()
    }

    private fun showTabs() {
        resetContent("tabs")
        val page = scrollPage()
        val shown = tabs.filter { it.isPrivate == tabsShowPrivate }
        val header = row().apply { minimumHeight = dp(48) }
        header.addView(label("Tabs", 22f, Palette.TEXT, 1), lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(label("${tabs.size} open", 13f, Palette.MUTED))
        page.addView(header, matchWrap())

        // Normal / Private segmented control: inset track, raised active segment.
        val segment = row().apply {
            setPadding(dp(6), dp(6), dp(6), dp(6))
            neu(this, 24, Depth.FIELD, inset = true)
        }
        listOf(false to "Normal", true to "Private").forEach { (isPrivate, name) ->
            val active = tabsShowPrivate == isPrivate
            val cell = label(name, 14f, if (active) Palette.ACCENT else Palette.MUTED, 1).apply {
                gravity = Gravity.CENTER
                contentDescription = "$name tabs"
                setOnClickListener { tabsShowPrivate = isPrivate; showTabs() }
                if (active) neu(this, 18, Depth.WELL) else isClickable = true
            }
            segment.addView(cell, lp(0, dp(40), 1f))
        }
        page.addView(segment, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply {
            topMargin = dp(18); bottomMargin = dp(22)
        })

        if (shown.isEmpty()) {
            page.addView(emptyState(
                Icons.TABS,
                if (tabsShowPrivate) "No private tabs" else "No open tabs",
                if (tabsShowPrivate) "Private tabs keep no history, cookies or site data after they are closed."
                else "Tap + to open a new tab."
            ))
        } else shown.forEach { tab ->
            val title = tab.title.ifBlank { if (tab.url.isBlank()) "New tab" else hostOf(tab.url) }
            val close = iconButton(Icons.CLOSE, "Close ${title} tab", Palette.MUTED, 40, 14) {
                closeTab(tab)
                showTabs()
            }
            val leading = if (tab === cur) well(icon(Icons.TABS, Palette.ACCENT, 20)) else letterWell(title)
            page.addView(
                listRow(leading, title, hostOf(tab.url), close) {
                    cur = tab
                    if (tab.url.isBlank()) showSearch("") else show("browse")
                },
                lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(16) }
            )
        }
        val add = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            contentDescription = if (tabsShowPrivate) "New private tab" else "New tab"
            setOnClickListener {
                if (tabsShowPrivate) requestPrivateTab()
                else if (newTab(false) != null) showSearch("")
            }
            neu(this, 28, Depth.BUTTON, pressable = true)
            addView(icon(Icons.PLUS, Palette.ACCENT, 24))
        }
        page.addView(add, lp(dp(56), dp(56)).apply { gravity = Gravity.END; topMargin = dp(6) })
        addMainNav("tabs")
    }

    private fun showBookmarks() {
        resetContent("bookmarks")
        val page = scrollPage()
        val add = iconButton(Icons.PLUS, "Bookmark current page", Palette.ACCENT) {
            if (currentUrl.isNotBlank()) {
                bookmarks.add(currentUrl)
                persistBookmarks()
                Toast.makeText(this, "Bookmark saved on this device", Toast.LENGTH_SHORT).show()
                showBookmarks()
            } else Toast.makeText(this, "Open a page first", Toast.LENGTH_SHORT).show()
        }
        val header = row().apply { minimumHeight = dp(48) }
        header.addView(label("Bookmarks", 22f, Palette.TEXT, 1), lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(add)
        page.addView(header, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(22)
        })
        if (bookmarks.isEmpty()) {
            page.addView(emptyState(Icons.BOOKMARK, "No bookmarks yet", "Open a page, then tap + to save it on this device."))
        } else bookmarks.toList().forEach { url ->
            val remove = iconButton(Icons.TRASH, "Remove bookmark", Palette.MUTED, 40, 14) {
                bookmarks.remove(url)
                persistBookmarks()
                showBookmarks()
            }
            page.addView(
                listRow(well(icon(Icons.BOOKMARK, Palette.ACCENT, 20, filled = true), 48, 16), hostOf(url), url, remove) {
                    navigate(url)
                },
                lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(16) }
            )
        }
        addMainNav("bookmarks")
    }

    private fun showHistory() {
        resetContent("history")
        val page = scrollPage()
        val header = row().apply { minimumHeight = dp(48) }
        header.addView(iconButton(Icons.BACK, "Back") { show("settings") })
        header.addView(label("History", 22f, Palette.TEXT, 1).apply { setPadding(dp(16), 0, 0, 0) },
            lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (history.isNotEmpty()) {
            header.addView(iconButton(Icons.TRASH, "Clear history") {
                history.clear()
                persistHistory()
                tabs.forEach { it.session.purgeHistory() }
                Toast.makeText(this, "History cleared", Toast.LENGTH_SHORT).show()
                showHistory()
            })
        }
        page.addView(header, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(22)
        })
        if (history.isEmpty()) {
            page.addView(emptyState(Icons.CLOCK, "Nothing here yet",
                "Pages you visit in normal tabs appear here. Private tabs are never recorded."))
        } else {
            val timeFormat = DateFormat.getTimeInstance(DateFormat.SHORT)
            val dayMs = 24L * 60 * 60 * 1000
            val startOfToday = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
            }.timeInMillis
            var lastGroup = ""
            history.toList().reversed().forEach { entry ->
                val group = when {
                    entry.time >= startOfToday -> "Today"
                    entry.time >= startOfToday - dayMs -> "Yesterday"
                    else -> "Earlier"
                }
                if (group != lastGroup) {
                    lastGroup = group
                    page.addView(label(group.uppercase(), 11f, Palette.MUTED, 1).apply {
                        letterSpacing = 0.12f
                        setPadding(dp(4), 0, 0, dp(12))
                    }, matchWrap())
                }
                page.addView(
                    listRow(
                        well(icon(Icons.CLOCK, Palette.MUTED, 20)),
                        entry.title.ifBlank { hostOf(entry.url) },
                        hostOf(entry.url),
                        label(timeFormat.format(Date(entry.time)), 12f, Palette.MUTED)
                    ) { navigate(entry.url) },
                    lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(14) }
                )
            }
        }
        addMainNav("settings")
    }

    private fun loadHistory() {
        try {
            val array = JSONArray(prefs.getString("history", "[]") ?: "[]")
            for (i in 0 until array.length()) {
                val o = array.getJSONArray(i)
                val url = o.optString(0)
                if (DownloadNaming.isHttpUrl(url)) history.add(HistoryEntry(url, o.optString(1), o.optLong(2)))
            }
        } catch (_: Exception) {
            // A corrupt history list is ignored.
        }
    }

    private fun persistHistory() {
        val array = JSONArray()
        history.takeLast(300).forEach { array.put(JSONArray().put(it.url).put(it.title).put(it.time)) }
        prefs.edit().putString("history", array.toString()).apply()
    }

    private fun playMedia(uri: Uri, name: String?) {
        startActivity(Intent(this, PlayerActivity::class.java).apply {
            data = uri
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra("name", name)
        })
    }

    // ---- Private Space ----------------------------------------------------------------

    private fun causes(e: Throwable): Sequence<Throwable> = generateSequence(e) { it.cause }

    /** Runs a vault operation, re-prompting for device unlock when the key window has expired. */
    private fun <T> vaultOp(block: (PrivateVault) -> T): T? = try {
        block(VaultKeys.vault(this))
    } catch (e: Exception) {
        when {
            causes(e).any { it is android.security.keystore.UserNotAuthenticatedException } -> {
                vaultUnlocked = false
                show("private")
            }
            causes(e).any { it is android.security.keystore.KeyPermanentlyInvalidatedException } -> showVaultInvalidSheet()
            e is PrivateVault.TooLargeException ->
                Toast.makeText(this, "That file is larger than 50 MB", Toast.LENGTH_LONG).show()
            else -> Toast.makeText(this, "Private Space error: ${e.message ?: e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
        }
        null
    }

    private fun showVaultInvalidSheet() {
        showConfirmSheet(
            "Private Space key was reset",
            "Android invalidated the encryption key (for example after the screen lock was changed). Existing items can no longer be decrypted. Reset Private Space to start fresh? This deletes the old items.",
            "Reset"
        ) {
            VaultKeys.vault(this).wipe()
            VaultKeys.delete()
            show("private")
        }
    }

    private fun unlockVault() {
        val manager = getSystemService(KeyguardManager::class.java)
        val intent = manager?.takeIf { it.isDeviceSecure }
            ?.createConfirmDeviceCredentialIntent("Unlock Private Space", null)
        if (intent == null) {
            Toast.makeText(this, "Set a screen lock in Android settings first", Toast.LENGTH_LONG).show()
            return
        }
        authInProgress = true
        vaultUnlock.launch(intent)
    }

    private fun importIntoVault(uri: Uri) {
        val name = contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "file"
        Thread {
            val result = try {
                contentResolver.openInputStream(uri)?.use { input ->
                    VaultKeys.vault(this).importFile(name, input, PrivateVault.MAX_FILE_BYTES)
                }
                null
            } catch (e: Exception) { e }
            runOnUiThread {
                if (result == null) {
                    Toast.makeText(this, "Encrypted and stored", Toast.LENGTH_SHORT).show()
                    if (currentPage == "private") show("private")
                } else when {
                    result is PrivateVault.TooLargeException ->
                        Toast.makeText(this, "That file is larger than 50 MB", Toast.LENGTH_LONG).show()
                    causes(result).any { it is android.security.keystore.UserNotAuthenticatedException } -> {
                        vaultUnlocked = false; show("private")
                    }
                    else -> Toast.makeText(this, "Import failed: ${result.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun exportFromVault(id: String, uri: Uri) {
        Thread {
            val error = try {
                contentResolver.openOutputStream(uri)?.use { out -> VaultKeys.vault(this).exportTo(id, out) }
                null
            } catch (e: Exception) { e }
            runOnUiThread {
                Toast.makeText(this, if (error == null) "Exported. The exported copy is not encrypted." else "Export failed: ${error.message}",
                    Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun showPrivateSpace() {
        resetContent("private")
        val page = scrollPage()
        val header = row().apply { minimumHeight = dp(48) }
        header.addView(iconButton(Icons.BACK, "Back") { show("settings") })
        header.addView(label("Private Space", 22f, Palette.TEXT, 1).apply { setPadding(dp(16), 0, 0, 0) })
        page.addView(header, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(22)
        })
        if (!vaultUnlocked) {
            val box = column().apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(8), dp(24), dp(8), 0) }
            val tile = LinearLayout(this).apply {
                gravity = Gravity.CENTER
                contentDescription = "Unlock Private Space"
                setOnClickListener { unlockVault() }
                neu(this, 40, Depth.CARD, pressable = true)
                addView(icon(Icons.LOCK, Palette.ACCENT, 40))
            }
            box.addView(tile, lp(dp(100), dp(100)))
            box.addView(label("Unlock to continue", 17f, Palette.TEXT, 1).apply {
                gravity = Gravity.CENTER; setPadding(0, dp(26), 0, 0)
            }, matchWrap())
            box.addView(label(
                "Notes and files here are encrypted with AES-256-GCM. The key is kept in the Android Keystore and needs your device fingerprint, PIN or pattern.",
                13f, Palette.MUTED
            ).apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.25f); setPadding(dp(8), dp(10), dp(8), dp(14)) }, matchWrap())
            box.addView(label(
                "There is no backup or recovery. If you remove your screen lock or Android resets the key, items cannot be decrypted. It does not protect a rooted or compromised device.",
                12f, Palette.MUTED
            ).apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.25f); setPadding(dp(8), 0, dp(8), 0) }, matchWrap())
            page.addView(box, matchWrap())
            addMainNav("settings")
            return
        }
        val entries = vaultOp { it.list() }
        if (entries == null) { addMainNav("settings"); return }
        val actions = row()
        actions.addView(textButton("New note", primary = true) { showNoteEditor(null) })
        actions.addView(textButton("Import file") { importLauncher.launch(arrayOf("*/*")) },
            lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(16) })
        page.addView(actions, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(22); topMargin = dp(4)
        })
        if (entries.isEmpty()) {
            page.addView(emptyState(Icons.LOCK, "Nothing stored yet", "Add a note or import a file (up to 50 MB). Everything is encrypted on this device."))
        } else {
            val dateFormat = DateFormat.getDateInstance(DateFormat.MEDIUM)
            entries.forEach { entry ->
                val isNote = entry.kind == "note"
                page.addView(
                    listRow(
                        well(icon(if (isNote) Icons.NOTE else Icons.FILE, Palette.ACCENT, 20)),
                        entry.name,
                        "${if (isNote) "Note" else "File"} · ${DownloadNaming.formatBytes(entry.size)} · ${dateFormat.format(Date(entry.added))}"
                    ) { if (isNote) showNoteEditor(entry) else showVaultFileSheet(entry) },
                    lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(14) }
                )
            }
        }
        addMainNav("settings")
    }

    private fun showVaultFileSheet(entry: PrivateVault.Entry) {
        showSheet { sheet, dialog ->
            sheet.addView(label(entry.name, 18f, Palette.TEXT, 1).apply {
                maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            }, matchWrap())
            sheet.addView(label("Exporting saves an unencrypted copy to a place you choose.", 13f, Palette.MUTED).apply {
                setPadding(0, dp(10), 0, dp(20))
            }, matchWrap())
            val actions = row().apply { gravity = Gravity.END }
            actions.addView(textButton("Delete") {
                dialog.dismiss()
                vaultOp { it.delete(entry.id) }
                show("private")
            })
            actions.addView(textButton("Export", primary = true) {
                dialog.dismiss()
                pendingExportId = entry.id
                exportLauncher.launch(entry.name)
            }, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(16) })
            sheet.addView(actions, matchWrap())
        }
    }

    private fun showNoteEditor(entry: PrivateVault.Entry?) {
        val existing = entry?.let { vaultOp { v -> v.readNote(it.id) } }
        if (entry != null && existing == null) return
        showSheet { sheet, dialog ->
            sheet.addView(label(if (entry == null) "New note" else "Edit note", 18f, Palette.TEXT, 1), matchWrap())
            fun field(hintText: String, multiline: Boolean) = EditText(this).apply {
                hint = hintText
                setHintTextColor(Palette.MUTED)
                setTextColor(Palette.TEXT)
                textSize = 15f
                if (multiline) {
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    gravity = Gravity.TOP
                    minLines = 5; maxLines = 8
                    setPadding(dp(20), dp(16), dp(20), dp(16))
                } else {
                    setSingleLine(true)
                    setPadding(dp(20), 0, dp(20), 0)
                }
                neu(this, if (multiline) 22 else 26, Depth.FIELD, inset = true)
            }
            val title = field("Title", false).apply { setText(entry?.name ?: "") }
            val body = field("Write something private", true).apply { setText(existing ?: "") }
            sheet.addView(title, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(18); bottomMargin = dp(14) })
            sheet.addView(body, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(20) })
            val actions = row().apply { gravity = Gravity.END }
            if (entry != null) actions.addView(textButton("Delete") {
                dialog.dismiss(); vaultOp { it.delete(entry.id) }; show("private")
            })
            actions.addView(textButton("Cancel") { dialog.dismiss() },
                lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(12) })
            actions.addView(textButton("Save", primary = true) {
                val ok = vaultOp { v ->
                    if (entry == null) v.addNote(title.text.toString(), body.text.toString())
                    else v.updateNote(entry.id, title.text.toString(), body.text.toString())
                    true
                }
                if (ok == true) { dialog.dismiss(); show("private") }
            }, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(12) })
            sheet.addView(actions, matchWrap())
        }
    }

    private fun requestDownloadNotifications() {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun startUrlDownload(url: String, isPrivate: Boolean) {
        requestDownloadNotifications()
        downloads.enqueueUrl(url, isPrivate)
        Toast.makeText(this, "Download started", Toast.LENGTH_SHORT).show()
    }

    private fun startDownload(response: org.mozilla.geckoview.WebResponse, isPrivate: Boolean) {
        requestDownloadNotifications()
        downloads.enqueue(response, isPrivate)
        Toast.makeText(this, "Download started", Toast.LENGTH_SHORT).show()
    }

    /** Rebuilds the Downloads screen while keeping the scroll position. */
    private fun refreshDownloads() {
        val y = (contentArea.getChildAt(0) as? ScrollView)?.scrollY ?: 0
        showDownloads()
        (contentArea.getChildAt(0) as? ScrollView)?.post { (contentArea.getChildAt(0) as? ScrollView)?.scrollTo(0, y) }
    }

    private fun showDownloads() {
        resetContent("downloads")
        val page = scrollPage()
        val header = row().apply { minimumHeight = dp(48) }
        header.addView(label("Downloads", 22f, Palette.TEXT, 1), lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(iconButton(Icons.PLAY, "Play a local video or audio file", Palette.ACCENT, filled = true) {
            mediaPicker.launch(arrayOf("video/*", "audio/*"))
        }, lp(dp(44), dp(44)).apply { rightMargin = dp(10) })
        if (downloads.items.any { it.state == DlState.DONE || it.state == DlState.FAILED }) {
            header.addView(iconButton(Icons.TRASH, "Clear finished downloads") { downloads.clearFinished() })
        }
        page.addView(header, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(22)
        })
        if (downloads.items.isEmpty()) {
            page.addView(emptyState(
                Icons.DOWNLOAD, "No downloads yet",
                "Files you download appear here. Downloads run while Veyra is open and are saved to Downloads/Veyra."
            ))
        } else downloads.items.toList().forEach { item ->
            page.addView(downloadCard(item), lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(16)
            })
        }
        addMainNav("downloads")
    }

    private fun downloadCard(item: DownloadItem): View {
        val card = column().apply {
            setPadding(dp(18), dp(14), dp(18), dp(14))
            neu(this, 24, Depth.CARD, pressable = item.state == DlState.DONE)
            if (item.state == DlState.DONE) setOnClickListener {
                if (DownloadNaming.isMediaMime(item.mime) && item.savedUri != null) {
                    playMedia(Uri.parse(item.savedUri), item.name)
                    return@setOnClickListener
                }
                val intent = downloads.openIntent(item)
                try { if (intent != null) startActivity(intent) else throw IllegalStateException() }
                catch (_: Exception) { Toast.makeText(this@MainActivity, "No app can open this file", Toast.LENGTH_SHORT).show() }
            }
        }
        val top = row()
        val iconPaths = if (item.state == DlState.FAILED) Icons.INFO else Icons.DOWNLOAD
        top.addView(well(icon(iconPaths, if (item.state == DlState.FAILED) Palette.MUTED else Palette.ACCENT, 20)))
        val text = column().apply { setPadding(dp(14), 0, dp(8), 0) }
        text.addView(label(if (item.isPrivate) "${item.name} (private)" else item.name, 15f, Palette.TEXT, 1).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        })
        val size = DownloadNaming.formatBytes(item.total)
        val status = when (item.state) {
            DlState.RUNNING -> listOf(
                "${DownloadNaming.formatBytes(item.done)} of $size",
                DownloadNaming.formatSpeed(item.speed)
            ).filter { it.isNotBlank() }.joinToString(" · ")
            DlState.PAUSED -> "Paused · ${DownloadNaming.formatBytes(item.done)} of $size"
            DlState.DONE -> "Saved · $size"
            DlState.FAILED -> item.error ?: "Failed"
        }
        text.addView(label(status, 13f, Palette.MUTED).apply {
            maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(4), 0, 0)
        })
        top.addView(text, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        fun action(paths: List<String>, description: String, filled: Boolean = false, onClick: () -> Unit) {
            top.addView(iconButton(paths, description, Palette.MUTED, 40, 14, filled, onClick),
                lp(dp(40), dp(40)).apply { leftMargin = dp(8) })
        }
        when (item.state) {
            DlState.RUNNING -> {
                action(Icons.PAUSE, "Pause download") { downloads.pause(item) }
                action(Icons.CLOSE, "Cancel download") { downloads.delete(item, deleteFile = false) }
            }
            DlState.PAUSED -> {
                action(Icons.PLAY, "Resume download", filled = true) { downloads.resume(item) }
                action(Icons.CLOSE, "Cancel download") { downloads.delete(item, deleteFile = false) }
            }
            DlState.FAILED -> {
                action(Icons.RELOAD, "Retry download") { downloads.resume(item) }
                action(Icons.TRASH, "Remove from list") { downloads.delete(item, deleteFile = false) }
            }
            DlState.DONE -> action(Icons.TRASH, "Delete file") { downloads.delete(item, deleteFile = true) }
        }
        card.addView(top)
        if (item.state == DlState.RUNNING || item.state == DlState.PAUSED) {
            val fraction = if (item.total > 0) (item.done.toFloat() / item.total).coerceIn(0f, 1f) else 0.3f
            val track = row().apply { neu(this, 4, Depth.KNOB, inset = true); weightSum = 1000f }
            track.addView(View(this).apply { setBackgroundColor(Palette.ACCENT) }, lp(0, dp(6), fraction * 1000f))
            track.addView(View(this), lp(0, dp(6), (1f - fraction) * 1000f))
            card.addView(track, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)).apply { topMargin = dp(14) })
        }
        return card
    }

    private fun showAppearance() {
        resetContent("appearance")
        val page = scrollPage()
        val header = row().apply { minimumHeight = dp(48) }
        header.addView(iconButton(Icons.BACK, "Back") { show("settings") })
        header.addView(label("Appearance", 22f, Palette.TEXT, 1).apply { setPadding(dp(16), 0, 0, 0) })
        page.addView(header, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(22)
        })
        fun swatch(color: Int) = View(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(color)
                setStroke(dp(1), Palette.MUTED)
            }
            layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).apply { leftMargin = dp(4) }
        }
        fun option(key: String, name: String, subtitle: String, preview: VeyraTheme?) {
            val selected = themeKey == key
            val trailing = row().apply {
                if (preview != null) {
                    addView(swatch(preview.bg)); addView(swatch(preview.accent)); addView(swatch(preview.text))
                }
                if (selected) addView(icon(Icons.SHIELD, Palette.ACCENT, 20).apply {
                    (layoutParams as LinearLayout.LayoutParams).leftMargin = dp(10)
                })
            }
            page.addView(
                listRow(well(icon(if (selected) Icons.SHIELD else Icons.SETTINGS, if (selected) Palette.ACCENT else Palette.MUTED, 20)),
                    name, subtitle, trailing) {
                    themeKey = key
                    prefs.edit().putString("theme", key).apply()
                    applyTheme()
                    showAppearance()
                },
                lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(14) }
            )
        }
        option(Themes.SYSTEM, "System default", "Midnight Navy in dark mode, Arctic White in light mode", null)
        Themes.all.forEach { option(it.key, it.name, if (it.light) "Light" else "Dark", it) }
        addMainNav("settings")
    }

    private fun showPrivacy() {
        resetContent("privacy")
        val page = scrollPage()
        val header = row().apply { minimumHeight = dp(48) }
        header.addView(iconButton(Icons.BACK, "Back") { show("settings") })
        header.addView(label("Privacy", 22f, Palette.TEXT, 1).apply { setPadding(dp(16), 0, 0, 0) })
        page.addView(header, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(22)
        })

        // Measured counter: only events Gecko's tracking protection actually reported as blocked.
        val stats = column().apply {
            setPadding(dp(22), dp(20), dp(22), dp(20))
            neu(this, 26, Depth.CARD)
        }
        stats.addView(label("Trackers blocked", 13f, Palette.MUTED))
        stats.addView(label(blockedTotal.toString(), 34f, Palette.ACCENT, 1).apply { setPadding(0, dp(8), 0, dp(4)) })
        stats.addView(label(
            "This session, ${cur?.blocked ?: 0} on the current page. Counted from Gecko's tracking-protection events; " +
                "this is domain-level protection, not a full ad blocker.",
            12f, Palette.MUTED
        ).apply { setLineSpacing(0f, 1.2f) })
        page.addView(stats, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(20)
        })

        fun choice(title: String, options: List<Triple<String, String, String>>, selected: String, onPick: (String) -> Unit) {
            page.addView(label(title.uppercase(), 11f, Palette.MUTED, 1).apply {
                letterSpacing = 0.12f
                setPadding(dp(4), dp(4), 0, dp(12))
            }, matchWrap())
            options.forEach { (key, name, description) ->
                val on = key == selected
                page.addView(
                    listRow(well(icon(if (on) Icons.SHIELD else Icons.SHIELD_OFF, if (on) Palette.ACCENT else Palette.MUTED, 20)),
                        name, description) { onPick(key) },
                    lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(14) }
                )
            }
        }
        choice("Protection level", listOf(
            Triple("standard", "Standard", "Blocks known trackers. Fewest broken sites."),
            Triple("strict", "Strict", "Blocks more trackers and content. Some sites may break.")
        ), if (strictProtection) "strict" else "standard") { key ->
            strictProtection = key == "strict"
            prefs.edit().putBoolean("protection_strict", strictProtection).apply()
            runtime.settings.contentBlocking.setAntiTracking(antiTrackingFlags())
            showPrivacy()
        }
        choice("Cookies", listOf(
            Triple("trackers", "Block tracking cookies", "Default. Allows normal site logins."),
            Triple("third_party", "Block third-party cookies", "Can break embedded logins and widgets."),
            Triple("all", "Block all cookies", "Most sites will not keep you signed in.")
        ), cookieMode) { key ->
            cookieMode = key
            prefs.edit().putString("cookie_mode", key).apply()
            runtime.settings.contentBlocking.setCookieBehavior(cookieBehavior())
            showPrivacy()
        }
        val js = NeuSwitch(this).apply {
            checked = javascriptEnabled
            contentDescription = "JavaScript"
            onChange = { on ->
                javascriptEnabled = on
                prefs.edit().putBoolean("javascript_enabled", on).apply()
                runtime.settings.setJavaScriptEnabled(on)
                Toast.makeText(this@MainActivity, "Reload pages for this to take effect", Toast.LENGTH_SHORT).show()
            }
        }
        page.addView(label("SITE CONTROLS", 11f, Palette.MUTED, 1).apply {
            letterSpacing = 0.12f
            setPadding(dp(4), dp(4), 0, dp(12))
        }, matchWrap())
        page.addView(
            listRow(well(icon(Icons.SETTINGS, Palette.ACCENT, 20)), "JavaScript", "Turning it off breaks most modern sites.", js),
            lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(14) }
        )
        page.addView(label(
            "Not implemented yet: per-site exceptions and a separate ad-block filter list.",
            12f, Palette.MUTED
        ).apply { setPadding(dp(4), dp(4), dp(4), 0) }, matchWrap())
        addMainNav("settings")
    }

    private fun showSettings() {
        resetContent("settings")
        val page = scrollPage()
        page.addView(label("Settings", 22f, Palette.TEXT, 1).apply { minHeight = dp(48); gravity = Gravity.CENTER_VERTICAL },
            lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(22) })

        fun switchRow(paths: List<String>, title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
            val toggle = NeuSwitch(this).apply {
                this.checked = checked
                this.onChange = onChange
                contentDescription = title
            }
            page.addView(
                listRow(well(icon(paths, Palette.ACCENT, 20)), title, subtitle, toggle),
                lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(16) }
            )
        }
        fun actionRow(paths: List<String>, title: String, subtitle: String, onClick: () -> Unit) {
            page.addView(
                listRow(well(icon(paths, Palette.ACCENT, 20)), title, subtitle, icon(Icons.CHEVRON, Palette.MUTED, 18), onClick),
                lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(16) }
            )
        }

        switchRow(Icons.SHIELD, "Tracking protection", "Blocks known trackers. Not a full ad blocker.", trackingProtectionEnabled) {
            setTrackingProtection(it)
        }
        switchRow(Icons.SHIELD, "Block pop-up windows", "Block websites opening unsolicited new windows or tabs.", blockPopups) {
            blockPopups = it
            prefs.edit().putBoolean("block_popups", it).apply()
            Toast.makeText(this, if (it) "Pop-up blocking enabled" else "Pop-up blocking disabled", Toast.LENGTH_SHORT).show()
        }
        val secure = deviceIsSecure()
        switchRow(
            Icons.LOCK, "App lock",
            if (secure) "Ask for your device unlock when returning to Veyra." else "Set a screen lock in Android settings first.",
            appLockEnabled && secure
        ) { enabled -> setAppLock(enabled) }
        actionRow(Icons.SHIELD, "Privacy", "Blocked trackers, cookies, JavaScript") { show("privacy") }
        actionRow(Icons.SETTINGS, "Appearance", "Theme: ${if (themeKey == Themes.SYSTEM) "System" else Themes.byKey(themeKey).name}") { show("appearance") }
        actionRow(Icons.LOCK, "Private Space", "Encrypted notes and files") { show("private") }
        actionRow(Icons.SHIELD, "VPN", vpnSubtitle()) { show("vpn") }
        actionRow(Icons.CLOCK, "History", "Pages visited in normal tabs") { show("history") }
        actionRow(Icons.TRASH, "Clear browsing data", "Cookies, cache, site data and history") { confirmClearData() }
        actionRow(Icons.INFO, "About Veyra", "Version, engine and licence") { showAbout() }
        addMainNav("settings")
    }

    // ---- VPN ---------------------------------------------------------------------------

    private fun vpnSubtitle(): String {
        val vpn = VpnManager.get(this)
        return when {
            vpn.status == VpnManager.Status.ON -> "Connected"
            vpn.hasConfig() -> "WireGuard config saved, off"
            else -> "Import your own WireGuard config"
        }
    }

    private fun startVpn() {
        val vpn = VpnManager.get(this)
        val intent = try { vpn.permissionIntent() } catch (_: Exception) { null }
        if (intent != null) vpnPermission.launch(intent) else vpn.connect()
    }

    private fun importVpnConfig(uri: Uri) {
        val text = try {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                val buffer = CharArray(20_001)
                val read = reader.read(buffer)
                if (read > 0) String(buffer, 0, read) else ""
            } ?: ""
        } catch (_: Exception) {
            Toast.makeText(this, "Could not read that file", Toast.LENGTH_LONG).show()
            return
        }
        saveVpnConfig(text)
    }

    private fun saveVpnConfig(text: String) {
        try {
            VpnManager.get(this).saveConfig(text)
            Toast.makeText(this, "VPN config saved (encrypted)", Toast.LENGTH_SHORT).show()
        } catch (e: IllegalArgumentException) {
            Toast.makeText(this, e.message ?: "Invalid config", Toast.LENGTH_LONG).show()
        }
        if (currentPage == "vpn") showVpn()
    }

    private fun showVpn() {
        resetContent("vpn")
        val vpn = VpnManager.get(this)
        vpn.refresh()
        vpn.listener = { if (currentPage == "vpn") showVpn() }
        val page = scrollPage()
        val header = row().apply { minimumHeight = dp(48) }
        header.addView(iconButton(Icons.BACK, "Back") { show("settings") })
        header.addView(label("VPN", 22f, Palette.TEXT, 1).apply { setPadding(dp(16), 0, 0, 0) })
        page.addView(header, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(22) })

        val status = vpn.status
        val card = column().apply {
            setPadding(dp(22), dp(20), dp(22), dp(20))
            neu(this, 26, Depth.CARD)
        }
        card.addView(label("Status", 13f, Palette.MUTED))
        card.addView(label(
            when (status) {
                VpnManager.Status.ON -> "Connected"
                VpnManager.Status.CONNECTING -> "Connecting…"
                VpnManager.Status.ERROR -> "Connection failed"
                VpnManager.Status.OFF -> if (vpn.hasConfig()) "Off" else "No config yet"
            },
            26f, if (status == VpnManager.Status.ON) Palette.ACCENT else Palette.TEXT, 1
        ).apply { setPadding(0, dp(8), 0, dp(6)) })
        card.addView(label(
            when {
                status == VpnManager.Status.ON -> "All apps on this device use the tunnel while it is on."
                status == VpnManager.Status.ERROR -> vpn.lastError ?: "Something went wrong."
                vpn.hasConfig() -> "Tap Connect to start the tunnel."
                else -> "Veyra does not provide VPN servers. Import a WireGuard .conf from your VPN provider or your own server."
            },
            13f, Palette.MUTED
        ).apply { setLineSpacing(0f, 1.2f) })
        page.addView(card, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(18) })

        val countryNote = column().apply {
            setPadding(dp(18), dp(16), dp(18), dp(16))
            neu(this, 22, Depth.CARD)
            addView(label("Server locations", 15f, Palette.TEXT, 1))
            addView(label(
                "Veyra can connect only to a real provider/owner-supplied WireGuard server profile. To offer selectable USA, New Zealand and a third country, each location needs a working endpoint and permission to use it; Veyra does not currently operate free VPN servers.",
                13f, Palette.MUTED
            ).apply { setPadding(0, dp(8), 0, 0); setLineSpacing(0f, 1.15f) })
        }
        page.addView(countryNote, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(18) })

        if (vpn.hasConfig()) {
            val busy = status == VpnManager.Status.CONNECTING
            val on = status == VpnManager.Status.ON
            page.addView(
                textButton(if (on) "Disconnect" else if (busy) "Connecting…" else "Connect", primary = !on) {
                    if (busy) return@textButton
                    if (on) vpn.disconnect() else startVpn()
                },
                lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(18) }
            )
            val summary = vpn.summary()
            if (summary != null) {
                val info = column().apply {
                    setPadding(dp(22), dp(18), dp(22), dp(18))
                    neu(this, 26, Depth.CARD)
                }
                fun line(name: String, value: String) {
                    info.addView(label(name, 12f, Palette.MUTED).apply { setPadding(0, dp(6), 0, 0) })
                    info.addView(label(value.ifBlank { "-" }, 14f, Palette.TEXT).apply { setPadding(0, dp(2), 0, 0) })
                }
                line("Server", summary.endpoints.joinToString("\n"))
                line("Device address", summary.addresses.joinToString(", "))
                line("DNS", summary.dns.joinToString(", "))
                line("Routes", if (summary.routesAllTraffic) "All traffic" else summary.allowedIps.joinToString(", "))
                info.addView(label("The private key is stored encrypted and is never shown.", 12f, Palette.MUTED)
                    .apply { setPadding(0, dp(14), 0, 0) })
                page.addView(info, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(18) })
            }
        }

        val actions = row()
        actions.addView(textButton(if (vpn.hasConfig()) "Replace file" else "Import file", primary = !vpn.hasConfig()) {
            vpnImport.launch(arrayOf("*/*"))
        }, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(textButton("Paste") {
            val clip = getSystemService(android.content.ClipboardManager::class.java)
                ?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
            if (clip.isBlank()) Toast.makeText(this, "Copy your WireGuard config first", Toast.LENGTH_SHORT).show()
            else saveVpnConfig(clip)
        }, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(14) })
        page.addView(actions, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(14) })
        if (vpn.hasConfig()) {
            page.addView(textButton("Remove config") {
                showConfirmSheet("Remove VPN config?", "The saved WireGuard config is deleted from this device.", "Remove") {
                    vpn.deleteConfig()
                    show("vpn")
                }
            }, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(18) })
        }
        page.addView(label(
            "Uses the WireGuard engine. Your provider sees the traffic that leaves the tunnel, so pick one you trust. " +
                "Not implemented: server list, kill switch and per-app split tunnelling.",
            12f, Palette.MUTED
        ).apply { setLineSpacing(0f, 1.2f); setPadding(dp(4), 0, dp(4), 0) }, matchWrap())
        addMainNav("settings")
    }

    // ---- page media (detect, play, save) ------------------------------------------------

    /**
     * Loads the built-in scanner extension once per process. It reads the page DOM for video and
     * audio URLs and reports them to [attachMediaScanner]'s delegate. If it fails to load,
     * everything else keeps working and the menu simply never offers page media.
     */
    private fun installMediaScanner() {
        if (mediaExtension != null) return
        try {
            val controller = runtime.webExtensionController
            controller.ensureBuiltIn("resource://android/assets/veyra_media/", "media@veyra.app").accept({ ext ->
                if (ext != null) {
                    mediaExtension = ext
                    controller.setAllowedInPrivateBrowsing(ext, true)
                    tabs.forEach { attachMediaScanner(it) }
                }
                null
            }, { _ -> null })
        } catch (_: Throwable) {
            // Media detection is optional.
        }
    }

    private fun attachMediaScanner(tab: Tab) {
        val ext = mediaExtension ?: return
        tab.session.webExtensionController.setMessageDelegate(ext, object : WebExtension.MessageDelegate {
            override fun onMessage(nativeApp: String, message: Any, sender: WebExtension.MessageSender): GeckoResult<Any>? {
                val result = MediaDetect.parse(message.toString())
                runOnUiThread {
                    tab.media = result.items
                    tab.unsupportedMedia = result.unsupported
                }
                return null
            }
        }, "veyraMedia")
    }

    private fun showMediaSheet() {
        val tab = cur ?: return
        showSheet { sheet, dialog ->
            sheet.addView(label("Media on this page", 18f, Palette.TEXT, 1), matchWrap())
            sheet.addView(label(
                "Direct files can be played or saved. Streams can only be played.",
                13f, Palette.MUTED
            ).apply { setPadding(0, dp(8), 0, dp(14)) }, matchWrap())
            val list = column().apply { setPadding(dp(8), dp(8), dp(8), dp(8)) }
            tab.media.forEach { media ->
                val card = column().apply {
                    setPadding(dp(16), dp(14), dp(16), dp(14))
                    neu(this, 20, Depth.BUTTON)
                }
                card.addView(label(media.title.ifBlank { media.fileName }, 14f, Palette.TEXT, 1).apply {
                    maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                })
                val kind = when (media.kind) {
                    DetectedMedia.Kind.VIDEO -> "Video"
                    DetectedMedia.Kind.AUDIO -> "Audio"
                    DetectedMedia.Kind.STREAM -> "Stream"
                }
                card.addView(label("$kind · ${hostOf(media.url)} · ${media.fileName}", 12f, Palette.MUTED).apply {
                    maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(0, dp(4), 0, dp(10))
                })
                val actions = row()
                actions.addView(textButton("Play", primary = true) {
                    dialog.dismiss(); playMedia(Uri.parse(media.url), media.title)
                }, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                if (media.downloadable) {
                    actions.addView(textButton("Save") {
                        dialog.dismiss()
                        startUrlDownload(media.url, tab.isPrivate)
                    }, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(12) })
                }
                card.addView(actions, matchWrap())
                list.addView(card, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(14) })
            }
            val scroller = ScrollView(this).apply {
                clipChildren = false; clipToPadding = false
                overScrollMode = View.OVER_SCROLL_NEVER
                addView(list)
            }
            sheet.addView(scroller, lp(
                ViewGroup.LayoutParams.MATCH_PARENT,
                if (tab.media.size > 2) dp(340) else ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            if (tab.unsupportedMedia > 0) {
                sheet.addView(label(
                    "${tab.unsupportedMedia} protected or segmented stream(s) on this page cannot be saved by Veyra " +
                        "(for example blob, DRM or DASH video).",
                    12f, Palette.MUTED
                ).apply { setLineSpacing(0f, 1.2f); setPadding(0, dp(10), 0, 0) }, matchWrap())
            }
        }
    }

    // ---- actions ----------------------------------------------------------------------

    private fun setTrackingProtection(enabled: Boolean) {
        trackingProtectionEnabled = enabled
        tabs.forEach { it.session.settings.setUseTrackingProtection(enabled) }
        prefs.edit().putBoolean("tracking_protection_enabled", enabled).apply()
    }

    private fun setAppLock(enabled: Boolean) {
        if (enabled && !deviceIsSecure()) {
            Toast.makeText(this, "Set a screen lock in Android settings first", Toast.LENGTH_LONG).show()
            show("settings")
            return
        }
        appLockEnabled = enabled
        prefs.edit().putBoolean("app_lock_enabled", enabled).apply()
        applySecureFlag()
        Toast.makeText(this, if (enabled) "App lock enabled" else "App lock disabled", Toast.LENGTH_SHORT).show()
    }

    private fun confirmClearData() {
        showConfirmSheet(
            "Clear browsing data?",
            "This removes cookies, cache, site data and history. Bookmarks and quick access stay.",
            "Clear"
        ) {
            history.clear()
            persistHistory()
            tabs.forEach { it.session.purgeHistory() }
            runtime.storageController.clearData(StorageController.ClearFlags.ALL)
            Toast.makeText(this, "Browsing data cleared", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showAbout() {
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (_: Exception) { "" }
        showSheet { sheet ->
            sheet.addView(label("About Veyra", 18f, Palette.TEXT, 1), matchWrap())
            sheet.addView(label(
                "Version $version\nEngine: Mozilla GeckoView\nOpen-source browser, GPL-3.0",
                14f, Palette.MUTED
            ).apply { setLineSpacing(0f, 1.25f); setPadding(0, dp(12), 0, dp(20)) }, matchWrap())
        }
    }

    private fun showBrowserMenu() {
        showSideSheet { sheet, dialog ->
            fun item(paths: List<String>, text: String, action: () -> Unit) {
                val rowView = row().apply {
                    setPadding(dp(12), dp(6), dp(12), dp(6))
                    minimumHeight = dp(44)
                    neu(this, 16, Depth.BUTTON, pressable = true)
                    setOnClickListener { dialog.dismiss(); action() }
                }
                rowView.addView(icon(paths, Palette.ACCENT, 18))
                rowView.addView(label(text, 14f, Palette.TEXT, 1).apply { setPadding(dp(10), 0, 0, 0) })
                sheet.addView(rowView, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { bottomMargin = dp(6) })
            }
            item(Icons.BOOKMARK, "Bookmark this page") {
                if (currentUrl.isNotBlank()) {
                    bookmarks.add(currentUrl); persistBookmarks()
                    Toast.makeText(this, "Bookmark saved on this device", Toast.LENGTH_SHORT).show()
                }
            }
            if (DownloadNaming.looksPlayable(currentUrl)) {
                item(Icons.PLAY, "Play in Veyra") { playMedia(Uri.parse(currentUrl), null) }
            }
            cur?.let { tab ->
                if (tab.media.isNotEmpty() || tab.unsupportedMedia > 0) {
                    val count = tab.media.size
                    item(Icons.PLAY, if (count > 0) "Media on this page ($count)" else "Media on this page") { showMediaSheet() }
                }
            }
            item(Icons.BOOKMARK, "Bookmarks") { show("bookmarks") }
            item(Icons.DOWNLOAD, "Downloads") { show("downloads") }
            item(Icons.SHIELD, "VPN") { show("vpn") }
            item(Icons.SEARCH, "Find in page") { showFindSheet() }
            item(Icons.TABS, if (cur?.desktop == true) "Mobile site" else "Desktop site") { toggleDesktop() }
            if (DownloadNaming.looksLikeDirectFile(currentUrl)) {
                item(Icons.DOWNLOAD, "Download this file") {
                    startUrlDownload(currentUrl, cur?.isPrivate == true)
                }
            }
            item(Icons.CLOCK, "History") { show("history") }
            item(Icons.SETTINGS, "Settings") { show("settings") }
        }
    }

    private fun toggleDesktop() {
        val tab = cur ?: return
        tab.desktop = !tab.desktop
        val settings = tab.session.settings
        settings.userAgentMode =
            if (tab.desktop) org.mozilla.geckoview.GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
            else org.mozilla.geckoview.GeckoSessionSettings.USER_AGENT_MODE_MOBILE
        settings.viewportMode =
            if (tab.desktop) org.mozilla.geckoview.GeckoSessionSettings.VIEWPORT_MODE_DESKTOP
            else org.mozilla.geckoview.GeckoSessionSettings.VIEWPORT_MODE_MOBILE
        tab.session.reload()
        Toast.makeText(this, if (tab.desktop) "Desktop site" else "Mobile site", Toast.LENGTH_SHORT).show()
    }

    private fun showFindSheet() {
        val tab = cur ?: return
        val finder = tab.session.finder
        showSheet { sheet, dialog ->
            sheet.addView(label("Find in page", 18f, Palette.TEXT, 1), matchWrap())
            val result = label("", 13f, Palette.MUTED).apply { setPadding(dp(6), dp(12), 0, dp(14)) }
            val input = EditText(this).apply {
                hint = "Search this page"
                setHintTextColor(Palette.MUTED)
                setTextColor(Palette.TEXT)
                textSize = 15f
                setSingleLine(true)
                imeOptions = EditorInfo.IME_ACTION_SEARCH
                setPadding(dp(20), 0, dp(20), 0)
                neu(this, 26, Depth.FIELD, inset = true)
            }
            fun find(backwards: Boolean) {
                val query = input.text.toString()
                if (query.isBlank()) { finder.clear(); result.text = ""; return }
                finder.find(query, if (backwards) GeckoSession.FINDER_FIND_BACKWARDS else 0)
                    .accept({ r ->
                        result.text = when {
                            r == null -> ""
                            !r.found -> "No matches"
                            else -> "${r.current} of ${r.total}"
                        }
                    }, { result.text = "Find failed" })
            }
            input.setOnEditorActionListener { _, _, _ -> find(false); true }
            sheet.addView(input, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(16) })
            sheet.addView(result, matchWrap())
            val actions = row().apply { gravity = Gravity.END }
            actions.addView(textButton("Prev") { find(true) })
            actions.addView(textButton("Next", primary = true) { find(false) },
                lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(12) })
            actions.addView(textButton("Done") { dialog.dismiss() },
                lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(12) })
            sheet.addView(actions, matchWrap())
            dialog.setOnDismissListener { finder.clear() }
        }
    }

    private fun showAddShortcutSheet() {
        showSheet { sheet, dialog ->
            sheet.addView(label("Add quick access", 18f, Palette.TEXT, 1), matchWrap())
            fun field(hintText: String, uri: Boolean): EditText = EditText(this).apply {
                hint = hintText
                setHintTextColor(Palette.MUTED)
                setTextColor(Palette.TEXT)
                textSize = 15f
                setSingleLine(true)
                inputType = InputType.TYPE_CLASS_TEXT or if (uri) InputType.TYPE_TEXT_VARIATION_URI else 0
                setPadding(dp(20), 0, dp(20), 0)
                neu(this, 26, Depth.FIELD, inset = true)
            }
            val name = field("Shortcut name", false)
            val url = field("https://example.com", true)
            sheet.addView(name, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(18); bottomMargin = dp(16) })
            sheet.addView(url, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { bottomMargin = dp(22) })
            val actions = row().apply { gravity = Gravity.END }
            actions.addView(textButton("Cancel") { dialog.dismiss() })
            actions.addView(textButton("Save", primary = true) {
                val title = name.text.toString().trim()
                val resolved = AddressResolver.resolve(url.text.toString().trim())
                if (title.isBlank() || resolved == null ||
                    !(resolved.startsWith("https://", true) || resolved.startsWith("http://", true))
                ) {
                    Toast.makeText(this, "Enter a name and a valid website URL", Toast.LENGTH_SHORT).show()
                } else {
                    val custom = loadQuickShortcuts().toMutableList()
                    custom.removeAll { it[1].equals(title, true) || it[2].equals(resolved, true) }
                    custom.add(listOf("↗", title, resolved))
                    saveQuickShortcuts(custom)
                    dialog.dismiss()
                    showHome()
                }
            }, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(16) })
            sheet.addView(actions, matchWrap())
        }
    }

    private fun showConfirmSheet(title: String, message: String, confirm: String, onConfirm: () -> Unit) {
        showSheet { sheet, dialog ->
            sheet.addView(label(title, 18f, Palette.TEXT, 1), matchWrap())
            sheet.addView(label(message, 14f, Palette.MUTED).apply {
                setLineSpacing(0f, 1.25f); setPadding(0, dp(12), 0, dp(22))
            }, matchWrap())
            val actions = row().apply { gravity = Gravity.END }
            actions.addView(textButton("Cancel") { dialog.dismiss() })
            actions.addView(textButton(confirm, primary = true) { dialog.dismiss(); onConfirm() },
                lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(16) })
            sheet.addView(actions, matchWrap())
        }
    }

    /** Compact right-side action drawer used by the browser's three-dot menu. */
    private fun showSideSheet(build: (LinearLayout, Dialog) -> Unit) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val sheet = column().apply {
            setPadding(dp(12), dp(18), dp(12), dp(18))
            neu(this, 24, Depth.CARD)
            isClickable = true
        }
        build(sheet, dialog)
        val scroller = ScrollView(this).apply {
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(0, dp(8), 0, dp(8))
            addView(sheet)
        }
        dialog.setContentView(scroller)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.argb(170, 4, 9, 16)))
            setLayout(dp(292), ViewGroup.LayoutParams.MATCH_PARENT)
            setGravity(Gravity.END)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
        dialog.window?.setLayout(dp(292), ViewGroup.LayoutParams.MATCH_PARENT)
    }

    private fun showSheet(build: (LinearLayout) -> Unit) = showSheet { sheet, _ -> build(sheet) }

    /** Neumorphic bottom sheet. */
    private fun showSheet(build: (LinearLayout, Dialog) -> Unit) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val outer = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        val sheet = column().apply {
            setPadding(dp(22), dp(22), dp(22), dp(22))
            neu(this, 28, Depth.CARD)
            isClickable = true
        }
        outer.addView(sheet, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        build(sheet, dialog)
        dialog.setContentView(outer)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.argb(150, 4, 9, 16)))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
    }

    // ---- storage ----------------------------------------------------------------------

    private fun hostOf(url: String): String {
        if (url.isBlank()) return ""
        return try {
            android.net.Uri.parse(url).host?.removePrefix("www.")?.ifBlank { null } ?: url
        } catch (_: Exception) { url }
    }

    private fun loadQuickShortcuts(): List<List<String>> {
        val raw = prefs.getString("quick_shortcuts", "[]") ?: "[]"
        return try {
            val saved = JSONArray(raw)
            (0 until saved.length()).mapNotNull { index ->
                val item = saved.optJSONArray(index) ?: return@mapNotNull null
                val name = item.optString(0, "").trim()
                val url = item.optString(1, "").trim()
                if (name.isNotBlank() && (url.startsWith("https://", true) || url.startsWith("http://", true))) {
                    listOf("↗", name, url)
                } else null
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveQuickShortcuts(shortcuts: List<List<String>>) {
        val saved = JSONArray()
        shortcuts.forEach { item -> saved.put(JSONArray().put(item[1]).put(item[2])) }
        prefs.edit().putString("quick_shortcuts", saved.toString()).apply()
    }

    private fun loadBookmarks() {
        val raw = prefs.getString("bookmarks", "[]") ?: "[]"
        try {
            val saved = JSONArray(raw)
            for (index in 0 until saved.length()) {
                val url = saved.optString(index, "")
                if (url.startsWith("https://", true) || url.startsWith("http://", true)) bookmarks.add(url)
            }
        } catch (_: Exception) {
            // Ignore malformed preferences rather than preventing the browser from opening.
        }
    }

    private fun persistBookmarks() {
        val saved = JSONArray()
        bookmarks.forEach { saved.put(it) }
        prefs.edit().putString("bookmarks", saved.toString()).apply()
    }

    companion object {
        @Volatile private var sharedRuntime: GeckoRuntime? = null
        @Volatile private var mediaExtension: WebExtension? = null
    }
}
