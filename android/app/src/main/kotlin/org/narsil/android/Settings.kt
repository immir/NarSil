package org.narsil.android

import android.content.Context
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/** Interface preferences, observable by Compose and persisted on every change. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("ui", Context.MODE_PRIVATE)

    private fun float(key: String, default: Float) = mutableFloatStateOf(prefs.getFloat(key, default))
    private fun save(key: String, v: Float) = prefs.edit().putFloat(key, v).apply()

    private val buttons = float("buttonScale", 1f)
    private val messageText = float("messageScale", 1f)
    private val itemText = float("itemScale", 1f)
    private val statusText = float("statusScale", 1f)
    private val padSize = float("dpadSize", 144f)
    private val padX = float("dpadX", 1f)
    private val padY = float("dpadY", 1f)
    private val pad = mutableIntStateOf(prefs.getInt("dpad", DPAD_DOCKED))
    /* "swipeRuns" was the earlier on/off setting: on was Run, off was Step. */
    private val swipe = mutableIntStateOf(prefs.getInt("swipeMode", if (prefs.getBoolean("swipeRuns", false)) SWIPE_RUN else SWIPE_STEP))
    private val scum = mutableStateOf(prefs.getBoolean("scumming", false))
    private val edge = mutableStateOf(prefs.getBoolean("edgeSwipePanel", false))
    private val tile = mutableStateOf(prefs.getBoolean("tiles", false))
    private val topTabs = mutableStateOf(prefs.getBoolean("tabsOnTop", false))
    private val details = mutableStateOf(prefs.getBoolean("itemDetails", false))
    private val buzz = mutableStateOf(prefs.getBoolean("lowHealthBuzz", true))
    private val combat = mutableStateOf(prefs.getBoolean("combatBuzz", true))
    private val tracing = mutableStateOf(prefs.getBoolean("traceUsage", false))
    private val msgLines = mutableIntStateOf(prefs.getInt("messageLines", 3))
    private val panelTabStyle = mutableIntStateOf(prefs.getInt("panelTabs", TABS_ICONS_AND_NAMES))
    private val themeChoice = mutableIntStateOf(prefs.getInt("theme", 0))
    private val tipsSeen = mutableStateOf(prefs.getBoolean("seenTips", false))
    private val quickCommands = mutableStateOf(prefs.getString("quickActs", "").orEmpty().split(',').mapNotNull { it.toIntOrNull() })
    private val quick = mutableStateOf(prefs.getString("quickKinds", "").orEmpty().split(',').mapNotNull { it.toIntOrNull() })

    var buttonScale: Float get() = buttons.floatValue; set(v) { buttons.floatValue = v; save("buttonScale", v) }
    var messageScale: Float get() = messageText.floatValue; set(v) { messageText.floatValue = v; save("messageScale", v) }
    var itemScale: Float get() = itemText.floatValue; set(v) { itemText.floatValue = v; save("itemScale", v) }
    var statusScale: Float get() = statusText.floatValue; set(v) { statusText.floatValue = v; save("statusScale", v) }
    var dpadSizeDp: Float get() = padSize.floatValue; set(v) { padSize.floatValue = v; save("dpadSize", v) }
    /** Floating d-pad position as fractions (0..1) of the free space in the map area. */
    var dpadX: Float get() = padX.floatValue; set(v) { padX.floatValue = v; save("dpadX", v) }
    var dpadY: Float get() = padY.floatValue; set(v) { padY.floatValue = v; save("dpadY", v) }
    var dpad: Int get() = pad.intValue; set(v) { pad.intValue = v; prefs.edit().putInt("dpad", v).apply() }
    /** What a one-finger swipe on the map does: [SWIPE_PAN], [SWIPE_STEP] or [SWIPE_RUN]. */
    var swipeMode: Int get() = swipe.intValue; set(v) { swipe.intValue = v; prefs.edit().putInt("swipeMode", v).apply() }
    var scumming: Boolean get() = scum.value; set(v) { scum.value = v; prefs.edit().putBoolean("scumming", v).apply() }
    var edgeSwipePanel: Boolean get() = edge.value; set(v) { edge.value = v; prefs.edit().putBoolean("edgeSwipePanel", v).apply() }
    /** A short buzz when health falls below the game's hitpoint warning. */
    /** A tick when the player's blow lands, a stronger one for a kill. */
    var combatBuzz: Boolean get() = combat.value; set(v) { combat.value = v; prefs.edit().putBoolean("combatBuzz", v).apply() }
    /** Records a usage trace on the device (Trace.kt); off until a tester turns it on. */
    var traceUsage: Boolean get() = tracing.value; set(v) { tracing.value = v; prefs.edit().putBoolean("traceUsage", v).apply() }
    var lowHealthBuzz: Boolean get() = buzz.value; set(v) { buzz.value = v; prefs.edit().putBoolean("lowHealthBuzz", v).apply() }
    /** How the panel's tabs are drawn: [TABS_ICONS_AND_NAMES], [TABS_ICONS] or [TABS_NAMES] (a scrolling strip). */
    var panelTabs: Int get() = panelTabStyle.intValue; set(v) { panelTabStyle.intValue = v; prefs.edit().putInt("panelTabs", v).apply() }
    /** Object kinds pinned to the action bar as quick slots, at most [NarSilNative.QUICK_MAX] (the game is told). */
    var quickKinds: List<Int> get() = quick.value
        set(v) { quick.value = v.take(NarSilNative.QUICK_MAX); prefs.edit().putString("quickKinds", quick.value.joinToString(",")).apply()
                 NarSilNative.nativeSetQuickKinds(quick.value.toIntArray()) }
    /** Pins [kind] to the action bar, or unpins it; pinning a fifth drops the oldest. */
    var quickActs: List<Int> get() = quickCommands.value
        set(v) { quickCommands.value = v.take(NarSilNative.QUICK_MAX); prefs.edit().putString("quickActs", quickCommands.value.joinToString(",")).apply() }
    fun toggleQuickAct(id: Int) { quickActs = if (id in quickActs) quickActs - id else (quickActs + id).takeLast(NarSilNative.QUICK_MAX) }

    fun toggleQuick(kind: Int) { quickKinds = if (kind in quickKinds) quickKinds - kind else (quickKinds + kind).takeLast(NarSilNative.QUICK_MAX) }
    /** The first-run tips card has been dismissed. */
    var seenTips: Boolean get() = tipsSeen.value; set(v) { tipsSeen.value = v; prefs.edit().putBoolean("seenTips", v).apply() }
    /** Index into [Theme.ALL]. */
    var theme: Int get() = themeChoice.intValue; set(v) { themeChoice.intValue = v; prefs.edit().putInt("theme", v).apply() }
    /** How many recent messages the strip shows (2..5). */
    var messageLines: Int get() = msgLines.intValue; set(v) { msgLines.intValue = v.coerceIn(2, 5); prefs.edit().putInt("messageLines", msgLines.intValue).apply() }
    /** MicroChasm tiles on the map (the game is told by NarSilNative.nativeSetTiles). */
    /** The panel's tabs always across the top of the screen (experimental). */
    var tabsOnTop: Boolean get() = topTabs.value; set(v) { topTabs.value = v; prefs.edit().putBoolean("tabsOnTop", v).apply() }
    var tiles: Boolean get() = tile.value; set(v) { tile.value = v; prefs.edit().putBoolean("tiles", v).apply(); NarSilNative.nativeSetTiles(v) }

    /** Extra item description lines: what an ego item gives, and what its abilities do (the game is told by nativeSetItemDetails). */
    var itemDetails: Boolean get() = details.value; set(v) { details.value = v; prefs.edit().putBoolean("itemDetails", v).apply(); NarSilNative.nativeSetItemDetails(v) }

    companion object {
        const val TABS_ICONS_AND_NAMES = 0
        const val TABS_ICONS = 1
        const val TABS_NAMES = 2
        const val DPAD_OFF = 0
        const val DPAD_DOCKED = 1
        const val DPAD_FLOATING = 2
        const val SWIPE_PAN = 0
        const val SWIPE_STEP = 1
        const val SWIPE_RUN = 2
        val SCALES = 0.8f..1.8f
    }
}

