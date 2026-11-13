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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.narsil.android.NarSilNative as N

/** One tappable action: a native action id, or an arbitrary handler. */
data class BarAction(val label: String, val emphasised: Boolean = false, val active: Boolean = false,
                     val longPress: (() -> Unit)? = null, val run: () -> Unit)

private fun act(id: Int): () -> Unit = { N.nativeRequestAction(id) }

/**
 * Context actions for where the player stands, in the order a Sil player reaches
 * for them: leave by the stairs, grab what is underfoot, shoot, then doors/traps.
 */
fun contextActions(s: GameState.Status): List<BarAction> = buildList {
    if (s.has(N.CTX_DOWN_STAIRS)) add(BarAction("Down ▼", emphasised = true, run = act(N.ACT_GO_DOWN)))
    if (s.has(N.CTX_UP_STAIRS)) add(BarAction("Up ▲", emphasised = true, run = act(N.ACT_GO_UP)))
    if (s.has(N.CTX_DOWN_KNOWN) && !s.has(N.CTX_DOWN_STAIRS)) add(BarAction("To ▼", run = act(N.ACT_TRAVEL_DOWN)))
    if (s.has(N.CTX_UP_KNOWN) && !s.has(N.CTX_UP_STAIRS)) add(BarAction("To ▲", run = act(N.ACT_TRAVEL_UP)))
    if (s.has(N.CTX_FLOOR_ITEMS)) add(BarAction("Pick up", emphasised = true, run = act(N.ACT_PICKUP)))
    if (s.has(N.CTX_FORGE)) add(BarAction("Forge", emphasised = true, run = act(N.ACT_SMITH)))
    if (s.has(N.CTX_CAN_FIRE)) add(BarAction("Fire", emphasised = true, run = act(N.ACT_FIRE_NEAREST)))
    if (s.has(N.CTX_CAN_THROW)) add(BarAction("Throw", emphasised = true, run = act(N.ACT_THROW_NEAREST)))
    if (s.has(N.CTX_ADJ_CLOSED_DOOR)) add(BarAction("Open", emphasised = true, run = act(N.ACT_OPEN)))
    if (s.has(N.CTX_ADJ_OPEN_DOOR)) add(BarAction("Close", emphasised = true, run = act(N.ACT_CLOSE)))
    if (s.has(N.CTX_ADJ_TRAP)) add(BarAction("Disarm", emphasised = true, run = act(N.ACT_DISARM)))
    if (s.has(N.CTX_ADJ_RUBBLE)) add(BarAction("Tunnel", emphasised = true, run = act(N.ACT_TUNNEL)))
}

/**
 * Always present: the turn-by-turn Sil toolkit.  Look is the native list of what is in view.
 * Sil does not level you up: experience waits to be spent (the cheapest point costs 100),
 * which matters but is not urgent, so it comes last.
 */
/** [restOptions] opens the rest choices (long press on Rest). */
fun coreActions(s: GameState.Status, restOptions: () -> Unit): List<BarAction> = listOfNotNull(
    BarAction("Rest", longPress = restOptions, run = act(N.ACT_REST)),
    BarAction("Wait", run = act(N.ACT_HOLD)),
    BarAction(if (s.has(N.CTX_STEALTH)) "Stealth ●" else "Stealth", active = s.has(N.CTX_STEALTH), run = act(N.ACT_STEALTH)),
    BarAction(if (s.has(N.CTX_SINGING)) "Song ♪" else "Song", active = s.has(N.CTX_SINGING), run = act(N.ACT_SONG)),
    BarAction("Pack", run = act(N.ACT_INVENTORY)),
    BarAction("Look", run = { N.nativeRequestLookList() }),
    BarAction("Again", run = act(N.ACT_REPEAT)),
    BarAction("Skills (${s.newExp})", active = true, run = act(N.ACT_SKILLS)).takeIf { s.newExp >= 100 },
)

