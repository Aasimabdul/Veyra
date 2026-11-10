package com.veyra.browser

import android.app.PictureInPictureParams
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TimeBar
import androidx.media3.ui.TrackSelectionDialogBuilder
import kotlin.math.abs

/**
 * Veyra's built-in player for local files and direct media URLs.
 * Tap shows controls, double-tap left/right seeks 10 s, swipe up/down on the left half changes
 * brightness and on the right half volume, swipe sideways scrubs, long-press plays at 2x.
 * If the hardware decoder fails it retries once with software decoders. It does not bypass DRM.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerActivity : AppCompatActivity() {
    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var controls: FrameLayout
    private lateinit var hud: TextView
    private lateinit var spinner: ProgressBar
    private lateinit var errorCard: LinearLayout
    private lateinit var errorText: TextView
    private lateinit var playButton: FrameLayout
    private lateinit var timeBar: DefaultTimeBar
    private lateinit var positionText: TextView
    private lateinit var durationText: TextView
    private lateinit var speedPill: TextView
    private lateinit var fitPill: TextView
    private lateinit var lockButton: TextView
    private lateinit var audio: AudioManager
    private val handler = Handler(Looper.getMainLooper())

    private val speeds = floatArrayOf(0.5f, 1f, 1.25f, 1.5f, 2f)
    private var speedIndex = 1
    private var fitIndex = 0
    private val fitModes = intArrayOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT,
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
        AspectRatioFrameLayout.RESIZE_MODE_FILL
    )
    private val fitLabels = arrayOf("Fit", "Zoom", "Fill")
    private var source: Uri? = null
    private var title: String = ""
    private var softwareOnly = false
    private var locked = false
    private var scrubbing = false
    private var userRotated = false
    private var autoRotated = false
    private var holdingSpeed = false

    private val hideControls = Runnable { setControlsVisible(false) }
    private val hideHud = Runnable { hud.visibility = View.GONE }
    private val ticker = object : Runnable {
        override fun run() {
            refreshProgress()
            handler.postDelayed(this, 400)
        }
    }

    // Software decoders first; used as a fallback when a hardware decoder cannot play a stream.
    private val softwareFirst = MediaCodecSelector { mime, secure, tunneling ->
        MediaCodecUtil.getDecoderInfos(mime, secure, tunneling).sortedBy { it.hardwareAccelerated }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        source = intent?.data
        title = displayTitle()
        audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        playerView = PlayerView(this).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShutterBackgroundColor(Color.BLACK)
            setKeepContentOnPlayerReset(true)
        }
        root.addView(playerView, FrameLayout.LayoutParams(-1, -1))
        root.addView(buildGestureLayer(), FrameLayout.LayoutParams(-1, -1))
        controls = buildControls()
        root.addView(controls, FrameLayout.LayoutParams(-1, -1))

        spinner = ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = android.content.res.ColorStateList.valueOf(Palette.ACCENT)
            visibility = View.GONE
        }
        root.addView(spinner, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER))

        hud = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(dp(22), dp(12), dp(22), dp(12))
            background = pill(Color.argb(170, 0, 0, 0), 18)
            visibility = View.GONE
        }
        root.addView(hud, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL or Gravity.TOP).apply { topMargin = dp(96) })

        lockButton = TextView(this).apply {
            text = "Unlock"
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = pill(Color.argb(170, 0, 0, 0), 20)
            visibility = View.GONE
            setOnClickListener { setLocked(false) }
        }
        root.addView(lockButton, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL or Gravity.START).apply { leftMargin = dp(18) })

        errorText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.15f)
        }
        errorCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(22), dp(22), dp(18))
            background = pill(Color.argb(235, 16, 22, 32), 22, 0x5539D8FF)
            visibility = View.GONE
            addView(errorText, LinearLayout.LayoutParams(-1, -2))
            addView(pillButton("Open with another app", "Open with another app") { openElsewhere() },
                LinearLayout.LayoutParams(-2, dp(42)).apply { topMargin = dp(16) })
        }
        root.addView(errorCard, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER).apply {
            leftMargin = dp(24); rightMargin = dp(24)
        })
        setContentView(root)
    }

    // ---- UI -----------------------------------------------------------------------------

    private fun buildControls(): FrameLayout {
        val layer = FrameLayout(this)

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(26))
            background = fade(top = true)
        }
        top.addView(roundButton("‹", 30f, "Back") { finish() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        top.addView(label(title, 15f, Color.WHITE, 1).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(12), 0, dp(8), 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        speedPill = pillButton("1×", "Playback speed") {
            speedIndex = (speedIndex + 1) % speeds.size
            player?.setPlaybackSpeed(speeds[speedIndex])
            speedPill.text = speedLabel()
            scheduleHide()
        }
        top.addView(speedPill, LinearLayout.LayoutParams(-2, dp(36)).apply { rightMargin = dp(8) })
        top.addView(pillButton("Audio", "Choose audio track") { pickTracks(C.TRACK_TYPE_AUDIO, "Audio tracks") },
            LinearLayout.LayoutParams(-2, dp(36)).apply { rightMargin = dp(8) })
        top.addView(pillButton("CC", "Subtitles") { pickTracks(C.TRACK_TYPE_TEXT, "Subtitles") },
            LinearLayout.LayoutParams(-2, dp(36)))
        layer.addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        val centre = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        centre.addView(roundButton("−10", 15f, "Back 10 seconds") { seekBy(-10_000) }, LinearLayout.LayoutParams(dp(52), dp(52)))
        playButton = FrameLayout(this).apply {
            contentDescription = "Play or pause"
            isClickable = true
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ThemeMath.mix(Color.BLACK, Palette.ACCENT, 0.30f))
                setStroke(dp(2), Palette.ACCENT)
            }
            setOnClickListener { togglePlay() }
        }
        centre.addView(playButton, LinearLayout.LayoutParams(dp(72), dp(72)).apply { leftMargin = dp(28); rightMargin = dp(28) })
        centre.addView(roundButton("+10", 15f, "Forward 10 seconds") { seekBy(10_000) }, LinearLayout.LayoutParams(dp(52), dp(52)))
        layer.addView(centre, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        updatePlayIcon(true)

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(30), dp(16), dp(10))
            background = fade(top = false)
        }
        val seekRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        positionText = label("0:00", 12f, Color.WHITE, 1)
        durationText = label("0:00", 12f, 0xFFB9C4D6.toInt(), 1)
        timeBar = DefaultTimeBar(this).apply {
            setPlayedColor(Palette.ACCENT)
            setScrubberColor(Palette.ACCENT)
            setBufferedColor(Color.argb(110, 255, 255, 255))
            setUnplayedColor(Color.argb(60, 255, 255, 255))
            addListener(object : TimeBar.OnScrubListener {
                override fun onScrubStart(timeBar: TimeBar, position: Long) { scrubbing = true; handler.removeCallbacks(hideControls) }
                override fun onScrubMove(timeBar: TimeBar, position: Long) { positionText.text = fmt(position) }
                override fun onScrubStop(timeBar: TimeBar, position: Long, canceled: Boolean) {
                    scrubbing = false
                    if (!canceled) player?.seekTo(position)
                    scheduleHide()
                }
            })
        }
        seekRow.addView(positionText, LinearLayout.LayoutParams(-2, -2))
        seekRow.addView(timeBar, LinearLayout.LayoutParams(0, dp(36), 1f).apply { leftMargin = dp(6); rightMargin = dp(6) })
        seekRow.addView(durationText, LinearLayout.LayoutParams(-2, -2))
        bottom.addView(seekRow, LinearLayout.LayoutParams(-1, -2))

        val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        tools.addView(pillButton("Lock", "Lock controls") { setLocked(true) }, LinearLayout.LayoutParams(-2, dp(36)))
        tools.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        fitPill = pillButton(fitLabels[0], "Video fit mode") {
            fitIndex = (fitIndex + 1) % fitModes.size
            playerView.resizeMode = fitModes[fitIndex]
            fitPill.text = fitLabels[fitIndex]
            scheduleHide()
        }
        tools.addView(fitPill, LinearLayout.LayoutParams(-2, dp(36)).apply { rightMargin = dp(8) })
        tools.addView(pillButton("Rotate", "Rotate screen") {
            userRotated = true
            requestedOrientation = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            scheduleHide()
        }, LinearLayout.LayoutParams(-2, dp(36)))
        bottom.addView(tools, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        layer.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        return layer
    }

    private fun buildGestureLayer(): View {
        val view = View(this)
        var mode = 0 // 0 none, 1 seek, 2 brightness, 3 volume
        var seekTarget = 0L
        var brightness = 0.5f
        var volume = 0f
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (locked) lockButton.visibility = if (lockButton.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                else setControlsVisible(controls.visibility != View.VISIBLE)
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (locked) return true
                if (e.x < view.width / 2f) seekBy(-10_000) else seekBy(10_000)
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (locked || player == null) return
                holdingSpeed = true
                player?.setPlaybackSpeed(2f)
                showHud("2× speed")
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                if (locked || e1 == null) return false
                val exo = player ?: return false
                if (mode == 0) {
                    mode = when {
                        abs(distanceX) > abs(distanceY) -> 1
                        e1.x < view.width / 2f -> 2
                        else -> 3
                    }
                    val current = window.attributes.screenBrightness
                    brightness = if (current >= 0f) current else 0.5f
                    val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                    volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
                    seekTarget = exo.currentPosition
                }
                when (mode) {
                    1 -> {
                        val duration = exo.duration.takeIf { it != C.TIME_UNSET } ?: return true
                        val span = (e2.x - e1.x) / view.width * 90_000f
                        seekTarget = (exo.currentPosition + span.toLong()).coerceIn(0L, duration)
                        showHud("${if (span >= 0) "+" else "−"}${abs(span.toLong() / 1000)}s   ${fmt(seekTarget)}")
                    }
                    2 -> {
                        brightness = (brightness + distanceY / view.height * 1.2f).coerceIn(0.02f, 1f)
                        window.attributes = window.attributes.apply { screenBrightness = brightness }
                        showHud("Brightness ${(brightness * 100).toInt()}%")
                    }
                    3 -> {
                        volume = (volume + distanceY / view.height * 1.2f).coerceIn(0f, 1f)
                        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (volume * max).toInt(), 0)
                        showHud("Volume ${(volume * 100).toInt()}%")
                    }
                }
                return true
            }
        })
        view.setOnTouchListener { _, event ->
            detector.onTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                if (mode == 1 && event.actionMasked == MotionEvent.ACTION_UP) player?.seekTo(seekTarget)
                if (holdingSpeed) { holdingSpeed = false; player?.setPlaybackSpeed(speeds[speedIndex]) }
                mode = 0
                handler.removeCallbacks(hideHud)
                handler.postDelayed(hideHud, 600)
            }
            true
        }
        return view
    }

    private fun pill(color: Int, radiusDp: Int, stroke: Int = Color.TRANSPARENT) = GradientDrawable().apply {
        cornerRadius = dpf(radiusDp.toFloat())
        setColor(color)
        if (stroke != Color.TRANSPARENT) setStroke(dp(1), stroke)
    }

    private fun fade(top: Boolean) = GradientDrawable(
        if (top) GradientDrawable.Orientation.TOP_BOTTOM else GradientDrawable.Orientation.BOTTOM_TOP,
        intArrayOf(Color.argb(200, 0, 0, 0), Color.argb(0, 0, 0, 0))
    )

    private fun roundButton(text: String, size: Float, description: String, action: () -> Unit) = TextView(this).apply {
        this.text = text
        textSize = size
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        contentDescription = description
        isClickable = true
        isFocusable = true
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.argb(120, 0, 0, 0)) }
        setOnClickListener { action(); scheduleHide() }
    }

    private fun pillButton(text: String, description: String, action: () -> Unit) = TextView(this).apply {
        this.text = text
        textSize = 13f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        contentDescription = description
        isClickable = true
        isFocusable = true
        minWidth = dp(46)
        setPadding(dp(14), 0, dp(14), 0)
        background = pill(Color.argb(120, 0, 0, 0), 18, Color.argb(70, 255, 255, 255))
        setOnClickListener { action() }
    }

    private fun updatePlayIcon(playing: Boolean) {
        playButton.removeAllViews()
        playButton.addView(
            icon(if (playing) Icons.PAUSE else Icons.PLAY, Color.WHITE, 30, filled = !playing),
            FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER)
        )
    }

    private fun displayTitle(): String {
        // MainActivity passes the actual filename as "name"; content URIs may otherwise
        // expose only an opaque MediaStore row ID in the player header.
        val raw = intent?.getStringExtra("name")?.takeIf { it.isNotBlank() }
            ?: intent?.getStringExtra("title")?.takeIf { it.isNotBlank() }
            ?: source?.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "Now playing"
        return Uri.decode(raw).substringAfterLast(':').take(72)
    }

    private fun fmt(ms: Long): String {
        val total = ms.coerceAtLeast(0) / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    private fun speedLabel() = speeds[speedIndex].let { if (it % 1f == 0f) "${it.toInt()}×" else "${it}×" }

    private fun showHud(text: String) {
        hud.text = text
        hud.visibility = View.VISIBLE
    }

    // ---- controls state ------------------------------------------------------------------

    private fun setControlsVisible(visible: Boolean) {
        controls.visibility = if (visible && !locked) View.VISIBLE else View.GONE
        handler.removeCallbacks(hideControls)
        if (controls.visibility == View.VISIBLE) scheduleHide()
    }

    private fun scheduleHide() {
        handler.removeCallbacks(hideControls)
        if (player?.isPlaying == true && !scrubbing) handler.postDelayed(hideControls, 3500)
    }

    private fun setLocked(value: Boolean) {
        locked = value
        controls.visibility = if (value) View.GONE else View.VISIBLE
        lockButton.visibility = if (value) View.VISIBLE else View.GONE
        if (!value) scheduleHide()
        if (value) Toast.makeText(this, "Controls locked. Tap to show Unlock.", Toast.LENGTH_SHORT).show()
    }

    private fun togglePlay() {
        val exo = player ?: return
        if (exo.playbackState == Player.STATE_ENDED) exo.seekTo(0)
        exo.playWhenReady = !exo.isPlaying
        scheduleHide()
    }

    private fun seekBy(deltaMs: Long) {
        val exo = player ?: return
        val duration = exo.duration.takeIf { it != C.TIME_UNSET } ?: Long.MAX_VALUE
        exo.seekTo((exo.currentPosition + deltaMs).coerceIn(0L, duration))
        showHud(if (deltaMs < 0) "−10s" else "+10s")
        handler.removeCallbacks(hideHud)
        handler.postDelayed(hideHud, 500)
    }

    private fun refreshProgress() {
        val exo = player ?: return
        if (scrubbing || controls.visibility != View.VISIBLE) return
        val duration = exo.duration.takeIf { it != C.TIME_UNSET } ?: 0L
        timeBar.setDuration(duration)
        timeBar.setPosition(exo.currentPosition)
        timeBar.setBufferedPosition(exo.bufferedPosition)
        positionText.text = fmt(exo.currentPosition)
        durationText.text = fmt(duration)
    }

    private fun pickTracks(type: Int, titleText: String) {
        val exo = player
        if (exo == null) {
            Toast.makeText(this, "Available after playback starts", Toast.LENGTH_SHORT).show()
            return
        }
        TrackSelectionDialogBuilder(this, titleText, exo, type).build().show()
    }

    // ---- playback ------------------------------------------------------------------------

    private fun prefs() = getSharedPreferences("veyra_player", MODE_PRIVATE)

    override fun onStart() {
        super.onStart()
        if (source == null) {
            showError("Nothing to play. Open a video file or a direct media link.")
            return
        }
        startPlayback(prefs().getLong("pos:$source", 0L))
        hideSystemBars()
    }

    private fun startPlayback(startAt: Long) {
        val uri = source ?: return
        player?.let { playerView.player = null; it.release() }
        val factory = DefaultRenderersFactory(this).setEnableDecoderFallback(true)
        if (softwareOnly) factory.setMediaCodecSelector(softwareFirst)
        val exo = ExoPlayer.Builder(this, factory).build()
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
        exo.setPlaybackSpeed(speeds[speedIndex])
        if (startAt > 0) exo.seekTo(startAt)
        exo.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                val decoderProblem = error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED ||
                    error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
                    error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ||
                    error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
                if (decoderProblem && !softwareOnly) {
                    softwareOnly = true
                    Toast.makeText(this@PlayerActivity, "Retrying with software decoding", Toast.LENGTH_SHORT).show()
                    startPlayback(player?.currentPosition ?: startAt)
                    return
                }
                showError(
                    if (decoderProblem) "This phone cannot decode this video (${error.errorCodeName}). " +
                        "It probably uses a codec or profile (for example 10-bit HEVC or AV1) that this device does not support."
                    else "Can't play this video (${error.errorCodeName}). The file may be damaged, or the source may block direct playback."
                )
            }

            override fun onPlaybackStateChanged(state: Int) {
                spinner.visibility = if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                if (state == Player.STATE_ENDED) {
                    prefs().edit().remove("pos:$uri").apply()
                    setControlsVisible(true)
                }
                if (state == Player.STATE_READY) errorCard.visibility = View.GONE
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updatePlayIcon(isPlaying)
                if (isPlaying) scheduleHide() else handler.removeCallbacks(hideControls)
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                // Landscape videos start in landscape once, unless the user already rotated manually.
                if (!autoRotated && !userRotated && videoSize.width > videoSize.height) {
                    autoRotated = true
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                }
            }
        })
        exo.prepare()
        exo.playWhenReady = true
        handler.removeCallbacks(ticker)
        handler.post(ticker)
        setControlsVisible(true)
    }

    private fun showError(message: String) {
        errorText.text = message
        errorCard.visibility = View.VISIBLE
        spinner.visibility = View.GONE
        setControlsVisible(true)
    }

    private fun openElsewhere() {
        val uri = source ?: return
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, contentResolver.getType(uri) ?: "video/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            val chooser = Intent.createChooser(view, "Open with").apply {
                putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(this@PlayerActivity, PlayerActivity::class.java)))
            }
            startActivity(chooser)
        } catch (_: Exception) {
            Toast.makeText(this, "No other app can open this file", Toast.LENGTH_SHORT).show()
        }
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= 26 && player?.isPlaying == true) {
            enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        controls.visibility = if (isInPictureInPictureMode) View.GONE else View.VISIBLE
    }

    override fun onStop() {
        savePosition()
        handler.removeCallbacksAndMessages(null)
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
