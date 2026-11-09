package com.veyra.browser

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.ColorFilter
import android.graphics.drawable.Drawable
import java.util.Random
import kotlin.math.PI
import kotlin.math.sin

/**
 * Home-screen wallpaper generated in code for each theme (no image files, so no APK growth).
 * Every layer is drawn at low alpha so text and cards keep their theme contrast.
 */
class WallpaperDrawable(private val theme: VeyraTheme) : Drawable() {
    enum class Style { AURORA, WAVES, RINGS, STARS, HORIZON, BANDS }

    private val style = Wallpapers.styleFor(theme.key)
    private val seed = theme.key.hashCode().toLong()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun accent(alpha: Int) = (alpha shl 24) or (theme.accent and 0xFFFFFF)
    private fun text(alpha: Int) = (alpha shl 24) or (theme.text and 0xFFFFFF)
    private val strength get() = if (theme.light) 0.8f else 1f
    private fun a(alpha: Int) = (alpha * strength).toInt()

    override fun draw(canvas: Canvas) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w <= 0f || h <= 0f) return
        canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = theme.bg
        canvas.drawRect(0f, 0f, w, h, paint)
        val random = Random(seed)
        when (style) {
            Style.AURORA -> aurora(canvas, w, h, random)
            Style.WAVES -> waves(canvas, w, h, random)
            Style.RINGS -> rings(canvas, w, h, random)
            Style.STARS -> stars(canvas, w, h, random)
            Style.HORIZON -> horizon(canvas, w, h)
            Style.BANDS -> bands(canvas, w, h, random)
        }
        paint.shader = null
        canvas.restore()
    }

    private fun blob(canvas: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        paint.shader = RadialGradient(cx, cy, r, color, color and 0xFFFFFF, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r, paint)
        paint.shader = null
    }

    private fun aurora(canvas: Canvas, w: Float, h: Float, r: Random) {
        blob(canvas, w * (0.1f + r.nextFloat() * 0.3f), h * (0.02f + r.nextFloat() * 0.18f), w * 0.95f, accent(a(0x3A)))
        blob(canvas, w * (0.6f + r.nextFloat() * 0.35f), h * (0.45f + r.nextFloat() * 0.2f), w * 0.85f, accent(a(0x22)))
        blob(canvas, w * (0.2f + r.nextFloat() * 0.5f), h * (0.85f + r.nextFloat() * 0.1f), w * 0.8f, accent(a(0x2A)))
    }

    private fun waves(canvas: Canvas, w: Float, h: Float, r: Random) {
        blob(canvas, w * 0.5f, h * 0.0f, w * 1.1f, accent(a(0x26)))
        for (i in 0 until 4) {
            val baseY = h * (0.5f + 0.1f * i)
            val amp = h * (0.018f + 0.006f * i)
            val phase = r.nextFloat() * (2 * PI).toFloat()
            val cycles = 1.2f + 0.5f * i
            val path = Path().apply {
                moveTo(0f, h)
                lineTo(0f, baseY)
                var x = 0f
                while (x <= w + 8f) {
                    lineTo(x, baseY + amp * sin(phase + x / w * cycles * 2 * PI.toFloat()))
                    x += 8f
                }
                lineTo(w, h)
                close()
            }
            paint.shader = LinearGradient(0f, baseY - amp, 0f, h, accent(a(0x2A - i * 4)), accent(a(0x06)), Shader.TileMode.CLAMP)
            canvas.drawPath(path, paint)
        }
        paint.shader = null
    }

    private fun rings(canvas: Canvas, w: Float, h: Float, r: Random) {
        val cx = w * (0.35f + r.nextFloat() * 0.3f)
        val cy = h * (0.22f + r.nextFloat() * 0.12f)
        blob(canvas, cx, cy, w * 0.9f, accent(a(0x2E)))
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f * (w / 360f).coerceAtLeast(1f)
        for (i in 1..7) {
            paint.color = accent(a(0x30 - i * 5).coerceAtLeast(0x06))
            canvas.drawCircle(cx, cy, w * 0.14f * i, paint)
        }
        paint.style = Paint.Style.FILL
    }

    private fun stars(canvas: Canvas, w: Float, h: Float, r: Random) {
        blob(canvas, w * 0.5f, h * 0.05f, w * 1.0f, accent(a(0x24)))
        for (i in 0 until 80) {
            val x = r.nextFloat() * w
            val y = r.nextFloat() * h
            val size = (0.6f + r.nextFloat() * 1.6f) * (w / 360f).coerceAtLeast(1f)
            paint.color = text(a(0x18 + r.nextInt(0x30)))
            canvas.drawCircle(x, y, size, paint)
        }
    }

    private fun horizon(canvas: Canvas, w: Float, h: Float) {
        paint.shader = LinearGradient(0f, h * 0.35f, 0f, h, theme.bg, ThemeMath.mix(theme.bg, theme.accent, if (theme.light) 0.18f else 0.22f), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, paint)
        blob(canvas, w * 0.5f, h * 0.7f, w * 0.55f, accent(a(0x55)))
        paint.shader = null
        paint.color = accent(a(0x30))
        canvas.drawCircle(w * 0.5f, h * 0.7f, w * 0.13f, paint)
    }

    private fun bands(canvas: Canvas, w: Float, h: Float, r: Random) {
        val skew = h * (0.25f + r.nextFloat() * 0.15f)
        val widths = floatArrayOf(0.34f, 0.2f, 0.14f)
        var x = -w * 0.2f
        for ((i, bw) in widths.withIndex()) {
            val path = Path().apply {
                moveTo(x, h)
                lineTo(x + w * bw, h)
                lineTo(x + w * bw + skew, 0f)
                lineTo(x + skew, 0f)
                close()
            }
            paint.color = accent(a(0x16 - i * 3))
            canvas.drawPath(path, paint)
            x += w * (bw + 0.22f)
        }
        blob(canvas, w * 0.85f, h * 0.1f, w * 0.7f, accent(a(0x22)))
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}

object Wallpapers {
    private val styles = mapOf(
        "midnight" to WallpaperDrawable.Style.AURORA,
        "neon" to WallpaperDrawable.Style.RINGS,
        "black" to WallpaperDrawable.Style.STARS,
        "arctic" to WallpaperDrawable.Style.BANDS,
        "ocean" to WallpaperDrawable.Style.WAVES,
        "violet" to WallpaperDrawable.Style.AURORA,
        "emerald" to WallpaperDrawable.Style.WAVES,
        "crimson" to WallpaperDrawable.Style.BANDS,
        "graphite" to WallpaperDrawable.Style.RINGS,
        "amoled" to WallpaperDrawable.Style.STARS,
        "sunset" to WallpaperDrawable.Style.HORIZON,
        "frost" to WallpaperDrawable.Style.BANDS,
        "mocha" to WallpaperDrawable.Style.HORIZON,
        "lavender" to WallpaperDrawable.Style.AURORA,
        "mint" to WallpaperDrawable.Style.WAVES
    )

    fun styleFor(key: String): WallpaperDrawable.Style = styles[key] ?: WallpaperDrawable.Style.AURORA

    /** True when every theme has its own wallpaper style entry. */
    fun covers(keys: Collection<String>): Boolean = keys.all { it in styles }
}
