package org.narsil.android

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/** How special items stand out: ego items in cyan, artefacts in gold (bold), items with properties still to discover in lilac; in the pack and, for the named ones, the message log. */
object Special {
    private val EGO = Color(0xFF5CE1E6)
    private val ARTEFACT = Color(0xFFFFB000)
    private val UNKNOWN = Color(0xFFD7A6FF)

    fun colour(tier: Int): Color? = when (tier) { 1 -> EGO; 2 -> ARTEFACT; 3 -> UNKNOWN; else -> null }

    private fun style(tier: Int) = SpanStyle(color = colour(tier) ?: Color.Unspecified, fontWeight = FontWeight.SemiBold)

    /** The message with its special item names coloured; [suffix] is appended in the base style. */
    fun styled(m: GameState.Message, suffix: String = ""): AnnotatedString = buildAnnotatedString {
        val end = m.spans.fold(0) { at, s ->
            append(m.text.substring(at, s.start))
            withStyle(style(s.tier)) { append(m.text.substring(s.start, s.end)) }
            s.end
        }
        append(m.text.substring(end))
        append(suffix)
    }
}