/** Less frequent but necessary: behind the ☰ button. */
fun moreActions(router: KeyRouter, showMessages: () -> Unit, showOptions: () -> Unit, showHelp: () -> Unit, toggleMap: () -> Unit,
                toggleKeyboard: () -> Unit): List<BarAction> = listOf(
    /* Pack, gear, character, skills, abilities, knowledge, smithing and game options live in the panel (@). */
    BarAction("Panel", run = act(N.ACT_PANEL)),
    BarAction("Map", run = toggleMap),
    BarAction("Messages", run = showMessages),
    BarAction("Bash", run = act(N.ACT_BASH)),
    BarAction("Tunnel", run = act(N.ACT_TUNNEL)),
    BarAction("Disarm", run = act(N.ACT_DISARM)),
    BarAction("Exchange", run = act(N.ACT_EXCHANGE)),
    BarAction("All commands", run = { router.special(Keys.ENTER) }),
    BarAction("Help", run = showHelp),
    BarAction("Options", run = showOptions),
    BarAction("Keyboard", run = toggleKeyboard),
    BarAction("Esc", run = { router.special(Keys.ESCAPE) }),
)

@Composable
fun ActionBar(actions: List<BarAction>, onMore: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Color(0xFF101012)).horizontalScroll(rememberScrollState())
            .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        actions.forEach { BarButton(it) }
        FilledTonalButton(onClick = onMore, contentPadding = PaddingValues(horizontal = 14.dp)) { Text("☰", fontSize = 18.sp) }
    }
}

/** The landscape rail's actions: the same buttons, wrapped into rows, scrolling if need be. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ActionRail(actions: List<BarAction>, modifier: Modifier = Modifier, onMore: () -> Unit) {
    FlowRow(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        actions.forEach { BarButton(it) }
        FilledTonalButton(onClick = onMore, contentPadding = PaddingValues(horizontal = 14.dp)) { Text("☰", fontSize = 18.sp) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BarButton(a: BarAction) {
    val padding = PaddingValues(horizontal = 14.dp)
    when {
        /* Material buttons take no long press: the same look, drawn by hand. */
        a.longPress != null -> Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.heightIn(min = 40.dp).combinedClickable(onLongClick = a.longPress, onClick = a.run)) {
            Box(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
                Text(a.label, style = MaterialTheme.typography.labelLarge)
            }
        }
        a.emphasised -> Button(onClick = a.run, contentPadding = padding) { Text(a.label, fontWeight = FontWeight.SemiBold) }
        a.active -> Button(onClick = a.run, contentPadding = padding,
                           colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A5F8A))) { Text(a.label) }
        else -> FilledTonalButton(onClick = a.run, contentPadding = padding) { Text(a.label) }
    }
}

/** Grid of the less frequent actions; tapping one runs it and closes the sheet. */
@Composable
fun MoreSheet(actions: List<BarAction>, onDismiss: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color(0x99000000))
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            color = Color(0xFF1C1C1E),
            modifier = Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, indication = null) {},
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                actions.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { a ->
                            FilledTonalButton(onClick = { onDismiss(); a.run() }, modifier = Modifier.weight(1f),
                                              contentPadding = PaddingValues(horizontal = 6.dp)) { Text(a.label, maxLines = 1) }
                        }
                        repeat(2 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

/** Long press on Rest: the game's rest modes, or a number of turns. */
@Composable
fun RestSheet(onDismiss: () -> Unit, onRest: (Int) -> Unit) {
    val choices = listOf(
        "As needed" to N.REST_AS_NEEDED,
        "Until health and voice are full" to N.REST_HP_AND_VOICE,
        "Until health or voice is full" to N.REST_HP_OR_VOICE,
        "10 turns" to 10, "50 turns" to 50, "100 turns" to 100,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rest") },
        text = {
            Column {
                choices.forEach { (label, turns) ->
                    Text(label, fontSize = 16.sp, modifier = Modifier.fillMaxWidth().clickable { onDismiss(); onRest(turns) }
                        .padding(vertical = 12.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
