package org.narsil.android

import android.os.Bundle
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import android.view.View
import android.content.res.Configuration
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import android.widget.Toast
import androidx.compose.material3.Typography
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.em
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private sealed interface Phase {
    data object Loading : Phase
    data object Running : Phase
    data class Failed(val reason: String) : Phase
    data class Exited(val message: String) : Phase
}

class MainActivity : ComponentActivity() {
    private val router = KeyRouter()
    private var terminal: TerminalView? = null
    private var game: GameState? = null
    private var showMore by mutableStateOf(false)
    private var showMessages by mutableStateOf(false)
    private var showOptions by mutableStateOf(false)
    private var showKeys by mutableStateOf(false)
    private var showScum by mutableStateOf(false)
    private var showHelp by mutableStateOf(false)
    private val scope = MainScope()
    private lateinit var settings: Settings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(applicationContext)
        onBackPressedDispatcher.addCallback(this) { router.special(Keys.ESCAPE) }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(), typography = compactTypography()) { App() }
        }
    }

    /** Runs at the game's next safe point (top-level command prompt). */
    override fun onDestroy() {
        scope.cancel()
        game?.stop()
        super.onDestroy()
    }

    override fun onStop() {
        super.onStop()
        if (NarSilNative.nativeState() == NarSilNative.STATE_RUNNING) NarSilNative.nativeRequestSave()
    }

    @Composable
    private fun App() {
        var phase by remember { mutableStateOf<Phase>(Phase.Loading) }

        LaunchedEffect(Unit) {
            phase = runCatching { withContext(Dispatchers.IO) { GameLauncher.ensureStarted(applicationContext) } }
                .fold({ game = GameState().also { it.start() }; Phase.Running }, { Phase.Failed(it.message ?: it.toString()) })
        }
        LaunchedEffect(phase) {
            while (phase == Phase.Running) {
                if (NarSilNative.nativeState() == NarSilNative.STATE_EXITED)
                    phase = Phase.Exited(NarSilNative.nativeExitMessage())
                delay(500)
            }
        }

        Box(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding().imePadding()) {
            when (val p = phase) {
                Phase.Loading -> Centered("Installing game data…")
                is Phase.Failed -> Centered("Failed to start: ${p.reason}")
                is Phase.Exited -> Centered(p.message.ifEmpty { "The game has ended." }, "Close") { exitApp() }
                Phase.Running -> GameScreen(checkNotNull(game))
            }
        }
    }

    /**
     * Map mode: message strip, native map, status strip.  Otherwise the terminal
     * fallback shows whatever textui screen is up.  The terminal stays underneath
     * the map so it keeps keyboard focus in both modes.
     */
    @Composable
    private fun GameScreen(state: GameState) {
        Box(Modifier.fillMaxSize()) {
            GameLayers(state)
            TextScale(settings.buttonScale) { Overlays(state) }
        }
    }

    @Composable
    private fun Overlays(state: GameState) {
        Box(Modifier.fillMaxSize()) {
            val answer: (Int) -> Unit = NarSilNative::nativeAnswer
            state.prompt?.let { prompt ->
                DisposableEffect(prompt) {
                    router.intercept = { code -> answerByKey(prompt, code)?.let(answer); true }
                    onDispose { router.intercept = null }
                }
                when (prompt) {
                    is GameState.Prompt.Items -> TextScale(settings.itemScale / settings.buttonScale) { ItemSheet(prompt, state::colour, answer) }
                    is GameState.Prompt.Actions -> TextScale(settings.itemScale / settings.buttonScale) { ActionSheet(prompt, state::colour, answer) }
                    is GameState.Prompt.Check -> CheckDialog(prompt, answer)
                    is GameState.Prompt.Choice -> ChoiceSheet(prompt, answer)
                    is GameState.Prompt.Skills -> SkillsSheet(prompt, answer)
                    is GameState.Prompt.Abilities -> AbilitiesSheet(prompt, answer)
                    is GameState.Prompt.Smith -> SmithSheet(prompt, answer)
                    is GameState.Prompt.Text -> TextPrompt(prompt) { answer(-1) }
                    is GameState.Prompt.Options -> OptionsScreen(prompt, answer)
                    is GameState.Prompt.Pack -> TextScale(settings.itemScale / settings.buttonScale) { PackSheet(prompt, state::colour, answer) }
                    is GameState.Prompt.Screen -> ScreenSheet(prompt, answer, onScum = { showScum = true }.takeIf { settings.scumming })
                    is GameState.Prompt.Aim, is GameState.Prompt.Direction -> Unit  // drawn by GameLayers over the map
                }
            }
            if (state.prompt == null) state.info?.let { info ->
                DisposableEffect(info) {
                    router.intercept = { code -> if (code == Keys.ESCAPE) state.info = null; code == Keys.ESCAPE }
                    onDispose { router.intercept = null }
                }
            }
            if (state.prompt == null) when (val info = state.info) {
                is GameState.Info.Inspect -> InspectSheet(info, state) { state.info = null }
                is GameState.Info.LookList -> LookSheet(info, state) { state.info = null }
                is GameState.Info.Character -> CharacterSheet(info, state) { state.info = null }
                null -> Unit
            }
            if (state.prompt == null && showMessages) {
                DisposableEffect(Unit) {
                    router.intercept = { code -> if (code == Keys.ESCAPE) showMessages = false; code == Keys.ESCAPE }
                    onDispose { router.intercept = null }
                }
                TextScale(settings.messageScale / settings.buttonScale) { MessagesSheet(state) { showMessages = false } }
            }
            if (showMore) MoreSheet(moreActions(router, { showMessages = true }, { showOptions = true }, { showHelp = true }) { terminal?.toggleKeyboard() } +
                                    listOfNotNull(scumAction(), BarAction("Restore scum save", run = { showScum = true }).takeIf { settings.scumming })) { showMore = false }
            if (showKeys) MoreSheet(extraKeys()) { showKeys = false }
            if (showOptions) {
                DisposableEffect(Unit) {
                    router.intercept = { code -> if (code == Keys.ESCAPE) showOptions = false; code == Keys.ESCAPE }
                    onDispose { router.intercept = null }
                }
                OptionsSheet(settings, onGameOptions = { NarSilNative.nativeRequestAction(NarSilNative.ACT_OPTIONS) }) { showOptions = false }
            }
            if (showScum) ScumSheet(this@MainActivity) { showScum = false }
            if (showHelp) {
                DisposableEffect(Unit) {
                    router.intercept = { code -> if (code == Keys.ESCAPE) showHelp = false; code == Keys.ESCAPE }
                    onDispose { router.intercept = null }
                }
                HelpScreen(this@MainActivity) { showHelp = false }
            }
        }
    }

    /**
     * Keyboard answers while a native prompt is open (all other keys are swallowed):
     * Esc cancels; item letters pick by label; y/n answer checks; keypad digits,
     * arrows, ' (nearest) and t/5 (current target) answer aims.
     */
    private fun answerByKey(prompt: GameState.Prompt, code: Int): Int? = when (prompt) {
        is GameState.Prompt.Items -> if (code == Keys.ESCAPE) -1
            else prompt.items.firstOrNull { it.label != '-' && it.label.code == code }?.index
        is GameState.Prompt.Actions -> if (code == Keys.ESCAPE) -1 else null
        is GameState.Prompt.Check -> when (code) {
            'y'.code, 'Y'.code -> 1
            'n'.code, 'N'.code, Keys.ESCAPE -> 0
            else -> null
        }
        is GameState.Prompt.Abilities, is GameState.Prompt.Smith -> if (code == Keys.ESCAPE) -1 else null
        is GameState.Prompt.Text -> if (code == Keys.ESCAPE) -1 else null
        is GameState.Prompt.Screen -> if (code == Keys.ESCAPE && prompt.stage != "death") -1 else null
        is GameState.Prompt.Options -> if (code == Keys.ESCAPE) -1 else null
        is GameState.Prompt.Pack -> if (code == Keys.ESCAPE) -1 else null
        is GameState.Prompt.Skills -> when (code) {
            Keys.ESCAPE -> -1
            Keys.ENTER -> NarSilNative.SKILL_ACCEPT
            else -> null
        }
        is GameState.Prompt.Choice -> if (code == Keys.ESCAPE) -1
            else (code - 'a'.code).takeIf { it in prompt.options.indices }
        is GameState.Prompt.Direction -> when (code) {
            Keys.ESCAPE -> -1
            in '1'.code..'9'.code -> (code - '0'.code).takeIf { it != 5 || prompt.allowHere }
            Keys.ARROW_UP -> 8
            Keys.ARROW_DOWN -> 2
            Keys.ARROW_LEFT -> 4
            Keys.ARROW_RIGHT -> 6
            else -> null
        }
        is GameState.Prompt.Aim -> when (code) {
            Keys.ESCAPE -> -1
            in '1'.code..'9'.code -> (code - '0'.code).takeIf { it != 5 } ?: NarSilNative.AIM_CURRENT
            Keys.ARROW_UP -> 8
            Keys.ARROW_DOWN -> 2
            Keys.ARROW_LEFT -> 4
            Keys.ARROW_RIGHT -> 6
            '\''.code -> NarSilNative.AIM_NEAREST
            't'.code -> NarSilNative.AIM_CURRENT
            else -> null
        }
    }

    @Composable
    private fun GameLayers(state: GameState) {
        Column(Modifier.fillMaxSize()) {
            /* At -more- a tap continues; otherwise it opens the scrollable history. */
            if (state.mapMode) TextScale(settings.messageScale) {
                MessageStrip(state) { if (state.messageLine.contains("-more-")) router.special(Keys.ENTER) else showMessages = true }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                AndroidView(
                    factory = { ctx -> TerminalView(ctx, state, router).also { terminal = it } },
                    modifier = Modifier.fillMaxSize(),
                )
                AndroidView(
                    factory = { ctx -> MapView(ctx, state, router) { settings.swipeRuns } },
                    update = { it.visibility = if (state.mapMode) View.VISIBLE else View.GONE },
                    modifier = Modifier.fillMaxSize(),
                )
                (state.prompt as? GameState.Prompt.Aim)?.let { aim ->
                    Box(Modifier.align(Alignment.BottomCenter)) { AimBar(aim, NarSilNative::nativeAnswer) }
                }
                (state.prompt as? GameState.Prompt.Direction)?.let { d ->
                    Box(Modifier.align(Alignment.BottomCenter)) { DirectionBar(d, NarSilNative::nativeAnswer) }
                }
                if (state.mapMode && dpadMode() == Settings.DPAD_FLOATING) FloatingDpad(state)
            }
            if (state.mapMode) TextScale(settings.statusScale) {
                if (dpadMode() == Settings.DPAD_DOCKED) Row(Modifier.background(Color(0xFF141414)), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { StatusStrip(state.status, state.statusLine, stacked = true) }
                    Box(Modifier.padding(4.dp)) { Dpad(settings.dpadSizeDp, { dpad(state, it) }) }
                } else StatusStrip(state.status, state.statusLine, stacked = false)
            }
            if (state.mapMode) TextScale(settings.buttonScale) {
                ActionBar(contextActions(state.status) + coreActions(state.status)) { showMore = true }
            }
            else TextScale(settings.buttonScale) { KeyBar(state.waiting) }
        }
    }

    /**
     * The last few messages (older ones dimmer, in their game colours), plus the
     * textui's top line when it holds something else: a prompt, or -more- (tap = Enter).
     */
    @Composable
    private fun MessageStrip(state: GameState, onTap: () -> Unit) {
        val recent = state.messages.takeLast(3)
        val line0 = state.messageLine
        val more = line0.contains("-more-")
        /* The textui joins several messages on its top line; only show it when it is something else (a prompt). */
        val banner = line0.takeIf { it.isNotEmpty() && !more && state.messages.none { m -> line0.contains(m.text) } }
        Column(Modifier.fillMaxWidth().background(Color(0xFF141414)).clickable(onClick = onTap)
            .padding(horizontal = 10.dp, vertical = 4.dp)) {
            recent.forEachIndexed { i, m ->
                val alpha = 0.45f + 0.55f * (i + 1) / recent.size
                Text(if (m.count > 1) "${m.text} (x${m.count})" else m.text,
                     color = Color(state.colour(m.attr)).copy(alpha = alpha), fontSize = 14.sp, lineHeight = 17.sp, maxLines = 2)
            }
            if (banner != null) Text(banner, color = Color(0xFFFFD54F), fontSize = 14.sp, lineHeight = 17.sp, maxLines = 2)
            if (more) Text("▸ tap to continue", color = Color(0xFFFFD54F), fontSize = 13.sp, lineHeight = 16.sp)
            if (recent.isEmpty() && banner == null && !more) Text(" ", fontSize = 14.sp)
        }
    }

    /** Docked in portrait (beside the status, which then stacks), otherwise floating; or off. */
    @Composable
    private fun dpadMode(): Int {
        val portrait = LocalConfiguration.current.orientation != Configuration.ORIENTATION_LANDSCAPE
        return if (settings.dpad == Settings.DPAD_DOCKED && !portrait) Settings.DPAD_FLOATING else settings.dpad
    }

    /**
     * A d-pad gesture: answers an open aim or direction prompt; at the map prompt
     * steps, runs, waits or rests; elsewhere (-more-) it sends the keypad digit.
     */
    private fun dpad(state: GameState, a: PadAction) {
        val dir = when (a) { is PadAction.Step -> a.dir; is PadAction.Run -> a.dir; else -> 5 }
        when (val p = state.prompt) {
            is GameState.Prompt.Aim -> NarSilNative.nativeAnswer(if (dir == 5) NarSilNative.AIM_CURRENT else dir)
            is GameState.Prompt.Direction -> if (dir != 5 || p.allowHere) NarSilNative.nativeAnswer(dir)
            null -> when {
                !state.atPrompt -> router.char('0'.code + dir)
                a == PadAction.Wait -> NarSilNative.nativeRequestAction(NarSilNative.ACT_HOLD)
                a == PadAction.Rest -> NarSilNative.nativeRequestAction(NarSilNative.ACT_REST)
                else -> NarSilNative.nativeRequestMove(dir, a is PadAction.Run)
            }
            else -> Unit
        }
    }

    /** Draggable by its centre; the position is kept as a fraction of the free space. */
    @Composable
    private fun FloatingDpad(state: GameState) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            val pad = with(density) { settings.dpadSizeDp.dp.toPx() }
            val freeW = (constraints.maxWidth - pad).coerceAtLeast(1f)
            val freeH = (constraints.maxHeight - pad).coerceAtLeast(1f)
            Box(Modifier.offset { IntOffset((settings.dpadX * freeW).roundToInt(), (settings.dpadY * freeH).roundToInt()) }) {
                Dpad(settings.dpadSizeDp, { dpad(state, it) }) { delta ->
                    settings.dpadX = (settings.dpadX + delta.x / freeW).coerceIn(0f, 1f)
                    settings.dpadY = (settings.dpadY + delta.y / freeH).coerceIn(0f, 1f)
                }
            }
        }
    }

    @Composable
    private fun StatusStrip(s: GameState.Status, line: String, stacked: Boolean) {
        Column(Modifier.fillMaxWidth().background(Color(0xFF141414)).padding(horizontal = 8.dp, vertical = 4.dp)) {
            if (stacked) {
                Meter("HP", s.hp, s.maxHp, Color(0xFFD04040), Modifier.fillMaxWidth())
                Meter("Voice", s.voice, s.maxVoice, Color(0xFF4080E0), Modifier.fillMaxWidth().padding(top = 4.dp))
                Text("${s.depth * 50} ft", color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            } else Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Meter("HP", s.hp, s.maxHp, Color(0xFFD04040), Modifier.weight(1f))
                Meter("Voice", s.voice, s.maxVoice, Color(0xFF4080E0), Modifier.weight(1f))
                Text("${s.depth * 50} ft", color = Color.White, fontSize = 13.sp)
            }
            if (line.isNotEmpty())
                Text(line.replace(Regex("\\s{2,}"), "  ·  "), color = Color(0xFFB0B0B0), fontSize = 12.sp, maxLines = if (stacked) 3 else 1)
            s.foe?.let { FoeLine(it) }
        }
    }

    /** Tracked monster: glyph, name, health, alertness/morale; tap to inspect. */
    @Composable
    private fun FoeLine(f: GameState.Foe) {
        val state = checkNotNull(game)
        val cell = state.mapCell(f.x, f.y)
        val stance = NarSilNative.STANCE_NAMES.getOrElse(f.stance) { "" }
        val healthColour = when {
            f.healthPct >= 60 -> Color(0xFF66BB6A)
            f.healthPct >= 25 -> Color(0xFFFFCA28)
            else -> Color(0xFFEF5350)
        }
        Row(Modifier.fillMaxWidth().clickable { NarSilNative.nativeRequestInspect(f.x, f.y) }.padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(cell?.let { String(Character.toChars(it.first)) } ?: "?", fontFamily = FontFamily.Monospace,
                 color = Color(cell?.let { state.colour(it.second) } ?: 0xFFFFFFFF.toInt()), fontSize = 16.sp)
            Text(f.name, color = Color.White, fontSize = 13.sp, maxLines = 1, modifier = Modifier.weight(1f))
            LinearProgressIndicator(progress = f.healthPct / 100f, color = healthColour, trackColor = healthColour.copy(alpha = 0.25f),
                                    modifier = Modifier.width(80.dp).height(6.dp))
            val morale = if (f.stance >= 4) " ${f.morale}" else ""
            Text("$stance$morale", color = Color(0xFF90CAF9), fontSize = 12.sp)
        }
    }

    @Composable
    private fun Meter(label: String, value: Int, max: Int, colour: Color, modifier: Modifier) {
        Column(modifier) {
            Text("$label $value/$max", color = Color.White, fontSize = 12.sp)
            LinearProgressIndicator(
                progress = if (max > 0) value.toFloat() / max else 0f,
                color = colour,
                trackColor = colour.copy(alpha = 0.25f),
                modifier = Modifier.fillMaxWidth().height(6.dp),
            )
        }
    }

    /**
     * Bottom bar for the remaining textui screens.  Taps on the screen pick menu lines
     * and swipes move, so the bar only offers what the screen is waiting for; the
     * rarer keys are behind ⋯.
     */
    @Composable
    private fun KeyBar(waiting: GameState.Waiting) {
        val keys: List<Pair<String, () -> Unit>> = when (waiting) {
            GameState.Waiting.YES_NO -> listOf("Yes" to { router.char('y'.code) }, "No" to { router.char('n'.code) })
            GameState.Waiting.MORE, GameState.Waiting.ANY_KEY -> listOf("Continue" to { router.special(Keys.ENTER) })
            GameState.Waiting.AT_SIGN -> listOf("Confirm (@)" to { router.char('@'.code) }, "Cancel" to { router.special(Keys.ESCAPE) })
            GameState.Waiting.MENU -> listOf("Back" to { router.special(Keys.ESCAPE) }, "OK" to { router.special(Keys.ENTER) })
        }
        Row(
            Modifier.fillMaxWidth().background(Color(0xFF101012)).padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            keys.forEachIndexed { i, (label, action) ->
                val padding = PaddingValues(horizontal = 14.dp)
                if (i == 0 && waiting != GameState.Waiting.MENU || label == "OK")
                    Button(onClick = action, contentPadding = padding, modifier = Modifier.weight(1f)) { Text(label) }
                else FilledTonalButton(onClick = action, contentPadding = padding, modifier = Modifier.weight(1f)) { Text(label) }
            }
            FilledTonalButton(onClick = { terminal?.toggleKeyboard() }, contentPadding = PaddingValues(horizontal = 14.dp)) { Text("⌨") }
            FilledTonalButton(onClick = { showKeys = true }, contentPadding = PaddingValues(horizontal = 14.dp)) { Text("⋯") }
        }
    }

    /** The rarely needed keys; Ctrl latches for the next key. */
    private fun extraKeys(): List<BarAction> = listOf(
        BarAction("Esc", run = { router.special(Keys.ESCAPE) }),
        BarAction("Enter", run = { router.special(Keys.ENTER) }),
        BarAction(if (router.ctrlLatched) "Ctrl ●" else "Ctrl", run = { router.ctrlLatched = !router.ctrlLatched }),
        BarAction("Tab", run = { router.special(Keys.TAB) }),
        BarAction("⌫", run = { router.special(Keys.BACKSPACE) }),
        BarAction("Space", run = { router.char(' '.code) }),
        BarAction("Page up", run = { router.special(Keys.PAGE_UP) }),
        BarAction("Page down", run = { router.special(Keys.PAGE_DOWN) }),
    ) + (1..9).map { d -> BarAction("$d", run = { router.char('0'.code + d) }) }

    @Composable
    private fun Centered(text: String, action: String? = null, onAction: () -> Unit = {}) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text, color = Color.White)
            if (action != null) Button(onClick = onAction, modifier = Modifier.padding(top = 16.dp)) { Text(action) }
        }
    }

    /** Native game state is process-global and cannot be re-initialised; end the process. */
    /** Save scumming's quick copy (Options → Save scumming). */
    private fun scumAction(): BarAction? = BarAction("Scum") {
        scope.launch { Toast.makeText(this@MainActivity, Scum.take(applicationContext), Toast.LENGTH_SHORT).show() }
    }.takeIf { settings.scumming }

    private fun exitApp() {
        finishAndRemoveTask()
        Process.killProcess(Process.myPid())
    }
}

/**
 * Material's styles fix line height in sp (bodyLarge: 24sp), which every Text
 * inherits even at 12sp; make it proportional to the font size instead, and
 * drop Android's extra font padding above and below each line.
 */
private fun compactTypography(): Typography = Typography().run {
    fun TextStyle.fit() = copy(lineHeight = 1.3.em, platformStyle = PlatformTextStyle(includeFontPadding = false),
                               lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both))
    Typography(displayLarge.fit(), displayMedium.fit(), displaySmall.fit(), headlineLarge.fit(), headlineMedium.fit(),
               headlineSmall.fit(), titleLarge.fit(), titleMedium.fit(), titleSmall.fit(), bodyLarge.fit(), bodyMedium.fit(),
               bodySmall.fit(), labelLarge.fit(), labelMedium.fit(), labelSmall.fit())
}
