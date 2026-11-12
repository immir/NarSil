package org.narsil.android

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown

/** Keypad layout, top row first: 7 8 9 / 4 5 6 / 1 2 3. */
private val PAD = listOf(listOf(7, 8, 9), listOf(4, 5, 6), listOf(1, 2, 3))
private val ARROWS = mapOf(7 to "↖", 8 to "↑", 9 to "↗", 4 to "←", 5 to "•", 6 to "→", 1 to "↙", 2 to "↓", 3 to "↘")

private sealed interface Press { data object Tap : Press; data object Hold : Press; data object Drag : Press }

/**
 * Nine-way pad.  [onPress] receives a keypad direction (5 = centre) and whether it
 * was held (long press).  With [onDrag], dragging from the centre moves the pad.
 */
@Composable
fun Dpad(sizeDp: Float, onPress: (dir: Int, held: Boolean) -> Unit, onDrag: ((Offset) -> Unit)? = null) {
    var pressed by remember { mutableIntStateOf(0) }
    val haptics = LocalHapticFeedback.current
    val press by rememberUpdatedState(onPress)
    val drag by rememberUpdatedState(onDrag)
    Column(
        Modifier.size(sizeDp.dp).background(Color(0x66202024), RoundedCornerShape(18.dp))
            .border(1.dp, Color(0x55FFFFFF), RoundedCornerShape(18.dp))
            .pointerInput(onDrag != null) {
                gestures({ pressed = it }, onPress = { dir, held ->
                    if (held) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    press(dir, held)
                }, if (onDrag == null) null else { d -> drag?.invoke(d) })
            },
    ) {
        PAD.forEach { row ->
            Row(Modifier.weight(1f)) {
                row.forEach { dir ->
                    Box(Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.Center) {
                        val lit = pressed == dir
                        Box(Modifier.size((sizeDp / 4.2f).dp)
                                .background(if (lit) Color(0xCC3A5F8A) else Color(0x33FFFFFF), CircleShape),
                            contentAlignment = Alignment.Center) {
                            Text(ARROWS.getValue(dir), color = Color.White, fontSize = (sizeDp / 7.5f).sp)
                        }
                    }
                }
            }
        }
    }
}

private suspend fun PointerInputScope.gestures(
    highlight: (Int) -> Unit, onPress: (Int, Boolean) -> Unit, onDrag: ((Offset) -> Unit)?,
) = awaitEachGesture {
    val down = awaitFirstDown()
    val cell = { p: Offset -> PAD[(3 * p.y / size.height).toInt().coerceIn(0, 2)][(3 * p.x / size.width).toInt().coerceIn(0, 2)] }
    val dir = cell(down.position)
    highlight(dir)
    val draggable = onDrag != null && dir == 5
    val press = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
        while (true) {
            val c = awaitPointerEvent().changes.first()
            if (!c.pressed) return@withTimeoutOrNull Press.Tap
            if (draggable && (c.position - down.position).getDistance() > viewConfiguration.touchSlop) return@withTimeoutOrNull Press.Drag
        }
        @Suppress("UNREACHABLE_CODE") Press.Tap
    } ?: Press.Hold
    when (press) {
        Press.Tap -> onPress(dir, false)
        Press.Hold -> onPress(dir, true)
        Press.Drag -> Unit
    }
    while (true) {
        val c = awaitPointerEvent().changes.first()
        if (!c.pressed) break
        if (press == Press.Drag) { onDrag?.invoke(c.positionChange()); c.consume() }
    }
    highlight(0)
}
