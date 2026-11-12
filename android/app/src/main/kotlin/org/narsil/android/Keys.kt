package org.narsil.android

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.PI
import kotlin.math.atan2

/** Screen-space swipe -> octant: 0 = east, counting anticlockwise in 45-degree steps (y grows downward). */
fun octant(dx: Float, dy: Float): Int =
    Math.floorMod(Math.round(atan2(-dy, dx) / (PI / 4).toFloat()), 8)

/** Angband keycodes from src/ui-event.h. */
object Keys {
    const val ARROW_DOWN = 0x80
    const val ARROW_LEFT = 0x81
    const val ARROW_RIGHT = 0x82
    const val ARROW_UP = 0x83
    const val ENTER = 0x9C
    const val TAB = 0x9D
    const val BACKSPACE = 0x9F
    const val PAGE_UP = 0x95
    const val PAGE_DOWN = 0x97
    const val ESCAPE = 0xE000

    /** KTRL(): control characters are delivered as raw ASCII with no modifier, as main-gcu does. */
    fun ctrl(cp: Int): Int = cp and 0x1F
}

/**
 * Single funnel for every input source (IME, hardware keyboard, key bar, back button),
 * so the sticky Ctrl latch applies uniformly.
 */
class KeyRouter(
    private val sink: (code: Int, mods: Int) -> Unit = NarSilNative::nativePushKey,
    private val mouseSink: (x: Int, y: Int, button: Int, mods: Int) -> Unit = NarSilNative::nativePushMouse,
) {
    var ctrlLatched by mutableStateOf(false)

    /** While a native prompt is open it gets first refusal on every key; true = consumed. */
    var intercept: ((code: Int) -> Boolean)? = null

    fun char(cp: Int) {
        val code = if (ctrlLatched && cp in 0x40..0x7F) Keys.ctrl(cp) else cp
        ctrlLatched = false
        if (intercept?.invoke(code) != true) sink(code, 0)
    }

    fun control(cp: Int) {
        ctrlLatched = false
        sink(Keys.ctrl(cp), 0)
    }

    fun mouse(x: Int, y: Int, button: Int) {
        ctrlLatched = false
        mouseSink(x, y, button, 0)
    }

    /** octant: 0 = east, anticlockwise.  Cardinals as arrows (menus scroll); diagonals as keypad digits. */
    fun direction(octant: Int) {
        require(octant in 0..7)
        special(DIRECTION_KEYS[octant])
    }

    fun special(code: Int) {
        ctrlLatched = false
        if (intercept?.invoke(code) != true) sink(code, 0)
    }

    private companion object {
        val DIRECTION_KEYS = intArrayOf(
            Keys.ARROW_RIGHT, '9'.code, Keys.ARROW_UP, '7'.code,
            Keys.ARROW_LEFT, '1'.code, Keys.ARROW_DOWN, '3'.code,
        )
    }
}
