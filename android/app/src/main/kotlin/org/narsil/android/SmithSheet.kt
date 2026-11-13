package org.narsil.android

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.narsil.android.NarSilNative as N

private val BG = Color(0xFF121214)
private val CARD = Color(0xFF1E1E22)
private val CARD_HI = Color(0xFF26262C)
private val LINE = Color(0xFF2E2E34)
private val TEXT = Color(0xFFE6E6EA)
private val SUB = Color(0xFF9A9AA0)
private val DIM = Color(0xFF5A5A60)
private val GOLD = Color(0xFFF3D27A)
private val OK = Color(0xFF81C784)
private val WARN = Color(0xFFFFB74D)
private val BAD = Color(0xFFE57373)
private val CHOSEN = Color(0xFF90CAF9)

private val VIEW_NAMES = mapOf(
    "tval" to "Base item", "sval" to "Base item", "special" to "Enchant", "artefact" to "Artifice",
    "property" to "Artifice", "ability" to "Artifice", "numbers" to "Numbers", "melt" to "Melt",
)

/**
 * The smithing screen (narsil_smith.c), full screen: the design pinned on top,
 * then the current menu.  On the main menu the steps are tiles and "Make it"
 * is the button at the bottom.  Back (or Esc) returns to the previous menu;
 * from the main menu it leaves the forge.
 */
@Composable
fun SmithSheet(p: GameState.Prompt.Smith, onAnswer: (Int) -> Unit) {
    val main = p.view == "main"
    Surface(color = BG, modifier = Modifier.fillMaxSize()) {
        Column {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!(main && LocalInPanel.current)) TextButton(onClick = { onAnswer(-1) }) { Text(if (main) "Close" else "‹ Back") }
                Column(Modifier.weight(1f)) {
                    if (!main) VIEW_NAMES[p.view]?.let { Text("Smithing · $it", color = SUB, fontSize = 12.sp) }
                    Text(p.title, color = GOLD, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            p.notes.forEach { Banner(it) }
            DesignCard(p.item, p.costs, p.description)
            val make = if (main) p.options.firstOrNull { it.label == "Make it" } else null
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 12.dp)) {
                when {
                    main -> items(p.options.filter { it !== make }.chunked(2)) { row -> TileRow(row, onAnswer) }
                    p.view == "numbers" -> items(p.options.chunked(2)) { pair -> NumbersRow(pair, onAnswer) }
                    else -> items(p.options) { o -> OptionRow(o, toggles = p.view == "property" || p.view == "ability", onAnswer) }
                }
            }
            make?.let { MakeBar(it, onAnswer) }
        }
    }
}

@Composable
private fun Banner(text: String) {
    Text(text, color = WARN, fontSize = 13.sp,
         modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)
             .background(WARN.copy(alpha = 0.10f), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 6.dp))
}

