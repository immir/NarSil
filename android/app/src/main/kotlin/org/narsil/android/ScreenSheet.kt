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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.narsil.android.NarSilNative as N

private val SUB = Color(0xFF9A9AA0)
private val GOLD = Color(0xFFF3D27A)

/** Stages whose options are chosen once (race, house, sex): a random pick and birth options are offered. */
private val CHOICE_STAGES = setOf("race", "house", "sex")

/**
 * Birth and death screens (narsil_birth.c), full screen: a title, some text,
 * then the options as cards, or the stat point buy.
 */
@Composable
fun ScreenSheet(p: GameState.Prompt.Screen, onAnswer: (Int) -> Unit, onScum: (() -> Unit)? = null) {
    val canGoBack = p.stage != "death" && p.stage != "race" && p.stage != "quickstart"
    Surface(color = Color(0xFF121214), modifier = Modifier.fillMaxSize()) {
        Column {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (canGoBack) TextButton(onClick = { onAnswer(-1) }) { Text("‹ Back") }
                Text(p.title, color = GOLD, fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
                     modifier = Modifier.weight(1f).padding(start = if (canGoBack) 0.dp else 16.dp))
                if (p.stage in CHOICE_STAGES) TextButton(onClick = { onAnswer(N.SCREEN_RANDOM) }) { Text("Random") }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(p.paragraphs) { t ->
                    Text(t, color = Color(0xFFD0D0D4), fontSize = 15.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                }
                if (p.stage == "stats") {
                    item {
                        Text("Points left: ${p.pointsLeft} of ${p.pointsTotal}", color = GOLD, fontSize = 15.sp,
                             modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    }
                    items(p.stats) { s -> StatRow(s, onAnswer) }
                    if (p.skills.isNotEmpty()) item { Figures(p) }
                } else items(p.options) { o -> OptionCard(o, onAnswer) }
                item { Box(Modifier.padding(bottom = 12.dp)) }
            }
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                if (p.stage in CHOICE_STAGES || p.stage == "quickstart")
                    TextButton(onClick = { onAnswer(N.SCREEN_OPTIONS) }) { Text("Birth options") }
                if (p.stage == "stats") {
                    TextButton(onClick = { onAnswer(N.SKILL_RESET) }) { Text("Reset") }
                    Button(onClick = { onAnswer(N.SKILL_ACCEPT) }) { Text("Accept") }
                }
                if (p.stage == "death" && onScum != null) Button(onClick = onScum) { Text("Restore scum save") }
                if (p.stage == "text") Button(onClick = { onAnswer(-1) }) { Text("Done") }
            }
        }
    }
}

@Composable
private fun OptionCard(o: GameState.ScreenOption, onAnswer: (Int) -> Unit) {
    val enabled = o.state != 0
    Surface(color = Color(0xFF1E1E22), shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp).clickable(enabled = enabled) { onAnswer(o.index) }) {
        Column(Modifier.heightIn(min = 48.dp).padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(o.label, color = if (enabled) Color.White else Color(0xFF55555A), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            if (o.summary.isNotEmpty()) Text(o.summary, color = Color(0xFF90CAF9), fontSize = 13.sp)
            if (o.description.isNotEmpty()) Text(o.description, color = SUB, fontSize = 13.sp)
        }
    }
}

@Composable
private fun StatRow(s: GameState.BirthStat, onAnswer: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(s.name, color = Color.White, fontSize = 16.sp)
            Text(if (s.spent > 0) "${s.spent} point${if (s.spent == 1) "" else "s"} spent" else "", color = SUB, fontSize = 12.sp)
        }
        Text("${s.value}", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(end = 4.dp))
        OutlinedButton(onClick = { onAnswer(N.SKILL_SELL + s.index) }, enabled = s.canSell,
                       contentPadding = PaddingValues(horizontal = 10.dp)) { Text("−") }
        FilledTonalButton(onClick = { onAnswer(N.SKILL_BUY + s.index) }, enabled = s.canBuy,
                          contentPadding = PaddingValues(horizontal = 10.dp)) { Text("+ ${s.nextCost}") }
    }
}

/** First signed number in a figure ("(+3, 1d8)" → 3, "[+2, 1-4]" → 2). */
private fun lead(v: String) = Regex("-?\\d+").find(v)?.value?.toIntOrNull()

/** Skills and combat as the stats now give them; what the last purchase changed is coloured. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Figures(p: GameState.Prompt.Screen) {
    val all = p.skills + p.combat
    val prev = remember { mutableMapOf<String, String>() }
    val delta = remember(p) {
        all.associate { (k, v) -> k to (prev[k]?.let { old -> (lead(v) ?: 0) - (lead(old) ?: 0) } ?: 0) }
            .also { all.forEach { (k, v) -> prev[k] = v } }
    }
    @Composable
    fun Figure(label: String, value: String) {
        val d = delta[label] ?: 0
        Row(Modifier.padding(end = 14.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = SUB, fontSize = 13.sp)
            Text(" $value", fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                 color = when { d > 0 -> Color(0xFF81C784); d < 0 -> Color(0xFFE57373); else -> Color.White })
            if (d != 0) Text(if (d > 0) " ▲" else " ▼", fontSize = 11.sp, color = if (d > 0) Color(0xFF81C784) else Color(0xFFE57373))
        }
    }
    Surface(color = Color(0xFF1E1E22), shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Skills", color = GOLD, fontSize = 14.sp)
            FlowRow { p.skills.forEach { (k, v) -> Figure(k, v) } }
            Text("Combat", color = GOLD, fontSize = 14.sp)
            FlowRow { p.combat.forEach { (k, v) -> Figure(k, v) } }
        }
    }
}
