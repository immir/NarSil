package org.narsil.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import org.narsil.android.GameState.OptionRow


/** The game's own options (narsil_options.c), full screen; changes apply at once. */
@Composable
fun OptionsScreen(p: GameState.Prompt.Options, onAnswer: (Int) -> Unit, onAppSettings: (() -> Unit)? = null) {
    val main = p.page == "main"
    Surface(color = Ui.Screen, modifier = Modifier.fillMaxSize()) {
        Column {
            ScreenHeader(p.title, onBack = if (!main) ({ onAnswer(-1) }) else null,
                         onClose = if (main && !LocalInPanel.current) ({ onAnswer(-1) }) else null)
            when (p.page) {
                "birth" -> if (p.rows.any { it is OptionRow.Toggle && it.locked }) Note("Fixed once the character exists.")
                "cheat" -> Note("Switching one on marks this character as a cheater for good, even if you switch it off again.")
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = rememberListPlace("options/${p.page}"), contentPadding = PaddingValues(bottom = 16.dp)) {
                if (main && onAppSettings != null) item {
                    LinkRow(OptionRow.Link(0, UiText.DISPLAY_LINK, UiText.DISPLAY_LINK_HINT), { onAppSettings() })
                }
                items(p.rows) { row ->
                    when (row) {
                        is OptionRow.Heading -> Text(row.text, color = Ui.Gold, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                                                     modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 6.dp))
                        is OptionRow.Toggle -> ToggleRow(row, onAnswer)
                        is OptionRow.Number -> NumberRow(row, onAnswer)
                        is OptionRow.Link -> LinkRow(row, onAnswer)
                    }
                }
            }
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(text, color = Ui.Warn, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
}

@Composable
private fun Card(onClick: (() -> Unit)?, content: @Composable () -> Unit) {
    Surface(color = Ui.Card, shape = Ui.CardShape, border = Ui.edge,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)) { content() }
}

@Composable
private fun ToggleRow(t: OptionRow.Toggle, onAnswer: (Int) -> Unit) {
    Card(if (t.locked) null else ({ onAnswer(t.answer) })) {
        Row(Modifier.heightIn(min = 56.dp).padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(t.description, color = if (t.locked) Ui.Sub else Ui.Text, fontSize = 15.sp)
            }
            Switch(t.on, { onAnswer(t.answer) }, enabled = !t.locked)
        }
    }
}

@Composable
private fun NumberRow(n: OptionRow.Number, onAnswer: (Int) -> Unit) {
    var drag by remember(n.value) { mutableFloatStateOf(n.value.toFloat()) }
    val shown = drag.roundToInt()
    fun set(v: Int) { if (v != n.value) onAnswer(n.base + v.coerceIn(n.min, n.max)) }
    Card(null) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(n.label, color = Ui.Text, fontSize = 15.sp)
                    Text(n.detail, color = Ui.Sub, fontSize = 12.sp)
                }
                FilledTonalButton(onClick = { set(n.value - 1) }, enabled = n.value > n.min,
                                  contentPadding = PaddingValues(horizontal = 12.dp)) { Text("−", fontSize = 18.sp) }
                Text("$shown", color = Ui.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                     modifier = Modifier.widthIn(min = 36.dp))
                FilledTonalButton(onClick = { set(n.value + 1) }, enabled = n.value < n.max,
                                  contentPadding = PaddingValues(horizontal = 12.dp)) { Text("+", fontSize = 18.sp) }
            }
            if (n.max - n.min > 20)
                Slider(drag, { drag = it }, valueRange = n.min.toFloat()..n.max.toFloat(), onValueChangeFinished = { set(shown) })
        }
    }
}

@Composable
private fun LinkRow(l: OptionRow.Link, onAnswer: (Int) -> Unit) {
    Card({ onAnswer(l.answer) }) {
        Row(Modifier.heightIn(min = 56.dp).padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(l.label, color = Ui.Text, fontSize = 15.sp)
                if (l.detail.isNotEmpty()) Text(l.detail, color = Ui.Sub, fontSize = 12.sp)
            }
            Text("›", color = Ui.Sub, fontSize = 20.sp)
        }
    }
}
