package com.veyra.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemesTest {
    @Test fun hasFifteenDistinctThemesEachWithAWallpaper() {
        assertEquals(15, Themes.all.size)
        assertTrue(Wallpapers.covers(Themes.all.map { it.key }))
        assertEquals(Themes.all.size, Themes.all.map { it.key }.toSet().size)
        assertEquals(Themes.all.size, Themes.all.map { Triple(it.bg, it.accent, it.text) }.toSet().size)
    }

    @Test fun textAndMutedTextAreReadableOnEveryTheme() {
        Themes.all.forEach { t ->
            assertTrue("${t.name} text", ThemeMath.contrast(t.text, t.bg) >= 7.0)
            assertTrue("${t.name} muted", ThemeMath.contrast(t.muted, t.bg) >= 4.5)
        }
    }

    @Test fun accentIsVisibleOnEveryTheme() {
        Themes.all.forEach { t -> assertTrue("${t.name} accent", ThemeMath.contrast(t.accent, t.bg) >= 3.0) }
    }

    @Test fun shadowsSitOnTheCorrectSideOfTheBackground() {
        Themes.all.forEach { t ->
            assertTrue("${t.name} dark shadow", ThemeMath.luminance(t.shadowDark) <= ThemeMath.luminance(t.bg))
            assertTrue("${t.name} light shadow", ThemeMath.luminance(t.shadowLight) >= ThemeMath.luminance(t.bg))
        }
    }

    @Test fun systemThemeFollowsDarkMode() {
        assertEquals("midnight", Themes.resolve(Themes.SYSTEM, true).key)
        assertEquals("arctic", Themes.resolve(Themes.SYSTEM, false).key)
        assertEquals("sunset", Themes.resolve("sunset", true).key)
        assertEquals("midnight", Themes.resolve("unknown", false).key)
    }
}
