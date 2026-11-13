package org.narsil.android

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.narsil.android.NarSilNative as N

/** What can be done with an item picked while browsing; the description replaces Inspect. */
@Composable
fun ActionSheet(prompt: GameState.Prompt.Actions, colour: (Int) -> Int, onAnswer: (Int) -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color(0x99000000))
            .clickable(remember { MutableInteractionSource() }, indication = null) { onAnswer(-1) },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            color = Color(0xFF1C1C1E),
            modifier = Modifier.fillMaxWidth()
                .clickable(remember { MutableInteractionSource() }, indication = null) {},
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(String(Character.toChars(prompt.glyph)), color = Color(colour(prompt.attr)),
                         fontFamily = FontFamily.Monospace, fontSize = 28.sp)
                    Text(prompt.name, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                         modifier = Modifier.weight(1f))
                }
                if (prompt.description.isNotEmpty())
                    Text(prompt.description, color = Color(0xFFC8C8CC), fontSize = 14.sp,
                         modifier = Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()))
                prompt.actions.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { a ->
                            FilledTonalButton(onClick = { onAnswer(a.index) }, enabled = a.valid,
                                              modifier = Modifier.weight(1f)) { Text(a.label, maxLines = 1) }
                        }
                        repeat(2 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                TextButton(onClick = { onAnswer(-1) }, modifier = Modifier.align(Alignment.End)) { Text("Back") }
            }
        }
    }
}

/** get_check: yes/no.  Dismissal counts as no, as Escape does in the textui. */
@Composable
fun CheckDialog(prompt: GameState.Prompt.Check, onAnswer: (Int) -> Unit) {
    AlertDialog(
        onDismissRequest = { onAnswer(0) },
        text = { Text(prompt.question.removeSuffix("[y/n]").trim(), fontSize = 17.sp) },
        confirmButton = { Button(onClick = { onAnswer(1) }) { Text("Yes") } },
        dismissButton = { OutlinedButton(onClick = { onAnswer(0) }) { Text("No") } },
    )
}

/**
 * Non-modal: the map stays live underneath (tap a monster or any grid, swipe a
 * direction).  Chips list targetable monsters nearest first.
 */
@Composable
fun AimBar(prompt: GameState.Prompt.Aim, onAnswer: (Int) -> Unit) {
    Surface(color = Color(0xEE1C1C1E), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (prompt.targets.isEmpty()) "Aim: swipe a direction or tap a grid" else "Aim: tap a target, or swipe a direction",
                 color = Color.White, fontSize = 14.sp)
            if (prompt.targets.isNotEmpty())
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    prompt.targets.forEach { t ->
                        AssistChip(onClick = { onAnswer(aimAt(t.x, t.y)) }, label = { Text(t.name, maxLines = 1) },
                                   colors = AssistChipDefaults.assistChipColors(
                                       labelColor = if (t.inRange) Color(0xFFFF8A80) else Color(0x80FF8A80)))
                    }
                }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (prompt.current != null)
                    FilledTonalButton(onClick = { onAnswer(N.AIM_CURRENT) }, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("Target") }
                if (prompt.targets.isNotEmpty())
                    FilledTonalButton(onClick = { onAnswer(N.AIM_NEAREST) }, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("Nearest") }
                OutlinedButton(onClick = { onAnswer(-1) }, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("Cancel") }
            }
        }
    }
}

fun aimAt(x: Int, y: Int): Int = N.AIM_GRID_BASE + y * N.AIM_GRID_STRIDE + x

/** Swipe octant (0 = east, anticlockwise) -> Angband keypad direction. */
val OCTANT_TO_DIR = intArrayOf(6, 9, 8, 7, 4, 1, 2, 3)

/** Grid offset (each -1..1) -> Angband keypad direction. */
fun keypadDir(dx: Int, dy: Int): Int = 5 - 3 * dy + dx

private val DIR_ARROWS = mapOf(1 to "↙", 2 to "↓", 3 to "↘", 4 to "←", 6 to "→", 7 to "↖", 8 to "↑", 9 to "↗")

/** Adjacent-direction prompt: the map stays live (tap a neighbour or swipe); chips for interesting neighbours. */
@Composable
fun DirectionBar(prompt: GameState.Prompt.Direction, onAnswer: (Int) -> Unit) {
    Surface(color = Color(0xEE1C1C1E), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Which way? Tap a neighbouring square or swipe", color = Color.White, fontSize = 14.sp)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                prompt.neighbours.forEach { n ->
                    FilledTonalButton(onClick = { onAnswer(n.dir) }, contentPadding = PaddingValues(horizontal = 12.dp)) {
                        Text("${n.what} ${DIR_ARROWS[n.dir] ?: ""}")
                    }
                }
                if (prompt.allowHere)
                    FilledTonalButton(onClick = { onAnswer(5) }, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("Here") }
                OutlinedButton(onClick = { onAnswer(-1) }, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("Cancel") }
            }
        }
    }
}

/** Pick one of several options (songs, ...): big rows with their descriptions. */
@Composable
fun ChoiceSheet(prompt: GameState.Prompt.Choice, onAnswer: (Int) -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color(0x99000000))
            .clickable(remember { MutableInteractionSource() }, indication = null) { onAnswer(-1) },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            color = Color(0xFF1C1C1E),
            modifier = Modifier.fillMaxWidth().heightIn(max = 600.dp)
                .clickable(remember { MutableInteractionSource() }, indication = null) {},
        ) {
            Column(Modifier.padding(vertical = 12.dp).verticalScroll(rememberScrollState())) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(prompt.title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    TextButton(onClick = { onAnswer(-1) }) { Text("Cancel") }
                }
                prompt.options.forEach { o ->
                    Column(Modifier.fillMaxWidth().clickable { onAnswer(o.index) }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Text(o.label, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        if (o.description.isNotEmpty()) Text(o.description, color = Color(0xFFB0B0B5), fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

/**
 * Spend experience on skills.  Every change is the game's own buy/sell command;
 * Cancel undoes this session, Accept keeps it.
 */
@Composable
fun SkillsSheet(prompt: GameState.Prompt.Skills, onAnswer: (Int) -> Unit) {
    PanelAwareSheet(null) {
            Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Skills", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text("Experience to spend: ${prompt.expLeft}", color = Color(0xFFFFD54F), fontSize = 14.sp)
                prompt.skills.forEach { sk ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(sk.name, color = Color.White, fontSize = 16.sp)
                            Text("base ${sk.base}" + if (sk.spent > 0) "  (+${sk.spent} exp)" else "", color = Color(0xFF9A9AA0), fontSize = 12.sp)
                        }
                        Text("${sk.total}", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                             modifier = Modifier.padding(end = 4.dp))
                        OutlinedButton(onClick = { onAnswer(N.SKILL_SELL + sk.index) }, enabled = sk.spent > 0,
                                       contentPadding = PaddingValues(horizontal = 10.dp)) { Text("−") }
                        FilledTonalButton(onClick = { onAnswer(N.SKILL_BUY + sk.index) }, enabled = sk.nextCost <= prompt.expLeft,
                                          contentPadding = PaddingValues(horizontal = 10.dp)) { Text("+ ${sk.nextCost}") }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.align(Alignment.End)) {
                    TextButton(onClick = { onAnswer(N.SKILL_RESET) }) { Text("Reset") }
                    OutlinedButton(onClick = { onAnswer(-1) }) { Text("Cancel") }
                    Button(onClick = { onAnswer(N.SKILL_ACCEPT) }) { Text("Accept") }
                }
            }
        }
}
