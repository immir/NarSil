package org.narsil.android

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import org.narsil.android.NarSilNative as N

/** Two thin concentric outlines inside a pill: the mark of a button that does more when held. */
private fun Modifier.holdMark(colour: Color): Modifier = drawWithContent {
    drawContent()
    val stroke = Stroke(1.dp.toPx())
    for (inset in listOf(1.5.dp.toPx(), 4.5.dp.toPx())) {
        drawRoundRect(colour.copy(alpha = 0.25f), Offset(inset, inset), Size(size.width - 2 * inset, size.height - 2 * inset),
                      CornerRadius(maxOf(size.height / 2f - inset, 0f)), style = stroke)
    }
}

/** One tappable action: a native action id, or an arbitrary handler. */
data class BarAction(val label: String, val emphasised: Boolean = false, val active: Boolean = false,
                     val longPress: (() -> Unit)? = null, val pin: Int? = null, val run: () -> Unit)

private fun act(id: Int): () -> Unit = { N.nativeRequestAction(id) }

/** Pinned kinds (Pack → an item → Pin to bar): tap uses one, long-press unpins. */
fun quickActions(slots: List<GameState.QuickSlot>, unpin: (Int) -> Unit): List<BarAction> =
    slots.map { q -> BarAction("${q.name} ×${q.count}", active = q.count > 0, longPress = { unpin(q.kind) }) { if (q.count > 0) N.nativeRequestUseKind(q.kind) } }

/** The commands that can be pinned to the bar (long-press one in ☰), by native action id. */
private val PINNABLE = mapOf(
    N.ACT_INVENTORY to "Pack", N.ACT_BASH to "Bash", N.ACT_TUNNEL to "Tunnel", N.ACT_DISARM to "Disarm", N.ACT_EXCHANGE to "Exchange",
    N.ACT_TRAVEL_DOWN to "Walk to ▼", N.ACT_TRAVEL_UP to "Walk to ▲",
)

/** Pinned commands (☰ → long-press a command): tap runs it, long-press unpins.  Ones the context already offers are left out. */
fun pinnedActions(ids: List<Int>, shown: List<BarAction>, unpin: (Int) -> Unit): List<BarAction> =
    ids.mapNotNull { id -> PINNABLE[id]?.takeIf { l -> shown.none { it.label == l } }?.let { BarAction(it, longPress = { unpin(id) }, run = act(id)) } }

/**
 * Context actions for where the player stands, in the order a Sil player reaches
 * for them: leave by the stairs, grab what is underfoot, shoot, then doors/traps.
 */
fun contextActions(s: GameState.Status): List<BarAction> = buildList {
    if (s.has(N.CTX_DOWN_STAIRS)) add(BarAction("Down ▼", emphasised = true, run = act(N.ACT_GO_DOWN)))
    if (s.has(N.CTX_UP_STAIRS)) add(BarAction("Up ▲", emphasised = true, run = act(N.ACT_GO_UP)))
    if (s.has(N.CTX_FLOOR_ITEMS)) add(BarAction(if (s.has(N.CTX_FLOOR_SPECIAL)) "Pick up ✦" else "Pick up", emphasised = true, longPress = act(N.ACT_FLOOR), run = act(N.ACT_PICKUP)))
    if (s.has(N.CTX_FORGE)) add(BarAction("Forge", emphasised = true, run = act(N.ACT_SMITH)))
    if (s.has(N.CTX_CAN_FIRE)) add(BarAction("Fire", emphasised = true, longPress = act(N.ACT_FIRE_AIM), run = act(N.ACT_FIRE_NEAREST)))
    if (s.has(N.CTX_CAN_THROW)) add(BarAction("Throw", emphasised = true, run = act(N.ACT_THROW_NEAREST)))
    if (s.has(N.CTX_ADJ_CLOSED_DOOR) || s.has(N.CTX_ADJ_CHEST)) add(BarAction("Open", emphasised = true, run = act(N.ACT_OPEN)))
    if (s.has(N.CTX_ADJ_SKELETON)) add(BarAction("Search", emphasised = true, run = act(N.ACT_SEARCH)))
    if (s.has(N.CTX_ADJ_OPEN_DOOR)) add(BarAction("Close", emphasised = true, run = act(N.ACT_CLOSE)))
    if (s.has(N.CTX_ADJ_TRAP)) add(BarAction("Disarm", emphasised = true, run = act(N.ACT_DISARM)))
    if (s.has(N.CTX_ADJ_RUBBLE)) add(BarAction("Tunnel", emphasised = true, run = act(N.ACT_TUNNEL)))
}

