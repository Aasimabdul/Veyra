package com.veyra.browser

import android.app.Dialog
import android.app.KeyguardManager
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
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
import org.json.JSONArray
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.StorageController
import java.text.DateFormat
import java.util.Date

/**
 * Veyra Android shell. Every screen follows the neumorphic mockups (see [UiKit]); only features
 * that actually work are exposed. Multiple tabs, private browsing and the download manager are
 * not implemented yet and are labelled as such instead of being faked.
 */
class MainActivity : AppCompatActivity() {
    private data class HistoryEntry(val url: String, var title: String, val time: Long)

    private lateinit var runtime: GeckoRuntime
    private lateinit var session: GeckoSession
    private lateinit var geckoView: GeckoView
    private lateinit var root: LinearLayout
    private lateinit var contentArea: LinearLayout
    private var bottomBar: View? = null
    private var urlHost: TextView? = null
    private var backButton: View? = null
    private var forwardButton: View? = null
    private var progressFill: View? = null
    private var currentPage = "home"
    private var currentUrl = ""
    private var currentTitle = ""
    private var canGoBack = false
    private var canGoForward = false
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

    private val prefs get() = getSharedPreferences("veyra_preferences", MODE_PRIVATE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = Palette.BG
        window.navigationBarColor = Palette.BG

        val contentBlockingSettings = ContentBlocking.Settings.Builder()
            .antiTracking(ContentBlocking.AntiTracking.DEFAULT)
            .build()
        runtime = GeckoRuntime.create(
            this,
            GeckoRuntimeSettings.Builder().contentBlocking(contentBlockingSettings).build()
        )
        trackingProtectionEnabled = prefs.getBoolean("tracking_protection_enabled", true)
        session = GeckoSession().apply {
            settings.setUseTrackingProtection(trackingProtectionEnabled)
            navigationDelegate = object : GeckoSession.NavigationDelegate {
                override fun onLocationChange(
                    session: GeckoSession,
                    url: String?,
                    perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
                    hasUserGesture: Boolean
                ) {
                    if (url.isNullOrBlank() || url == "about:blank") return
                    currentUrl = url
                    currentTitle = ""
                    if (history.lastOrNull()?.url != url) {
                        history.add(HistoryEntry(url, "", System.currentTimeMillis()))
                    }
                    urlHost?.text = hostOf(url)
                }

                override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                    this@MainActivity.canGoBack = canGoBack
                }

                override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
                    this@MainActivity.canGoForward = canGoForward
                    forwardButton?.alpha = if (canGoForward) 1f else 0.4f
                }
            }
            contentDelegate = object : GeckoSession.ContentDelegate {
                override fun onTitleChange(session: GeckoSession, title: String?) {
                    if (title.isNullOrBlank()) return
                    currentTitle = title
                    history.lastOrNull()?.title = title
                }
            }
            progressDelegate = object : GeckoSession.ProgressDelegate {
                override fun onProgressChange(session: GeckoSession, progress: Int) {
                    progressFill?.let { bar ->
                        val parent = bar.parent as? View
                        bar.visibility = if (progress in 1..99) View.VISIBLE else View.INVISIBLE
                        bar.pivotX = 0f
                        bar.scaleX = progress / 100f
                        parent?.visibility = View.VISIBLE
                    }
                }
            }
        }
        session.open(runtime)
        geckoView = GeckoView(this).apply { setSession(this@MainActivity.session) }

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
        applyGutters()
        loadBookmarks()

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
    }

    override fun onDestroy() {
        if (::session.isInitialized) session.close()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            currentPage == "lock" -> super.onBackPressed()
            currentPage == "browse" && canGoBack -> session.goBack()
            currentPage != "home" -> show("home")
            else -> super.onBackPressed()
        }
    }

    private fun deviceIsSecure(): Boolean =
        getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    private fun applySecureFlag() {
        if (appLockEnabled) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
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

    // ---- layout helpers ---------------------------------------------------------------

    /** 24dp gutter on phones; content is centred at a readable width on tablets. */
    private fun applyGutters() {
        val width = resources.displayMetrics.widthPixels
        val gutter = maxOf(dp(24), (width - dp(640)) / 2)
        root.setPadding(gutter, dp(16), gutter, dp(0))
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
            "lock" -> showLock()
            else -> showHome()
        }
    }

    private fun resetContent(page: String) {
        currentPage = page
        urlHost = null
        backButton = null
        forwardButton = null
        progressFill = null
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
        val scroll = ScrollView(this).apply {
            clipChildren = false
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
            well.addView(icon(item.paths, if (active) Palette.ACCENT else Palette.MUTED, 22))
            slot.addView(well, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER))
            nav.addView(slot, lp(0, dp(72), 1f))
        }
        attachBottomBar(nav)
    }

    private fun addBrowserBar() {
        val bar = row().apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), 0, dp(8), 0)
            neu(this, 28, Depth.CARD)
        }
        fun cell(paths: List<String>, description: String, color: Int, action: () -> Unit): View =
            LinearLayout(this).apply {
                gravity = Gravity.CENTER
                contentDescription = description
                isClickable = true
                setOnClickListener { action() }
                addView(icon(paths, color, 22))
            }
        val back = cell(Icons.BACK, "Back", Palette.MUTED) {
            if (canGoBack) session.goBack() else show("home")
        }
        val forward = cell(Icons.FORWARD, "Forward", Palette.MUTED) {
            if (canGoForward) session.goForward()
        }.apply { alpha = if (canGoForward) 1f else 0.4f }
        backButton = back
        forwardButton = forward
        val share = cell(Icons.SHARE, "Share page", Palette.MUTED) {
            if (currentUrl.isNotBlank()) {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, currentUrl)
                }, "Share page"))
            }
        }
        val tabs = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            contentDescription = "Tabs, 1 open"
            isClickable = true
            setOnClickListener { show("tabs") }
            addView(label("1", 12f, Palette.ACCENT, 1).apply {
                gravity = Gravity.CENTER
                neu(this, 8, Depth.KNOB, inset = true)
                layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
            })
        }
        val menu = cell(Icons.MENU, "Menu", Palette.MUTED) { showBrowserMenu() }
        listOf(back, forward, share, tabs, menu).forEach { view ->
            bar.addView(view, lp(0, dp(48), 1f))
        }
        attachBottomBar(bar)
    }

    private fun attachBottomBar(bar: View) {
        bottomBar = bar
        root.addView(bar, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(72)).apply {
            topMargin = dp(12)
            bottomMargin = dp(20)
            leftMargin = 0
            rightMargin = 0
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
            topMargin = dp(20); bottomMargin = dp(28)
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

        val top = row().apply { minimumHeight = dp(52) }
        val pill = row().apply {
            setPadding(dp(18), 0, dp(6), 0)
            contentDescription = "Edit address"
            setOnClickListener { showSearch(currentUrl) }
            neu(this, 26, Depth.FIELD, inset = true)
        }
        pill.addView(icon(Icons.LOCK, Palette.ACCENT, 16))
        urlHost = label(hostOf(currentUrl), 14f, Palette.TEXT, 1).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(12), 0, dp(8), 0)
        }
        pill.addView(urlHost, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val reload = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            contentDescription = "Reload page"
            isClickable = true
            setOnClickListener { session.reload() }
            addView(icon(Icons.RELOAD, Palette.MUTED, 18))
        }
        pill.addView(reload, lp(dp(40), dp(40)))
        top.addView(pill, lp(0, dp(52), 1f).apply { rightMargin = dp(14) })
        top.addView(trackerShield())
        contentArea.addView(top, matchWrap())

        // Thin progress track under the address row.
        val track = FrameLayout(this).apply { visibility = View.INVISIBLE }
        val fill = View(this).apply {
            setBackgroundColor(Palette.ACCENT)
            pivotX = 0f
            scaleX = 0f
        }
        track.addView(fill, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2)))
        progressFill = fill
        contentArea.addView(track, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(2)).apply {
            topMargin = dp(10); bottomMargin = dp(6)
        })

        (geckoView.parent as? ViewGroup)?.removeView(geckoView)
        contentArea.addView(geckoView, lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        addBrowserBar()
    }

    private fun trackerShield(): View {
        val button = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setOnClickListener { setTrackingProtection(!trackingProtectionEnabled); show("browse"); session.reload() }
            neu(this, 18, Depth.BUTTON, pressable = true)
        }
        button.contentDescription =
            if (trackingProtectionEnabled) "Tracker protection on, tap to turn off"
            else "Tracker protection off, tap to turn on"
        button.addView(icon(
            if (trackingProtectionEnabled) Icons.SHIELD else Icons.SHIELD_OFF,
            if (trackingProtectionEnabled) Palette.ACCENT else Palette.MUTED, 22
        ))
        button.layoutParams = LinearLayout.LayoutParams(dp(52), dp(52))
        return button
    }

    private fun navigate(raw: String) {
        val uri = AddressResolver.resolve(raw) ?: run {
            Toast.makeText(this, "Enter a valid address or search term", Toast.LENGTH_SHORT).show()
            return
        }
        currentUrl = uri
        currentTitle = ""
        try {
            session.loadUri(uri)
            show("browse")
        } catch (_: Exception) {
            Toast.makeText(this, "Could not open that address", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showTabs() {
        resetContent("tabs")
        val page = scrollPage()
        val open = if (currentUrl.isBlank()) 0 else 1
        val header = row().apply { minimumHeight = dp(48) }
        header.addView(label("Tabs", 22f, Palette.TEXT, 1), lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(label("$open open", 13f, Palette.MUTED))
        page.addView(header, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(22)
        })
        if (open == 0) {
            page.addView(emptyState(Icons.TABS, "No open tabs", "Search or pick a quick access tile to open a page."))
        } else {
            val title = currentTitle.ifBlank { hostOf(currentUrl) }
            val close = iconButton(Icons.CLOSE, "Close tab", Palette.MUTED, 40, 14) {
                currentUrl = ""; currentTitle = ""; canGoBack = false; canGoForward = false
                session.loadUri("about:blank")
                showTabs()
            }
            page.addView(
                listRow(letterWell(title), title, hostOf(currentUrl), close) { show("browse") },
                lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(18) }
            )
        }
        page.addView(label(
            "Multiple tabs and private tabs are not available yet; Veyra keeps one browsing session for now.",
            12f, Palette.MUTED
        ).apply { setPadding(dp(4), dp(10), dp(4), 0) }, matchWrap())
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
            header.addView(iconButton(Icons.TRASH, "Clear session history") {
                history.clear()
                session.purgeHistory()
                Toast.makeText(this, "Session history cleared", Toast.LENGTH_SHORT).show()
                showHistory()
            })
        }
        page.addView(header, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(22)
        })
        if (history.isEmpty()) {
            page.addView(emptyState(Icons.CLOCK, "Nothing here yet", "Pages you visit in this session appear here. History is not saved after you close Veyra."))
        } else {
            val timeFormat = DateFormat.getTimeInstance(DateFormat.SHORT)
            history.toList().reversed().forEach { entry ->
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

    private fun showDownloads() {
        resetContent("downloads")
        val page = scrollPage()
        page.addView(label("Downloads", 22f, Palette.TEXT, 1).apply { minHeight = dp(48); gravity = Gravity.CENTER_VERTICAL },
            lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(22) })
        page.addView(emptyState(
            Icons.DOWNLOAD, "No downloads yet",
            "The download manager is not built yet, so file downloads are not available in this preview."
        ))
        addMainNav("downloads")
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
        val secure = deviceIsSecure()
        switchRow(
            Icons.LOCK, "App lock",
            if (secure) "Ask for your device unlock when returning to Veyra." else "Set a screen lock in Android settings first.",
            appLockEnabled && secure
        ) { enabled -> setAppLock(enabled) }
        actionRow(Icons.CLOCK, "History", "Pages visited this session") { show("history") }
        actionRow(Icons.TRASH, "Clear browsing data", "Cookies, cache, site data and history") { confirmClearData() }
        actionRow(Icons.INFO, "About Veyra", "Version, engine and licence") { showAbout() }
        addMainNav("settings")
    }

    // ---- actions ----------------------------------------------------------------------

    private fun setTrackingProtection(enabled: Boolean) {
        trackingProtectionEnabled = enabled
        session.settings.setUseTrackingProtection(enabled)
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
            "This removes cookies, cache, site data and this session's history. Bookmarks and quick access stay.",
            "Clear"
        ) {
            history.clear()
            session.purgeHistory()
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
        showSheet { sheet, dialog ->
            fun item(paths: List<String>, text: String, action: () -> Unit) {
                val rowView = row().apply {
                    setPadding(dp(18), dp(12), dp(18), dp(12))
                    neu(this, 20, Depth.BUTTON, pressable = true)
                    setOnClickListener { dialog.dismiss(); action() }
                }
                rowView.addView(icon(paths, Palette.ACCENT, 20))
                rowView.addView(label(text, 15f, Palette.TEXT, 1).apply { setPadding(dp(16), 0, 0, 0) })
                sheet.addView(rowView, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { bottomMargin = dp(14) })
            }
            item(Icons.BOOKMARK, "Bookmark this page") {
                if (currentUrl.isNotBlank()) {
                    bookmarks.add(currentUrl); persistBookmarks()
                    Toast.makeText(this, "Bookmark saved on this device", Toast.LENGTH_SHORT).show()
                }
            }
            item(Icons.CLOCK, "History") { show("history") }
            item(Icons.SETTINGS, "Settings") { show("settings") }
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
}
