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
    private val swipe = mutableStateOf(prefs.getBoolean("swipeRuns", false))
    private val scum = mutableStateOf(prefs.getBoolean("scumming", false))
    private val edge = mutableStateOf(prefs.getBoolean("edgeSwipePanel", false))
    private val tile = mutableStateOf(prefs.getBoolean("tiles", false))

    var buttonScale: Float get() = buttons.floatValue; set(v) { buttons.floatValue = v; save("buttonScale", v) }
    var messageScale: Float get() = messageText.floatValue; set(v) { messageText.floatValue = v; save("messageScale", v) }
    var itemScale: Float get() = itemText.floatValue; set(v) { itemText.floatValue = v; save("itemScale", v) }
    var statusScale: Float get() = statusText.floatValue; set(v) { statusText.floatValue = v; save("statusScale", v) }
    var dpadSizeDp: Float get() = padSize.floatValue; set(v) { padSize.floatValue = v; save("dpadSize", v) }
    /** Floating d-pad position as fractions (0..1) of the free space in the map area. */
    var dpadX: Float get() = padX.floatValue; set(v) { padX.floatValue = v; save("dpadX", v) }
    var dpadY: Float get() = padY.floatValue; set(v) { padY.floatValue = v; save("dpadY", v) }
    var dpad: Int get() = pad.intValue; set(v) { pad.intValue = v; prefs.edit().putInt("dpad", v).apply() }
    var swipeRuns: Boolean get() = swipe.value; set(v) { swipe.value = v; prefs.edit().putBoolean("swipeRuns", v).apply() }
    var scumming: Boolean get() = scum.value; set(v) { scum.value = v; prefs.edit().putBoolean("scumming", v).apply() }
    var edgeSwipePanel: Boolean get() = edge.value; set(v) { edge.value = v; prefs.edit().putBoolean("edgeSwipePanel", v).apply() }
    /** MicroChasm tiles on the map (the game is told by NarSilNative.nativeSetTiles). */
    var tiles: Boolean get() = tile.value; set(v) { tile.value = v; prefs.edit().putBoolean("tiles", v).apply(); NarSilNative.nativeSetTiles(v) }

    companion object {
        const val DPAD_OFF = 0
        const val DPAD_DOCKED = 1
        const val DPAD_FLOATING = 2
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
fun OptionsSheet(settings: Settings, onGameOptions: () -> Unit, onExportSave: () -> Unit, onImportSave: () -> Unit,
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
                Scale("Buttons and menus", settings.buttonScale) { settings.buttonScale = it }
                Scale("Messages", settings.messageScale) { settings.messageScale = it }
                Scale("Inventory and items", settings.itemScale) { settings.itemScale = it }
                Scale("Status", settings.statusScale) { settings.statusScale = it }

                Label("D-pad")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(Settings.DPAD_OFF to "Off", Settings.DPAD_DOCKED to "Beside status", Settings.DPAD_FLOATING to "Floating")
                        .forEach { (mode, label) ->
                            FilterChip(selected = settings.dpad == mode, onClick = { settings.dpad = mode }, label = { Text(label) })
                        }
                }
                Hint("Tap a key to step; hold it, or drag from the centre key towards it, to run.  " +
                     "Centre: tap to wait, hold to rest; on a floating pad hold the centre then drag to move it.  " +
                     "Beside status is portrait only (it floats in landscape).")
                if (settings.dpad != Settings.DPAD_OFF) {
                    Label("D-pad size  ${settings.dpadSizeDp.roundToInt()} dp")
                    Slider(settings.dpadSizeDp, { settings.dpadSizeDp = it }, valueRange = 100f..220f)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Label("Tiles on the map")
                        Hint("MicroChasm's pictures instead of letters. Lists and menus keep the letters.")
                    }
                    Switch(settings.tiles, { settings.tiles = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Label("Swipe on the map runs")
                        Hint("Otherwise a swipe takes one step.")
                    }
                    Switch(settings.swipeRuns, { settings.swipeRuns = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Label("Swipe from the right edge opens the panel")
                        Hint("A short stretch in the middle of the right edge, marked by a faint handle, opens the character panel. " +
                             "That stretch no longer works as the system Back gesture.")
                    }
                    Switch(settings.edgeSwipePanel, { settings.edgeSwipePanel = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Label("Save scumming (for testing)")
                        Hint("Adds Scum (keep a copy of your save) and Restore scum save to the ☰ menu, and Restore to the death screen.")
                    }
                    Switch(settings.scumming, { settings.scumming = it })
                }
                Label("Saves")
                Hint("Export sends a copy of your save to another app (Drive, mail…). Import loads one back, replacing the current game.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { onDismiss(); onExportSave() }) { Text("Export save") }
                    FilledTonalButton(onClick = { onDismiss(); onImportSave() }) { Text("Import save…") }
                }
                Label("Problems")
                Hint("Send logs shares a zip of the app's log, your save and character dump, for a bug report.")
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
