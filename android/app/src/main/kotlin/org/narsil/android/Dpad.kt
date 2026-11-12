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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp

/** What a d-pad gesture asks for; dir is a keypad direction, 5 = centre. */
sealed interface PadAction {
    data class Step(val dir: Int) : PadAction
    data class Run(val dir: Int) : PadAction
    data object Wait : PadAction
    data object Rest : PadAction
}

private const val CENTRE = 0.32f       // centre button radius, as a fraction of the pad radius
private const val SWIPE_OUT = 0.42f    // drag this far from the centre (fraction of radius) to run

/**
 * Round eight-way pad.
 *  - tap a direction: step; hold it: run
 *  - centre: tap waits; drag out towards a direction runs that way; hold rests
 *  - with [onMove]: hold the centre, then drag, to move the pad
 */
@Composable
fun Dpad(sizeDp: Float, onAction: (PadAction) -> Unit, onMove: ((Offset) -> Unit)? = null) {
    var lit by remember { mutableIntStateOf(0) }
    val haptics = LocalHapticFeedback.current
    val action by rememberUpdatedState(onAction)
    val move by rememberUpdatedState(onMove)
    val movable = onMove != null
    Canvas(
        Modifier.size(sizeDp.dp).pointerInput(movable) {
            awaitEachGesture {
                val down = awaitFirstDown()
                val r = size.width / 2f
                val centre = Offset(r, size.height / 2f)
                fun dirAt(p: Offset) = (p - centre).let { if (it.getDistance() < CENTRE * r) 5 else OCTANT_TO_DIR[octant(it.x, it.y)] }
                fun fire(a: PadAction) {
                    if (a is PadAction.Run || a == PadAction.Rest) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
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
                            if (d.getDistance() > SWIPE_OUT * r) return@withTimeoutOrNull PadAction.Run(OCTANT_TO_DIR[octant(d.x, d.y)])
                        }
                        @Suppress("UNREACHABLE_CODE") PadAction.Wait
                    }
                    when (outcome) {
                        PadAction.Wait -> fire(PadAction.Wait)
                        is PadAction.Run -> { lit = outcome.dir; fire(outcome); awaitRelease() }
                        else -> {  // held
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
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
    ) { drawPad(lit) }
}

/** Suspends until the pointer that went down is lifted; returns that change. */
private suspend fun AwaitPointerEventScope.awaitRelease(): PointerInputChange {
    while (true) {
        val c = awaitPointerEvent().changes.first()
        if (!c.pressed) return c
    }
}

/** Keypad direction -> screen angle in degrees (0 = east, clockwise as drawn, y down). */
private val DIR_ANGLE = mapOf(6 to 0f, 3 to 45f, 2 to 90f, 1 to 135f, 4 to 180f, 7 to 225f, 8 to 270f, 9 to 315f)

private fun DrawScope.drawPad(lit: Int) {
    val r = size.minDimension / 2f
    val c = center
    drawCircle(Color(0x99141418), r, c)
    drawCircle(Color(0x40FFFFFF), r - 1.dp.toPx(), c, style = Stroke(1.dp.toPx()))
    DIR_ANGLE[lit]?.let { a ->
        drawArc(Color(0x803A5F8A), a - 22.5f, 45f, useCenter = true, topLeft = c - Offset(r, r), size = Size(2 * r, 2 * r))
    }
    val arrow = Path().apply {
        val s = r * 0.11f
        moveTo(r * 0.78f + s, 0f); lineTo(r * 0.78f - s * 0.6f, -s); lineTo(r * 0.78f - s * 0.6f, s); close()
        translate(c)
    }
    DIR_ANGLE.forEach { (dir, a) ->
        val cardinal = dir % 2 == 0
        rotate(a, c) { drawPath(arrow, if (dir == lit) Color.White else Color(if (cardinal) 0xC0FFFFFF else 0x80FFFFFF)) }
    }
    drawCircle(if (lit == 5) Color(0xA03A5F8A) else Color(0x40FFFFFF), r * CENTRE, c)
    drawCircle(Color(0x30FFFFFF), r * CENTRE, c, style = Stroke(1.dp.toPx()))
}
