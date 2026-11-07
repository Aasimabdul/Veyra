package com.veyra.browser

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

class MainActivity : AppCompatActivity() {
    private lateinit var runtime: GeckoRuntime
    private lateinit var session: GeckoSession
    private lateinit var address: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(7, 17, 31)
        window.navigationBarColor = Color.rgb(7, 17, 31)

        runtime = GeckoRuntime.create(this)
        session = GeckoSession()
        session.open(runtime)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(7, 17, 31))
            setPadding(14, 12, 14, 0)
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        address = EditText(this).apply {
            hint = "Search or enter address"
            singleLine = true
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
            setBackgroundColor(Color.rgb(16, 30, 49))
            imeOptions = EditorInfo.IME_ACTION_GO
            setPadding(18, 8, 18, 8)
        }
        val go = Button(this).apply {
            text = "Go"
            setTextColor(Color.rgb(57, 216, 255))
            setOnClickListener { navigate() }
        }
        address.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) { navigate(); true } else false
        }
        bar.addView(address, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(go)
        root.addView(bar)

        val geckoView = GeckoView(this)
        geckoView.setSession(session)
        root.addView(geckoView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        setContentView(root)
        address.setText("https://www.mozilla.org")
        session.loadUri("https://www.mozilla.org")
    }

    private fun navigate() {
        val input = address.text.toString().trim()
        if (input.isBlank()) return
        val uri = when {
            input.startsWith("https://") || input.startsWith("http://") -> input
            input.contains('.') && !input.contains(' ') -> "https://$input"
            else -> "https://www.google.com/search?q=" +
                java.net.URLEncoder.encode(input, "UTF-8")
        }
        try {
            session.loadUri(uri)
        } catch (_: Exception) {
            Toast.makeText(this, "Could not open that address", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        if (::session.isInitialized) session.close()
        super.onDestroy()
    }
}
