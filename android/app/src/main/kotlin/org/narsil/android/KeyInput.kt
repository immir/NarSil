package org.narsil.android

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/* Keys: answering native prompts from the keyboard, and the key bar of the remaining textui screens. */

/**
 * Keyboard answers while a native prompt is open (all other keys are swallowed):
 * Esc cancels; item letters pick by label; y/n answer checks; keypad digits,
 * arrows, ' (nearest) and t/5 (current target) answer aims.
 */
internal fun answerByKey(prompt: GameState.Prompt, code: Int): Int? = when (prompt) {
    is GameState.Prompt.Items -> if (code == Keys.ESCAPE) -1
        else prompt.items.firstOrNull { it.label != '-' && it.label.code == code }?.index
    is GameState.Prompt.Check -> when (code) {
        'y'.code, 'Y'.code -> 1
        'n'.code, 'N'.code, Keys.ESCAPE -> 0
        else -> null
    }
    is GameState.Prompt.Abilities, is GameState.Prompt.Smith -> if (code == Keys.ESCAPE) -1 else null
    is GameState.Prompt.Text -> if (code == Keys.ESCAPE) -1 else null
    is GameState.Prompt.Screen -> if (code == Keys.ESCAPE && prompt.stage != "death") -1 else null
    is GameState.Prompt.Options -> if (code == Keys.ESCAPE) -1 else null
    is GameState.Prompt.Pack -> if (code == Keys.ESCAPE) -1 else null
    is GameState.Prompt.Knowledge -> if (code == Keys.ESCAPE) -1 else null
    is GameState.Prompt.Character -> if (code == Keys.ESCAPE) -1 else null
    is GameState.Prompt.Quantity -> if (code == Keys.ESCAPE) -1 else null
    is GameState.Prompt.Skills -> when (code) {
        Keys.ESCAPE -> -1
        Keys.ENTER -> NarSilNative.SKILL_ACCEPT
        else -> null
    }
    is GameState.Prompt.Choice -> if (code == Keys.ESCAPE) -1
        else (code - 'a'.code).takeIf { it in prompt.options.indices }
    is GameState.Prompt.Direction -> when (code) {
        Keys.ESCAPE -> -1
        in '1'.code..'9'.code -> (code - '0'.code).takeIf { it != 5 || prompt.allowHere }
        Keys.ARROW_UP -> 8
        Keys.ARROW_DOWN -> 2
        Keys.ARROW_LEFT -> 4
        Keys.ARROW_RIGHT -> 6
        else -> null
    }
    is GameState.Prompt.Aim -> when (code) {
        Keys.ESCAPE -> -1
        in '1'.code..'9'.code -> (code - '0'.code).takeIf { it != 5 } ?: NarSilNative.AIM_CURRENT
        Keys.ARROW_UP -> 8
        Keys.ARROW_DOWN -> 2
        Keys.ARROW_LEFT -> 4
        Keys.ARROW_RIGHT -> 6
        '\''.code -> NarSilNative.AIM_NEAREST
        't'.code -> NarSilNative.AIM_CURRENT
        else -> null
    }
}

/**
 * Bottom bar for the remaining textui screens.  Taps on the screen pick menu lines
 * and swipes move, so the bar only offers what the screen is waiting for; the
 * rarer keys are behind ⋯.
 */
@Composable
internal fun KeyBar(waiting: GameState.Waiting, router: KeyRouter, onKeyboard: () -> Unit, onMoreKeys: () -> Unit) {
    val keys: List<Pair<String, () -> Unit>> = when (waiting) {
        GameState.Waiting.YES_NO -> listOf("Yes" to { router.char('y'.code) }, "No" to { router.char('n'.code) })
        GameState.Waiting.MORE, GameState.Waiting.ANY_KEY -> listOf("Continue" to { router.special(Keys.ENTER) })
        GameState.Waiting.AT_SIGN -> listOf("Confirm (@)" to { router.char('@'.code) }, "Cancel" to { router.special(Keys.ESCAPE) })
        GameState.Waiting.MENU -> listOf("Back" to { router.special(Keys.ESCAPE) }, "OK" to { router.special(Keys.ENTER) })
    }
    Row(
        Modifier.fillMaxWidth().background(Color(0xFF101012)).padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        keys.forEachIndexed { i, (label, action) ->
            val padding = PaddingValues(horizontal = 14.dp)
            if (i == 0 && waiting != GameState.Waiting.MENU || label == "OK")
                Button(onClick = action, contentPadding = padding, modifier = Modifier.weight(1f)) { Text(label) }
            else FilledTonalButton(onClick = action, contentPadding = padding, modifier = Modifier.weight(1f)) { Text(label) }
        }
        FilledTonalButton(onClick = onKeyboard, contentPadding = PaddingValues(horizontal = 14.dp)) { Text("⌨") }
        FilledTonalButton(onClick = onMoreKeys, contentPadding = PaddingValues(horizontal = 14.dp)) { Text("⋯") }
    }
}

/** The rarely needed keys; Ctrl latches for the next key. */
internal fun extraKeys(router: KeyRouter): List<BarAction> = listOf(
    BarAction("Esc", run = { router.special(Keys.ESCAPE) }),
    BarAction("Enter", run = { router.special(Keys.ENTER) }),
    BarAction(if (router.ctrlLatched) "Ctrl ●" else "Ctrl", run = { router.ctrlLatched = !router.ctrlLatched }),
    BarAction("⌫", run = { router.special(Keys.BACKSPACE) }),
    BarAction("Space", run = { router.char(' '.code) }),
)
