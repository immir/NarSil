package org.narsil.android

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Angband keycodes from src/ui-event.h. */
object Keys {
    const val ARROW_DOWN = 0x80
    const val ARROW_LEFT = 0x81
    const val ARROW_RIGHT = 0x82
    const val ARROW_UP = 0x83
    const val ENTER = 0x9C
    const val TAB = 0x9D
    const val BACKSPACE = 0x9F
    const val ESCAPE = 0xE000

    /** KTRL(): control characters are delivered as raw ASCII with no modifier, as main-gcu does. */
    fun ctrl(cp: Int): Int = cp and 0x1F
}

/**
 * Single funnel for every input source (IME, hardware keyboard, key bar, back button),
 * so the sticky Ctrl latch applies uniformly.
 */
class KeyRouter(private val sink: (code: Int, mods: Int) -> Unit = NarSilNative::nativePushKey) {
    var ctrlLatched by mutableStateOf(false)

    fun char(cp: Int) {
        val code = if (ctrlLatched && cp in 0x40..0x7F) Keys.ctrl(cp) else cp
        ctrlLatched = false
        sink(code, 0)
    }

    fun control(cp: Int) {
        ctrlLatched = false
        sink(Keys.ctrl(cp), 0)
    }

    fun special(code: Int) {
        ctrlLatched = false
        sink(code, 0)
    }
}
