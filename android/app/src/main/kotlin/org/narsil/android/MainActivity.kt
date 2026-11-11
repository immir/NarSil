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
import kotlinx.coroutines.Dispatchers
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this) { router.special(Keys.ESCAPE) }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) { App() }
        }
    }

    /** Runs at the game's next safe point (top-level command prompt). */
    override fun onDestroy() {
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
            val answer: (Int) -> Unit = NarSilNative::nativeAnswer
            state.prompt?.let { prompt ->
                DisposableEffect(prompt) {
                    router.intercept = { code -> answerByKey(prompt, code)?.let(answer); true }
                    onDispose { router.intercept = null }
                }
                when (prompt) {
                    is GameState.Prompt.Items -> ItemSheet(prompt, state::colour, answer)
                    is GameState.Prompt.Actions -> ActionSheet(prompt, state::colour, answer)
                    is GameState.Prompt.Check -> CheckDialog(prompt, answer)
                    is GameState.Prompt.Choice -> ChoiceSheet(prompt, answer)
                    is GameState.Prompt.Skills -> SkillsSheet(prompt, answer)
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
                null -> Unit
            }
            if (state.prompt == null && showMessages) {
                DisposableEffect(Unit) {
                    router.intercept = { code -> if (code == Keys.ESCAPE) showMessages = false; code == Keys.ESCAPE }
                    onDispose { router.intercept = null }
                }
                MessagesSheet(state) { showMessages = false }
            }
            if (showMore) MoreSheet(moreActions(router, { showMessages = true }) { terminal?.toggleKeyboard() }) { showMore = false }
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
            if (state.mapMode) MessageStrip(state) {
                if (state.messageLine.contains("-more-")) router.special(Keys.ENTER) else showMessages = true
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                AndroidView(
                    factory = { ctx -> TerminalView(ctx, state, router).also { terminal = it } },
                    modifier = Modifier.fillMaxSize(),
                )
                AndroidView(
                    factory = { ctx -> MapView(ctx, state, router) },
                    update = { it.visibility = if (state.mapMode) View.VISIBLE else View.GONE },
                    modifier = Modifier.fillMaxSize(),
                )
                (state.prompt as? GameState.Prompt.Aim)?.let { aim ->
                    Box(Modifier.align(Alignment.BottomCenter)) { AimBar(aim, NarSilNative::nativeAnswer) }
                }
                (state.prompt as? GameState.Prompt.Direction)?.let { d ->
                    Box(Modifier.align(Alignment.BottomCenter)) { DirectionBar(d, NarSilNative::nativeAnswer) }
                }
            }
            if (state.mapMode) StatusStrip(state.status, state.statusLine)
            if (state.mapMode) ActionBar(contextActions(state.status) + coreActions(state.status)) { showMore = true }
            else KeyBar()
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
            .padding(horizontal = 10.dp, vertical = 6.dp)) {
            recent.forEachIndexed { i, m ->
                val alpha = 0.45f + 0.55f * (i + 1) / recent.size
                Text(if (m.count > 1) "${m.text} (x${m.count})" else m.text,
                     color = Color(state.colour(m.attr)).copy(alpha = alpha), fontSize = 14.sp, maxLines = 2)
            }
            if (banner != null) Text(banner, color = Color(0xFFFFD54F), fontSize = 14.sp, maxLines = 2)
            if (more) Text("▸ tap to continue", color = Color(0xFFFFD54F), fontSize = 13.sp)
            if (recent.isEmpty() && banner == null && !more) Text(" ", fontSize = 14.sp)
        }
    }

    @Composable
    private fun StatusStrip(s: GameState.Status, line: String) {
        Column(Modifier.fillMaxWidth().background(Color(0xFF141414)).padding(horizontal = 8.dp, vertical = 4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Meter("HP", s.hp, s.maxHp, Color(0xFFD04040), Modifier.weight(1f))
                Meter("Voice", s.voice, s.maxVoice, Color(0xFF4080E0), Modifier.weight(1f))
                Text("${s.depth * 50} ft", color = Color.White, fontSize = 13.sp)
            }
            if (line.isNotEmpty())
                Text(line.replace(Regex("\\s{2,}"), "  ·  "), color = Color(0xFFB0B0B0), fontSize = 12.sp, maxLines = 1)
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

    @Composable
    private fun KeyBar() {
        val keys: List<Pair<String, () -> Unit>> = listOf(
            "Esc" to { router.special(Keys.ESCAPE) },
            "⏎" to { router.special(Keys.ENTER) },
            "⌨" to { terminal?.toggleKeyboard() },
            "7" to { router.char('7'.code) }, "8" to { router.char('8'.code) }, "9" to { router.char('9'.code) },
            "4" to { router.char('4'.code) }, "5" to { router.char('5'.code) }, "6" to { router.char('6'.code) },
            "1" to { router.char('1'.code) }, "2" to { router.char('2'.code) }, "3" to { router.char('3'.code) },
            "Tab" to { router.special(Keys.TAB) },
            "⌫" to { router.special(Keys.BACKSPACE) },
        )
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            FilledTonalButton(
                onClick = { router.ctrlLatched = !router.ctrlLatched },
                colors = if (router.ctrlLatched) ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors(),
                contentPadding = PaddingValues(horizontal = 10.dp),
            ) { Text("Ctrl") }
            keys.forEach { (label, action) ->
                FilledTonalButton(onClick = action, contentPadding = PaddingValues(horizontal = 10.dp)) { Text(label) }
            }
        }
    }

    @Composable
    private fun Centered(text: String, action: String? = null, onAction: () -> Unit = {}) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text, color = Color.White)
            if (action != null) Button(onClick = onAction, modifier = Modifier.padding(top = 16.dp)) { Text(action) }
        }
    }

    /** Native game state is process-global and cannot be re-initialised; end the process. */
    private fun exitApp() {
        finishAndRemoveTask()
        Process.killProcess(Process.myPid())
    }
}
