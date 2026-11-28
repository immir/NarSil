package org.narsil.android

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
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
 * A handle on a boundary: a thin bar to drag.  [horizontalBar] is a bar lying across the screen (dragged up and down);
 * otherwise it stands upright (dragged left and right).  [onDrag] gets the movement in pixels along the drag axis.
 */
@Composable
fun ResizeHandle(horizontalBar: Boolean, length: Dp? = null, onDrag: (Float) -> Unit) {
    val update by rememberUpdatedState(onDrag)
    val grip = Modifier.pointerInput(Unit) { detectDragGestures { change, drag -> change.consume(); update(if (horizontalBar) drag.y else drag.x) } }
    /* In a row that is only as tall as its content (portrait, beside the d-pad) the handle takes a [length], or it would stretch the row. */
    val along = when { length != null -> Modifier.height(length); horizontalBar -> Modifier.fillMaxWidth(); else -> Modifier.fillMaxHeight() }
    Box((if (horizontalBar) along.height(24.dp) else along.width(24.dp))
            .background(Ui.Gold.copy(alpha = 0.14f)).then(grip), contentAlignment = Alignment.Center) {
        Box((if (horizontalBar) Modifier.width(40.dp).height(4.dp) else Modifier.height(40.dp).width(4.dp)).background(Ui.Gold, RoundedCornerShape(2.dp)))
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
