package com.veyra.browser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.PathParser
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Colour tokens taken from the Veyra neumorphic mockups. */
object Palette {
    val BG = Color.parseColor("#0F1A2B")
    val SHADOW_DARK = Color.parseColor("#070E19")
    val SHADOW_LIGHT = Color.parseColor("#182A42")
    val TEXT = Color.parseColor("#E8F0FB")
    val MUTED = Color.parseColor("#8FA3BF")
    val ACCENT = Color.parseColor("#39D8FF")
}

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
fun Context.dpf(value: Float): Float = value * resources.displayMetrics.density

/** How deep a neumorphic surface is (offset / blur, in dp), matching the mockup shadows. */
enum class Depth(val offset: Float, val blur: Float) {
    CARD(6f, 14f),
    BUTTON(4f, 10f),
    FIELD(5f, 10f),
    WELL(3f, 7f),
    KNOB(2f, 4f)
}

/**
 * Soft two-sided neumorphic surface. The shadows are rendered once per size into a bitmap, so
 * drawing stays cheap on low-RAM devices. Raised surfaces draw their shadows outside the view
 * bounds; ancestors must therefore keep clipChildren = false and leave a gutter at the edges.
 */
class NeuDrawable(
    private val radiusPx: Float,
    private val offsetPx: Float,
    private val blurPx: Float,
    private val inset: Boolean,
    private val fill: Int = Palette.BG
) : Drawable() {
    private var cache: Bitmap? = null
    private val extent: Int = if (inset) 0 else ceil(offsetPx + blurPx * 0.9f).toInt()

    override fun onBoundsChange(bounds: Rect) {
        cache = null
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val bitmap = cache ?: render(b.width(), b.height()).also { cache = it }
        canvas.drawBitmap(bitmap, (b.left - extent).toFloat(), (b.top - extent).toFloat(), null)
    }

    private fun blurRadius(): Float {
        // CSS blur radius B => Gaussian sigma B/2; Skia converts radius to sigma as r*0.57735+0.5.
        val sigma = blurPx / 2f
        return max(1f, (sigma - 0.5f) / 0.57735f)
    }

    private fun render(w: Int, h: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(w + 2 * extent, h + 2 * extent, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val rect = RectF(extent.toFloat(), extent.toFloat(), (extent + w).toFloat(), (extent + h).toFloat())
        val r = min(radiusPx, min(w, h) / 2f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        if (!inset) {
            paint.maskFilter = BlurMaskFilter(blurRadius(), BlurMaskFilter.Blur.NORMAL)
            paint.color = Palette.SHADOW_DARK
            canvas.save(); canvas.translate(offsetPx, offsetPx)
            canvas.drawRoundRect(rect, r, r, paint); canvas.restore()
            paint.color = Palette.SHADOW_LIGHT
            canvas.save(); canvas.translate(-offsetPx, -offsetPx)
            canvas.drawRoundRect(rect, r, r, paint); canvas.restore()
            paint.maskFilter = null
            paint.color = fill
            canvas.drawRoundRect(rect, r, r, paint)
        } else {
            paint.color = fill
            canvas.drawRoundRect(rect, r, r, paint)
            val clip = Path().apply { addRoundRect(rect, r, r, Path.Direction.CW) }
            canvas.save()
            canvas.clipPath(clip)
            val frame = Path().apply {
                fillType = Path.FillType.EVEN_ODD
                val big = offsetPx + blurPx * 2f + 8f
                addRect(rect.left - big, rect.top - big, rect.right + big, rect.bottom + big, Path.Direction.CW)
                addRoundRect(rect, r, r, Path.Direction.CW)
            }
            paint.maskFilter = BlurMaskFilter(blurRadius(), BlurMaskFilter.Blur.NORMAL)
            paint.color = Palette.SHADOW_DARK
            canvas.save(); canvas.translate(offsetPx, offsetPx); canvas.drawPath(frame, paint); canvas.restore()
            paint.color = Palette.SHADOW_LIGHT
            canvas.save(); canvas.translate(-offsetPx, -offsetPx); canvas.drawPath(frame, paint); canvas.restore()
            canvas.restore()
        }
        return bitmap
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** Applies a raised (or inset) neumorphic background. [pressable] swaps to inset while pressed. */
fun Context.neu(
    view: View,
    radiusDp: Int,
    depth: Depth = Depth.CARD,
    inset: Boolean = false,
    pressable: Boolean = false,
    fill: Int = Palette.BG
): View {
    val radius = dpf(radiusDp.toFloat())
    fun make(isInset: Boolean) = NeuDrawable(radius, dpf(depth.offset), dpf(depth.blur), isInset, fill)
    view.background = if (pressable && !inset) {
        StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), make(true))
            addState(intArrayOf(), make(false))
        }
    } else make(inset)
    view.isClickable = view.isClickable || pressable
    view.isFocusable = view.isFocusable || pressable
    return view
}

/** Stroke icon drawn from 24x24 SVG path data (same geometry as the mockup icons). */
class IconDrawable(
    paths: List<String>,
    color: Int,
    private val strokeWidth: Float = 1.8f,
    private val filled: Boolean = false
) : Drawable() {
    private val parsed = paths.map { PathParser.createPathFromPathData(it) }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val matrix = android.graphics.Matrix()
    private val scaled = Path()

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val scale = min(b.width(), b.height()) / 24f
        matrix.setScale(scale, scale)
        matrix.postTranslate(b.left + (b.width() - 24f * scale) / 2f, b.top + (b.height() - 24f * scale) / 2f)
        paint.style = if (filled) Paint.Style.FILL_AND_STROKE else Paint.Style.STROKE
        paint.strokeWidth = strokeWidth * scale
        parsed.forEach { p ->
            scaled.reset()
            p.transform(matrix, scaled)
            canvas.drawPath(scaled, paint)
        }
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

object Icons {
    val HOME = listOf("M4 11.5 12 4l8 7.5", "M6 10.5V20h12v-9.5")
    val TABS = listOf("M8 5h8a4 4 0 0 1 4 4v6a4 4 0 0 1-4 4H8a4 4 0 0 1-4-4V9a4 4 0 0 1 4-4z", "M4 10h16")
    val BOOKMARK = listOf("M7 4h10v16l-5-3.5L7 20z")
    val DOWNLOAD = listOf("M12 4v11", "m7.5 11 4.5 4.5 4.5-4.5", "M5 19h14")
    val SETTINGS = listOf(
        "M5 8h14", "M5 16h14",
        "M6.8 8a2.2 2.2 0 1 0 4.4 0a2.2 2.2 0 1 0-4.4 0",
        "M12.8 16a2.2 2.2 0 1 0 4.4 0a2.2 2.2 0 1 0-4.4 0"
    )
    val SEARCH = listOf("M4.5 11a6.5 6.5 0 1 0 13 0a6.5 6.5 0 1 0-13 0", "m16 16 4 4")
    val CLOCK = listOf("M4 12a8 8 0 1 0 16 0a8 8 0 1 0-16 0", "M12 8v4l2.5 2")
    val TRASH = listOf("M5 7h14", "M9 7V5h6v2", "M7 7l1 12h8l1-12")
    val PLUS = listOf("M12 5v14M5 12h14")
    val CLOSE = listOf("M6 6l12 12M18 6L6 18")
    val BACK = listOf("M15 5l-7 7 7 7")
    val FORWARD = listOf("M9 5l7 7-7 7")
    val SHARE = listOf("M12 15V4", "m8 8 4-4 4 4", "M6 12v6a2 2 0 0 0 2 2h8a2 2 0 0 0 2-2v-6")
    val MENU = listOf(
        "M10.6 6a1.4 1.4 0 1 0 2.8 0a1.4 1.4 0 1 0-2.8 0",
        "M10.6 12a1.4 1.4 0 1 0 2.8 0a1.4 1.4 0 1 0-2.8 0",
        "M10.6 18a1.4 1.4 0 1 0 2.8 0a1.4 1.4 0 1 0-2.8 0"
    )
    val SHIELD = listOf("M12 3l7 3v5c0 4.5-3 8-7 10-4-2-7-5.5-7-10V6z", "m9 12 2 2 4-4")
    val SHIELD_OFF = listOf("M12 3l7 3v5c0 4.5-3 8-7 10-4-2-7-5.5-7-10V6z")
    val LOCK = listOf("M7 11V8a5 5 0 0 1 10 0v3", "M8 11h8a2 2 0 0 1 2 2v5a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2v-5a2 2 0 0 1 2-2z")
    val FINGERPRINT = listOf(
        "M3.5 10.5a8.5 8.5 0 0 1 17 0v1",
        "M6.5 12a5.5 5.5 0 0 1 11 0v2.5",
        "M9.5 21c1-1.8 1.6-3.6 1.6-6.5a1 1 0 0 1 2 0c0 3-.6 5.2-1.6 7",
        "M6.2 14.5c0 2-.3 3.5-1 5",
        "M15.8 21c.6-1.4.9-3 .9-4.5V13"
    )
    val RELOAD = listOf("M20 12a8 8 0 1 1-2.5-5.8", "M20 4v4h-4")
    val CHEVRON = listOf("M9 5l7 7-7 7")
    val PAUSE = listOf("M9 6v12M15 6v12")
    val PLAY = listOf("M8 5l11 7-11 7z")
    val INFO = listOf("M4 12a8 8 0 1 0 16 0a8 8 0 1 0-16 0", "M12 11v5", "M12 8h.01")
}

fun Context.icon(paths: List<String>, color: Int = Palette.MUTED, sizeDp: Int = 22, filled: Boolean = false): ImageView =
    ImageView(this).apply {
        setImageDrawable(IconDrawable(paths, color, filled = filled))
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

fun Context.label(
    text: String,
    sizeSp: Float = 15f,
    color: Int = Palette.TEXT,
    weight: Int = 0
): TextView = TextView(this).apply {
    this.text = text
    textSize = sizeSp
    setTextColor(color)
    includeFontPadding = false
    typeface = when (weight) {
        0 -> Typeface.DEFAULT
        else -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
}

fun Context.column(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    clipChildren = false
    clipToPadding = false
}

fun Context.row(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    clipChildren = false
    clipToPadding = false
}

fun lp(width: Int, height: Int, weight: Float = 0f) = LinearLayout.LayoutParams(width, height, weight)

/** Round raised icon button (mockup header / toolbar buttons). */
fun Context.iconButton(
    paths: List<String>,
    description: String,
    color: Int = Palette.MUTED,
    sizeDp: Int = 44,
    radiusDp: Int = 16,
    filled: Boolean = false,
    onClick: () -> Unit
): View {
    val box = LinearLayout(this).apply {
        gravity = Gravity.CENTER
        contentDescription = description
        setOnClickListener { onClick() }
    }
    neu(box, radiusDp, Depth.BUTTON, pressable = true)
    box.addView(icon(paths, color, 20, filled))
    box.layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    return box
}

/** Raised pill text button; [primary] uses the accent colour. */
fun Context.textButton(text: String, primary: Boolean = false, onClick: () -> Unit): TextView =
    label(text, 14f, if (primary) Palette.ACCENT else Palette.TEXT, 1).apply {
        gravity = Gravity.CENTER
        minHeight = dp(48)
        setPadding(dp(20), 0, dp(20), 0)
        setOnClickListener { onClick() }
        neu(this, 24, Depth.BUTTON, pressable = true)
    }

/** Leading inset "well" holding an icon or a letter, as used in list rows. */
fun Context.well(content: View, sizeDp: Int = 44, radiusDp: Int = 15): View =
    LinearLayout(this).apply {
        gravity = Gravity.CENTER
        neu(this, radiusDp, Depth.WELL, inset = true)
        addView(content)
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

fun Context.letterWell(letter: String, sizeDp: Int = 44): View =
    well(label(letter.take(1).uppercase(), 17f, Palette.ACCENT, 1).apply { gravity = Gravity.CENTER }, sizeDp)

/** Raised list row: leading well, title + subtitle, optional trailing view. */
fun Context.listRow(
    leading: View,
    title: String,
    subtitle: String,
    trailing: View? = null,
    onClick: (() -> Unit)? = null
): View {
    val card = row().apply {
        setPadding(dp(18), dp(14), dp(18), dp(14))
        neu(this, 24, Depth.CARD, pressable = onClick != null)
        if (onClick != null) setOnClickListener { onClick() }
    }
    card.addView(leading)
    val text = column().apply { setPadding(dp(14), 0, dp(8), 0) }
    text.addView(label(title, 15f, Palette.TEXT, 1).apply {
        maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
    })
    if (subtitle.isNotBlank()) {
        text.addView(label(subtitle, 13f, Palette.MUTED).apply {
            maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(4), 0, 0)
        })
    }
    card.addView(text, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    if (trailing != null) card.addView(trailing)
    return card
}

/** Neumorphic switch: inset track with a raised knob. */
class NeuSwitch(context: Context) : View(context) {
    var checked: Boolean = false
        set(value) { field = value; invalidate() }
    var onChange: ((Boolean) -> Unit)? = null
    private val track = NeuDrawable(context.dpf(15f), context.dpf(3f), context.dpf(7f), true)
    private val knobOn = NeuDrawable(context.dpf(13f), context.dpf(2f), context.dpf(4f), false, Palette.ACCENT)
    private val knobOff = NeuDrawable(context.dpf(13f), context.dpf(2f), context.dpf(4f), false, Color.parseColor("#22364F"))

    init {
        isClickable = true
        isFocusable = true
        setOnClickListener {
            checked = !checked
            onChange?.invoke(checked)
            announceForAccessibility(if (checked) "On" else "Off")
        }
        layoutParams = LinearLayout.LayoutParams(context.dp(54), context.dp(30))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(context.dp(54), context.dp(30))
    }

    override fun onDraw(canvas: Canvas) {
        track.setBounds(0, 0, width, height)
        track.draw(canvas)
        val d = context.dp(4)
        val size = height - 2 * d
        val left = if (checked) width - d - size else d
        val knob = if (checked) knobOn else knobOff
        knob.setBounds(left, d, left + size, d + size)
        knob.draw(canvas)
    }
}
