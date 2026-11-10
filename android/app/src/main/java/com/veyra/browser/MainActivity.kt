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
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
    private var websiteColorScheme = "light"
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
    private var vpnStatusLabel: TextView? = null
    private var backButton: View? = null
    private var forwardButton: View? = null
    private var progressFill: View? = null
    private var currentPage = "home"
    private var websiteFullscreen = false
    private var fullscreenPreviousBottomVisibility = View.VISIBLE
    private var fullscreenPreviousActionVisibility = View.VISIBLE
    private val screenBackStack = java.util.ArrayDeque<String>()
    private var restoringScreen = false
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
    private var privateFolderUnlocked = false
    private val folderUnlock = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) { privateFolderUnlocked = true; show("pfolder") }
        else Toast.makeText(this, "Private folder stays locked", Toast.LENGTH_SHORT).show()
    }
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
        onBackPressedDispatcher.addCallback(this, backCallback)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        themeKey = prefs.getString("theme", Themes.SYSTEM) ?: Themes.SYSTEM
        strictProtection = prefs.getBoolean("protection_strict", false)
        cookieMode = prefs.getString("cookie_mode", "trackers") ?: "trackers"
        javascriptEnabled = prefs.getBoolean("javascript_enabled", true)
        blockPopups = prefs.getBoolean("block_popups", true)
        websiteColorScheme = prefs.getString("website_color_scheme", "light") ?: "light"
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
                .preferredColorScheme(websiteColorSchemeCode())
                .consoleOutput(false)
                .remoteDebuggingEnabled(false)
                .build()
        ).also { sharedRuntime = it }
        // A shared GeckoRuntime may outlive Activity recreation; reconcile the active preference too.
        runtime.settings.setPreferredColorScheme(websiteColorSchemeCode())
        trackingProtectionEnabled = prefs.getBoolean("tracking_protection_enabled", true)
        installMediaScanner()
        geckoView = GeckoView(this)
        downloads = DownloadCenter(this) {
            if (currentPage == "downloads") refreshDownloads()
            else if (currentPage == "pfolder") showPrivateFolder()
        }
        VpnManager.get(this).listener = {
            val vpn = VpnManager.get(this)
            vpnStatusLabel?.text = if (vpn.status == VpnManager.Status.ON) "VPN ON" else "VPN"
            if (currentPage == "vpn") showVpn()
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
                bar.visibility = if (websiteFullscreen || (keyboardVisible && currentPage == "browse")) View.GONE else View.VISIBLE
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

    override fun onResume() {
        super.onResume()
        if (::downloads.isInitialized) VpnManager.get(this).refresh()
    }

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

    /** Predictive back (targetSdk 36) never calls onBackPressed(), so back is handled by a dispatcher callback. */
    private val backCallback = object : androidx.activity.OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            when {
                currentPage == "lock" -> passBackToSystem()
                websiteFullscreen -> cur?.session?.exitFullScreen()
                currentPage == "browse" && canGoBack -> cur?.session?.goBack()
                // Browser may be the visible child of Settings/Downloads/VPN. Restore that
                // screen before treating Back as an exit to Home.
                currentPage == "browse" && screenBackStack.isNotEmpty() -> {
                    val destination = screenBackStack.removeLast()
                    restoringScreen = true
                    try { show(destination) } finally { restoringScreen = false }
                }
                currentPage == "browse" -> show("home")
                screenBackStack.isNotEmpty() -> {
                    val destination = screenBackStack.removeLast()
                    restoringScreen = true
                    try { show(destination) } finally { restoringScreen = false }
                }
                currentPage != "home" -> show("home")
                else -> passBackToSystem()
            }
        }

        private fun passBackToSystem() {
            isEnabled = false
            onBackPressedDispatcher.onBackPressed()
            isEnabled = true
        }
    }

    private fun deviceIsSecure(): Boolean =
        getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    private fun applySecureFlag() {
        val debuggable = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        // Debug builds skip the flag for private tabs only so CI can screenshot them.
        val privateSecure = tabs.any { it.isPrivate } && !debuggable
        if (appLockEnabled || privateSecure || currentPage == "private" || currentPage == "pfolder") window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
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

    private fun translucent(color: Int, alpha: Int): Int =
        Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    private fun websiteColorSchemeCode(): Int = when (websiteColorScheme.lowercase()) {
        "dark" -> GeckoRuntimeSettings.COLOR_SCHEME_DARK
        "system" -> GeckoRuntimeSettings.COLOR_SCHEME_SYSTEM
        else -> GeckoRuntimeSettings.COLOR_SCHEME_LIGHT
    }

    private fun showWebsiteAppearance() {
        showSheet { sheet, dialog ->
            sheet.addView(label("Website appearance", 18f, Palette.TEXT, 1), matchWrap())
            sheet.addView(label(
                "This sets the web page's preferred color scheme. Light is the default to avoid sites unexpectedly choosing a black/dark page; some websites may still use their own colors.",
                13f, Palette.MUTED
            ).apply { setPadding(0, dp(8), 0, dp(14)); setLineSpacing(0f, 1.2f) }, matchWrap())
            listOf(
                Triple("light", "Light", "Prefer light website themes"),
                Triple("system", "System", "Follow Android's appearance"),
                Triple("dark", "Dark", "Prefer dark website themes")
            ).forEach { (key, name, description) ->
                val selected = websiteColorScheme == key
                sheet.addView(
                    listRow(
                        well(icon(if (selected) Icons.SHIELD else Icons.SETTINGS, if (selected) Palette.ACCENT else Palette.MUTED, 20)),
                        name, description,
                        icon(if (selected) Icons.SHIELD else Icons.CHEVRON, Palette.ACCENT, 18)
                    ) {
                        websiteColorScheme = key
                        prefs.edit().putString("website_color_scheme", key).apply()
                        runtime.settings.setPreferredColorScheme(websiteColorSchemeCode())
                        dialog.dismiss()
                        cur?.session?.reload()
                        Toast.makeText(this, "Website appearance updated", Toast.LENGTH_SHORT).show()
                    },
                    lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }
                )
            }
        }
    }

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
        val contentTop = if (websiteFullscreen || currentPage == "browse") 0 else dp(16)
        root.setPadding(
            systemInsetLeft + contentGutter,
            if (websiteFullscreen) 0 else systemInsetTop + contentTop,
            systemInsetRight + contentGutter,
            if (websiteFullscreen) 0 else maxOf(systemInsetBottom, imeInsetBottom)
        )
    }

    private fun show(page: String) {
        if (page != currentPage && !restoringScreen) {
            if (page == "home") screenBackStack.clear()
            else screenBackStack.addLast(currentPage)
        }
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
            "pfolder" -> showPrivateFolder()
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
        if (page != "pfolder") privateFolderUnlocked = false
        applySecureFlag()
        urlHost = null
        vpnStatusLabel = null
        backButton = null
        forwardButton = null
        progressFill = null
        browserActionRow = null
        hideKeyboard()
        contentArea.removeAllViews()
        // Scroll pages turn this on so rows never paint under the dock or system bars.
        contentArea.clipChildren = false
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
        contentArea.clipChildren = true
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
        // Same capsule language as the browser dock: theme-tinted surface, thin accent outline,
        // and a soft pill behind the active item instead of a heavy inset well.
        val surface = ThemeMath.mix(Palette.BG, Palette.TEXT, 0.055f)
        val accentSurface = ThemeMath.mix(Palette.BG, Palette.ACCENT, if (Palette.LIGHT_THEME) 0.09f else 0.16f)
        val outline = ThemeMath.mix(Palette.BG, Palette.ACCENT, if (Palette.LIGHT_THEME) 0.34f else 0.48f)
        val nav = row().apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), 0, dp(8), 0)
            elevation = dpf(6f)
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(translucent(accentSurface, 222), translucent(surface, 214), translucent(ThemeMath.mix(Palette.BG, Palette.ACCENT, 0.10f), 218))
            ).apply {
                cornerRadius = dpf(30f)
                setStroke(dp(1), translucent(outline, 155))
            }
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
                contentDescription = item.description
                isClickable = true
                setOnClickListener { show(item.key) }
            }
            val pill = LinearLayout(this).apply {
                gravity = Gravity.CENTER
                if (active) background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dpf(20f)
                    setColor(ThemeMath.mix(Palette.BG, Palette.ACCENT, if (Palette.LIGHT_THEME) 0.16f else 0.26f))
                }
            }
            pill.addView(icon(item.paths, if (active) Palette.ACCENT else Palette.MUTED, 22))
            slot.addView(pill, FrameLayout.LayoutParams(dp(50), dp(40), Gravity.CENTER))
            nav.addView(slot, lp(0, dp(58), 1f))
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
                setColor(translucent(ThemeMath.mix(Palette.BG, Palette.ACCENT, if (Palette.LIGHT_THEME) 0.035f else 0.075f), 216))
                setStroke(dp(1), translucent(ThemeMath.mix(Palette.BG, Palette.ACCENT, 0.18f), 115))
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
                    else -> if (vpnManager.hasConfig()) startVpn() else setupFreeVpnAndConnect()
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
            vpnStatusLabel = badge
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
        val home = cell(Icons.HOME, "Home") { show("home") }
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
        var menuAnchor: View? = null
        val menu = cell(Icons.MENU, "Menu", filled = true) { menuAnchor?.let { showBrowserMenu(it) } }
        menuAnchor = menu
        listOf(back, forward, home, tabsButton, menu).forEach { actions.addView(it, lp(0, dp(44), 1f)) }
        capsule.addView(actions, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(2) })
        browserActionRow = actions

        attachBottomBar(capsule, matchWidth = true)
    }

    private fun setWebsiteFullscreen(enabled: Boolean) {
        if (websiteFullscreen == enabled) return
        websiteFullscreen = enabled
        if (enabled) {
            fullscreenPreviousBottomVisibility = bottomBar?.visibility ?: View.VISIBLE
            fullscreenPreviousActionVisibility = browserActionRow?.visibility ?: View.VISIBLE
            bottomBar?.visibility = View.GONE
            browserActionRow?.visibility = View.GONE
            root.setPadding(systemInsetLeft, 0, systemInsetRight, 0)
            WindowInsetsControllerCompat(window, window.decorView).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        } else {
            WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
            bottomBar?.visibility = fullscreenPreviousBottomVisibility
            browserActionRow?.visibility = fullscreenPreviousActionVisibility
            applyGutters()
        }
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
        // Main nav sits exactly on the page gutter (same edge as the cards); the browser dock is
        // full-bleed, so it keeps a small fixed margin. Both get the same vertical spacing.
        root.addView(bar, lp(
            ViewGroup.LayoutParams.MATCH_PARENT,
            if (matchWidth) ViewGroup.LayoutParams.WRAP_CONTENT else dp(58)
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(8)
            bottomMargin = dp(10)
            leftMargin = dp(if (matchWidth) 16 else 0)
            rightMargin = dp(if (matchWidth) 16 else 0)
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
                // Only the visible tab renders; background tabs are paused to save CPU and RAM.
                tabs.forEach { it.session.setActive(it === tab) }
            }
        }
        // The page fills the whole content area; the address pill lives in the bottom dock.
        contentArea.addView(geckoView, lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        mediaBanner = null
        updateMediaBanner()
        addBrowserBar()
        revealBrowserChrome()
    }

    private var mediaBanner: View? = null
    private val dismissedBanners = HashSet<String>()

    /** UC/PlayIt-style prompt: when the page has playable media, offer Play (stream) or Download. */
    private fun updateMediaBanner() {
        if (currentPage != "browse") return
        mediaBanner?.let { (it.parent as? ViewGroup)?.removeView(it) }
        mediaBanner = null
        val tab = cur ?: return
        if (tab.media.isEmpty() || websiteFullscreen || tab.url in dismissedBanners) return
        val count = tab.media.size
        val downloadableCount = tab.media.count { it.downloadable }
        val banner = row().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(10), dp(10))
            neu(this, 20, Depth.CARD)
        }
        banner.addView(icon(Icons.DOWNLOAD, Palette.ACCENT, 18))
        banner.addView(label(
            if (count == 1) tab.media.first().qualityLabel else "$count media sources",
            13f, Palette.TEXT, 1
        ).apply { setPadding(dp(10), 0, dp(8), 0); maxLines = 2 }, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (downloadableCount > 0) {
            banner.addView(textButton("Download", primary = true) { showMediaSheet() })
        }
        banner.addView(iconButton(Icons.CLOSE, "Dismiss", Palette.MUTED, 34, 12, false) {
            dismissedBanners.add(tab.url)
            updateMediaBanner()
        }, lp(dp(34), dp(34)).apply { leftMargin = dp(4) })
        mediaBanner = banner
        contentArea.addView(banner, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(12); rightMargin = dp(12); topMargin = dp(8); bottomMargin = dp(8)
        })
    }

    private fun navigate(raw: String) {
        val trimmed = raw.trim()
        // Hand torrent magnets/files to an installed torrent client (for example Flud).
        // Veyra does not implement a torrent engine or pretend a torrent is a direct HTTP file.
        if (trimmed.startsWith("magnet:", true) ||
            (trimmed.startsWith("http", true) && trimmed.substringBefore('#').substringBefore('?').endsWith(".torrent", true))) {
            try {
                val torrentUri = Uri.parse(trimmed)
                val torrentIntent = if (trimmed.startsWith("magnet:", true)) {
                    Intent(Intent.ACTION_VIEW, torrentUri)
                } else {
                    Intent(Intent.ACTION_VIEW).setDataAndType(torrentUri, "application/x-bittorrent")
                }
                startActivity(torrentIntent)
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
                if (tab === cur) mediaBanner?.let { (it.parent as? ViewGroup)?.removeView(it); mediaBanner = null }
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
            override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
                if (tab !== cur) return
                setWebsiteFullscreen(fullScreen)
            }

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
            when {
                cur?.isPrivate == true -> Toast.makeText(
                    this, "Bookmarks are disabled in private tabs so this page is not saved.", Toast.LENGTH_LONG
                ).show()
                currentUrl.isNotBlank() -> {
                    bookmarks.add(currentUrl)
                    persistBookmarks()
                    Toast.makeText(this, "Bookmark saved on this device", Toast.LENGTH_SHORT).show()
                    showBookmarks()
                }
                else -> Toast.makeText(this, "Open a page first", Toast.LENGTH_SHORT).show()
            }
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

    /** Prefer an actual audio/video MIME type; ignore generic octet-stream when a filename is known. */
    private fun resolvePlaybackMime(name: String?, uri: Uri, rawMime: String?): String? {
        val mime = rawMime?.substringBefore(';')?.trim()?.lowercase(java.util.Locale.ROOT)
        if (mime != null && (
                mime.startsWith("video/") || mime.startsWith("audio/") ||
                    mime == "application/vnd.apple.mpegurl" ||
                    mime == "application/x-mpegurl" ||
                    mime == "audio/mpegurl" ||
                    mime == "application/dash+xml"
            )
        ) return mime
        val sourceName = name?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment.orEmpty()
        val extension = sourceName.substringBefore('?').substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)
        return android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
    }

    private fun playMedia(uri: Uri, name: String?, mimeType: String? = null) {
        val playbackMime = resolvePlaybackMime(name, uri, mimeType)
        startActivity(Intent(this, PlayerActivity::class.java).apply {
            data = uri
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra("name", name)
            if (!playbackMime.isNullOrBlank()) putExtra("mime_type", playbackMime)
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

    private fun startUrlDownload(
        url: String,
        isPrivate: Boolean,
        privateFolder: Boolean = false,
        suggestedFileName: String? = null
    ) {
        requestDownloadNotifications()
        downloads.enqueueUrl(url, isPrivate, privateFolder, suggestedFileName)
        Toast.makeText(this, if (privateFolder || isPrivate) "Saving to the private folder" else "Download started", Toast.LENGTH_SHORT).show()
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
        header.addView(iconButton(Icons.PLUS, "Download from a link") { showLinkDownloadSheet() },
            lp(dp(44), dp(44)).apply { rightMargin = dp(10) })
        header.addView(iconButton(Icons.PLAY, "Play a local video or audio file", Palette.ACCENT, filled = true) {
            mediaPicker.launch(arrayOf("video/*", "audio/*"))
        }, lp(dp(44), dp(44)).apply { rightMargin = dp(10) })
        if (downloads.items.any { it.state == DlState.DONE || it.state == DlState.FAILED }) {
            header.addView(iconButton(Icons.TRASH, "Clear finished downloads") { downloads.clearFinished() })
        }
        page.addView(header, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(22)
        })
        val privateCount = privateFolderFiles().size +
            downloads.items.count { it.inPrivateFolder && it.state != DlState.DONE }
        page.addView(
            listRow(
                well(icon(Icons.LOCK, Palette.ACCENT, 20)), "Private folder",
                if (privateCount == 0) "Hidden downloads, opened with your screen lock"
                else "$privateCount item(s), opened with your screen lock",
                null
            ) { requestPrivateFolder() },
            lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(16) }
        )
        val publicItems = downloads.items.toList().filter { !it.inPrivateFolder }
        if (publicItems.isEmpty()) {
            page.addView(emptyState(
                Icons.DOWNLOAD, "No downloads yet",
                "Files you download appear here. Downloads run while Veyra is open and are saved to Downloads/Veyra."
            ))
        } else publicItems.forEach { item ->
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
                val kind = MediaDetect.kindFromExtension(item.name)
                val playable = DownloadNaming.isMediaMime(item.mime) ||
                    kind == DetectedMedia.Kind.VIDEO || kind == DetectedMedia.Kind.AUDIO
                if (playable && item.savedUri != null) {
                    playMedia(Uri.parse(item.savedUri), item.name, item.mime)
                    return@setOnClickListener
                }
                val intent = downloads.openIntent(item)
                try { if (intent != null) startActivity(intent) else throw IllegalStateException() }
                catch (_: Exception) { Toast.makeText(this@MainActivity, "No app can open this file", Toast.LENGTH_SHORT).show() }
            }
        }
        if (item.state == DlState.DONE) card.setOnLongClickListener { showDownloadActions(item); true }
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

    /** Small preview of the wallpaper a theme puts on the home screen. */
    private fun wallpaperThumb(theme: VeyraTheme) = View(this).apply {
        background = WallpaperDrawable(theme)
        clipToOutline = true
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, dpf(10f))
            }
        }
        layoutParams = LinearLayout.LayoutParams(dp(36), dp(52)).apply { leftMargin = dp(4) }
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
                if (preview != null) addView(wallpaperThumb(preview))
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
        actionRow(Icons.SETTINGS, "Website appearance", "Light, dark or Android system page colors") { showWebsiteAppearance() }
        actionRow(Icons.LOCK, "Private Space", "Encrypted notes and files") { show("private") }
        actionRow(Icons.SHIELD, "VPN", vpnSubtitle()) { show("vpn") }
        actionRow(Icons.CLOCK, "History", "Pages visited in normal tabs") { show("history") }
        actionRow(Icons.TRASH, "Clear browsing data", "Cookies, cache, site data and history") { confirmClearData() }
        actionRow(Icons.INFO, "About Veyra", "Version, engine and licence") { showAbout() }
        addMainNav("settings")
    }

    // ---- private folder & link download -------------------------------------------------

    private fun privateFolderFiles(): List<java.io.File> =
        DownloadCenter.privateDir(this).listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    private fun requestPrivateFolder() {
        val intent = getSystemService(KeyguardManager::class.java)?.createConfirmDeviceCredentialIntent(
            "Unlock private folder", "Verify your screen lock to open your private downloads"
        )
        if (intent == null) {
            Toast.makeText(this, "Set a PIN, pattern or password in Android Settings to use the private folder.", Toast.LENGTH_LONG).show()
            try { startActivity(Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS)) } catch (_: Exception) { }
            return
        }
        folderUnlock.launch(intent)
    }

    private fun showPrivateFolder() {
        resetContent("pfolder")
        if (!privateFolderUnlocked) { show("downloads"); return }
        val page = scrollPage()
        val header = row().apply { minimumHeight = dp(48) }
        header.addView(iconButton(Icons.BACK, "Back") { show("downloads") })
        header.addView(label("Private folder", 22f, Palette.TEXT, 1).apply { setPadding(dp(16), 0, 0, 0) })
        page.addView(header, matchWrap().apply { bottomMargin = dp(18) })
        page.addView(label(
            "Files here are hidden from Gallery and file managers, and Veyra asks for your screen lock to open this folder. " +
                "They are stored in Veyra's private storage, not encrypted, and are removed if you uninstall Veyra.",
            12f, Palette.MUTED
        ).apply { setLineSpacing(0f, 1.2f); setPadding(dp(4), 0, dp(4), dp(16)) }, matchWrap())
        val running = downloads.items.toList().filter { it.inPrivateFolder && it.state != DlState.DONE }
        running.forEach { page.addView(downloadCard(it), matchWrap().apply { bottomMargin = dp(16) }) }
        val files = privateFolderFiles()
        if (files.isEmpty() && running.isEmpty()) {
            page.addView(emptyState(
                Icons.LOCK, "Nothing here yet",
                "Download a supported media source, then long-press the completed download and choose Move to private folder."
            ))
        }
        files.forEach { file ->
            val fileMime = downloads.items.firstOrNull { it.inPrivateFolder && it.name == file.name }?.mime
                ?: android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
            val media = DownloadNaming.isMediaMime(fileMime) ||
                MediaDetect.kindFromExtension(file.name).let { it == DetectedMedia.Kind.VIDEO || it == DetectedMedia.Kind.AUDIO }
            val delete = iconButton(Icons.TRASH, "Delete ${file.name}") {
                showConfirmSheet("Delete this file?", file.name, "Delete") {
                    file.delete()
                    downloads.items.toList().filter { it.inPrivateFolder && it.name == file.name }
                        .forEach { downloads.delete(it, false) }
                    showPrivateFolder()
                }
            }
            page.addView(listRow(
                well(icon(if (media) Icons.PLAY else Icons.FILE, Palette.ACCENT, 20)),
                file.name, DownloadNaming.formatBytes(file.length()), delete
            ) {
                val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", file)
                if (media) playMedia(uri, file.name, fileMime)
                else try {
                    startActivity(Intent(Intent.ACTION_VIEW).apply {
                        data = uri
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    })
                } catch (_: Exception) {
                    Toast.makeText(this, "No app can open this file", Toast.LENGTH_SHORT).show()
                }
            }.also { row -> row.setOnLongClickListener { showPrivateFileActions(file); true } },
                matchWrap().apply { bottomMargin = dp(16) })
        }
        addMainNav("downloads")
    }

    private fun shareUri(uri: Uri, mime: String?) {
        try {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = mime?.takeIf { it.isNotBlank() } ?: "*/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "Share"))
        } catch (_: Exception) {
            Toast.makeText(this, "Nothing can receive this file", Toast.LENGTH_SHORT).show()
        }
    }

    /** One row inside the long-press action sheets. */
    private fun sheetAction(paths: List<String>, text: String, danger: Boolean = false, onClick: () -> Unit): View =
        row().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(14), dp(8), dp(14))
            isClickable = true
            setOnClickListener { onClick() }
            addView(icon(paths, if (danger) 0xFFFF6B6B.toInt() else Palette.ACCENT, 20))
            addView(label(text, 15f, if (danger) 0xFFFF6B6B.toInt() else Palette.TEXT, 1).apply { setPadding(dp(16), 0, 0, 0) })
        }

    private fun showRenameSheet(current: String, onRename: (String) -> String?, onDone: () -> Unit) {
        showSheet { sheet, dialog ->
            sheet.addView(label("Rename", 18f, Palette.TEXT, 1), matchWrap())
            val input = EditText(this).apply {
                setText(current)
                setSelection(current.substringBeforeLast('.', current).length)
                setTextColor(Palette.TEXT)
                textSize = 15f
                setSingleLine(true)
                setPadding(dp(20), 0, dp(20), 0)
                neu(this, 26, Depth.FIELD, inset = true)
            }
            sheet.addView(input, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(16); bottomMargin = dp(18) })
            val actions = row().apply { gravity = Gravity.END }
            actions.addView(textButton("Cancel") { dialog.dismiss() })
            actions.addView(textButton("Save", primary = true) {
                val error = onRename(input.text.toString())
                if (error == null) { dialog.dismiss(); onDone() }
                else Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
            }, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(16) })
            sheet.addView(actions, matchWrap())
            input.post { input.requestFocus() }
        }
    }

    /** Long-press menu for a finished download. */
    private fun showDownloadActions(item: DownloadItem) {
        showSheet { sheet, dialog ->
            sheet.addView(label(item.name, 16f, Palette.TEXT, 1).apply {
                maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            }, matchWrap())
            sheet.addView(label(DownloadNaming.formatBytes(item.total), 12f, Palette.MUTED).apply { setPadding(0, dp(4), 0, dp(10)) }, matchWrap())
            val uri = item.savedUri?.let { Uri.parse(it) }
            val playable = DownloadNaming.isMediaMime(item.mime) || MediaDetect.kindFromExtension(item.name)
                .let { it == DetectedMedia.Kind.VIDEO || it == DetectedMedia.Kind.AUDIO }
            if (uri != null && playable) sheet.addView(sheetAction(Icons.PLAY, "Play in Veyra") {
                dialog.dismiss(); playMedia(uri, item.name, item.mime)
            }, matchWrap())
            if (uri != null) sheet.addView(sheetAction(Icons.SHARE, "Share") { dialog.dismiss(); shareUri(uri, item.mime) }, matchWrap())
            sheet.addView(sheetAction(Icons.EDIT, "Rename") {
                dialog.dismiss()
                showRenameSheet(item.name, { downloads.rename(item, it) }) { }
            }, matchWrap())
            if (!item.inPrivateFolder && !item.isPrivate) sheet.addView(sheetAction(Icons.LOCK, "Move to private folder") {
                dialog.dismiss()
                Thread {
                    val ok = downloads.moveToPrivate(item)
                    runOnUiThread {
                        Toast.makeText(this, if (ok) "Moved to private folder" else "Could not move this file", Toast.LENGTH_SHORT).show()
                        if (currentPage == "downloads") refreshDownloads()
                    }
                }.start()
            }, matchWrap())
            if (item.inPrivateFolder && !item.isPrivate) sheet.addView(sheetAction(Icons.DOWNLOAD, "Move out of private folder") {
                dialog.dismiss()
                Thread {
                    val file = java.io.File(DownloadCenter.privateDir(this), item.name)
                    val ok = downloads.moveOutOfPrivate(file)
                    runOnUiThread {
                        Toast.makeText(this, if (ok) "Moved to Downloads/Veyra" else "Could not move this file", Toast.LENGTH_SHORT).show()
                        if (currentPage == "pfolder") showPrivateFolder()
                        else if (currentPage == "downloads") refreshDownloads()
                    }
                }.start()
            }, matchWrap())
            sheet.addView(sheetAction(Icons.INFO, "File details") {
                dialog.dismiss()
                showDownloadDetails(item)
            }, matchWrap())
            sheet.addView(sheetAction(Icons.TRASH, "Delete", danger = true) {
                dialog.dismiss()
                showConfirmSheet("Delete this file?", item.name, "Delete") { downloads.delete(item, deleteFile = true) }
            }, matchWrap())
        }
    }

    /** Long-press menu for a file inside the private folder. */
    private fun showPrivateFileActions(file: java.io.File) {
        showSheet { sheet, dialog ->
            sheet.addView(label(file.name, 16f, Palette.TEXT, 1).apply {
                maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            }, matchWrap())
            sheet.addView(label(DownloadNaming.formatBytes(file.length()), 12f, Palette.MUTED).apply { setPadding(0, dp(4), 0, dp(10)) }, matchWrap())
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", file)
            val itemMime = downloads.items.firstOrNull { it.inPrivateFolder && it.name == file.name }?.mime
                ?: android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
            val kind = MediaDetect.kindFromExtension(file.name)
            val playable = DownloadNaming.isMediaMime(itemMime) ||
                kind == DetectedMedia.Kind.VIDEO || kind == DetectedMedia.Kind.AUDIO
            sheet.addView(sheetAction(if (playable) Icons.PLAY else Icons.FILE, "Open / Play") {
                dialog.dismiss()
                if (playable) playMedia(uri, file.name, itemMime)
                else try {
                    startActivity(Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, itemMime ?: "*/*")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    })
                } catch (_: Exception) {
                    Toast.makeText(this, "No app can open this file", Toast.LENGTH_SHORT).show()
                }
            }, matchWrap())
            sheet.addView(sheetAction(Icons.SHARE, "Share") {
                dialog.dismiss()
                shareUri(uri, itemMime)
            }, matchWrap())
            sheet.addView(sheetAction(Icons.EDIT, "Rename") {
                dialog.dismiss()
                showRenameSheet(file.name, { downloads.renamePrivateFile(file, it) }) { showPrivateFolder() }
            }, matchWrap())
            if (!downloads.items.any { it.inPrivateFolder && it.name == file.name && it.isPrivate }) {
                sheet.addView(sheetAction(Icons.DOWNLOAD, "Move out of private folder") {
                    dialog.dismiss()
                    Thread {
                        val ok = downloads.moveOutOfPrivate(file)
                        runOnUiThread {
                            Toast.makeText(this, if (ok) "Moved to Downloads/Veyra" else "Could not move this file", Toast.LENGTH_SHORT).show()
                            if (currentPage == "pfolder") showPrivateFolder()
                        }
                    }.start()
                }, matchWrap())
            }
            sheet.addView(sheetAction(Icons.INFO, "File details") {
                dialog.dismiss()
                showPrivateFileDetails(file)
            }, matchWrap())
            sheet.addView(sheetAction(Icons.TRASH, "Delete", danger = true) {
                dialog.dismiss()
                showConfirmSheet("Delete this file?", file.name, "Delete") {
                    file.delete()
                    downloads.items.toList().filter { it.inPrivateFolder && it.name == file.name }
                        .forEach { downloads.delete(it, false) }
                    showPrivateFolder()
                }
            }, matchWrap())
        }
    }

    private fun showDownloadDetails(item: DownloadItem) {
        showSheet { sheet ->
            sheet.addView(label("File details", 18f, Palette.TEXT, 1), matchWrap())
            val modified = runCatching {
                val localDir = if (item.inPrivateFolder) DownloadCenter.privateDir(this)
                    else java.io.File(getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS), "Veyra")
                val local = java.io.File(localDir, item.name)
                if (local.isFile) java.text.DateFormat.getDateTimeInstance().format(java.util.Date(local.lastModified()))
                else {
                    val uri = item.savedUri?.let { Uri.parse(it) }
                    val value = if (uri != null && uri.scheme == "content") {
                        contentResolver.query(uri, arrayOf(android.provider.MediaStore.MediaColumns.DATE_MODIFIED), null, null, null)
                            ?.use { cursor ->
                                if (cursor.moveToFirst()) cursor.getLong(0).takeIf { it > 0 }?.times(1000L) else null
                            }
                    } else null
                    value?.let { java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it)) } ?: "Unknown"
                }
            }.getOrDefault("Unknown")
            val fields = listOf(
                "Name" to item.name,
                "Size" to DownloadNaming.formatBytes(item.total),
                "Type" to (item.mime ?: "Unknown"),
                "Status" to item.state.name.lowercase().replaceFirstChar { it.uppercase() },
                "Location" to if (item.inPrivateFolder) "Veyra Private folder (app storage)" else "Downloads/Veyra",
                "Last modified" to modified,
                "Source" to hostOf(item.url).ifBlank { "Local file" }
            )
            fields.forEach { (name, value) ->
                sheet.addView(label(name, 11f, Palette.MUTED).apply { setPadding(0, dp(10), 0, dp(2)) }, matchWrap())
                sheet.addView(label(value, 14f, Palette.TEXT).apply {
                    maxLines = 3
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }, matchWrap())
            }
        }
    }

    private fun showPrivateFileDetails(file: java.io.File) {
        showSheet { sheet ->
            sheet.addView(label("File details", 18f, Palette.TEXT, 1), matchWrap())
            val mime = downloads.items.firstOrNull { it.inPrivateFolder && it.name == file.name }?.mime
                ?: android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
                ?: "Unknown"
            val fields = listOf(
                "Name" to file.name,
                "Size" to DownloadNaming.formatBytes(file.length()),
                "Type" to mime,
                "Location" to "Veyra Private folder (app storage, not encrypted)",
                "Last modified" to java.text.DateFormat.getDateTimeInstance().format(java.util.Date(file.lastModified()))
            )
            fields.forEach { (name, value) ->
                sheet.addView(label(name, 11f, Palette.MUTED).apply { setPadding(0, dp(10), 0, dp(2)) }, matchWrap())
                sheet.addView(label(value, 14f, Palette.TEXT).apply {
                    maxLines = 3
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }, matchWrap())
            }
        }
    }

    private val streamingHosts = listOf(
        "youtube.com", "youtu.be", "netflix.com", "primevideo.com", "hotstar.com", "jiocinema.com",
        "spotify.com", "disneyplus.com", "sonyliv.com", "zee5.com"
    )

    private data class LinkInspection(
        val statusCode: Int,
        val mimeType: String?,
        val contentLength: Long,
        val finalUrl: String,
        val error: String? = null,
        val suggestedFileName: String? = null
    )

    /** Inspect metadata in the background without reading the full media body into memory. */
    private fun inspectLink(url: String): LinkInspection {
        var connection: java.net.HttpURLConnection? = null
        try {
            val head = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "HEAD"
                connectTimeout = 10_000
                readTimeout = 12_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Veyra")
                setRequestProperty("Accept", "*/*")
            }
            connection = head
            var status = head.responseCode
            var mime = head.contentType?.substringBefore(';')?.trim()
            var length = head.contentLengthLong
            var finalUrl = head.url.toString()
            var disposition = head.getHeaderField("Content-Disposition")
            val unsupportedRedirect = !DownloadNaming.isHttpUrl(finalUrl)
            if (unsupportedRedirect) {
                return LinkInspection(status, mime, length, finalUrl, "This link redirects to an unsupported URL type.")
            }
            val finalHost = hostOf(finalUrl).lowercase()
            if (streamingHosts.any { finalHost == it || finalHost.endsWith(".$it") }) {
                return LinkInspection(status, mime, length, finalUrl, "This website must use its original player. Veyra will not extract protected streams.")
            }

            // Some legitimate file servers reject HEAD or omit its metadata. Probe one byte only.
            val needsProbe = status == 405 || status == 501 || (status in 200..299 && mime.isNullOrBlank())
            connection.disconnect()
            connection = null
            if (needsProbe) {
                val probe = (java.net.URL(finalUrl).openConnection() as java.net.HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 10_000
                    readTimeout = 12_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "Veyra")
                    setRequestProperty("Accept", "*/*")
                    setRequestProperty("Range", "bytes=0-0")
                }
                connection = probe
                status = probe.responseCode
                mime = probe.contentType?.substringBefore(';')?.trim()
                disposition = probe.getHeaderField("Content-Disposition") ?: disposition
                val rangeTotal = probe.getHeaderField("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                length = rangeTotal ?: probe.contentLengthLong
                finalUrl = probe.url.toString()
                if (status in 200..299) {
                    // Never consume the whole response if a server ignores Range.
                    runCatching { probe.inputStream.use { it.read() } }
                }
            }
            if (!DownloadNaming.isHttpUrl(finalUrl)) {
                return LinkInspection(status, mime, length, finalUrl, "This link redirects to an unsupported URL type.")
            }
            if (status !in 200..299) {
                return LinkInspection(status, mime, length, finalUrl, "The server replied with HTTP $status. The link may have expired or require sign-in.")
            }
            val suggestedName = disposition?.takeIf { it.isNotBlank() }?.let { DownloadNaming.fileName(it, finalUrl) }
            return LinkInspection(status, mime, length, finalUrl, suggestedFileName = suggestedName)
        } catch (_: Exception) {
            return LinkInspection(-1, null, -1, url, "Could not inspect this link. Check the connection and the URL.")
        } finally {
            connection?.disconnect()
        }
    }

    /** Classify pasted links after redirects and HTTP metadata have been checked. */
    private fun showLinkDownloadSheet() {
        showSheet { sheet, dialog ->
            sheet.addView(label("Download from a link", 18f, Palette.TEXT, 1), matchWrap())
            sheet.addView(label(
                "Paste a direct video, audio or file link. Veyra checks redirects and media metadata first. " +
                    "Ordinary webpages open in the browser; protected streaming services must use their own player.",
                13f, Palette.MUTED
            ).apply { setLineSpacing(0f, 1.2f); setPadding(0, dp(8), 0, dp(12)) }, matchWrap())

            val input = EditText(this).apply {
                hint = "https://"
                setHintTextColor(Palette.MUTED)
                setTextColor(Palette.TEXT)
                textSize = 15f
                setSingleLine(true)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                setPadding(dp(20), 0, dp(20), 0)
                neu(this, 26, Depth.FIELD, inset = true)
            }
            sheet.addView(input, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { bottomMargin = dp(10) })

            val statusLabel = label("", 12f, Palette.MUTED).apply {
                visibility = View.GONE
                setLineSpacing(0f, 1.15f)
            }
            sheet.addView(statusLabel, matchWrap().apply { bottomMargin = dp(12) })

            var checking = false
            val actionButtons = ArrayList<TextView>(2)
            fun setChecking(value: Boolean) {
                checking = value
                actionButtons.forEach { button ->
                    button.isEnabled = !value
                    button.alpha = if (value) 0.55f else 1f
                }
                if (value) statusLabel.text = "Checking redirects and media type…"
                statusLabel.visibility = if (value || statusLabel.text.isNotBlank()) View.VISIBLE else View.GONE
            }

            fun resolveUrl(): String? {
                val raw = input.text.toString().trim()
                return when {
                    DownloadNaming.isHttpUrl(raw) -> raw
                    raw.contains('.') && !raw.contains(' ') -> "https://$raw"
                    else -> null
                }
            }

            fun runAction(playOnly: Boolean) {
                if (checking) return
                val url = resolveUrl()
                if (url == null) {
                    statusLabel.text = "Paste a valid HTTP(S) link."
                    statusLabel.visibility = View.VISIBLE
                    return
                }
                val initialHost = hostOf(url).lowercase()
                if (streamingHosts.any { initialHost == it || initialHost.endsWith(".$it") }) {
                    statusLabel.text = "This protected website must use its original player. Veyra will not extract its streams."
                    statusLabel.visibility = View.VISIBLE
                    return
                }

                setChecking(true)
                Thread {
                    val result = inspectLink(url)
                    runOnUiThread {
                        if (!dialog.isShowing) return@runOnUiThread
                        setChecking(false)
                        if (result.error != null) {
                            statusLabel.text = result.error
                            statusLabel.visibility = View.VISIBLE
                            return@runOnUiThread
                        }

                        val finalHost = hostOf(result.finalUrl).lowercase()
                        if (streamingHosts.any { finalHost == it || finalHost.endsWith(".$it") }) {
                            statusLabel.text = "The link redirects to a protected streaming service. Use its website or official app."
                            statusLabel.visibility = View.VISIBLE
                            return@runOnUiThread
                        }

                        val finalUrl = result.finalUrl
                        val mime = result.mimeType?.substringBefore(';')?.trim()
                        val kind = MediaDetect.kindFromExtension(finalUrl) ?: MediaDetect.kindFromMime(mime.orEmpty())
                        val htmlPage = mime.equals("text/html", ignoreCase = true) ||
                            mime.equals("application/xhtml+xml", ignoreCase = true)
                        val uri = Uri.parse(finalUrl)
                        val playbackMime = mime ?: when (MediaDetect.kindFromExtension(finalUrl)) {
                            DetectedMedia.Kind.STREAM ->
                                if (MediaDetect.extensionOf(finalUrl) == "mpd") "application/dash+xml"
                                else "application/vnd.apple.mpegurl"
                            else -> null
                        }

                        when {
                            htmlPage -> {
                                if (playOnly) {
                                    statusLabel.text = "This link is a webpage, not a direct media file. Open it in Veyra and use Media on this page."
                                    statusLabel.visibility = View.VISIBLE
                                } else {
                                    dialog.dismiss()
                                    navigate(finalUrl)
                                }
                            }
                            kind == DetectedMedia.Kind.STREAM -> {
                                if (playOnly) {
                                    dialog.dismiss()
                                    playMedia(uri, null, playbackMime)
                                } else {
                                    statusLabel.text = "This is a segmented stream, not a single downloadable file. Use Play / Stream when the source permits it."
                                    statusLabel.visibility = View.VISIBLE
                                }
                            }
                            kind == DetectedMedia.Kind.VIDEO || kind == DetectedMedia.Kind.AUDIO -> {
                                dialog.dismiss()
                                if (playOnly) playMedia(uri, null, playbackMime)
                                else startUrlDownload(
                                    finalUrl,
                                    cur?.isPrivate == true,
                                    suggestedFileName = result.suggestedFileName
                                )
                            }
                            !playOnly && !mime.isNullOrBlank() && !htmlPage -> {
                                // A known non-HTML MIME type is a direct resource even without an extension.
                                dialog.dismiss()
                                startUrlDownload(
                                    finalUrl,
                                    cur?.isPrivate == true,
                                    suggestedFileName = result.suggestedFileName
                                )
                            }
                            !playOnly && DownloadNaming.looksLikeDirectFile(finalUrl) -> {
                                dialog.dismiss()
                                startUrlDownload(
                                    finalUrl,
                                    cur?.isPrivate == true,
                                    suggestedFileName = result.suggestedFileName
                                )
                            }
                            else -> {
                                if (playOnly) {
                                    statusLabel.text = "Veyra couldn't confirm a direct media file at this URL. Open the page first, then use Media on this page."
                                    statusLabel.visibility = View.VISIBLE
                                } else {
                                    dialog.dismiss()
                                    navigate(finalUrl)
                                }
                            }
                        }
                    }
                }.apply { name = "veyra-link-inspector"; isDaemon = true }.start()
            }

            val mainActions = row().apply { gravity = Gravity.CENTER }
            val playButton = textButton("Play / Stream", primary = true) { runAction(playOnly = true) }
            val downloadButton = textButton("Download") { runAction(playOnly = false) }
            actionButtons.add(playButton)
            actionButtons.add(downloadButton)
            mainActions.addView(playButton, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            mainActions.addView(downloadButton, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(10) })
            sheet.addView(mainActions, matchWrap())

            val secondaryActions = row().apply { gravity = Gravity.END }
            secondaryActions.addView(textButton("Cancel") { dialog.dismiss() },
                lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            sheet.addView(secondaryActions, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(10)
            })
        }
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

    /** One tap: create a free WARP profile on this device, then ask for VPN permission and connect. */
    private fun setupFreeVpnAndConnect() {
        Toast.makeText(this, "Setting up free VPN…", Toast.LENGTH_SHORT).show()
        VpnManager.get(this).provisionFree { error ->
            if (error == null) startVpn()
            else Toast.makeText(this, error, Toast.LENGTH_LONG).show()
            if (currentPage == "vpn") showVpn()
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
        showSaveVpnProfileSheet(text)
    }

    private fun showSaveVpnProfileSheet(configText: String) {
        val endpoint = VpnConfigSummary.of(configText).endpoints.firstOrNull().orEmpty()
        val host = endpoint.substringBefore(':').lowercase()
        val suggestedName = when {
            Regex("""(^|[.-])(us|usa|unitedstates)([.-]|$)""").containsMatchIn(host) -> "United States"
            Regex("""(^|[.-])(nl|nld|netherlands)([.-]|$)""").containsMatchIn(host) -> "Netherlands"
            host.isNotBlank() -> host.take(36)
            else -> "WireGuard server"
        }
        showSheet { sheet, dialog ->
            sheet.addView(label("Save VPN server profile", 18f, Palette.TEXT, 1), matchWrap())
            sheet.addView(label(
                "Name this imported server (for example United States or Netherlands). The endpoint and private key stay in app storage; the config is encrypted with Android Keystore.",
                13f, Palette.MUTED
            ).apply { setLineSpacing(0f, 1.2f); setPadding(0, dp(8), 0, dp(14)) }, matchWrap())
            val nameInput = EditText(this).apply {
                setText(suggestedName)
                setSingleLine(true)
                setTextColor(Palette.TEXT)
                setHintTextColor(Palette.MUTED)
                textSize = 15f
                setPadding(dp(18), 0, dp(18), 0)
                neu(this, 24, Depth.FIELD, inset = true)
            }
            sheet.addView(nameInput, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { bottomMargin = dp(18) })
            val actions = row().apply { gravity = Gravity.END }
            actions.addView(textButton("Cancel") { dialog.dismiss() })
            actions.addView(textButton("Save profile", primary = true) {
                try {
                    val profile = VpnManager.get(this).saveProfile(configText, nameInput.text.toString())
                    dialog.dismiss()
                    Toast.makeText(
                        this,
                        if (profile.active) "Profile saved and selected: ${profile.name}"
                        else "Profile saved. Disconnect the active VPN before switching to it.",
                        Toast.LENGTH_LONG
                    ).show()
                    if (currentPage == "vpn") showVpn()
                } catch (e: Exception) {
                    Toast.makeText(this, e.message ?: "Could not save this WireGuard profile", Toast.LENGTH_LONG).show()
                }
            }, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(12) })
            sheet.addView(actions, matchWrap())
        }
    }

    private fun showFreeVpnProvidersSheet() {
        showSheet { sheet, dialog ->
            sheet.addView(label("Free VPN locations", 18f, Palette.TEXT, 1), matchWrap())
            sheet.addView(label(
                "Veyra connects through a real WireGuard profile; it does not run VPN servers itself. Proton VPN lists free servers in the United States and Netherlands, but profile generation and country availability depend on its current account/plan rules. Choose your country on the provider page, download the .conf file, then import it in Veyra.",
                13f, Palette.MUTED
            ).apply { setLineSpacing(0f, 1.2f); setPadding(0, dp(8), 0, dp(14)) }, matchWrap())

            listOf(
                Triple("United States", "Choose a US server in Proton's WireGuard profile generator", "us"),
                Triple("Netherlands", "Choose a Netherlands server in Proton's WireGuard profile generator", "nl")
            ).forEach { (country, detail, _) ->
                sheet.addView(
                    listRow(
                        well(icon(Icons.SHIELD, Palette.ACCENT, 20)),
                        country,
                        detail,
                        icon(Icons.CHEVRON, Palette.MUTED, 18)
                    ) {
                        dialog.dismiss()
                        openExternalPage("https://account.protonvpn.com/downloads#wireguard-config")
                    },
                    lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }
                )
            }
            sheet.addView(
                listRow(
                    well(icon(Icons.INFO, Palette.ACCENT, 20)),
                    "Free server locations and limits",
                    "Check current Proton VPN Free availability",
                    icon(Icons.CHEVRON, Palette.MUTED, 18)
                ) {
                    dialog.dismiss()
                    openExternalPage("https://protonvpn.com/support/change-server-free-plan")
                },
                lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(14) }
            )
            sheet.addView(label(
                "Veyra won't invent credentials or report a country connection before the imported tunnel is actually up. Switching locations requires importing/selecting the corresponding profile and disconnecting before switching.",
                12f, Palette.MUTED
            ).apply { setLineSpacing(0f, 1.2f) }, matchWrap())
        }
    }

    private fun openExternalPage(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        catch (_: Exception) { Toast.makeText(this, "Could not open the provider page", Toast.LENGTH_SHORT).show() }
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
        val vpnSummary = vpn.summary()
        val card = column().apply {
            setPadding(dp(22), dp(20), dp(22), dp(20))
            neu(this, 26, Depth.CARD)
        }
        card.addView(label("Status", 13f, Palette.MUTED))
        card.addView(label(
            when (status) {
                VpnManager.Status.ON -> "Connected"
                VpnManager.Status.CONNECTING -> "Connecting…"
                VpnManager.Status.DISCONNECTING -> "Disconnecting…"
                VpnManager.Status.ERROR -> "Connection failed"
                VpnManager.Status.OFF -> if (vpn.hasConfig()) "Off" else "No config yet"
            },
            26f, if (status == VpnManager.Status.ON) Palette.ACCENT else Palette.TEXT, 1
        ).apply { setPadding(0, dp(8), 0, dp(6)) })
        card.addView(label(
            when {
                status == VpnManager.Status.ON -> when {
                    vpnSummary != null && vpnSummary.routesAllTraffic && vpnSummary.dns.isNotEmpty() ->
                        "The tunnel is up and the profile includes default routes for both IPv4 and IPv6. A kill switch is not provided."
                    vpnSummary != null && vpnSummary.routesAllTraffic ->
                        "The profile routes IPv4 and IPv6 by default, but specifies no DNS server. DNS behavior may depend on Android and the network."
                    vpnSummary?.routesAllIpv4 == true ->
                        "The tunnel is up, but only IPv4 has a default route. IPv6 traffic may bypass the tunnel."
                    vpnSummary?.routesAllIpv6 == true ->
                        "The tunnel is up, but only IPv6 has a default route. IPv4 traffic may bypass the tunnel."
                    vpnSummary != null && vpnSummary.allowedIps.isNotEmpty() ->
                        "The tunnel is up, but only the profile's listed routes use it. Other traffic may bypass the tunnel."
                    else -> "The tunnel reports as up, but Veyra could not confirm its routing rules."
                }
                status == VpnManager.Status.DISCONNECTING -> "Disconnecting the tunnel and releasing its resources…"
                status == VpnManager.Status.ERROR -> vpn.lastError ?: "Something went wrong."
                vpn.hasConfig() -> "Tap Connect to start the tunnel."
                else -> "Tap Turn on free VPN for one-tap protection, or import a WireGuard profile for a specific country."
            },
            13f, Palette.MUTED
        ).apply { setLineSpacing(0f, 1.2f) })
        page.addView(card, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(18) })

        if (!vpn.hasConfig() && status != VpnManager.Status.CONNECTING) {
            page.addView(
                textButton("Turn on free VPN", primary = true) { setupFreeVpnAndConnect() },
                lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) }
            )
            page.addView(
                textButton("Choose a country (Proton profile)") { showFreeVpnProvidersSheet() },
                lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(18) }
            )
        }

        val countryNote = column().apply {
            setPadding(dp(18), dp(16), dp(18), dp(16))
            neu(this, 22, Depth.CARD)
            addView(label("Use a real provider profile", 15f, Palette.TEXT, 1))
            addView(label(
                "Turn on free VPN uses Cloudflare WARP: one tap, no account, but you cannot pick a country and Cloudflare's sign-up interface is unofficial, so it may stop working. For a specific country, import a WireGuard profile from a provider you trust.",
                13f, Palette.MUTED
            ).apply { setPadding(0, dp(8), 0, 0); setLineSpacing(0f, 1.15f) })
        }
        page.addView(countryNote, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(18) })

        val profiles = vpn.profiles()
        if (profiles.isNotEmpty()) {
            page.addView(label("SAVED SERVER PROFILES", 11f, Palette.MUTED, 1).apply {
                letterSpacing = 0.12f
                setPadding(dp(4), dp(4), 0, dp(10))
            }, matchWrap())
            profiles.forEach { profile ->
                val detail = buildString {
                    if (profile.active) append("Active profile · ")
                    append(profile.endpoint.ifBlank { "WireGuard endpoint not available" })
                }
                page.addView(
                    listRow(
                        well(icon(if (profile.active) Icons.SHIELD else Icons.SHIELD_OFF,
                            if (profile.active) Palette.ACCENT else Palette.MUTED, 20)),
                        profile.name,
                        detail,
                        icon(if (profile.active) Icons.SHIELD else Icons.CHEVRON, Palette.ACCENT, 18)
                    ) {
                        if (profile.active) return@listRow
                        if (vpn.status == VpnManager.Status.ON || vpn.status == VpnManager.Status.CONNECTING ||
                            vpn.status == VpnManager.Status.DISCONNECTING) {
                            Toast.makeText(this, "Disconnect the current tunnel before switching servers", Toast.LENGTH_LONG).show()
                        } else try {
                            vpn.selectProfile(profile.id)
                            Toast.makeText(this, "Selected ${profile.name}", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            Toast.makeText(this, e.message ?: "Could not select this server", Toast.LENGTH_LONG).show()
                        }
                    },
                    lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }
                )
            }
            page.addView(label(
                "Tap a saved profile to select it. Disconnect first before switching servers. Profiles are encrypted locally; Veyra connects only to the endpoints in the imported config.",
                12f, Palette.MUTED
            ).apply { setPadding(dp(4), dp(2), dp(4), dp(14)); setLineSpacing(0f, 1.2f) }, matchWrap())
        }

        if (vpn.hasConfig()) {
            val busy = status == VpnManager.Status.CONNECTING || status == VpnManager.Status.DISCONNECTING
            val on = status == VpnManager.Status.ON
            val actionLabel = when {
                on -> "Disconnect"
                status == VpnManager.Status.DISCONNECTING -> "Disconnecting…"
                status == VpnManager.Status.CONNECTING -> "Connecting…"
                else -> "Connect"
            }
            page.addView(
                textButton(actionLabel, primary = !on) {
                    if (busy) return@textButton
                    if (on) vpn.disconnect() else startVpn()
                },
                lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(18) }
            )
            val summary = vpnSummary
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
        actions.addView(textButton(if (vpn.hasConfig()) "Import another profile" else "Import WireGuard profile", primary = !vpn.hasConfig()) {
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
            page.addView(textButton("Remove active profile") {
                val active = vpn.profiles().firstOrNull { it.active }
                showConfirmSheet(
                    "Remove active VPN profile?",
                    "The encrypted active config will be removed. Other saved profiles remain available.",
                    "Remove"
                ) {
                    try {
                        if (active != null) vpn.deleteProfile(active.id) else vpn.deleteConfig()
                        show("vpn")
                    } catch (e: Exception) {
                        Toast.makeText(this, e.message ?: "Disconnect the VPN before removing it", Toast.LENGTH_LONG).show()
                    }
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
                    if (tab === cur) updateMediaBanner()
                }
                return null
            }
        }, "veyraMedia")
    }

    private fun showMediaSheet() {
        val tab = cur ?: return
        showSheet { sheet, dialog ->
            sheet.addView(label("Choose video quality", 18f, Palette.TEXT, 1), matchWrap())
            sheet.addView(label(
                "Select one detected media source, then download it. Quality is shown only when Veyra can infer it from page metadata or the URL; unknown quality is labelled honestly. Protected streams stay in the website player.",
                13f, Palette.MUTED
            ).apply { setLineSpacing(0f, 1.2f); setPadding(0, dp(8), 0, dp(14)) }, matchWrap())

            val candidates = tab.media.filter { it.downloadable }
            var selected: DetectedMedia? = candidates.firstOrNull()
            val list = column().apply { setPadding(dp(6), dp(4), dp(6), dp(4)) }
            fun renderOptions() {
                list.removeAllViews()
                candidates.forEach { media ->
                    val isSelected = selected?.url == media.url
                    val card = column().apply {
                        setPadding(dp(14), dp(13), dp(14), dp(13))
                        neu(this, 18, if (isSelected) Depth.WELL else Depth.BUTTON)
                        isClickable = true
                        isFocusable = true
                        contentDescription = "${media.qualityLabel}, ${media.fileName}"
                        setOnClickListener {
                            selected = media
                            renderOptions()
                        }
                    }
                    val headline = row()
                    headline.addView(icon(
                        if (media.kind == DetectedMedia.Kind.AUDIO) Icons.FILE else Icons.PLAY,
                        if (isSelected) Palette.ACCENT else Palette.MUTED, 18
                    ))
                    headline.addView(label(media.qualityLabel, 14f, Palette.TEXT, 1).apply {
                        setPadding(dp(10), 0, 0, 0)
                    }, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    if (isSelected) headline.addView(label("SELECTED", 9f, Palette.ACCENT, 1))
                    card.addView(headline, matchWrap())
                    card.addView(label(
                        "${media.title.ifBlank { media.fileName }} · ${hostOf(media.url)}",
                        12f, Palette.MUTED
                    ).apply {
                        maxLines = 2
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        setPadding(dp(28), dp(5), 0, 0)
                    }, matchWrap())
                    list.addView(card, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = dp(10)
                    })
                }
            }
            if (candidates.isEmpty()) {
                list.addView(label(
                    if (tab.unsupportedMedia > 0 || tab.media.any { it.kind == DetectedMedia.Kind.STREAM })
                        "No direct downloadable file was found. This video may use a protected or segmented stream; keep using the website's own player."
                    else
                        "No downloadable video source was detected yet. Start the video on the page and open this menu again.",
                    13f, Palette.MUTED
                ).apply { setLineSpacing(0f, 1.2f); setPadding(dp(6), dp(8), dp(6), dp(8)) }, matchWrap())
            } else {
                renderOptions()
            }
            val scroller = ScrollView(this).apply {
                clipChildren = false; clipToPadding = false
                overScrollMode = View.OVER_SCROLL_NEVER
                addView(list)
            }
            sheet.addView(scroller, lp(
                ViewGroup.LayoutParams.MATCH_PARENT,
                if (candidates.size > 3) dp(300) else ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            if (tab.unsupportedMedia > 0) {
                sheet.addView(label(
                    "${tab.unsupportedMedia} browser-managed source(s) cannot be saved as an ordinary file. Veyra does not extract DRM-protected media.",
                    12f, Palette.MUTED
                ).apply { setLineSpacing(0f, 1.2f); setPadding(0, dp(10), 0, 0) }, matchWrap())
            }
            if (candidates.isNotEmpty()) {
                sheet.addView(textButton("Download", primary = true) {
                    val media = selected ?: return@textButton
                    dialog.dismiss()
                    startUrlDownload(media.url, tab.isPrivate, suggestedFileName = media.fileName)
                }, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })
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

    private fun showBrowserMenu(anchor: View) {
        showSideSheet(anchor) { sheet, popup ->
            fun item(paths: List<String>, text: String, action: () -> Unit) {
                val rowView = row().apply {
                    setPadding(dp(12), dp(6), dp(12), dp(6))
                    minimumHeight = dp(44)
                    neu(this, 16, Depth.BUTTON, pressable = true)
                    setOnClickListener { popup.dismiss(); action() }
                }
                rowView.addView(icon(paths, Palette.ACCENT, 18))
                rowView.addView(label(text, 14f, Palette.TEXT, 1).apply { setPadding(dp(10), 0, 0, 0) })
                sheet.addView(rowView, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { bottomMargin = dp(6) })
            }
            item(Icons.BOOKMARK, "Bookmark this page") {
                val tab = cur
                when {
                    tab?.isPrivate == true -> Toast.makeText(
                        this, "Bookmarks are disabled in private tabs so this page is not saved.", Toast.LENGTH_LONG
                    ).show()
                    currentUrl.isBlank() -> Toast.makeText(this, "Open a page first", Toast.LENGTH_SHORT).show()
                    else -> {
                        bookmarks.add(currentUrl); persistBookmarks()
                        Toast.makeText(this, "Bookmark saved on this device", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            item(Icons.SHARE, "Share page") {
                if (currentUrl.isNotBlank()) {
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, currentUrl)
                    }, "Share page"))
                }
            }
            if (DownloadNaming.looksPlayable(currentUrl)) {
                item(Icons.PLAY, "Play in Veyra") { playMedia(Uri.parse(currentUrl), null) }
            }
            cur?.let { tab ->
                if (tab.media.isNotEmpty() || tab.unsupportedMedia > 0 || hostOf(tab.url).let { h -> streamingHosts.any { h == it || h.endsWith(".$it") } }) {
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
    /** Compact, scrollable menu positioned against the three-dot toolbar action. */
    private fun showSideSheet(anchor: View, build: (LinearLayout, PopupWindow) -> Unit) {
        val sheet = column().apply {
            setPadding(dp(12), dp(12), dp(12), dp(12))
            neu(this, 22, Depth.CARD)
            isClickable = true
        }
        val scroller = ScrollView(this).apply {
            clipChildren = false
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            setPadding(0, dp(4), 0, dp(4))
        }
        val screenWidth = resources.displayMetrics.widthPixels
        val menuWidth = minOf(
            dp(276),
            (screenWidth - systemInsetLeft - systemInsetRight - dp(24)).coerceAtLeast(dp(220))
        )
        val maxHeight = (resources.displayMetrics.heightPixels - systemInsetTop - systemInsetBottom - dp(24))
            .coerceAtLeast(dp(220))
        scroller.addView(sheet, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        // Let the popup measure to its content, bounded to the visible screen. The old full-height
        // Dialog dimmed and covered much of the webpage, making the menu look like a side drawer.
        scroller.measure(
            View.MeasureSpec.makeMeasureSpec(menuWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST)
        )
        val menuHeight = scroller.measuredHeight.coerceIn(dp(180), maxHeight)
        val popup = PopupWindow(scroller, menuWidth, menuHeight, true).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            elevation = dpf(14f)
            inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        build(sheet, popup)
        // Re-measure after the rows are inserted so the anchor position matches the actual menu.
        scroller.measure(
            View.MeasureSpec.makeMeasureSpec(menuWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST)
        )
        val height = scroller.measuredHeight.coerceIn(dp(180), maxHeight)
        popup.height = height
        val position = IntArray(2)
        anchor.getLocationOnScreen(position)
        val maxTop = (resources.displayMetrics.heightPixels - systemInsetBottom - height - dp(8))
            .coerceAtLeast(systemInsetTop + dp(8))
        val y = (position[1] + anchor.height - height)
            .coerceIn(systemInsetTop + dp(8), maxTop)
        popup.showAtLocation(root, Gravity.TOP or Gravity.END, systemInsetRight + dp(12), y)
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