/**
 * Always present: the turn-by-turn Sil toolkit.  Look is the native list of what is in view.  The pack is in the
 * panel (☰ → Pack can pin it here), and unspent experience is the pill in the status, which opens Skills.
 */
/**
 * [restOptions] opens the rest choices (long press on Rest).  With the d-pad shown its centre key waits (tap) and
 * rests (hold), so Wait and Rest leave the bar and the rest choices move to ☰.
 */
fun coreActions(s: GameState.Status, padShown: Boolean, restOptions: () -> Unit): List<BarAction> = listOfNotNull(
    BarAction("Rest", longPress = restOptions, run = act(N.ACT_REST)).takeIf { !padShown },
    BarAction("Explore", longPress = act(N.ACT_EXPLORE_STEP), run = act(N.ACT_EXPLORE)),
    BarAction("Wait", run = act(N.ACT_HOLD)).takeIf { !padShown },
    BarAction(if (s.has(N.CTX_STEALTH)) "Stealth ●" else "Stealth", active = s.has(N.CTX_STEALTH), run = act(N.ACT_STEALTH)),
    BarAction(if (s.has(N.CTX_SINGING)) "Song ♪" else "Song", active = s.has(N.CTX_SINGING), run = act(N.ACT_SONG)),
    BarAction("Look", run = { N.nativeRequestLookList() }),
    BarAction("Again", run = act(N.ACT_REPEAT)),
)

/** Less frequent but necessary: behind the ☰ button. */
fun moreActions(s: GameState.Status, router: KeyRouter, showMessages: () -> Unit, showHelp: () -> Unit, toggleMap: () -> Unit,
                toggleTiles: () -> Unit, tilesOn: Boolean, restOptions: (() -> Unit)?, toggleKeyboard: () -> Unit, tabsOnTop: Boolean = false): List<BarAction> = listOfNotNull(
    /* Pack, gear, character, skills, abilities, knowledge, smithing and game options live in the panel (@). */
    BarAction("Panel", run = act(N.ACT_PANEL)).takeIf { !tabsOnTop },
    restOptions?.let { BarAction("Rest…", run = it) },
    BarAction("Pack", pin = N.ACT_INVENTORY, run = act(N.ACT_INVENTORY)),
    BarAction("Map", run = toggleMap),
    BarAction("Walk to ▼", pin = N.ACT_TRAVEL_DOWN, run = act(N.ACT_TRAVEL_DOWN)).takeIf { s.has(N.CTX_DOWN_KNOWN) && !s.has(N.CTX_DOWN_STAIRS) },
    BarAction("Walk to ▲", pin = N.ACT_TRAVEL_UP, run = act(N.ACT_TRAVEL_UP)).takeIf { s.has(N.CTX_UP_KNOWN) && !s.has(N.CTX_UP_STAIRS) },
    BarAction("Messages", run = showMessages),
    BarAction("Bash", pin = N.ACT_BASH, run = act(N.ACT_BASH)),
    BarAction("Tunnel", pin = N.ACT_TUNNEL, run = act(N.ACT_TUNNEL)),
    BarAction("Disarm", pin = N.ACT_DISARM, run = act(N.ACT_DISARM)),
    BarAction("Exchange", pin = N.ACT_EXCHANGE, run = act(N.ACT_EXCHANGE)),
    BarAction("All commands", run = { router.special(Keys.ENTER) }),
    BarAction("Help", run = showHelp),
    BarAction("Options", run = act(N.ACT_OPTIONS)),
    BarAction(if (tilesOn) "Tiles: on" else "Tiles: off", run = toggleTiles),
    BarAction("Keyboard", run = toggleKeyboard),
    BarAction("Retire character", run = { router.char('Q'.code) }),
    BarAction("Esc", run = { router.special(Keys.ESCAPE) }),
)