/** A small coloured label. */
@Composable
private fun Pill(text: String, colour: Color) {
    Text(text, color = colour, fontSize = 11.sp, maxLines = 1,
         modifier = Modifier.background(colour.copy(alpha = 0.14f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
}

/** Difficulty against skill: the fill is the difficulty, the tick is your skill. */
@Composable
private fun DifficultyMeter(item: GameState.SmithItem) {
    val scale = maxOf(item.difficulty, item.max, 1) * 1.15f
    val colour = when { item.drains -> CHOSEN; item.difficulty > item.max -> WARN; else -> OK }
    Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(LINE)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth((item.difficulty / scale).coerceIn(0f, 1f)).background(colour))
        Box(Modifier.fillMaxWidth((item.max / scale).coerceIn(0f, 1f)), contentAlignment = Alignment.CenterEnd) {
            Box(Modifier.width(2.dp).fillMaxHeight().background(TEXT))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DesignCard(item: GameState.SmithItem?, costs: List<GameState.SmithCost>, description: List<String>) {
    var expanded by remember { mutableStateOf(false) }
    Surface(color = CARD, shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
                .clickable(enabled = description.isNotEmpty()) { expanded = !expanded }) {
        if (item == null) {
            Text("Choose a base item to begin.", color = SUB, fontSize = 14.sp, modifier = Modifier.padding(14.dp))
            return@Surface
        }
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(item.name, color = TEXT, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            DifficultyMeter(item)
            Row {
                Text("Difficulty ${item.difficulty}", color = when { item.drains -> CHOSEN; item.difficulty > item.max -> WARN; else -> OK },
                     fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text("  ·  your skill ${item.max}", color = SUB, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text(if (item.turnsLeft > 0) "${item.turnsLeft} of ${item.turns} turns left" else "${item.turns} turns", color = SUB, fontSize = 13.sp)
            }
            if (item.drains) Text("Beyond your skill: making it drains Smithing.", color = CHOSEN, fontSize = 12.sp)
            if (costs.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                costs.forEach { c ->
                    when (c.state) {
                        0 -> Pill("${c.label} · short", BAD)
                        2 -> Pill("${c.label} · needs ability", WARN)
                        3 -> Pill("drains ${c.label}", CHOSEN)
                        else -> Pill(c.label, TEXT)
                    }
                }
            }
            if (description.isNotEmpty()) {
                if (expanded) description.forEach { Text(it, color = Color(0xFFC8C8CC), fontSize = 13.sp) }
                else Text("${description.first()}  ▸", color = SUB, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private fun optionColour(state: Int) = when (state) {
    N.OPT_OFF -> DIM
    N.OPT_MAYBE -> WARN
    N.OPT_CHOSEN -> CHOSEN
    N.OPT_DEAR -> SUB
    N.OPT_ARTISTRY -> BAD
    else -> TEXT
}

@Composable
private fun StateBadge(state: Int) = when (state) {
    N.OPT_MAYBE -> Pill("needs ability", WARN)
    N.OPT_DEAR -> Pill("can't afford", SUB)
    N.OPT_ARTISTRY -> Pill("needs Artistry", BAD)
    else -> Unit
}

/** Main menu: two steps to a row. */
@Composable
private fun TileRow(row: List<GameState.SmithOption>, onAnswer: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(0.dp)) {
        row.forEach { o -> Box(Modifier.weight(1f).padding(4.dp)) { Tile(o, onAnswer) } }
        if (row.size == 1) Box(Modifier.weight(1f))
    }
}

@Composable
private fun Tile(o: GameState.SmithOption, onAnswer: (Int) -> Unit) {
    val enabled = o.state != N.OPT_OFF
    Surface(color = if (enabled) CARD_HI else CARD, shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 92.dp).clickable(enabled = enabled) { onAnswer(o.index) }) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(o.label, color = optionColour(o.state), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            if (o.detail.isNotEmpty()) Text(o.detail, color = if (enabled) SUB else DIM, fontSize = 12.sp, lineHeight = 15.sp, maxLines = 3)
            StateBadge(o.state)
        }
    }
}

@Composable
private fun MakeBar(o: GameState.SmithOption, onAnswer: (Int) -> Unit) {
    val enabled = o.state != N.OPT_OFF
    Column(Modifier.fillMaxWidth().background(Color(0xFF17171A)).padding(horizontal = 12.dp, vertical = 10.dp)) {
        if (!enabled && o.detail.isNotEmpty()) Text(o.detail, color = SUB, fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp))
        Button(onClick = { onAnswer(o.index) }, enabled = enabled, modifier = Modifier.fillMaxWidth().height(52.dp),
               colors = ButtonDefaults.buttonColors(containerColor = GOLD, contentColor = Color(0xFF241A00))) {
            Text("Make it", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun OptionRow(o: GameState.SmithOption, toggles: Boolean, onAnswer: (Int) -> Unit) {
    val enabled = o.state != N.OPT_OFF
    val chosen = o.state == N.OPT_CHOSEN
    Surface(color = if (chosen) CHOSEN.copy(alpha = 0.10f) else CARD, shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp).clickable(enabled = enabled) { onAnswer(o.index) }) {
        Row(Modifier.heightIn(min = 52.dp).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (toggles) Box(Modifier.size(20.dp).clip(CircleShape)
                    .then(if (chosen) Modifier.background(CHOSEN) else Modifier.border(1.5.dp, if (enabled) SUB else DIM, CircleShape)),
                contentAlignment = Alignment.Center) { if (chosen) Text("✓", color = BG, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(o.label, color = optionColour(o.state), fontSize = 15.sp, fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal)
                if (o.detail.isNotEmpty()) Text(o.detail, color = SUB, fontSize = 12.sp, maxLines = 3, lineHeight = 15.sp)
            }
            StateBadge(o.state)
        }
    }
}

/** An increase/decrease pair: "Attack bonus  +3  [−] [+]". */
@Composable
private fun NumbersRow(pair: List<GameState.SmithOption>, onAnswer: (Int) -> Unit) {
    val inc = pair[0]
    val dec = pair.getOrNull(1)
    val name = inc.label.removePrefix("Increase ").replaceFirstChar { it.uppercase() }
    val usable = inc.state != N.OPT_OFF || (dec != null && dec.state != N.OPT_OFF)
    Surface(color = CARD, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp)) {
        Row(Modifier.heightIn(min = 56.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(name, color = if (usable) TEXT else DIM, fontSize = 15.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    dec?.let { if (it.state != N.OPT_ON && it.state != N.OPT_OFF) StateBadge(it.state) }
                    if (inc.state != N.OPT_ON && inc.state != N.OPT_OFF) StateBadge(inc.state)
                }
            }
            Text(inc.detail, color = TEXT, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 4.dp))
            dec?.let { d ->
                FilledTonalButton(onClick = { onAnswer(d.index) }, enabled = d.state != N.OPT_OFF,
                                  contentPadding = PaddingValues(horizontal = 12.dp)) { Text("−", color = optionColour(d.state), fontSize = 18.sp) }
            }
            FilledTonalButton(onClick = { onAnswer(inc.index) }, enabled = inc.state != N.OPT_OFF,
                              contentPadding = PaddingValues(horizontal = 12.dp)) { Text("+", color = optionColour(inc.state), fontSize = 18.sp) }
        }
    }
}

/** NB_PROMPT_TEXT: a line of text (e.g. an artefact's name); empty or Cancel keeps the default. */
@Composable
fun TextPrompt(p: GameState.Prompt.Text, onCancel: () -> Unit) {
    var text by remember(p) { mutableStateOf(p.initial) }
    val long = p.maxBytes > 40
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(p.title) },
        text = {
            OutlinedTextField(text, { t -> if (t.toByteArray().size <= p.maxBytes) text = t.replace('\n', ' ') },
                              singleLine = !long, minLines = if (long) 4 else 1)
        },
        confirmButton = { TextButton(onClick = { N.nativeAnswerText(text.trim()) }) { Text("OK") } },
        dismissButton = {
            Row {
                if (p.random) TextButton(onClick = { N.nativeAnswer(N.TEXT_RANDOM) }) { Text("Random") }
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        },
    )
}
