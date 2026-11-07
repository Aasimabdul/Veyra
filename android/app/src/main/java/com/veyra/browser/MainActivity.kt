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
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import java.net.URLEncoder

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
    private val history = linkedSetOf<String>()
    private val bookmarks = linkedSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = navy
        window.navigationBarColor = navy
        window.decorView.systemUiVisibility = 0

        runtime = GeckoRuntime.create(this)
        session = GeckoSession()
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
        root.addView(geckoView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        setContentView(root)
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

        val header = row()
        header.addView(label("Veyra", 25, true), LinearLayout.LayoutParams(0, dp(52), 1f))
        header.addView(chip("◉", 42) { showSettings() })
        contentArea.addView(header)

        val brand = column().apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(22))
        }
        brand.addView(label("V", 54, true, cyan).apply { gravity = Gravity.CENTER })
        brand.addView(label("Private · Fast · Minimal", 13, false, muted).apply {
            gravity = Gravity.CENTER
        })
        contentArea.addView(brand)

        address = EditText(this).apply {
            hint = "⌕  Search or enter URL"
            setSingleLine(true)
            textSize = 14f
            setTextColor(white)
            setHintTextColor(muted)
            background = surface(panelLight, 28)
            imeOptions = EditorInfo.IME_ACTION_GO
            setPadding(dp(18), dp(14), dp(18), dp(14))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_URI
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_NULL) {
                    navigate(text.toString()); true
                } else false
            }
        }
        contentArea.addView(address, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(54)
        ).apply { bottomMargin = dp(24) })

        contentArea.addView(label("QUICK ACCESS", 11, true, muted).apply {
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
                    elevation = dp(5).toFloat()
                    setPadding(dp(4), dp(12), dp(4), dp(10))
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        if (item[2].isNotBlank()) navigate(item[2])
                        else Toast.makeText(this@MainActivity, "Quick links editor will be added in the next milestone", Toast.LENGTH_SHORT).show()
                    }
                }
                tile.addView(label(item[0], 23, true, cyan).apply { gravity = Gravity.CENTER })
                tile.addView(label(item[1], 11, false, white).apply { gravity = Gravity.CENTER })
                line.addView(tile, LinearLayout.LayoutParams(0, dp(82), 1f).apply {
                    marginEnd = dp(9)
                    bottomMargin = dp(10)
                })
            }
            contentArea.addView(line)
        }

        val continueCard = column().apply {
            background = surface(panel, 24)
            elevation = dp(4).toFloat()
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setOnClickListener {
                if (currentUrl.isNotBlank()) navigate(currentUrl)
                else navigate("https://www.mozilla.org")
            }
        }
        continueCard.addView(label("▣   Continue browsing", 15, true))
        continueCard.addView(label(
            if (currentUrl.isBlank()) "Open your first page" else currentUrl,
            12, false, muted
        ).apply { setPadding(dp(2), dp(6), 0, 0) })
        contentArea.addView(continueCard, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(16) })

        addBottomNav("home")
    }

    private fun showBrowser() {
        currentPage = "browse"
        contentArea.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        contentArea.visibility = View.VISIBLE
        geckoView.visibility = View.VISIBLE
        contentArea.removeAllViews()

        val overlay = column()
        val toolbar = row().apply {
            gravity = Gravity.CENTER_VERTICAL
            background = surface(panel, 24)
            setPadding(dp(10), dp(3), dp(6), dp(3))
        }
        toolbar.addView(label("🔒", 15, true, cyan))
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
        overlay.addView(chip("←", 42) {
            session.goBack()
        })
        contentArea.addView(overlay)
        contentArea.visibility = View.VISIBLE
        geckoView.visibility = View.VISIBLE
        // The page view remains below the compact toolbar and is resized by the layout.
        root.removeView(geckoView)
        root.addView(geckoView, 1, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        addBottomNav("browse")
    }

    private fun navigate(raw: String) {
        val input = raw.trim()
        if (input.isBlank()) return
        val uri = when {
            input.startsWith("https://") || input.startsWith("http://") -> input
            input.contains('.') && !input.contains(' ') -> "https://$input"
            else -> "https://www.google.com/search?q=" + URLEncoder.encode(input, "UTF-8")
        }
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
                Toast.makeText(this, "Bookmark saved for this session", Toast.LENGTH_SHORT).show()
                showBookmarks()
            } else Toast.makeText(this, "Open a page first", Toast.LENGTH_SHORT).show()
        })
        if (bookmarks.isEmpty()) {
            contentArea.addView(label("Your saved pages will appear here.", 13, false, muted).apply {
                setPadding(dp(4), dp(18), 0, 0)
            })
        } else bookmarks.forEach { url ->
            contentArea.addView(chip(url, 54) { navigate(url) })
        }
        addBottomNav("bookmarks")
    }

    private fun showHistory() {
        currentPage = "history"
        geckoView.visibility = View.GONE
        contentArea.visibility = View.VISIBLE
        contentArea.removeAllViews()
        contentArea.addView(label("History", 24, true).apply { setPadding(0, dp(12), 0, dp(18)) })
        if (history.isEmpty()) contentArea.addView(label("Pages you visit in this session appear here.", 13, false, muted))
        history.toList().reversed().forEach { url -> contentArea.addView(chip(url, 52) { navigate(url) }) }
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
        listOf(
            "General" to "Home, tabs, browsing behavior",
            "Privacy & Security" to "Tracking prevention, cookies, private mode",
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

    private fun addBottomNav(selected: String) {
        val nav = row().apply {
            gravity = Gravity.CENTER
            background = surface(panel, 28)
            elevation = dp(8).toFloat()
            setPadding(dp(4), dp(5), dp(4), dp(5))
        }
        val items = listOf(
            Triple("⌂", "home", "Home"),
            Triple("◷", "history", "History"),
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
                        "history" -> showHistory()
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
