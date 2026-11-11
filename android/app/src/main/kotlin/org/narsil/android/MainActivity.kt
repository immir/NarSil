package org.narsil.android

import android.os.Bundle
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this) { router.special(Keys.ESCAPE) }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) { App() }
        }
    }

    /** Runs at the game's next safe point (top-level command prompt). */
    override fun onStop() {
        super.onStop()
        if (NarSilNative.nativeState() == NarSilNative.STATE_RUNNING) NarSilNative.nativeRequestSave()
    }

    @Composable
    private fun App() {
        var phase by remember { mutableStateOf<Phase>(Phase.Loading) }

        LaunchedEffect(Unit) {
            phase = runCatching { withContext(Dispatchers.IO) { GameLauncher.ensureStarted(applicationContext) } }
                .fold({ Phase.Running }, { Phase.Failed(it.message ?: it.toString()) })
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
                Phase.Running -> Column(Modifier.fillMaxSize()) {
                    AndroidView(
                        factory = { ctx -> TerminalView(ctx, router).also { terminal = it } },
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                    KeyBar()
                }
            }
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
