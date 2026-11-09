package com.veyra.browser

import android.app.PictureInPictureParams
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

/**
 * Clean, MX-style built-in player for local files and direct media URLs.
 * Uses Media3's accessible seek/playback controls; does not bypass DRM or site restrictions.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerActivity : AppCompatActivity() {
    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var errorView: TextView
    private lateinit var topBar: LinearLayout
    private lateinit var quickControls: LinearLayout
    private lateinit var speedButton: TextView
    private lateinit var fitButton: TextView
    private val speeds = floatArrayOf(0.5f, 1f, 1.25f, 1.5f, 2f)
    private var speedIndex = 1
    private var fitIndex = 0
    private val fitModes = intArrayOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT,
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
        AspectRatioFrameLayout.RESIZE_MODE_FILL
    )
    private val fitLabels = arrayOf("FIT", "ZOOM", "FILL")
    private var source: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        source = intent?.data
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            clipChildren = false
        }
        playerView = PlayerView(this).apply {
            useController = true
            controllerShowTimeoutMs = 3500
            setShowSubtitleButton(true)
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShutterBackgroundColor(Color.BLACK)
            setKeepContentOnPlayerReset(true)
        }
        root.addView(playerView, FrameLayout.LayoutParams(-1, -1))

        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = gradientSurface(top = true)
        }
        val close = control("‹", "Back") { finish() }.apply {
            textSize = 30f
        }
        topBar.addView(close, LinearLayout.LayoutParams(dp(42), dp(42)))
        val titleColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val title = label(displayTitle(), 15f, Color.WHITE, 1).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        titleColumn.addView(title, LinearLayout.LayoutParams(-1, -2))
        titleColumn.addView(label("VEYRA PLAYER", 10f, 0xFF9CAAC0.toInt(), 1).apply {
            letterSpacing = 0.14f
            setPadding(0, dp(3), 0, 0)
        })
        topBar.addView(titleColumn, LinearLayout.LayoutParams(0, -2, 1f))
        topBar.addView(control("↻", "Rotate screen") {
            requestedOrientation = if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE)
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }, LinearLayout.LayoutParams(dp(42), dp(42)))
        root.addView(topBar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        quickControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = rounded(Color.argb(210, 14, 20, 30), dp(24).toFloat(), ThemeMath.mix(Color.BLACK, Palette.ACCENT, 0.42f))
        }
        speedButton = control("1×", "Playback speed") {
            speedIndex = (speedIndex + 1) % speeds.size
            player?.setPlaybackSpeed(speeds[speedIndex])
            speedButton.text = speedLabel()
        }
        fitButton = control("FIT", "Video fit mode") {
            fitIndex = (fitIndex + 1) % fitModes.size
            playerView.resizeMode = fitModes[fitIndex]
            fitButton.text = fitLabels[fitIndex]
        }
        quickControls.addView(speedButton, LinearLayout.LayoutParams(dp(64), dp(40)))
        val divider = View(this).apply { setBackgroundColor(0x557F91AA) }
        quickControls.addView(divider, LinearLayout.LayoutParams(dp(1), dp(22)))
        quickControls.addView(fitButton, LinearLayout.LayoutParams(dp(64), dp(40)))
        root.addView(quickControls, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
            topMargin = dp(70)
            rightMargin = dp(14)
        })

        errorView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(28), dp(28), dp(28))
            background = rounded(Color.argb(225, 16, 23, 34), dp(20).toFloat(), 0x5539D8FF)
            visibility = View.GONE
        }
        root.addView(errorView, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER).apply {
            leftMargin = dp(24)
            rightMargin = dp(24)
        })

        playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility: Int ->
            topBar.visibility = visibility
            quickControls.visibility = visibility
        })
        setContentView(root)
    }

    private fun displayTitle(): String {
        val raw = source?.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: intent?.getStringExtra("title")?.takeIf { it.isNotBlank() }
            ?: "Now playing"
        return Uri.decode(raw).substringAfterLast(':').take(72)
    }

    private fun rounded(color: Int, radius: Float, stroke: Int = Color.TRANSPARENT) =
        GradientDrawable().apply {
            cornerRadius = radius
            setColor(color)
            if (stroke != Color.TRANSPARENT) setStroke(dp(1), stroke)
        }

    private fun gradientSurface(top: Boolean) = GradientDrawable(
        if (top) GradientDrawable.Orientation.TOP_BOTTOM else GradientDrawable.Orientation.BOTTOM_TOP,
        intArrayOf(Color.argb(230, 0, 0, 0), Color.argb(0, 0, 0, 0))
    )

    private fun control(textValue: String, description: String, action: () -> Unit) =
        TextView(this).apply {
            text = textValue
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            contentDescription = description
            isFocusable = true
            isClickable = true
            background = rounded(Color.argb(190, 27, 35, 48), dp(16).toFloat(), ThemeMath.mix(Color.BLACK, Palette.ACCENT, 0.45f))
            setOnClickListener { action() }
        }

    private fun speedLabel() = speeds[speedIndex].let {
        if (it % 1f == 0f) "${it.toInt()}×" else "${it}×"
    }

    private fun prefs() = getSharedPreferences("veyra_player", MODE_PRIVATE)

    override fun onStart() {
        super.onStart()
        val uri = source
        if (uri == null) {
            showError("Nothing to play. Open a video file or a direct media link.")
            return
        }
        val exo = ExoPlayer.Builder(this).build()
        player = exo
        playerView.player = exo
        exo.setMediaItem(MediaItem.fromUri(uri))
        exo.playbackParameters = exo.playbackParameters.withSpeed(speeds[speedIndex])
        val saved = prefs().getLong("pos:$uri", 0L)
        if (saved > 0) exo.seekTo(saved)
        exo.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                showError("Can't play this video (${error.errorCodeName}). The format may be unsupported or the source may block direct playback.")
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) prefs().edit().remove("pos:$uri").apply()
                if (state == Player.STATE_READY) errorView.visibility = View.GONE
            }
        })
        exo.prepare()
        exo.playWhenReady = true
        hideSystemBars()
    }

    private fun showError(message: String) {
        errorView.text = message
        errorView.visibility = View.VISIBLE
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= 26 && player?.isPlaying == true) {
            enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())
        }
    }

    override fun onStop() {
        savePosition()
        playerView.player = null
        player?.release()
        player = null
        super.onStop()
    }

    private fun savePosition() {
        val exo = player ?: return
        val uri = source ?: return
        if (exo.playbackState != Player.STATE_ENDED && exo.currentPosition > 0) {
            prefs().edit().putLong("pos:$uri", exo.currentPosition).apply()
        }
    }
}
