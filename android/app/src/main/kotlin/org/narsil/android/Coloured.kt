package org.narsil.android

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle

/**
 * Description text with the game's colours (monster recall, item and knowledge text): runs the bridge wraps as
 * COLOUR_BEGIN, '@' + the game's colour index, text, COLOUR_END (see NB_COLOUR_BEGIN in narsil_bridge.h).
 */
fun colouredText(raw: String, colour: (Int) -> Int): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < raw.length) {
        if (raw[i] == COLOUR_BEGIN && i + 1 < raw.length) {
            val end = raw.indexOf(COLOUR_END, i + 2).let { if (it < 0) raw.length else it }
            withStyle(SpanStyle(color = Color(colour(raw[i + 1] - '@')))) { append(raw.substring(i + 2, end)) }
            i = end + 1
        } else {
            val next = raw.indexOf(COLOUR_BEGIN, i).let { if (it < 0) raw.length else it }
            append(raw.substring(i, next))
            i = next
        }
    }
}

private const val COLOUR_BEGIN = '\u0003'
private const val COLOUR_END = '\u0004'
