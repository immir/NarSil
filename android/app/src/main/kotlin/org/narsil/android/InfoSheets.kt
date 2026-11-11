package org.narsil.android

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Divider
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.narsil.android.NarSilNative as N

@Composable
private fun BottomSheet(onDismiss: () -> Unit, tall: Boolean = false, content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color(0x99000000))
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            color = Color(0xFF1C1C1E),
            modifier = Modifier.fillMaxWidth().let { if (tall) it.fillMaxHeight(0.85f) else it.heightIn(max = 600.dp) }
                .clickable(remember { MutableInteractionSource() }, indication = null) {},
        ) { content() }
    }
}

@Composable
private fun Glyph(cell: Pair<Int, Int>?, colour: (Int) -> Int, size: Int = 24) {
    Text(cell?.let { String(Character.toChars(it.first)) } ?: " ", color = Color(cell?.let { colour(it.second) } ?: 0xFFFFFFFF.toInt()),
         fontFamily = FontFamily.Monospace, fontSize = size.sp, modifier = Modifier.width((size + 6).dp))
}

/** What is at one grid: monster (with its recall: what the player has learnt), objects, terrain. */
@Composable
fun InspectSheet(info: GameState.Info.Inspect, state: GameState, onDismiss: () -> Unit) {
    BottomSheet(onDismiss) {
        Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val cell = state.mapCell(info.x, info.y)
            info.monster?.let { m ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Glyph(cell, state::colour, 28)
                    Column(Modifier.weight(1f)) {
                        Text(m.name, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        if (m.state.isNotEmpty()) Text(m.state, color = Color(0xFFFFB74D), fontSize = 14.sp)
                    }
                }
                if (m.recall.isNotEmpty()) Text(m.recall, color = Color(0xFFC8C8CC), fontSize = 14.sp)
            }
            info.objects.forEach { o -> Text(o, color = Color.White, fontSize = 16.sp) }
            info.terrain?.let { t -> Text(t, color = Color(0xFF9A9AA0), fontSize = 14.sp) }
            if (info.monster == null && info.objects.isEmpty() && info.terrain == null)
                Text("You know nothing about that place.", color = Color(0xFF9A9AA0))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.align(Alignment.End)) {
                if (state.atPrompt) {
                    FilledTonalButton(onClick = { onDismiss(); N.nativeRequestPreview(info.x, info.y) }) { Text("Show path") }
                    FilledTonalButton(onClick = { onDismiss(); N.nativeRequestTravel(info.x, info.y) }) { Text("Go there") }
                }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        }
    }
}

/** Everything interesting in view, nearest first; tap one to inspect it. */
@Composable
fun LookSheet(info: GameState.Info.LookList, state: GameState, onDismiss: () -> Unit) {
    BottomSheet(onDismiss) {
        Column(Modifier.padding(top = 12.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("In view", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Close") }
            }
            if (info.entries.isEmpty())
                Text("Nothing of interest.", color = Color(0xFF9A9AA0), modifier = Modifier.padding(16.dp))
            LazyColumn {
                items(info.entries) { e ->
                    Row(Modifier.fillMaxWidth().clickable { N.nativeRequestInspect(e.x, e.y) }.heightIn(min = 48.dp)
                            .padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Glyph(state.mapCell(e.x, e.y), state::colour)
                        Text(e.summary, color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    }
                    Divider(color = Color(0xFF2C2C2E), modifier = Modifier.padding(start = 52.dp))
                }
            }
        }
    }
}

/**
 * The message log, newest at the bottom and scrolled there; scroll up for history.
 * Live: messages arriving while it is open are appended.
 */
@Composable
fun MessagesSheet(state: GameState, onDismiss: () -> Unit) {
    val messages = state.messages
    val list = rememberLazyListState(initialFirstVisibleItemIndex = maxOf(messages.size - 1, 0))
    BottomSheet(onDismiss, tall = true) {
        Column(Modifier.padding(top = 12.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Messages", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Close") }
            }
            if (messages.isEmpty()) Text("No messages yet.", color = Color(0xFF9A9AA0), modifier = Modifier.padding(16.dp))
            LazyColumn(Modifier.fillMaxWidth(), state = list) {
                items(messages) { m ->
                    Text(if (m.count > 1) "${m.text} (x${m.count})" else m.text, color = Color(state.colour(m.attr)), fontSize = 15.sp,
                         modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp))
                }
                item { Box(Modifier.padding(bottom = 12.dp)) }
            }
        }
    }
}
