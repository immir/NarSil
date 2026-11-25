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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Divider
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val SECTION_TITLES = mapOf(0 to "Inventory", 1 to "Equipment", 2 to "On the floor", 3 to "Throwing")
/** Display order: throwables first when offered (they are what the command wants). */
private val SECTION_ORDER = listOf(3, 0, 1, 2)

/**
 * Native replacement for the textui item menu.  Rows are large touch targets;
 * the game's own glyph and colour identify each item.  [onAnswer] receives the
 * item index, or -1 to cancel (scrim tap, Cancel, Back/Esc via the key router).
 */
@Composable
fun ItemSheet(prompt: GameState.Prompt.Items, colour: (Int) -> Int, onAnswer: (Int) -> Unit) {
    val sections = remember(prompt) {
        prompt.items.groupBy { it.section }.toList().sortedBy { (s, _) -> SECTION_ORDER.indexOf(s) }
    }
    Box(
        Modifier.fillMaxSize().background(Color(0x99000000))
            .clickable(remember { MutableInteractionSource() }, indication = null) { onAnswer(-1) },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            shape = Ui.SheetTop, border = Ui.edge,
            color = Ui.Sheet,
            modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp)
                .clickable(remember { MutableInteractionSource() }, indication = null) {},
        ) {
            Column(Modifier.padding(top = 12.dp)) {
                ScreenHeader(prompt.title, onClose = { onAnswer(-1) })
                LazyColumn(Modifier.fillMaxWidth()) {
                    sections.forEach { (section, items) ->
                        item(key = "h$section") {
                            Text(SECTION_TITLES[section] ?: "", color = Ui.Sub, fontSize = 13.sp,
                                 modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 4.dp))
                        }
                        items(items, key = { it.index }) { item -> ItemRow(item, colour) { onAnswer(item.index) } }
                    }
                    item { Box(Modifier.padding(bottom = 12.dp)) }
                }
            }
        }
    }
}

@Composable
private fun ItemRow(item: GameState.Item, colour: (Int) -> Int, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 52.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(String(Character.toChars(item.glyph)), color = Color(colour(item.attr)), fontFamily = FontFamily.Monospace,
             fontSize = 22.sp, modifier = Modifier.width(24.dp))
        Text(if (item.label == '-') "" else "${item.label})", color = Ui.Sub, fontFamily = FontFamily.Monospace,
             fontSize = 14.sp, modifier = Modifier.width(24.dp))
        Text(item.name, color = Special.colour(item.special) ?: Color.White, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
             modifier = Modifier.weight(1f))
        if (item.weightTenthsLb > 0)
            Text("%d.%d lb".format(item.weightTenthsLb / 10, item.weightTenthsLb % 10), color = Ui.Sub, fontSize = 13.sp)
    }
    Divider(color = Ui.Divider, modifier = Modifier.padding(start = 76.dp))
}
