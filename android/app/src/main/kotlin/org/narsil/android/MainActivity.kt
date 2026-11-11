package org.narsil.android

import android.os.Bundle
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.height
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
        Column(Modifier.fillMaxSize()) {
            if (state.mapMode) MessageStrip(state.messageLine) { router.special(Keys.ENTER) }
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
            }
            if (state.mapMode) StatusStrip(state.status, state.statusLine)
            KeyBar()
        }
    }

    @Composable
    private fun MessageStrip(text: String, onTap: () -> Unit) {
        Text(
            text.ifEmpty { " " },
            color = Color(0xFFE0E0E0),
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
            maxLines = 2,
            modifier = Modifier.fillMaxWidth().background(Color(0xFF141414)).clickable(onClick = onTap)
                .padding(horizontal = 8.dp, vertical = 6.dp),
        )
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
