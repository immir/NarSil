package org.narsil.android

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
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
fun ResizeHandle(horizontalBar: Boolean, onDrag: (Float) -> Unit) {
    val update by rememberUpdatedState(onDrag)
    val grip = Modifier.pointerInput(Unit) { detectDragGestures { change, drag -> change.consume(); update(if (horizontalBar) drag.y else drag.x) } }
    Box((if (horizontalBar) Modifier.fillMaxWidth().height(24.dp) else Modifier.fillMaxHeight().width(24.dp))
            .background(Ui.Gold.copy(alpha = 0.14f)).then(grip), contentAlignment = Alignment.Center) {
        Box((if (horizontalBar) Modifier.width(40.dp).height(4.dp) else Modifier.height(40.dp).width(4.dp)).background(Ui.Gold, RoundedCornerShape(2.dp)))
    }
}

/** The card over the map while laying out: the presets, the mirror, Reset and Done. */
@OptIn(ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun LayoutBar(settings: Settings, onDone: () -> Unit) {
    Surface(shape = Ui.CardShape, color = Ui.Sheet, border = Ui.edge, modifier = Modifier.padding(8.dp)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Drag the gold handles to resize the panes", color = Ui.Sub, fontSize = 13.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                Settings.LAYOUT_PRESETS.forEach { (name, _) ->
                    FilterChip(selected = settings.layoutPreset == name, onClick = { settings.applyLayoutPreset(name) }, label = { Text(name) })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Left-handed", color = Ui.Text, fontSize = 14.sp, modifier = Modifier.padding(end = 6.dp))
                    Switch(settings.layoutMirror, { settings.layoutMirror = it })
                }
                TextButton(onClick = { settings.resetLayout() }) { Text("Reset") }
                Button(onClick = onDone) { Text("Done") }
            }
        }
    }
}
