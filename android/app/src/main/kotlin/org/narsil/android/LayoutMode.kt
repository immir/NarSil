package org.narsil.android

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.currentCompositeKeyHash
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.toSize
import kotlin.math.abs
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Layout mode (☰ → Adjust layout): the boundaries between the panes of the main screen grow gold handles that are
 * dragged to resize them, with a few presets and a left-handed mirror.  The sizes are fractions of the screen and are kept
 * in Settings, so they carry across rotation and other phones.
 */

/**
 * The draggable boundaries while laying out.  A handle takes no room in the layout: it is a zero-size anchor that reports where
 * its boundary lies, and the guides are drawn and dragged from one layer over the whole area ([guideDrag], [GuideLayer]),
 * so what is being resized keeps its true size.
 */
class Guides {
    val rects = mutableStateMapOf<Int, Rect>()
    val horizontal = HashMap<Int, Boolean>()
    val drags = HashMap<Int, (Float) -> Unit>()
    var origin by mutableStateOf(Offset.Zero)
    var exclude by mutableStateOf(Rect.Zero)

    /** The guide nearest [p] (in the area's own coordinates) within [reach], unless [p] is on the card. */
    fun hit(p: Offset, reach: Float): Int? =
        if (exclude.translate(-origin).contains(p)) null
        else rects.entries.mapNotNull { (id, r0) ->
            val r = r0.translate(-origin)
            val across = if (horizontal[id] == true) abs(p.y - r.top) else abs(p.x - r.left)
            val along = if (horizontal[id] == true) p.x in r.left..r.right else p.y in r.top..r.bottom
            if (along && across <= reach) id to across else null
        }.minByOrNull { it.second }?.first
}

val LocalGuides = compositionLocalOf { Guides() }

/**
 * A handle on a boundary.  [horizontalBar] is a bar lying across the screen (dragged up and down); otherwise it stands
 * upright (dragged left and right).  [onDrag] gets the movement in pixels along the drag axis.  [length] is the extent of an
 * upright boundary inside a row that is only as tall as its content.
 */
@Composable
fun ResizeHandle(horizontalBar: Boolean, length: Dp? = null, onDrag: (Float) -> Unit) {
    val guides = LocalGuides.current
    val id = currentCompositeKeyHash
    val update by rememberUpdatedState(onDrag)
    DisposableEffect(id) {
        guides.horizontal[id] = horizontalBar
        guides.drags[id] = { update(it) }
        onDispose { guides.drags.remove(id); guides.horizontal.remove(id); guides.rects.remove(id) }
    }
    val along = when { length != null -> Modifier.height(length); horizontalBar -> Modifier.fillMaxWidth(); else -> Modifier.fillMaxHeight() }
    Spacer((if (horizontalBar) along.height(0.dp) else along.width(0.dp))
        .onGloballyPositioned { guides.rects[id] = Rect(it.positionInRoot(), it.size.toSize()) })
}

/** Takes a touch that begins on a guide (before anything under it sees it) and turns its movement into resizing. */
fun Modifier.guideDrag(guides: Guides): Modifier = onGloballyPositioned { guides.origin = it.positionInRoot() }.pointerInput(Unit) {
    val reach = 18.dp.toPx()
    awaitPointerEventScope {
        var active: Int? = null
        while (true) {
            val e = awaitPointerEvent(PointerEventPass.Initial)
            val c = e.changes.singleOrNull()
            val id = active
            when {
                c == null -> active = null
                c.changedToDown() -> { active = guides.hit(c.position, reach); if (active != null) c.consume() }
                id != null && c.pressed -> {
                    val d = c.positionChange()
                    guides.drags[id]?.invoke(if (guides.horizontal[id] == true) d.y else d.x)
                    c.consume()
                }
                id != null -> { c.consume(); active = null }
            }
        }
    }
}

/** The translucent guides, drawn over everything and never touched by the layout. */
@Composable
fun GuideLayer(guides: Guides) {
    Canvas(Modifier.fillMaxSize()) {
        guides.rects.forEach { (id, r0) ->
            val r = r0.translate(-guides.origin)
            val band = 18.dp.toPx(); val line = 2.dp.toPx(); val pill = 40.dp.toPx(); val thick = 5.dp.toPx()
            val fill = Ui.Gold.copy(alpha = 0.16f)
            if (guides.horizontal[id] == true) {
                drawRect(fill, Offset(r.left, r.top - band / 2), Size(r.width, band))
                drawRect(Ui.Gold.copy(alpha = 0.7f), Offset(r.left, r.top - line / 2), Size(r.width, line))
                drawRoundRect(Ui.Gold, Offset(r.center.x - pill / 2, r.top - thick / 2), Size(pill, thick), CornerRadius(thick / 2))
            } else {
                drawRect(fill, Offset(r.left - band / 2, r.top), Size(band, r.height))
                drawRect(Ui.Gold.copy(alpha = 0.7f), Offset(r.left - line / 2, r.top), Size(line, r.height))
                drawRoundRect(Ui.Gold, Offset(r.left - thick / 2, r.center.y - pill / 2), Size(thick, pill), CornerRadius(thick / 2))
            }
        }
    }
}

/** The strip over the map while laying out: the presets, the mirror, Reset and Done, as small as will hold them; it wraps on a narrow screen. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LayoutBar(settings: Settings, onDone: () -> Unit) {
    val small = 11.sp
    Surface(shape = RoundedCornerShape(12.dp), color = Ui.Sheet, border = Ui.edge, modifier = Modifier.padding(4.dp)) {
        FlowRow(Modifier.padding(horizontal = 6.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Settings.LAYOUT_PRESETS.forEach { p ->
                val on = settings.layoutPreset == p.name
                Text(p.name, color = if (on) Ui.Gold else Ui.Text, fontSize = small, maxLines = 1, softWrap = false,
                     modifier = Modifier.background(if (on) Ui.Gold.copy(alpha = 0.2f) else Ui.Raised, RoundedCornerShape(50))
                         .clickable { settings.applyLayoutPreset(p.name) }.padding(horizontal = 8.dp, vertical = 5.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Left-handed", color = Ui.Text, fontSize = small, maxLines = 1, softWrap = false)
                Switch(settings.layoutMirror, { settings.layoutMirror = it }, modifier = Modifier.scale(0.6f).size(width = 34.dp, height = 22.dp))
            }
            Text("Reset", color = Ui.Gold, fontSize = small, maxLines = 1, softWrap = false,
                 modifier = Modifier.clickable { settings.resetLayout() }.padding(horizontal = 8.dp, vertical = 5.dp))
            Text("Done", color = Ui.OnGold, fontSize = small, maxLines = 1, softWrap = false, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                 modifier = Modifier.background(Ui.Gold, RoundedCornerShape(50)).clickable(onClick = onDone).padding(horizontal = 10.dp, vertical = 5.dp))
        }
    }
}
