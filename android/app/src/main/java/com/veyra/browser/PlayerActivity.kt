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
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TrackSelectionDialogBuilder

/**
 * Clean, MX-style built-in player for local files and direct media URLs.
 * Uses Media3's accessible seek/playback controls; does not bypass DRM or site restrictions.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerActivity : AppCompatActivity() {
    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var errorView: TextView
    private lateinit var errorPanel: LinearLayout
    private lateinit var retryButton: TextView
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
        val qualityButton = control("QUALITY", "Choose video quality") {
            val activePlayer = player
            if (activePlayer == null) {
                showError("Video quality options are available after playback starts.")
            } else {
                TrackSelectionDialogBuilder(this, "Video quality", activePlayer, C.TRACK_TYPE_VIDEO)
                    .build()
                    .show()
            }
        }
        quickControls.addView(View(this).apply { setBackgroundColor(0x557F91AA) },
            LinearLayout.LayoutParams(dp(1), dp(22)))
        quickControls.addView(qualityButton, LinearLayout.LayoutParams(dp(78), dp(40)))
        val audioButton = control("AUDIO", "Choose audio track or language") {
            val activePlayer = player
            if (activePlayer == null) {
                showError("Audio tracks are available after playback starts.")
            } else {
                TrackSelectionDialogBuilder(this, "Audio tracks", activePlayer, C.TRACK_TYPE_AUDIO)
                    .build()
                    .show()
            }
        }
        quickControls.addView(View(this).apply { setBackgroundColor(0x557F91AA) },
            LinearLayout.LayoutParams(dp(1), dp(22)))
        quickControls.addView(audioButton, LinearLayout.LayoutParams(dp(70), dp(40)))
        root.addView(quickControls, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
            topMargin = dp(70)
            rightMargin = dp(14)
        })

        errorView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.15f)
        }
        retryButton = control("Retry playback", "Retry playback") { retryPlayback() }
        errorPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(22), dp(20), dp(22), dp(18))
            background = rounded(Color.argb(235, 16, 23, 34), dp(20).toFloat(), 0x5539D8FF)
            visibility = View.GONE
        }
        errorPanel.addView(errorView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        errorPanel.addView(retryButton, LinearLayout.LayoutParams(dp(164), dp(44)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(14)
        })
        root.addView(errorPanel, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER).apply {
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
        // Downloaded MediaStore/FileProvider URIs often end with a numeric ID, not a filename.
        // MainActivity passes the display name in "name"; prefer it over the URI path.
        val raw = intent?.getStringExtra("name")?.takeIf { it.isNotBlank() }
            ?: intent?.getStringExtra("title")?.takeIf { it.isNotBlank() }
            ?: source?.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
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
        val mediaItem = MediaItem.Builder().setUri(uri).apply {
            val requestedMime = intent?.getStringExtra("mime_type")?.substringBefore(';')?.trim()
            val playbackMime = when (requestedMime?.lowercase(java.util.Locale.ROOT)) {
                "application/vnd.apple.mpegurl", "audio/mpegurl" -> MimeTypes.APPLICATION_M3U8
                "application/dash+xml" -> MimeTypes.APPLICATION_MPD
                else -> requestedMime
            }
            if (!playbackMime.isNullOrBlank()) setMimeType(playbackMime)
        }.build()
        exo.setMediaItem(mediaItem)
        exo.playbackParameters = exo.playbackParameters.withSpeed(speeds[speedIndex])
        val saved = prefs().getLong("pos:$uri", 0L)
        if (saved > 0) exo.seekTo(saved)
        exo.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                showError(playbackErrorMessage(error))
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) prefs().edit().remove("pos:$uri").apply()
                if (state == Player.STATE_READY) errorPanel.visibility = View.GONE
            }
        })
        exo.prepare()
        exo.playWhenReady = true
        hideSystemBars()
    }

    private fun playbackErrorMessage(error: PlaybackException): String {
        val code = error.errorCodeName.uppercase(java.util.Locale.ROOT)
        return when {
            "DECODING_FORMAT_UNSUPPORTED" in code ->
                "This device does not support the video's audio or video codec."
            "DECODING" in code ->
                "The media decoder failed. The file may be damaged or use a codec this device cannot decode."
            "IO_BAD_HTTP_STATUS" in code ->
                "The media server returned an HTTP error. The link may have expired or access may be denied."
            "IO_NETWORK" in code || "IO_" in code ->
                "Veyra could not read the media source. Check the connection or try another link."
            "PARSING" in code ->
                "The media container or playlist is invalid or unsupported."
            else ->
                "Veyra could not play this source. It may be unavailable, incomplete, or require its original website player."
        }
    }

    private fun showError(message: String) {
        errorView.text = message
        retryButton.isEnabled = player != null
        retryButton.alpha = if (player != null) 1f else 0.45f
        errorPanel.visibility = View.VISIBLE
    }

    private fun retryPlayback() {
        val active = player ?: return
        errorPanel.visibility = View.GONE
        active.seekToDefaultPosition()
        active.prepare()
        active.playWhenReady = true
        hideSystemBars()
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
