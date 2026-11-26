package org.narsil.android

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** What a d-pad gesture asks for; dir is a keypad direction, 5 = centre. */
sealed interface PadAction {
    data class Step(val dir: Int) : PadAction
    data class Run(val dir: Int) : PadAction
    data object Wait : PadAction
    data object Rest : PadAction
}

/** Keypad layout, top row first. */
private val PAD = listOf(listOf(7, 8, 9), listOf(4, 5, 6), listOf(1, 2, 3))
private const val SWIPE_OUT = 0.6f     // drag this far from the centre, in key widths, to run

/**
 * Flat 3x3 keypad.
 *  - tap a direction: step; hold it: run
 *  - centre: tap waits; drag out towards a direction runs that way; hold rests
 *  - with [onMove]: hold the centre, then drag, to move the pad
 *  - [marked]: the directions a prompt suggests (a door, a chest; 5 = here), lit while the others dim
 */
private val PAD_NAMES = listOf(8 to "north", 9 to "north-east", 6 to "east", 3 to "south-east", 2 to "south", 1 to "south-west", 4 to "west", 7 to "north-west")

@Composable
fun Dpad(sizeDp: Float, onAction: (PadAction) -> Unit, onMove: ((Offset) -> Unit)? = null, marked: Set<Int> = emptySet()) {
    var lit by remember { mutableIntStateOf(0) }
    val action by rememberUpdatedState(onAction)
    val move by rememberUpdatedState(onMove)
    val movable = onMove != null
    Canvas(
        Modifier.size(sizeDp.dp).semantics {
            contentDescription = "Movement pad"
            customActions = PAD_NAMES.flatMap { (dir, name) ->
                listOf(CustomAccessibilityAction("Step $name") { action(PadAction.Step(dir)); true },
                       CustomAccessibilityAction("Run $name") { action(PadAction.Run(dir)); true })
            } + listOf(CustomAccessibilityAction("Wait") { action(PadAction.Wait); true },
                       CustomAccessibilityAction("Rest") { action(PadAction.Rest); true })
        }.pointerInput(movable) {
            awaitEachGesture {
                val down = awaitFirstDown()
                val key = size.width / 3f
                val centre = Offset(size.width / 2f, size.height / 2f)
                fun dirAt(p: Offset) = PAD[(p.y / key).toInt().coerceIn(0, 2)][(p.x / key).toInt().coerceIn(0, 2)]
                fun fire(a: PadAction) {
                    if (a is PadAction.Run || a == PadAction.Rest) Haptics.press()
                    action(a)
                }
                val start = dirAt(down.position)
                lit = start
                val timeout = viewConfiguration.longPressTimeoutMillis
                if (start != 5) {
                    val released = withTimeoutOrNull(timeout) { awaitRelease() } != null
                    fire(if (released) PadAction.Step(start) else PadAction.Run(start))
                    if (!released) awaitRelease()
                } else {
                    /* Centre: released → wait; dragged out → run; held still → rest, or move the pad. */
                    val outcome = withTimeoutOrNull(timeout) {
                        while (true) {
                            val c = awaitPointerEvent().changes.first()
                            if (!c.pressed) return@withTimeoutOrNull PadAction.Wait
                            val d = c.position - centre
                            if (d.getDistance() > SWIPE_OUT * key) return@withTimeoutOrNull PadAction.Run(OCTANT_TO_DIR[octant(d.x, d.y)])
                        }
                        @Suppress("UNREACHABLE_CODE") PadAction.Wait
                    }
                    when (outcome) {
                        PadAction.Wait -> fire(PadAction.Wait)
                        is PadAction.Run -> { lit = outcome.dir; fire(outcome); awaitRelease() }
                        else -> {  // held
                            Haptics.press()
                            var moved = false
                            while (true) {
                                val c = awaitPointerEvent().changes.first()
                                if (!c.pressed) break
                                if (movable && c.positionChange() != Offset.Zero) { moved = true; move?.invoke(c.positionChange()); c.consume() }
                            }
                            if (!moved) action(PadAction.Rest)
                        }
                    }
                }
                lit = 0
            }
        },
    ) { drawPad(lit, marked) }
}

/** Suspends until the pointer that went down is lifted; returns that change. */
private suspend fun AwaitPointerEventScope.awaitRelease(): PointerInputChange {
    while (true) {
        val c = awaitPointerEvent().changes.first()
        if (!c.pressed) return c
    }
}

private val ARROW_ANGLE = mapOf(6 to 0f, 3 to 45f, 2 to 90f, 1 to 135f, 4 to 180f, 7 to 225f, 8 to 270f, 9 to 315f)

private val MARK = Ui.Gold

private fun DrawScope.drawPad(lit: Int, marked: Set<Int>) {
    val key = size.minDimension / 3f
    val gap = 3.dp.toPx()
    val corner = CornerRadius(6.dp.toPx())
    val arrow = Path().apply {
        val a = key * 0.16f
        moveTo(a, 0f); lineTo(-a * 0.7f, -a * 0.85f); lineTo(-a * 0.7f, a * 0.85f); close()
    }
    PAD.forEachIndexed { row, dirs ->
        dirs.forEachIndexed { col, dir ->
            val topLeft = Offset(col * key + gap / 2, row * key + gap / 2)
            val k = Size(key - gap, key - gap)
            val mark = dir in marked
            val dim = marked.isNotEmpty() && !mark
            drawRoundRect(when { dir == lit -> Color(0xD03A5F8A); mark -> Color(0xC06A5018); else -> Color(0x8C26262B) }, topLeft, k, corner)
            drawRoundRect(if (mark) MARK else Color(0x30FFFFFF), topLeft, k, corner, style = Stroke((if (mark) 2 else 1).dp.toPx()))
            val mid = topLeft + Offset(k.width / 2, k.height / 2)
            val ink = when { dir == lit || mark -> Color.White; dim -> Color(0x55FFFFFF); else -> Color(0xCCFFFFFF) }
            ARROW_ANGLE[dir]?.let { a -> translate(mid.x, mid.y) { rotate(a, Offset.Zero) { drawPath(arrow, ink) } } }
                ?: drawCircle(ink, key * 0.07f, mid)
        }
    }
}
