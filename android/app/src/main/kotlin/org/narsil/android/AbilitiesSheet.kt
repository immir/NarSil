package org.narsil.android

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.narsil.android.NarSilNative as N

/**
 * Which skill tab and ability are open.  Held outside the sheet: learning an
 * ability briefly replaces it with the game's confirmation prompt.
 */
object AbilitiesView {
    var tab by mutableIntStateOf(0)
    var open by mutableStateOf<String?>(null)
}

private fun owned(a: GameState.Ability) = a.state >= N.ABILITY_ON

private fun colour(a: GameState.Ability) = when (a.state) {
    N.ABILITY_ON -> Color.White
    N.ABILITY_OFF, N.ABILITY_ITEM_OFF -> Color(0xFFEF9A9A)
    N.ABILITY_ITEM_ON -> Color(0xFFA5D6A7)
    N.ABILITY_AVAILABLE -> Color(0xFF90CAF9)
    else -> Color(0xFF77777D)
}

private fun summary(a: GameState.Ability, skill: GameState.AbilitySkill, expLeft: Int) = when (a.state) {
    N.ABILITY_ON -> "Learnt"
    N.ABILITY_OFF -> "Learnt · switched off"
    N.ABILITY_ITEM_ON -> "From equipment"
    N.ABILITY_ITEM_OFF -> "From equipment · switched off"
    N.ABILITY_AVAILABLE -> if (a.cost <= expLeft) "Learn for ${a.cost} exp" else "${a.cost} exp (you have $expLeft)"
    else -> listOfNotNull(
        "needs ${a.points} base ${skill.name}, you have ${skill.base}".takeIf { skill.base < a.points },
        "needs ${a.prereqs}".takeIf { a.cost < 0 && a.prereqs.isNotEmpty() },
    ).joinToString(" · ").replaceFirstChar { it.uppercase() }
}

/** do_cmd_abilities() natively: skills as tabs, abilities as rows; tap to read, then learn or switch. */
@Composable
fun AbilitiesSheet(prompt: GameState.Prompt.Abilities, onAnswer: (Int) -> Unit) {
    val tab = AbilitiesView.tab.coerceIn(0, maxOf(prompt.skills.size - 1, 0))
    val shown = prompt.abilities.filter { it.skill == tab }
    Box(Modifier.fillMaxSize().background(Color(0x99000000)), contentAlignment = Alignment.BottomCenter) {
        Surface(shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp), color = Color(0xFF1C1C1E),
                modifier = Modifier.fillMaxWidth().fillMaxHeight(0.9f)) {
            Column {
                Row(Modifier.padding(start = 16.dp, top = 12.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Abilities", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Text("Experience to spend: ${prompt.expLeft}", color = Color(0xFFFFD54F), fontSize = 13.sp)
                    }
                    TextButton(onClick = { onAnswer(-1) }) { Text("Close") }
                }
                ScrollableTabRow(selectedTabIndex = tab, containerColor = Color(0xFF1C1C1E), edgePadding = 8.dp) {
                    prompt.skills.forEachIndexed { i, sk ->
                        val name = sk.name
                        val learnt = prompt.abilities.count { it.skill == i && it.state == N.ABILITY_ON || it.skill == i && it.state == N.ABILITY_OFF }
                        Tab(selected = i == tab, onClick = { AbilitiesView.tab = i; AbilitiesView.open = null },
                            text = { Text(if (learnt > 0) "$name $learnt" else name) })
                    }
                }
                prompt.skills.getOrNull(tab)?.let { sk ->
                    Text("${sk.name}: base ${sk.base}" + (if (sk.total != sk.base) " (${sk.total} with bonuses)" else "") +
                             ". Abilities depend on base points.",
                         color = Color(0xFF9A9AA0), fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                }
                LazyColumn(Modifier.fillMaxWidth()) {
                    items(shown, key = { it.index }) { a ->
                        AbilityRow(a, prompt.skills.getOrElse(a.skill) { GameState.AbilitySkill("", 0, 0) }, prompt.expLeft, AbilitiesView.open == a.name, onAnswer)
                    }
                }
            }
        }
    }
}

@Composable
private fun AbilityRow(a: GameState.Ability, skill: GameState.AbilitySkill, expLeft: Int, open: Boolean, onAnswer: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().clickable { AbilitiesView.open = if (open) null else a.name }
        .padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(a.name, color = colour(a), fontSize = 16.sp, fontWeight = if (owned(a)) FontWeight.SemiBold else FontWeight.Normal,
                 modifier = Modifier.weight(1f))
            Text("${a.points}", color = Color(0xFF77777D), fontSize = 13.sp)
        }
        Text(summary(a, skill, expLeft), color = Color(0xFF9A9AA0), fontSize = 12.sp)
        if (open) {
            Text(a.description, color = Color(0xFFD0D0D4), fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
            if (a.prereqs.isNotEmpty() && !owned(a))
                Text("Requires ${a.prereqs}", color = Color(0xFF9A9AA0), fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (a.state) {
                    N.ABILITY_AVAILABLE -> Button(onClick = { onAnswer(a.index) }, enabled = a.cost <= expLeft) { Text("Learn (${a.cost} exp)") }
                    N.ABILITY_ON, N.ABILITY_ITEM_ON -> FilledTonalButton(onClick = { onAnswer(a.index) }) { Text("Switch off") }
                    N.ABILITY_OFF, N.ABILITY_ITEM_OFF -> FilledTonalButton(onClick = { onAnswer(a.index) }) { Text("Switch on") }
                }
            }
        }
    }
    Divider(color = Color(0xFF2C2C2E))
}
