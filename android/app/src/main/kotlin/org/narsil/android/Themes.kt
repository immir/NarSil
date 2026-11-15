package org.narsil.android

import kotlin.math.pow

/**
 * Colour themes for the map and lists: each recolours the game's 29 Angband colours from a dozen base hues,
 * as terminal schemes do, and every hue that would be hard to read on black is lifted towards white until it
 * reaches [Theme.minLuminance].  Black, the dark greys and the shade (backgrounds, unseen grids) are never touched.
 */
class Theme(
    val name: String,
    private val hues: Hues?,
    private val minLuminance: Double,
) {
    /** Base hues as 0xRRGGBB; the rest of the palette is mixed from these. */
    class Hues(val fg: Int, val grey: Int, val dim: Int, val red: Int, val green: Int, val blue: Int, val yellow: Int,
               val orange: Int, val magenta: Int, val violet: Int, val cyan: Int, val brown: Int)

    /** [game] is the game's own palette (0xRRGGBB per Angband colour index); the result is ARGB. */
    fun apply(game: IntArray): IntArray {
        val p = game.copyOf()
        hues?.let { h ->
            fun set(i: Int, rgb: Int) { if (i < p.size) p[i] = rgb }
            set(COLOUR_WHITE, h.fg); set(COLOUR_SLATE, h.grey); set(COLOUR_ORANGE, h.orange); set(COLOUR_RED, h.red)
            set(COLOUR_GREEN, h.green); set(COLOUR_BLUE, h.blue); set(COLOUR_UMBER, h.brown); set(COLOUR_L_DARK, h.dim)
            set(COLOUR_L_WHITE, mix(h.fg, h.grey, 0.4)); set(COLOUR_L_PURPLE, lighter(h.magenta)); set(COLOUR_YELLOW, h.yellow)
            set(COLOUR_L_RED, lighter(h.red)); set(COLOUR_L_GREEN, lighter(h.green)); set(COLOUR_L_BLUE, lighter(h.blue))
            set(COLOUR_L_UMBER, lighter(h.brown)); set(COLOUR_PURPLE, h.magenta); set(COLOUR_VIOLET, h.violet); set(COLOUR_TEAL, h.cyan)
            set(COLOUR_MUD, mix(h.brown, h.green, 0.5)); set(COLOUR_L_YELLOW, lighter(h.yellow)); set(COLOUR_MAGENTA, h.magenta)
            set(COLOUR_L_TEAL, lighter(h.cyan)); set(COLOUR_L_VIOLET, lighter(h.violet)); set(COLOUR_L_PINK, lighter(h.red, 0.5))
            set(COLOUR_MUSTARD, mix(h.yellow, h.brown, 0.5)); set(COLOUR_BLUE_SLATE, mix(h.blue, h.fg, 0.5)); set(COLOUR_DEEP_L_BLUE, lighter(h.blue))
        }
        return IntArray(p.size) { i -> 0xFF000000.toInt() or if (i in UNTOUCHED) p[i] else lift(p[i], minLuminance) }
    }

    /** A handful of colours for the options preview. */
    fun swatch(game: IntArray): List<Int> = apply(game).let { p -> listOf(COLOUR_RED, COLOUR_ORANGE, COLOUR_YELLOW, COLOUR_GREEN, COLOUR_TEAL, COLOUR_BLUE, COLOUR_VIOLET, COLOUR_WHITE).map { p[it] } }

    companion object {
        const val COLOUR_DARK = 0; const val COLOUR_WHITE = 1; const val COLOUR_SLATE = 2; const val COLOUR_ORANGE = 3; const val COLOUR_RED = 4
        const val COLOUR_GREEN = 5; const val COLOUR_BLUE = 6; const val COLOUR_UMBER = 7; const val COLOUR_L_DARK = 8; const val COLOUR_L_WHITE = 9
        const val COLOUR_L_PURPLE = 10; const val COLOUR_YELLOW = 11; const val COLOUR_L_RED = 12; const val COLOUR_L_GREEN = 13
        const val COLOUR_L_BLUE = 14; const val COLOUR_L_UMBER = 15; const val COLOUR_PURPLE = 16; const val COLOUR_VIOLET = 17
        const val COLOUR_TEAL = 18; const val COLOUR_MUD = 19; const val COLOUR_L_YELLOW = 20; const val COLOUR_MAGENTA = 21
        const val COLOUR_L_TEAL = 22; const val COLOUR_L_VIOLET = 23; const val COLOUR_L_PINK = 24; const val COLOUR_MUSTARD = 25
        const val COLOUR_BLUE_SLATE = 26; const val COLOUR_DEEP_L_BLUE = 27; const val COLOUR_SHADE = 28

        /** Backgrounds and the dim greys that mean "remembered" or "unseen": they must stay dark. */
        private val UNTOUCHED = setOf(COLOUR_DARK, COLOUR_L_DARK, COLOUR_SHADE)

        val ALL: List<Theme> = listOf(
            Theme("Bright", null, 0.22),
            Theme("Original", null, 0.0),
            Theme("High contrast", null, 0.40),
            Theme("Solarized", Hues(fg = 0xFDF6E3, grey = 0x93A1A1, dim = 0x586E75, red = 0xDC322F, green = 0x859900, blue = 0x268BD2,
                yellow = 0xB58900, orange = 0xCB4B16, magenta = 0xD33682, violet = 0x6C71C4, cyan = 0x2AA198, brown = 0x9C6B30), 0.20),
            Theme("Dracula", Hues(fg = 0xF8F8F2, grey = 0xBFBFC8, dim = 0x6272A4, red = 0xFF5555, green = 0x50FA7B, blue = 0x6D9CFF,
                yellow = 0xF1FA8C, orange = 0xFFB86C, magenta = 0xFF79C6, violet = 0xBD93F9, cyan = 0x8BE9FD, brown = 0xC08A58), 0.22),
            Theme("Gruvbox", Hues(fg = 0xEBDBB2, grey = 0xA89984, dim = 0x665C54, red = 0xFB4934, green = 0xB8BB26, blue = 0x83A598,
                yellow = 0xFABD2F, orange = 0xFE8019, magenta = 0xD3869B, violet = 0xB16286, cyan = 0x8EC07C, brown = 0xBD7A3A), 0.22),
        )

        fun byIndex(i: Int): Theme = ALL[i.coerceIn(0, ALL.lastIndex)]

        private fun channel(rgb: Int, shift: Int) = (rgb shr shift) and 0xFF

        /** [a] towards [b] by [t] (0..1), per channel. */
        fun mix(a: Int, b: Int, t: Double): Int = listOf(16, 8, 0).fold(0) { acc, shift ->
            (acc shl 8) or (channel(a, shift) * (1 - t) + channel(b, shift) * t).toInt().coerceIn(0, 255)
        }

        private fun lighter(rgb: Int, t: Double = 0.35) = mix(rgb, 0xFFFFFF, t)

        /** Relative luminance (WCAG) of 0xRRGGBB. */
        fun luminance(rgb: Int): Double {
            fun lin(c: Int) = (c / 255.0).let { if (it <= 0.03928) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
            return 0.2126 * lin(channel(rgb, 16)) + 0.7152 * lin(channel(rgb, 8)) + 0.0722 * lin(channel(rgb, 0))
        }

        /** Mixes [rgb] towards white in small steps until it is at least [min] luminance. */
        fun lift(rgb: Int, min: Double): Int =
            (0..20).map { lighter(rgb, it * 0.05) }.firstOrNull { luminance(it) >= min } ?: 0xFFFFFF
    }
}
