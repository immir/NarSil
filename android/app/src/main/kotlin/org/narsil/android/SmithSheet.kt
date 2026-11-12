package org.narsil.android

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Divider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.narsil.android.NarSilNative as N

private val DIM = Color(0xFF77777D)
private val SUB = Color(0xFF9A9AA0)
private val WARN = Color(0xFFFFB74D)
private val BAD = Color(0xFFEF5350)
private val CHOSEN = Color(0xFF90CAF9)

/**
 * The smithing screen (narsil_smith.c): the design so far on top, then the
 * current menu.  Back (or Esc) returns to the previous menu; from the main
 * menu it leaves the forge.
 */
@Composable
fun SmithSheet(p: GameState.Prompt.Smith, onAnswer: (Int) -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0x99000000)), contentAlignment = Alignment.BottomCenter) {
        Surface(shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp), color = Color(0xFF1C1C1E),
                modifier = Modifier.fillMaxWidth().fillMaxHeight(0.94f)) {
            Column {
                Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { onAnswer(-1) }) { Text(if (p.view == "main") "Close" else "‹ Back") }
                    Text(p.title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                }
                LazyColumn(Modifier.fillMaxWidth()) {
                    items(p.notes) { n -> Text(n, color = WARN, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) }
                    p.item?.let { item -> item { DesignCard(item, p.costs, p.description) } }
                    if (p.view == "numbers") items(p.options.chunked(2)) { pair -> NumbersRow(pair, onAnswer) }
                    else items(p.options) { o -> OptionRow(o, toggles = p.view == "property" || p.view == "ability", onAnswer) }
                    item { Box(Modifier.padding(bottom = 16.dp)) }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DesignCard(item: GameState.SmithItem, costs: List<GameState.SmithCost>, description: List<String>) {
    var expanded by remember { mutableStateOf(false) }
    Surface(color = Color(0xFF26262B), shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).clickable { expanded = !expanded }) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(item.name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            val tooHard = item.difficulty > item.max
            Text("Difficulty ${item.difficulty} (your skill ${item.max})  ·  ${item.turns} turns" +
                     if (item.turnsLeft > 0) "  ·  ${item.turnsLeft} left" else "",
                 color = when { item.drains -> CHOSEN; tooHard -> WARN; else -> SUB }, fontSize = 13.sp)
            if (costs.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                costs.forEach { c ->
                    val (fg, text) = when (c.state) {
                        0 -> BAD to c.label
                        2 -> WARN to "needs ${c.label}"
                        3 -> CHOSEN to "drains ${c.label}"
                        else -> Color(0xFFD0D0D4) to c.label
                    }
                    Text(text, color = fg, fontSize = 12.sp,
                         modifier = Modifier.background(fg.copy(alpha = 0.14f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
                }
            }
            if (description.isNotEmpty()) {
                if (expanded) description.forEach { Text(it, color = Color(0xFFC8C8CC), fontSize = 13.sp) }
                else Text("${description.first()}  ▸", color = SUB, fontSize = 13.sp, maxLines = 1)
            }
        }
    }
}

private fun optionColour(state: Int) = when (state) {
    N.OPT_OFF -> Color(0xFF55555A)
    N.OPT_MAYBE -> WARN
    N.OPT_CHOSEN -> CHOSEN
    N.OPT_DEAR -> DIM
    N.OPT_ARTISTRY -> BAD
    else -> Color.White
}

private fun optionHint(state: Int) = when (state) {
    N.OPT_MAYBE -> "needs an ability you lack"
    N.OPT_DEAR -> "can't afford"
    N.OPT_ARTISTRY -> "needs Artistry"
    else -> null
}

@Composable
private fun OptionRow(o: GameState.SmithOption, toggles: Boolean, onAnswer: (Int) -> Unit) {
    val enabled = o.state != N.OPT_OFF
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { onAnswer(o.index) }.heightIn(min = 48.dp)
            .padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (toggles) Text(if (o.state == N.OPT_CHOSEN) "✓" else "", color = CHOSEN, fontSize = 16.sp, modifier = Modifier.width(24.dp))
        Column(Modifier.weight(1f)) {
            Text(o.label, color = optionColour(o.state), fontSize = 16.sp)
            listOfNotNull(o.detail.takeIf { it.isNotEmpty() }, optionHint(o.state)).joinToString("  ·  ").takeIf { it.isNotEmpty() }
                ?.let { Text(it, color = SUB, fontSize = 12.sp, maxLines = 3) }
        }
    }
    Divider(color = Color(0xFF2C2C2E))
}

/** An increase/decrease pair: "Attack bonus  +3  [−] [+]". */
@Composable
private fun NumbersRow(pair: List<GameState.SmithOption>, onAnswer: (Int) -> Unit) {
    val inc = pair[0]
    val dec = pair.getOrNull(1)
    val name = inc.label.removePrefix("Increase ").replaceFirstChar { it.uppercase() }
    val usable = inc.state != N.OPT_OFF || (dec != null && dec.state != N.OPT_OFF)
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(name, color = if (usable) Color.White else Color(0xFF55555A), fontSize = 16.sp)
            listOfNotNull(dec?.let { optionHint(it.state)?.let { h -> "− $h" } }, optionHint(inc.state)?.let { "+ $it" })
                .joinToString("  ·  ").takeIf { it.isNotEmpty() }?.let { Text(it, color = SUB, fontSize = 12.sp) }
        }
        Text(inc.detail, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        dec?.let { d ->
            FilledTonalButton(onClick = { onAnswer(d.index) }, enabled = d.state != N.OPT_OFF,
                              contentPadding = PaddingValues(horizontal = 12.dp)) { Text("−", color = optionColour(d.state)) }
        }
        FilledTonalButton(onClick = { onAnswer(inc.index) }, enabled = inc.state != N.OPT_OFF,
                          contentPadding = PaddingValues(horizontal = 12.dp)) { Text("+", color = optionColour(inc.state)) }
    }
    Divider(color = Color(0xFF2C2C2E))
}

/** NB_PROMPT_TEXT: a line of text (e.g. an artefact's name); empty or Cancel keeps the default. */
@Composable
fun TextPrompt(p: GameState.Prompt.Text, onCancel: () -> Unit) {
    var text by remember(p) { mutableStateOf(p.initial) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(p.title) },
        text = { OutlinedTextField(text, { text = it.take(19) }, singleLine = true) },
        confirmButton = { TextButton(onClick = { N.nativeAnswerText(text.trim()) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}
