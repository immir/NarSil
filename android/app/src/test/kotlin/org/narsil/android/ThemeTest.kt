package org.narsil.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeTest {
    /** The game's colours that matter here (z-color.c): blue is 0040ff, dark red c00000, shade 282828. */
    private val game = IntArray(29) { 0x808080 }.also {
        it[Theme.COLOUR_DARK] = 0x000000; it[Theme.COLOUR_BLUE] = 0x0040FF; it[Theme.COLOUR_RED] = 0xC00000
        it[Theme.COLOUR_L_DARK] = 0x606060; it[Theme.COLOUR_SHADE] = 0x282828
    }

    private fun rgb(argb: Int) = argb and 0xFFFFFF

    @Test fun originalKeepsTheGamesColours() {
        val p = Theme.ALL.first { it.name == "Original" }.apply(game)
        assertEquals(0x0040FF, rgb(p[Theme.COLOUR_BLUE]))
        assertEquals(0xC00000, rgb(p[Theme.COLOUR_RED]))
    }

    @Test fun brightLiftsDarkBlueToReadable() {
        val p = Theme.byIndex(0).apply(game)
        assertTrue(Theme.luminance(rgb(p[Theme.COLOUR_BLUE])) >= 0.22)
        assertTrue(Theme.luminance(0x0040FF) < 0.22)
    }

    @Test fun backgroundsAndDimGreysAreNeverLifted() = Theme.ALL.forEach { t ->
        val p = t.apply(game)
        assertEquals(0x000000, rgb(p[Theme.COLOUR_DARK]))
        assertEquals(0x282828, rgb(p[Theme.COLOUR_SHADE]))
        assertTrue(t.name, Theme.luminance(rgb(p[Theme.COLOUR_SHADE])) < 0.05)
    }

    @Test fun everyThemeLeavesEveryHueReadable() = Theme.ALL.filter { it.name != "Original" }.forEach { t ->
        val p = t.apply(game)
        listOf(Theme.COLOUR_RED, Theme.COLOUR_GREEN, Theme.COLOUR_BLUE, Theme.COLOUR_VIOLET, Theme.COLOUR_PURPLE, Theme.COLOUR_UMBER).forEach {
            assertTrue("${t.name} colour $it", Theme.luminance(rgb(p[it])) >= 0.15)
        }
    }

    @Test fun opaqueAndSameSize() = Theme.ALL.forEach { assertEquals(game.size, it.apply(game).size); assertTrue(it.apply(game).all { c -> (c ushr 24) == 0xFF }) }
}