@Composable
fun ActionBar(actions: List<BarAction>, onMore: () -> Unit) {
    val scroll = rememberScrollState()
    /* A scroll of the bar is a sign it does not hold what the player wants: log where it came to rest. */
    LaunchedEffect(scroll) {
        snapshotFlow { scroll.isScrollInProgress }.drop(1).filter { !it }.collect { Trace.log("bar.scroll", "at" to scroll.value, "of" to scroll.maxValue) }
    }
    /* Context actions (stairs, Pick up, Fire, doors, traps) come first: when a new one appears, show it. */
    val context = actions.filter { it.emphasised }.map { it.label }
    val seen = remember { mutableListOf<String>() }
    LaunchedEffect(context) {
        if (context.any { it !in seen } && scroll.value > 0) scroll.animateScrollTo(0)
        seen.clear(); seen.addAll(context)
    }
    Row(
        Modifier.fillMaxWidth().background(Ui.Chrome).horizontalScroll(scroll)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        actions.forEach { BarButton(it) }
        FilledTonalButton(onClick = onMore, contentPadding = PaddingValues(horizontal = 14.dp)) { Text("☰", fontSize = 18.sp) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BarButton(a: BarAction) {
    val padding = PaddingValues(horizontal = 14.dp)
    val run = { Trace.log("bar.tap", "act" to a.label); a.run() }
    when {
        /* Material buttons take no long press: the same look, drawn by hand. */
        a.longPress != null -> Surface(shape = RoundedCornerShape(50),
                color = if (a.emphasised) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
                contentColor = if (a.emphasised) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.heightIn(min = 40.dp)
                    .holdMark(if (a.emphasised) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer)
                    .combinedClickable(onLongClick = { Trace.log("bar.longpress", "act" to a.label); a.longPress?.invoke() }, onClick = run)) {
            Box(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
                Text(a.label, style = MaterialTheme.typography.labelLarge, fontWeight = if (a.emphasised) FontWeight.SemiBold else null)
            }
        }
        a.emphasised -> Button(onClick = run, contentPadding = padding) { Text(a.label, fontWeight = FontWeight.SemiBold) }
        a.active -> Button(onClick = run, contentPadding = padding, border = BorderStroke(1.5.dp, Ui.Gold),
                           colors = ButtonDefaults.buttonColors(containerColor = Ui.Card, contentColor = Ui.Gold)) { Text(a.label) }
        else -> FilledTonalButton(onClick = run, contentPadding = padding) { Text(a.label) }
    }
}

/** Grid of the less frequent actions; tapping one runs it and closes the sheet, long-pressing a pinnable one pins it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MoreSheet(actions: List<BarAction>, pinned: List<Int> = emptyList(), onPin: (Int) -> Unit = {}, onDismiss: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color(0x99000000))
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            shape = Ui.SheetTop, border = Ui.edge,
            color = Ui.Sheet,
            modifier = Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, indication = null) {},
        ) {
            /* Scrolls: in landscape the rows do not all fit above the bottom edge. */
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                actions.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { a ->
                            val mark = if (a.pin != null && a.pin in pinned) "● " else ""
                            Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.weight(1f).heightIn(min = 40.dp)
                                        .then(if (a.pin != null) Modifier.holdMark(MaterialTheme.colorScheme.onSecondaryContainer) else Modifier)
                                        .combinedClickable(
                                        onLongClick = a.pin?.let { id -> { Trace.log("menu.pin", "act" to a.label); onPin(id) } }, onClick = { Trace.log("menu.pick", "act" to a.label); onDismiss(); a.run() })) {
                                Box(Modifier.padding(horizontal = 6.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
                                    Text(mark + a.label, maxLines = 1, style = MaterialTheme.typography.labelLarge)
                                }
                            }
                        }
                        repeat(2 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                if (actions.any { it.pin != null }) Text("Long-press a command to pin it to the bar (●).", color = Ui.Sub, fontSize = 12.sp)
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
            Column(Modifier.verticalScroll(rememberScrollState())) {
                choices.forEach { (label, turns) ->
                    Text(label, fontSize = 16.sp, modifier = Modifier.fillMaxWidth().clickable { onDismiss(); onRest(turns) }
                        .padding(vertical = 12.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
