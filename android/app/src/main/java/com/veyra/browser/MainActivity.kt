package com.veyra.browser

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.json.JSONArray

/**
 * First Veyra neumorphic UI milestone.
 * The home and browsing surfaces are real; advanced browser features are added incrementally.
 */
class MainActivity : AppCompatActivity() {
    private val navy = Color.rgb(7, 17, 31)
    private val panel = Color.rgb(16, 32, 52)
    private val panelLight = Color.rgb(24, 45, 69)
    private val cyan = Color.rgb(57, 190, 255)
    private val white = Color.rgb(238, 244, 255)
    private val muted = Color.rgb(160, 180, 204)

    private lateinit var runtime: GeckoRuntime
    private lateinit var session: GeckoSession
    private lateinit var geckoView: GeckoView
    private lateinit var address: EditText
    private lateinit var contentArea: LinearLayout
    private lateinit var root: LinearLayout
    private var bottomNav: LinearLayout? = null
    private var currentPage = "home"
    private var currentUrl = ""
    private var trackingProtectionEnabled = true
    private val history = linkedSetOf<String>()
    private val bookmarks = linkedSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = navy
        window.navigationBarColor = navy
        window.decorView.systemUiVisibility = 0

        val contentBlockingSettings = ContentBlocking.Settings.Builder()
            .antiTracking(ContentBlocking.AntiTracking.DEFAULT)
            .build()
        runtime = GeckoRuntime.create(
            this,
            GeckoRuntimeSettings.Builder()
                .contentBlocking(contentBlockingSettings)
                .build()
        )
        trackingProtectionEnabled = getSharedPreferences("veyra_preferences", MODE_PRIVATE)
            .getBoolean("tracking_protection_enabled", true)
        session = GeckoSession().apply {
            settings.setUseTrackingProtection(trackingProtectionEnabled)
        }
        session.open(runtime)
        geckoView = GeckoView(this).apply {
            setSession(this@MainActivity.session)
            visibility = View.GONE
        }

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(navy)
            setPadding(dp(16), dp(12), dp(16), dp(4))
        }
        contentArea = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(contentArea, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        setContentView(root)
        loadBookmarks()
        showHome()
    }

    private fun showHome() {
        currentPage = "home"
        contentArea.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        )
        geckoView.visibility = View.GONE
        contentArea.visibility = View.VISIBLE
        contentArea.removeAllViews()

        // All home content lives in a scroll container; bottom navigation remains fixed.
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val page = column().apply { setPadding(0, dp(4), 0, dp(18)) }
        scroll.addView(page, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        contentArea.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

        val header = row()
        header.addView(label("Veyra", 26, true), LinearLayout.LayoutParams(0, dp(52), 1f))
        header.addView(chip("☆", 42) { showBookmarks() }.apply {
            contentDescription = "Open bookmarks"
        }, LinearLayout.LayoutParams(dp(46), dp(44)).apply { marginEnd = dp(8) })
        header.addView(chip("⚙", 42) { showSettings() }.apply {
            contentDescription = "Open settings"
        }, LinearLayout.LayoutParams(dp(46), dp(44)))
        page.addView(header)

        val brand = column().apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, dp(20))
        }
        brand.addView(label("V", 58, true, cyan).apply {
            gravity = Gravity.CENTER
            setShadowLayer(dp(12).toFloat(), 0f, 0f, Color.rgb(0, 110, 255))
        })
        brand.addView(label("Minimal · Fast · Private", 13, false, muted).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(3), 0, 0)
        })
        page.addView(brand)

        address = EditText(this).apply {
            hint = "⌕  Search or enter URL"
            setSingleLine(true)
            textSize = 15f
            setTextColor(white)
            setHintTextColor(muted)
            background = surface(panelLight, 30)
            imeOptions = EditorInfo.IME_ACTION_GO
            setPadding(dp(18), dp(14), dp(18), dp(14))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_URI
            setOnFocusChangeListener { _, focused ->
                if (focused && currentPage == "home") showSearch()
            }
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_NULL) {
                    navigate(text.toString()); true
                } else false
            }
        }
        page.addView(address, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(56)
        ).apply { bottomMargin = dp(24) })

        page.addView(label("QUICK ACCESS", 11, true, muted).apply {
            letterSpacing = .14f
            setPadding(dp(4), 0, 0, dp(12))
        })
        val shortcuts = listOf(
            listOf("▶", "YouTube", "https://m.youtube.com"),
            listOf("G", "Google", "https://google.com"),
            listOf("●", "GitHub", "https://github.com"),
            listOf("◉", "Reddit", "https://reddit.com"),
            listOf("𝕏", "X", "https://x.com"),
            listOf("+", "Add", "")
        )
        shortcuts.chunked(3).forEach { group ->
            val line = row()
            group.forEach { item ->
                val tile = column().apply {
                    gravity = Gravity.CENTER
                    background = surface(panel, 22)
                    elevation = dp(3).toFloat()
                    setPadding(dp(4), dp(12), dp(4), dp(10))
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        if (item[2].isNotBlank()) navigate(item[2])
                        else Toast.makeText(this@MainActivity, "Custom quick links are coming next", Toast.LENGTH_SHORT).show()
                    }
                }
                tile.addView(label(item[0], 23, true, cyan).apply { gravity = Gravity.CENTER })
                tile.addView(label(item[1], 11, false, white).apply { gravity = Gravity.CENTER })
                line.addView(tile, LinearLayout.LayoutParams(0, dp(82), 1f).apply {
                    marginEnd = dp(8)
                    bottomMargin = dp(9)
                })
            }
            page.addView(line)
        }

        val continueCard = column().apply {
            background = surface(panel, 24)
            elevation = dp(3).toFloat()
            setPadding(dp(16), dp(15), dp(16), dp(15))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (currentUrl.isNotBlank()) navigate(currentUrl)
                else address.requestFocus()
            }
        }
        continueCard.addView(label("▣   Continue browsing", 15, true))
        continueCard.addView(label(
            if (currentUrl.isBlank()) "Your last visited page appears here" else currentUrl,
            12, false, muted
        ).apply { setPadding(dp(2), dp(6), 0, 0) })
        page.addView(continueCard, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) })

        addBottomNav("home")
    }

    private fun showBrowser() {
        currentPage = "browse"
        contentArea.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        )
        contentArea.visibility = View.VISIBLE
        contentArea.removeAllViews()
        geckoView.visibility = View.VISIBLE

        val overlay = column()
        val toolbar = row().apply {
            gravity = Gravity.CENTER_VERTICAL
            background = surface(panel, 24)
            setPadding(dp(10), dp(3), dp(6), dp(3))
        }
        toolbar.addView(label("◉", 15, true, cyan))
        address = EditText(this).apply {
            setSingleLine(true)
            textSize = 13f
            setTextColor(white)
            setHintTextColor(muted)
            setBackgroundColor(Color.TRANSPARENT)
            hint = "Search or enter URL"
            setPadding(dp(8), dp(10), dp(4), dp(10))
            imeOptions = EditorInfo.IME_ACTION_GO
            setText(currentUrl)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_NULL) {
                    navigate(text.toString()); true
                } else false
            }
        }
        toolbar.addView(address, LinearLayout.LayoutParams(0, dp(44), 1f))
        toolbar.addView(chip("↻", 38) { if (currentUrl.isNotBlank()) session.reload() })
        overlay.addView(toolbar)
        val navigationControls = row()
        navigationControls.addView(chip("←", 42) {
            session.goBack()
        }.apply {
            contentDescription = "Go back"
        }, LinearLayout.LayoutParams(dp(48), dp(44)).apply {
            marginEnd = dp(8)
        })
        navigationControls.addView(chip("→", 42) {
            session.goForward()
        }.apply {
            contentDescription = "Go forward"
        }, LinearLayout.LayoutParams(dp(48), dp(44)))
        overlay.addView(navigationControls)
        contentArea.addView(overlay, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(6) })
        // Keep the web renderer inside the content column so the toolbar, page and bottom
        // navigation have separate, predictable layout bounds on different screen sizes.
        contentArea.addView(geckoView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        addBottomNav("browse")
    }

    private fun navigate(raw: String) {
        val uri = AddressResolver.resolve(raw) ?: return
        currentUrl = uri
        history.add(uri)
        try {
            session.loadUri(uri)
            showBrowser()
        } catch (_: Exception) {
            Toast.makeText(this, "Could not open that address", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showTabs() {
        currentPage = "tabs"
        geckoView.visibility = View.GONE
        contentArea.visibility = View.VISIBLE
        contentArea.removeAllViews()
        contentArea.addView(label("Tabs", 24, true).apply { setPadding(0, dp(12), 0, dp(20)) })
        val card = column().apply {
            background = surface(panel, 24)
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        card.addView(label(if (currentUrl.isBlank()) "New tab" else currentUrl, 15, true))
        card.addView(label("Current browsing session", 12, false, muted).apply {
            setPadding(0, dp(6), 0, dp(12))
        })
        card.addView(chip("Open current tab", 44) {
            if (currentUrl.isNotBlank()) showBrowser() else showHome()
        })
        contentArea.addView(card)
        contentArea.addView(label("Multiple independent tabs and private sessions are planned for the next browser milestone.", 13, false, muted).apply {
            setPadding(dp(4), dp(18), dp(4), 0)
        })
        addBottomNav("tabs")
    }

    private fun showBookmarks() {
        currentPage = "bookmarks"
        geckoView.visibility = View.GONE
        contentArea.visibility = View.VISIBLE
        contentArea.removeAllViews()
        contentArea.addView(label("Bookmarks", 24, true).apply { setPadding(0, dp(12), 0, dp(18)) })
        contentArea.addView(chip("＋  Bookmark current page", 48) {
            if (currentUrl.isNotBlank()) {
                bookmarks.add(currentUrl)
                persistBookmarks()
                Toast.makeText(this, "Bookmark saved on this device", Toast.LENGTH_SHORT).show()
                showBookmarks()
            } else Toast.makeText(this, "Open a page first", Toast.LENGTH_SHORT).show()
        })
        if (bookmarks.isEmpty()) {
            contentArea.addView(label("Your saved pages will appear here.", 13, false, muted).apply {
                setPadding(dp(4), dp(18), 0, 0)
            })
        } else bookmarks.toList().forEach { url ->
            val itemRow = row()
            itemRow.addView(chip(url, 48) { navigate(url) }, LinearLayout.LayoutParams(
                0, dp(52), 1f
            ).apply { marginEnd = dp(8); bottomMargin = dp(8) })
            itemRow.addView(chip("×", 48) {
                bookmarks.remove(url)
                persistBookmarks()
                showBookmarks()
            }, LinearLayout.LayoutParams(dp(48), dp(52)).apply {
                bottomMargin = dp(8)
            })
            contentArea.addView(itemRow)
        }
        addBottomNav("bookmarks")
    }

    private fun loadBookmarks() {
        val raw = getSharedPreferences("veyra_preferences", MODE_PRIVATE)
            .getString("bookmarks", "[]") ?: "[]"
        try {
            val saved = JSONArray(raw)
            for (index in 0 until saved.length()) {
                val url = saved.optString(index, "")
                if (url.startsWith("https://", ignoreCase = true) ||
                    url.startsWith("http://", ignoreCase = true)
                ) {
                    bookmarks.add(url)
                }
            }
        } catch (_: Exception) {
            // Ignore malformed preferences rather than preventing the browser from opening.
        }
    }

    private fun persistBookmarks() {
        val saved = JSONArray()
        bookmarks.forEach { saved.put(it) }
        getSharedPreferences("veyra_preferences", MODE_PRIVATE).edit()
            .putString("bookmarks", saved.toString())
            .apply()
    }

    private fun showHistory() {
        currentPage = "history"
        geckoView.visibility = View.GONE
        contentArea.visibility = View.VISIBLE
        contentArea.removeAllViews()
        contentArea.addView(label("History", 24, true).apply { setPadding(0, dp(12), 0, dp(18)) })
        if (history.isNotEmpty()) {
            contentArea.addView(chip("Clear session history", 44) {
                history.clear()
                session.purgeHistory()
                showHistory()
                Toast.makeText(
                    this@MainActivity,
                    "Session and back/forward history cleared",
                    Toast.LENGTH_SHORT
                ).show()
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)
            ).apply { bottomMargin = dp(12) })
        }
        if (history.isEmpty()) {
            contentArea.addView(label("Pages you visit in this session appear here.", 13, false, muted))
        } else {
            history.toList().reversed().forEach { url ->
                contentArea.addView(chip(url, 52) { navigate(url) })
            }
        }
        addBottomNav("history")
    }

    private fun showDownloads() {
        currentPage = "downloads"
        geckoView.visibility = View.GONE
        contentArea.visibility = View.VISIBLE
        contentArea.removeAllViews()
        contentArea.addView(label("Downloads", 24, true).apply { setPadding(0, dp(12), 0, dp(18)) })
        contentArea.addView(label("Download management is being built next. This screen will show active, completed, video, audio and file downloads.", 14, false, muted))
        addBottomNav("downloads")
    }

    private fun showSettings() {
        currentPage = "settings"
        geckoView.visibility = View.GONE
        contentArea.visibility = View.VISIBLE
        contentArea.removeAllViews()
        contentArea.addView(label("Settings", 24, true).apply { setPadding(0, dp(12), 0, dp(18)) })
        val trackingCard = row().apply {
            background = surface(panel, 20)
            elevation = dp(3).toFloat()
            setPadding(dp(16), dp(12), dp(12), dp(12))
        }
        val trackingDescription = column()
        trackingDescription.addView(label("Tracking protection", 14, true))
        trackingDescription.addView(label(
            "Blocks known tracker resources. This is not a full ad blocker.",
            11, false, muted
        ).apply { setPadding(0, dp(4), dp(8), 0) })
        trackingCard.addView(trackingDescription, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ))
        trackingCard.addView(SwitchCompat(this).apply {
            isChecked = trackingProtectionEnabled
            contentDescription = "Enable tracking protection"
            setOnCheckedChangeListener { _, enabled ->
                trackingProtectionEnabled = enabled
                session.settings.setUseTrackingProtection(enabled)
                getSharedPreferences("veyra_preferences", MODE_PRIVATE).edit()
                    .putBoolean("tracking_protection_enabled", enabled)
                    .apply()
                Toast.makeText(
                    this@MainActivity,
                    if (enabled) "Tracking protection enabled" else "Tracking protection disabled",
                    Toast.LENGTH_SHORT
                ).show()
            }
        })
        contentArea.addView(trackingCard, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(10) })

        listOf(
            "General" to "Home, tabs, browsing behavior",
            "Downloads" to "Location and download behavior",
            "Appearance" to "Neumorphic UI, themes and accent colors",
            "About Veyra" to "Open-source browser"
        ).forEach { (title, subtitle) ->
            val item = column().apply {
                background = surface(panel, 20)
                elevation = dp(3).toFloat()
                setPadding(dp(16), dp(13), dp(16), dp(13))
            }
            item.addView(label(title, 14, true))
            item.addView(label(subtitle, 11, false, muted).apply { setPadding(0, dp(4), 0, 0) })
            contentArea.addView(item, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) })
        }
        addBottomNav("settings")
    }

    private fun showSearch() {
        currentPage = "search"
        geckoView.visibility = View.GONE
        contentArea.visibility = View.VISIBLE
        contentArea.removeAllViews()
        val searchPage = column()
        val header = row()
        header.addView(chip("←", 42) { showHome() }, LinearLayout.LayoutParams(dp(48), dp(44)))
        header.addView(label("Search", 22, true), LinearLayout.LayoutParams(0, dp(48), 1f).apply {
            marginStart = dp(12)
        })
        searchPage.addView(header)
        val query = EditText(this).apply {
            hint = "Search or enter URL"
            textSize = 15f
            setTextColor(white)
            setHintTextColor(muted)
            background = surface(panelLight, 28)
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_GO
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_NULL) {
                    navigate(text.toString()); true
                } else false
            }
        }
        searchPage.addView(query, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(54)
        ).apply { topMargin = dp(16); bottomMargin = dp(18) })
        searchPage.addView(label("QUICK SEARCH", 11, true, muted).apply {
            setPadding(dp(4), 0, 0, dp(10))
        })
        listOf("Google" to "https://google.com", "DuckDuckGo" to "https://duckduckgo.com",
            "Wikipedia" to "https://wikipedia.org").forEach { (name, url) ->
            searchPage.addView(chip("⌕   $name", 48) { navigate(url) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply {
                    bottomMargin = dp(8)
                })
        }
        searchPage.addView(label("Recent searches are not saved yet.", 12, false, muted).apply {
            setPadding(dp(4), dp(14), 0, 0)
        })
        contentArea.addView(searchPage, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        addBottomNav("search")
        query.requestFocus()
    }

    private fun addBottomNav(selected: String) {
        val nav = row().apply {
            gravity = Gravity.CENTER
            background = surface(panel, 28)
            elevation = dp(8).toFloat()
            setPadding(dp(4), dp(5), dp(4), dp(5))
        }
        val items = listOf(
            Triple("⌂", "home", "Home"),
            Triple("⌕", "search", "Search"),
            Triple("▢", "tabs", "Tabs"),
            Triple("↓", "downloads", "Downloads"),
            Triple("☷", "settings", "Settings")
        )
        items.forEach { (icon, key, description) ->
            val item = TextView(this).apply {
                text = icon
                textSize = 22f
                gravity = Gravity.CENTER
                setTextColor(if (selected == key) cyan else muted)
                background = surface(if (selected == key) panelLight else panel, 20)
                contentDescription = description
                setOnClickListener {
                    when (key) {
                        "home" -> showHome()
                        "search" -> showSearch()
                        "tabs" -> showTabs()
                        "downloads" -> showDownloads()
                        else -> showSettings()
                    }
                }
            }
            nav.addView(item, LinearLayout.LayoutParams(0, dp(46), 1f).apply {
                marginStart = dp(3); marginEnd = dp(3)
            })
        }
        bottomNav?.let { root.removeView(it) }
        bottomNav = nav
        root.addView(nav, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(56)
        ).apply { topMargin = dp(8); bottomMargin = dp(4) })
    }

    private fun chip(text: String, height: Int, action: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(white)
            gravity = Gravity.CENTER
            background = surface(panelLight, 22)
            elevation = dp(3).toFloat()
            setPadding(dp(12), dp(6), dp(12), dp(6))
            minHeight = dp(height)
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
        }

    private fun label(text: String, size: Int, bold: Boolean, color: Int = white): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size.toFloat()
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER_VERTICAL
        }

    private fun row() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun column() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
    }

    private fun surface(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        setStroke(dp(1), Color.rgb(30, 53, 78))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onBackPressed() {
        when {
            currentPage == "browse" -> { showHome() }
            currentPage != "home" -> showHome()
            else -> super.onBackPressed()
        }
    }

    override fun onDestroy() {
        if (::session.isInitialized) session.close()
        super.onDestroy()
    }
}
