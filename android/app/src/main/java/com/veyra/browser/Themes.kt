package com.veyra.browser

import kotlin.math.pow

/** One selectable colour theme. Shadow colours are derived so every theme stays neumorphic. */
data class VeyraTheme(
    val key: String,
    val name: String,
    val bg: Int,
    val text: Int,
    val muted: Int,
    val accent: Int,
    val light: Boolean
) {
    val shadowDark: Int get() = ThemeMath.mix(bg, 0xFF000000.toInt(), if (light) 0.16f else 0.45f)
    val shadowLight: Int get() = ThemeMath.mix(bg, 0xFFFFFFFF.toInt(), if (light) 0.85f else 0.07f)
}

object ThemeMath {
    fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(shift: Int): Int {
            val x = (a shr shift) and 0xFF
            val y = (b shr shift) and 0xFF
            return (x + (y - x) * t).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun channel(v: Int): Double {
        val c = v / 255.0
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    fun luminance(color: Int): Double =
        0.2126 * channel((color shr 16) and 0xFF) +
            0.7152 * channel((color shr 8) and 0xFF) +
            0.0722 * channel(color and 0xFF)

    /** WCAG contrast ratio between two colours. */
    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }
}

object Themes {
    private fun c(hex: Long) = (0xFF000000 or hex).toInt()

    val all: List<VeyraTheme> = listOf(
        VeyraTheme("midnight", "Midnight Navy", c(0x0F1A2B), c(0xE8F0FB), c(0x8FA3BF), c(0x39D8FF), false),
        VeyraTheme("neon", "Neon Blue", c(0x080E1C), c(0xEAF4FF), c(0x8CA0C0), c(0x2E8BFF), false),
        VeyraTheme("black", "Pure Black", c(0x000000), c(0xF2F2F2), c(0x9A9A9A), c(0x4FC3F7), false),
        VeyraTheme("arctic", "Arctic White", c(0xE6ECF4), c(0x1B2A3C), c(0x4F5F74), c(0x0063B1), true),
        VeyraTheme("ocean", "Ocean Glass", c(0x0B2230), c(0xE3F4F8), c(0x86A9B6), c(0x3DE0D0), false),
        VeyraTheme("violet", "Violet Night", c(0x17132B), c(0xEDE8FF), c(0xA59CC8), c(0xB28CFF), false),
        VeyraTheme("emerald", "Emerald Dark", c(0x0E1F1A), c(0xE6F5EF), c(0x8DB0A3), c(0x3DDC97), false),
        VeyraTheme("crimson", "Crimson Dark", c(0x1F1114), c(0xFBE9EC), c(0xC39AA2), c(0xFF5C7A), false),
        VeyraTheme("graphite", "Graphite", c(0x1E2126), c(0xECEFF3), c(0x9AA1AB), c(0x8AB4FF), false),
        VeyraTheme("amoled", "AMOLED Blue", c(0x02060F), c(0xE8F0FB), c(0x8FA3BF), c(0x2EA8FF), false),
        VeyraTheme("sunset", "Sunset", c(0x241518), c(0xFFF0E6), c(0xC9A596), c(0xFF9A4D), false),
        VeyraTheme("frost", "Silver Frost", c(0xEDEFF2), c(0x20242B), c(0x535B67), c(0x1D5FD6), true),
        VeyraTheme("mocha", "Mocha Dune", c(0x1C1512), c(0xF5ECE6), c(0xBBA89C), c(0xD9A066), false),
        VeyraTheme("lavender", "Lavender Mist", c(0xECE8F5), c(0x241B3A), c(0x5A5075), c(0x6A3FD0), true),
        VeyraTheme("mint", "Mint Garden", c(0xE4F1EC), c(0x14302A), c(0x4A6860), c(0x0B7A5E), true)
    )

    const val SYSTEM = "system"

    fun byKey(key: String): VeyraTheme = all.firstOrNull { it.key == key } ?: all.first()

    /** Resolves "system" to Midnight Navy (dark) or Arctic White (light). */
    fun resolve(key: String, systemDark: Boolean): VeyraTheme =
        if (key == SYSTEM) byKey(if (systemDark) "midnight" else "arctic") else byKey(key)
}
