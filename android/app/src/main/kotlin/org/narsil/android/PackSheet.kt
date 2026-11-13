package org.narsil.android

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Divider
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.narsil.android.NarSilNative as N

private val SECTION_TITLES = mapOf(0 to "Inventory", 1 to "Equipment", 2 to "On the floor", 3 to "Throwing")
private val SECTION_ORDER = listOf(0, 1, 2, 3)
private val SUB = Color(0xFF9A9AA0)
private val OPEN = Color(0xFF26262C)

/**
 * Where the pack list was scrolled, kept across the pack closing and reopening
 * around an action (the game runs the command in between).
 */
private object PackView {
    var first = 0
    var offset = 0
}

private fun lb(tenths: Int) = "%d.%d lb".format(tenths / 10, tenths % 10)

/**
 * The pack (narsil_prompt.c, publish_pack()): every item in one list.  Tapping
 * an item opens it in place with its description and actions; tapping another
 * closes it and opens that one.  After an action the game reopens the pack.
 */
@Composable
fun PackSheet(p: GameState.Prompt.Pack, colour: (Int) -> Int, onAnswer: (Int) -> Unit) {
    val sections = remember(p) { p.items.groupBy { it.section }.toList().sortedBy { (s, _) -> SECTION_ORDER.indexOf(s) } }
    /* A fresh open (Pack, Gear) starts at that section; a reopen after an action keeps its place. */
    val list = remember {
        if (p.resumed) LazyListState(PackView.first, PackView.offset)
        else LazyListState(sections.takeWhile { it.first != p.focus }.sumOf { 1 + it.second.size }
                               .takeIf { sections.any { it.first == p.focus } } ?: 0)
    }
    DisposableEffect(Unit) { onDispose { PackView.first = list.firstVisibleItemIndex; PackView.offset = list.firstVisibleItemScrollOffset } }
    Box(
        Modifier.fillMaxSize().background(Color(0x99000000))
            .clickable(remember { MutableInteractionSource() }, indication = null) { onAnswer(-1) },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp), color = Color(0xFF1C1C1E),
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.88f)
                .clickable(remember { MutableInteractionSource() }, indication = null) {},
        ) {
            Column(Modifier.padding(top = 10.dp)) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Pack", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        Text("Carrying ${lb(p.weight)} of ${lb(p.limit)}", fontSize = 12.sp,
                             color = if (p.weight > p.limit) Color(0xFFFFB74D) else SUB)
                    }
                    TextButton(onClick = { onAnswer(-1) }) { Text("Close") }
                }
                LazyColumn(Modifier.fillMaxWidth(), state = list) {
                    sections.forEach { (section, items) ->
                        item(key = "h$section") {
                            Text(SECTION_TITLES[section] ?: "", color = SUB, fontSize = 13.sp,
                                 modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp))
                        }
                        items(items, key = { "${it.section}/${it.label}/${it.name}" }) { item ->
                            PackRow(item, p, colour, onAnswer)
                        }
                    }
                    item { Box(Modifier.padding(bottom = 24.dp)) }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PackRow(item: GameState.Item, p: GameState.Prompt.Pack, colour: (Int) -> Int, onAnswer: (Int) -> Unit) {
    val open = item.index == p.expanded
    Column(Modifier.fillMaxWidth().background(if (open) OPEN else Color.Transparent).animateContentSize()) {
        Row(
            Modifier.fillMaxWidth().clickable { onAnswer(if (open) N.PACK_COLLAPSE else N.PACK_EXPAND + item.index) }
                .heightIn(min = 52.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(String(Character.toChars(item.glyph)), color = Color(colour(item.attr)), fontFamily = FontFamily.Monospace,
                 fontSize = 22.sp, modifier = Modifier.width(24.dp))
            Text(if (item.label == '-') "" else "${item.label})", color = SUB, fontFamily = FontFamily.Monospace,
                 fontSize = 14.sp, modifier = Modifier.width(24.dp))
            Text(item.name, color = Color.White, fontSize = 16.sp, fontWeight = if (open) FontWeight.SemiBold else FontWeight.Normal,
                 maxLines = if (open) 3 else 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (item.weightTenthsLb > 0) Text(lb(item.weightTenthsLb), color = SUB, fontSize = 13.sp)
        }
        if (open) {
            Column(Modifier.padding(start = 68.dp, end = 16.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (p.description.isNotEmpty()) Text(p.description, color = Color(0xFFC8C8CC), fontSize = 14.sp, lineHeight = 19.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    p.actions.forEach { a ->
                        FilledTonalButton(onClick = { onAnswer(N.PACK_ACT + item.index * N.PACK_ACT_STRIDE + a.index) }, enabled = a.valid) {
                            Text(a.label, maxLines = 1)
                        }
                    }
                }
            }
        }
        Divider(color = Color(0xFF2C2C2E), modifier = Modifier.padding(start = 68.dp))
    }
}
