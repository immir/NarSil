package org.narsil.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

private const val MANUAL = "https://narsil.readthedocs.io/en/latest/"

/** The help files shipped with the game data, read from the installed copy. */
private fun helpFile(context: Context, name: String): String =
    runCatching { File(context.filesDir, "lib/help/$name").readText() }.getOrDefault("($name is missing)")
        .lines().filterNot { it.startsWith(".. menu::") }.joinToString("\n").trimEnd()

/** Help: a touch-controls guide, the game's command and symbol references, and the online manual. */
@Composable
fun HelpScreen(context: Context, onDismiss: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val tabs = listOf("Touch controls", "Symbols", "Keyboard")
    Surface(color = Ui.Screen, modifier = Modifier.fillMaxSize()) {
        Column {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss) { Text("Close") }
                Text("Help", color = Ui.Gold, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(MANUAL))) }) { Text("Online manual ↗") }
            }
            ScrollableTabRow(selectedTabIndex = tab, containerColor = Ui.Screen, edgePadding = 8.dp) {
                tabs.forEachIndexed { i, t -> Tab(selected = i == tab, onClick = { tab = i }, text = { Text(t) }) }
            }
            when (tab) {
                0 -> Guide()
                1 -> HelpFile(remember { helpFile(context, "symbols.txt") })
                else -> HelpFile(remember { helpFile(context, "commands.txt") })
            }
        }
    }
}

@Composable
private fun Guide() {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        items(TOUCH_GUIDE) { (title, points) ->
            Text(title, color = Ui.Gold, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
            points.forEach { p ->
                Row(Modifier.padding(bottom = 6.dp)) {
                    Text("•  ", color = Ui.Sub, fontSize = 15.sp)
                    Text(p, color = Ui.Text, fontSize = 15.sp, lineHeight = 20.sp)
                }
            }
        }
        item { Text(" ", modifier = Modifier.padding(bottom = 16.dp)) }
    }
}

/** A help file, parsed out of its fixed-width layout. */
private sealed interface HelpBlock {
    data class Heading(val text: String) : HelpBlock
    data class Prose(val text: String) : HelpBlock
    data class Pre(val text: String) : HelpBlock
    data class Entry(val key: String, val text: String) : HelpBlock
}

private val ENTRY = Regex("""^\s*(\S{1,3})\s{2,}(\S.*?)\s*$""")
private val UNDERLINE = Regex("""^[-=]{3,}\s*$""")

/** Where a two-column table line splits: a run of spaces, then "key  text" from column 20 on. */
private fun splitColumns(line: String): Pair<String, String>? =
    Regex("""\s{2,}(?=\S{1,3}\s{2,}\S)""").findAll(line).firstOrNull { it.range.last + 1 >= 20 }
        ?.let { m -> line.substring(0, m.range.first) to line.substring(m.range.last + 1) }

/**
 * Headings (underlined), two-column tables (each half "key  text"; "-" means
 * unused), indented diagrams kept as they are, and prose to wrap.
 */
private fun parseHelp(text: String): List<HelpBlock> {
    val out = mutableListOf<HelpBlock>()
    val prose = StringBuilder()
    val pre = mutableListOf<String>()
    var inTable = false
    fun flush() {
        if (prose.isNotEmpty()) out += HelpBlock.Prose(prose.toString().trim()).also { prose.clear() }
        if (pre.isNotEmpty()) out += HelpBlock.Pre(pre.joinToString("\n").trimEnd()).also { pre.clear() }
    }
    fun entry(half: String) = ENTRY.find(half)?.let { m -> HelpBlock.Entry(m.groupValues[1], m.groupValues[2]) }
    val lines = text.lines()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val next = lines.getOrNull(i + 1).orEmpty()
        val table = splitColumns(line)?.let { (l, r) -> listOfNotNull(entry(l), entry(r)).takeIf { it.size == 2 } }
        when {
            line.isBlank() -> { flush(); inTable = false }
            UNDERLINE.matches(next) && !line.startsWith(" ") -> { flush(); out += HelpBlock.Heading(line.trim()); i++ }
            table != null -> { flush(); out += table; inTable = true }
            inTable && entry(line) != null -> out += entry(line)!!
            inTable && line.startsWith("   ") -> out += HelpBlock.Entry(" ", line.trim())
            line.startsWith(" ") -> { if (prose.isNotEmpty()) flush(); pre += line }
            else -> { if (pre.isNotEmpty()) flush(); prose.append(line.trim()).append(' ') }
        }
        i++
    }
    flush()
    return out.filterNot { it is HelpBlock.Entry && it.text == "-" }
}

@Composable
private fun HelpFile(text: String) {
    val blocks = remember(text) { parseHelp(text) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        items(blocks) { b ->
            when (b) {
                is HelpBlock.Heading -> Text(b.text, color = Ui.Gold, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                                             modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
                is HelpBlock.Prose -> Text(b.text, color = Ui.Text, fontSize = 14.sp, lineHeight = 19.sp, modifier = Modifier.padding(vertical = 4.dp))
                is HelpBlock.Pre -> Text(b.text, color = Ui.Text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp,
                                         softWrap = false, modifier = Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp))
                is HelpBlock.Entry -> Row(Modifier.padding(vertical = 3.dp)) {
                    Text(b.key, color = Ui.Gold, fontFamily = FontFamily.Monospace, fontSize = 15.sp, modifier = Modifier.width(44.dp))
                    Text(b.text, color = Ui.Text, fontSize = 14.sp, lineHeight = 18.sp)
                }
            }
        }
        item { Text(" ", modifier = Modifier.padding(bottom = 16.dp)) }
    }
}

/** Shown once, the first time the map appears: the few gestures that matter most, and a way into Help. */
@Composable
fun TipsCard(onHelp: () -> Unit, onDismiss: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0x99000000))
            .clickable(remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center) {
        Surface(shape = RoundedCornerShape(16.dp), color = Ui.Sheet, border = Ui.edge, modifier = Modifier.padding(24.dp)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(FIRST_RUN_TITLE, color = Ui.Gold, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                FIRST_RUN_TIPS.forEach { t ->
                    Row {
                        Text("•  ", color = Ui.Sub, fontSize = 15.sp)
                        Text(t, color = Ui.Text, fontSize = 15.sp, lineHeight = 20.sp)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    TextButton(onClick = onHelp) { Text("Open Help") }
                    Button(onClick = onDismiss) { Text("Got it") }
                }
            }
        }
    }
}
