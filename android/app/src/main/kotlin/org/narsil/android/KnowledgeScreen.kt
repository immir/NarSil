package org.narsil.android

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Divider
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.narsil.android.GameState.KnowRow
import org.narsil.android.NarSilNative as N

private val BG = Color(0xFF121214)
private val CARD = Color(0xFF1E1E22)
private val OPEN = Color(0xFF26262C)
private val TEXT = Color(0xFFE6E6EA)
private val SUB = Color(0xFF9A9AA0)
private val GOLD = Color(0xFFF3D27A)

/**
 * The knowledge browser (narsil_knowledge.c): the main page lists the
 * sections; each section lists its entries under group headings, and tapping
 * one opens it in place with what is known.
 */
@Composable
fun KnowledgeScreen(p: GameState.Prompt.Knowledge, colour: (Int) -> Int, onAnswer: (Int) -> Unit) {
    val main = p.view == "main"
    key(p.view) { Page(p, main, rememberListPlace("knowledge/${p.view}"), colour, onAnswer) }
}

/** One view; [list] is its own, so each view starts at the top. */
@Composable
private fun Page(p: GameState.Prompt.Knowledge, main: Boolean, list: LazyListState, colour: (Int) -> Int, onAnswer: (Int) -> Unit) {
    Surface(color = BG, modifier = Modifier.fillMaxSize()) {
        Column {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!(main && LocalInPanel.current)) TextButton(onClick = { onAnswer(-1) }) { Text(if (main) "Close" else "‹ Back") }
                Text(p.title, color = GOLD, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (!main) Text("${p.rows.count { it is KnowRow.Entry }}", color = SUB, fontSize = 13.sp)
            }
            if (!main && p.rows.none { it is KnowRow.Entry })
                Text(when (p.view) { "history" -> "Nothing has happened yet."; "scores" -> "No characters have ended yet."; else -> "Nothing here yet." }, color = SUB, fontSize = 14.sp,
                     modifier = Modifier.padding(16.dp))
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(bottom = 24.dp)) {
                items(p.rows) { row ->
                    when (row) {
                        is KnowRow.Group -> Text(row.title, color = SUB, fontSize = 13.sp,
                                                 modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 4.dp))
                        is KnowRow.Entry -> if (main) SectionCard(row, colour, onAnswer)
                                            else EntryRow(row, p, colour, onAnswer)
                    }
                }
            }
        }
    }
}

@Composable
private fun Glyph(e: KnowRow.Entry, colour: (Int) -> Int, size: Int = 20) {
    Text(String(Character.toChars(e.glyph)), color = Color(colour(e.attr)), fontFamily = FontFamily.Monospace,
         fontSize = size.sp, modifier = Modifier.width((size + 4).dp))
}

@Composable
private fun SectionCard(e: KnowRow.Entry, colour: (Int) -> Int, onAnswer: (Int) -> Unit) {
    Surface(color = CARD, shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clickable { onAnswer(e.index) }) {
        Row(Modifier.heightIn(min = 64.dp).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Glyph(e, colour, 24)
            Column(Modifier.weight(1f)) {
                Text(e.name, color = TEXT, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                if (e.note.isNotEmpty()) Text(e.note, color = SUB, fontSize = 13.sp)
            }
            Text("›", color = SUB, fontSize = 20.sp)
        }
    }
}

@Composable
private fun EntryRow(e: KnowRow.Entry, p: GameState.Prompt.Knowledge, colour: (Int) -> Int, onAnswer: (Int) -> Unit) {
    val open = e.index == p.expanded
    val expandable = p.view != "history" && p.view != "scores"
    Column(Modifier.fillMaxWidth().background(if (open) OPEN else Color.Transparent).animateContentSize()) {
        Row(Modifier.fillMaxWidth().then(if (expandable) Modifier.clickable { onAnswer(if (open) N.KNOW_COLLAPSE else e.index) } else Modifier)
                .heightIn(min = 46.dp).padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Glyph(e, colour)
            Text(e.name, color = TEXT, fontSize = 15.sp, fontWeight = if (open) FontWeight.SemiBold else FontWeight.Normal,
                 maxLines = if (open || !expandable) 4 else 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (e.note.isNotEmpty()) Text(e.note, color = SUB, fontSize = 12.sp)
        }
        if (open && p.text.isNotEmpty())
            Text(p.text, color = Color(0xFFC8C8CC), fontSize = 14.sp, lineHeight = 19.sp,
                 modifier = Modifier.padding(start = 50.dp, end = 16.dp, bottom = 12.dp))
        if (open && p.actions.isNotEmpty())
            Row(Modifier.padding(start = 50.dp, end = 16.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                p.actions.forEach { (answer, label) -> FilledTonalButton(onClick = { onAnswer(answer) }) { Text(label) } }
            }
        Divider(color = Color(0xFF2C2C2E), modifier = Modifier.padding(start = 50.dp))
    }
}
