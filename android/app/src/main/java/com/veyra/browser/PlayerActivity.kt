package com.veyra.browser

import android.app.PictureInPictureParams
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

/**
 * Built-in player for local files and direct media URLs the user opened. No DRM and no
 * background playback: playback stops when the screen is left (except picture-in-picture).
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerActivity : AppCompatActivity() {
    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var errorView: TextView
    private lateinit var speedButton: TextView
    private val speeds = floatArrayOf(0.5f, 1f, 1.25f, 1.5f, 2f)
    private var speedIndex = 1
    private var source: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        source = intent?.data
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.BLACK
        window.navigationBarColor = android.graphics.Color.BLACK

        val root = FrameLayout(this).apply { setBackgroundColor(android.graphics.Color.BLACK) }
        playerView = PlayerView(this).apply {
            setShowSubtitleButton(true)
            controllerShowTimeoutMs = 3000
        }
        root.addView(playerView, FrameLayout.LayoutParams(-1, -1))

        errorView = TextView(this).apply {
            setTextColor(Palette.TEXT)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(32), dp(32), dp(32))
            visibility = View.GONE
        }
        root.addView(errorView, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))

        speedButton = TextView(this).apply {
            text = "1x"
            textSize = 14f
            setTextColor(Palette.ACCENT)
            gravity = Gravity.CENTER
            contentDescription = "Playback speed"
            setPadding(dp(18), 0, dp(18), 0)
            neu(this, 22, Depth.BUTTON, pressable = true)
            setOnClickListener {
                speedIndex = (speedIndex + 1) % speeds.size
                player?.setPlaybackSpeed(speeds[speedIndex])
                text = speedLabel()
            }
        }
        root.addView(speedButton, FrameLayout.LayoutParams(dp(72), dp(44), Gravity.TOP or Gravity.END).apply {
            topMargin = dp(40); rightMargin = dp(28)
        })
        root.clipChildren = false
        setContentView(root)
    }

    private fun speedLabel() = speeds[speedIndex].let { if (it % 1f == 0f) "${it.toInt()}x" else "${it}x" }

    private fun prefs() = getSharedPreferences("veyra_player", MODE_PRIVATE)

    override fun onStart() {
        super.onStart()
        val uri = source
        if (uri == null) {
            showError("Nothing to play.")
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
                showError("This file cannot be played (${error.errorCodeName}). It may be corrupt or use an unsupported format.")
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) prefs().edit().remove("pos:$uri").apply()
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
