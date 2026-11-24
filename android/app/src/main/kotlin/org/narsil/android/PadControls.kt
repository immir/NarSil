package org.narsil.android

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * A d-pad gesture: answers an open aim or direction prompt; at the map prompt
 * steps, runs, waits or rests; elsewhere (-more-) it sends the keypad digit.
 */
fun applyPad(state: GameState, router: KeyRouter, a: PadAction) {
    val dir = when (a) { is PadAction.Step -> a.dir; is PadAction.Run -> a.dir; else -> 5 }
    Trace.log("pad", "kind" to when (a) { is PadAction.Step -> "step"; is PadAction.Run -> "run"; PadAction.Wait -> "wait"; PadAction.Rest -> "rest" })
    when (val p = state.prompt) {
        is GameState.Prompt.Aim -> NarSilNative.nativeAnswer(if (dir == 5) NarSilNative.AIM_CURRENT else dir)
        is GameState.Prompt.Direction -> if (dir != 5 || p.allowHere) NarSilNative.nativeAnswer(dir)
        null -> when {
            !state.atPrompt -> router.char('0'.code + dir)
            a == PadAction.Wait -> NarSilNative.nativeRequestAction(NarSilNative.ACT_HOLD)
            a == PadAction.Rest -> NarSilNative.nativeRequestAction(NarSilNative.ACT_REST)
            else -> NarSilNative.nativeRequestMove(dir, a is PadAction.Run)
        }
        else -> Unit
    }
}

/** The d-pad keys a direction prompt suggests: its interesting neighbours, and the centre when "here" is allowed. */
fun padMarks(p: GameState.Prompt?): Set<Int> = (p as? GameState.Prompt.Direction)?.let { d ->
    d.neighbours.map { it.dir }.toSet() + if (d.allowHere) setOf(5) else emptySet()
} ?: emptySet()

/** Draggable by its centre; the position is kept as a fraction of the free space. */
@Composable
fun FloatingDpad(settings: Settings, marked: Set<Int>, onAction: (PadAction) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val pad = with(density) { settings.dpadSizeDp.dp.toPx() }
        val freeW = (constraints.maxWidth - pad).coerceAtLeast(1f)
        val freeH = (constraints.maxHeight - pad).coerceAtLeast(1f)
        Box(Modifier.offset { IntOffset((settings.dpadX * freeW).roundToInt(), (settings.dpadY * freeH).roundToInt()) }) {
            Dpad(settings.dpadSizeDp, onAction, { delta ->
                settings.dpadX = (settings.dpadX + delta.x / freeW).coerceIn(0f, 1f)
                settings.dpadY = (settings.dpadY + delta.y / freeH).coerceIn(0f, 1f)
            }, marked)
        }
    }
}