/** Content whose sp text is multiplied by [scale] on top of the system font scale. */
@Composable
fun TextScale(scale: Float, content: @Composable () -> Unit) {
    val d = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(d.density, d.fontScale * scale), content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OptionsSheet(settings: Settings, gamePalette: IntArray, autoMore: Boolean, autoMoreEnabled: Boolean, onAutoMore: (Boolean) -> Unit, onGameOptions: () -> Unit, onExportSave: () -> Unit, onImportSave: () -> Unit,
                 onSendLogs: () -> Unit, onDismiss: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color(0x99000000))
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            color = Color(0xFF1C1C1E),
            modifier = Modifier.fillMaxWidth().heightIn(max = 640.dp)
                .clickable(remember { MutableInteractionSource() }, indication = null) {},
        ) {
            Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Options", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Done") }
                }
                Scale(UiText.BUTTON_SCALE, settings.buttonScale) { settings.buttonScale = it }
                Scale(UiText.MESSAGE_SCALE, settings.messageScale) { settings.messageScale = it }
                Label(UiText.COLOURS)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Theme.ALL.forEachIndexed { i, t -> FilterChip(selected = settings.theme == i, onClick = { settings.theme = i }, label = { Text(t.name) }) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Theme.byIndex(settings.theme).swatch(gamePalette).forEach { c -> Box(Modifier.size(28.dp).background(Color(c), RoundedCornerShape(6.dp))) }
                }
                Hint(UiText.COLOURS_HINT)
                Scale(UiText.ITEM_SCALE, settings.itemScale) { settings.itemScale = it }
                Scale(UiText.STATUS_SCALE, settings.statusScale) { settings.statusScale = it }
                Label(UiText.MESSAGE_LINES)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (2..5).forEach { n -> FilterChip(selected = settings.messageLines == n, onClick = { settings.messageLines = n }, label = { Text("$n") }) }
                }
                Label(UiText.PANEL_TABS)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(Settings.TABS_ICONS_AND_NAMES to "Icons and names", Settings.TABS_ICONS to "Icons", Settings.TABS_NAMES to "Names")
                        .forEach { (style, label) ->
                            FilterChip(selected = settings.panelTabs == style, onClick = { settings.panelTabs = style }, label = { Text(label) })
                        }
                }
                Hint(UiText.PANEL_TABS_HINT)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Label(UiText.TABS_ON_TOP)
                        Hint(UiText.TABS_ON_TOP_HINT)
                    }
                    Switch(settings.tabsOnTop, { settings.tabsOnTop = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Label(UiText.TILES)
                        Hint(UiText.TILES_HINT)
                    }
                    Switch(settings.tiles, { settings.tiles = it })
                }
                Label(UiText.DPAD)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(Settings.DPAD_OFF to "Off", Settings.DPAD_DOCKED to "Beside status", Settings.DPAD_FLOATING to "Floating")
                        .forEach { (mode, label) ->
                            FilterChip(selected = settings.dpad == mode, onClick = { settings.dpad = mode }, label = { Text(label) })
                        }
                }
                Hint(UiText.DPAD_HINT)
                if (settings.dpad != Settings.DPAD_OFF) {
                    Label("${UiText.DPAD_SIZE}  ${settings.dpadSizeDp.roundToInt()} dp")
                    Slider(settings.dpadSizeDp, { settings.dpadSizeDp = it }, valueRange = 100f..220f)
                }
                Label(UiText.SWIPE)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(Settings.SWIPE_PAN to "Pan", Settings.SWIPE_STEP to "Step", Settings.SWIPE_RUN to "Run")
                        .forEach { (mode, label) ->
                            FilterChip(selected = settings.swipeMode == mode, onClick = { settings.swipeMode = mode }, label = { Text(label) })
                        }
                }
                Hint(UiText.SWIPE_HINT)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Label(UiText.AUTO_MORE)
                        Hint(UiText.AUTO_MORE_HINT + if (autoMoreEnabled) "" else "  " + UiText.AUTO_MORE_LOCKED_HINT)
                    }
                    Switch(autoMore, onAutoMore, enabled = autoMoreEnabled)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Label(UiText.ITEM_DETAILS)
                        Hint(UiText.ITEM_DETAILS_HINT)
                    }
                    Switch(settings.itemDetails, { settings.itemDetails = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Label(UiText.TRACE_USAGE)
                        Hint(UiText.TRACE_USAGE_HINT)
                    }
                    Switch(settings.traceUsage, { settings.traceUsage = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Label(UiText.COMBAT_BUZZ)
                        Hint(UiText.COMBAT_BUZZ_HINT)
                    }
                    Switch(settings.combatBuzz, { settings.combatBuzz = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Label(UiText.LOW_HEALTH_BUZZ)
                        Hint(UiText.LOW_HEALTH_BUZZ_HINT)
                    }
                    Switch(settings.lowHealthBuzz, { settings.lowHealthBuzz = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Label(UiText.EDGE_SWIPE)
                        Hint(UiText.EDGE_SWIPE_HINT)
                    }
                    Switch(settings.edgeSwipePanel, { settings.edgeSwipePanel = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Label(UiText.SCUMMING)
                        Hint(UiText.SCUMMING_HINT)
                    }
                    Switch(settings.scumming, { settings.scumming = it })
                }
                Label(UiText.SAVES)
                Hint(UiText.SAVES_HINT)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { onDismiss(); onExportSave() }) { Text("Export save") }
                    FilledTonalButton(onClick = { onDismiss(); onImportSave() }) { Text("Import save…") }
                }
                Label(UiText.PROBLEMS)
                Hint(UiText.PROBLEMS_HINT)
                FilledTonalButton(onClick = { onDismiss(); onSendLogs() }) { Text("Send logs") }
                TextButton(onClick = { onDismiss(); onGameOptions() }) { Text("Game options…") }
            }
        }
    }
}

@Composable
private fun Scale(label: String, value: Float, set: (Float) -> Unit) {
    Label("$label  ${(value * 100).roundToInt()}%")
    Slider(value, { set((it * 20).roundToInt() / 20f) }, valueRange = Settings.SCALES)
}

@Composable private fun Label(text: String) = Text(text, color = Color.White, fontSize = 15.sp)
@Composable private fun Hint(text: String) = Text(text, color = Color(0xFF9A9AA0), fontSize = 13.sp)
