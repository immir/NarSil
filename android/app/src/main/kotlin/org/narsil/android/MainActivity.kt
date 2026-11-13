package org.narsil.android

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import android.os.Bundle
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import android.view.View
import android.content.res.Configuration
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import android.widget.Toast
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
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
    private var mapView: MapView? = null
    private var game: GameState? = null
    private var showMore by mutableStateOf(false)
    private var showMessages by mutableStateOf(false)
    private var showOptions by mutableStateOf(false)
    private var showKeys by mutableStateOf(false)
    private var showScum by mutableStateOf(false)
    private var showHelp by mutableStateOf(false)
    private var showRest by mutableStateOf(false)
    private var shareDumpPending = false
    private var pendingImport by mutableStateOf<Scum.Copy?>(null)
    private val importSave = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { scope.launch { pendingImport = runCatching { Scum.import(applicationContext, it) }.getOrNull() } }
    }
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

    override fun onStart() {
        super.onStart()
        game?.start()
    }

    /** Saves, and stops polling the game while nothing is on screen. */
    override fun onStop() {
        super.onStop()
        game?.stop()
        if (NarSilNative.nativeState() == NarSilNative.STATE_RUNNING) NarSilNative.nativeRequestSave()
    }

    @Composable
    private fun App() {
        var phase by remember { mutableStateOf<Phase>(Phase.Loading) }

        LaunchedEffect(Unit) {
            phase = runCatching { withContext(Dispatchers.IO) { GameLauncher.ensureStarted(applicationContext) } }
                .fold({ game = GameState().also { it.start() }; NarSilNative.nativeSetTiles(settings.tiles); Phase.Running }, { Phase.Failed(it.message ?: it.toString()) })
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
        /* The textui draws its own splash while loading; show nothing until the game's first native screen or map. */
        var started by remember { mutableStateOf(GameLauncher.started) }
        if (!started && (state.prompt != null || state.mapMode)) started = true.also { GameLauncher.started = true }
        LaunchedEffect(Unit) { delay(5000); started = true }
        Box(Modifier.fillMaxSize()) {
            GameLayers(state)
            TextScale(settings.buttonScale) { Overlays(state) }
            if (!started) Box(Modifier.fillMaxSize().background(Color.Black))
        }
    }

    @Composable
    private fun Overlays(state: GameState) {
        Box(Modifier.fillMaxSize()) {
            val answer: (Int) -> Unit = NarSilNative::nativeAnswer
            PanelLayer(state, answer)
            state.prompt?.let { prompt ->
                DisposableEffect(prompt) {
                    router.intercept = { code -> answerByKey(prompt, code)?.let(answer); true }
                    onDispose { router.intercept = null }
                }
                /* In the panel, its tab screens are drawn by PanelLayer; anything else is asked over it. */
                if (state.panelTab < 0 || !prompt.isPanelContent()) PromptUi(state, prompt, answer)
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
                is GameState.Info.Character -> CharacterSheet(info, state, onDismiss = { state.info = null })
                null -> Unit
            }
            if (state.prompt == null && showMessages) {
                DisposableEffect(Unit) {
                    router.intercept = { code -> if (code == Keys.ESCAPE) showMessages = false; code == Keys.ESCAPE }
                    onDispose { router.intercept = null }
                }
                TextScale(settings.messageScale / settings.buttonScale) { MessagesSheet(state) { showMessages = false } }
            }
            if (showMore) MoreSheet(moreActions(router, { showMessages = true }, { showOptions = true }, { showHelp = true }, { mapView?.toggleOverview() }) { terminal?.toggleKeyboard() } +
                                    listOfNotNull(scumAction(), BarAction("Restore scum save", run = { showScum = true }).takeIf { settings.scumming })) { showMore = false }
            if (showKeys) MoreSheet(extraKeys(router)) { showKeys = false }
            if (showOptions) {
                DisposableEffect(Unit) {
                    router.intercept = { code -> if (code == Keys.ESCAPE) showOptions = false; code == Keys.ESCAPE }
                    onDispose { router.intercept = null }
                }
                OptionsSheet(settings, onGameOptions = { NarSilNative.nativeRequestAction(NarSilNative.ACT_OPTIONS) },
                             onExportSave = ::exportSave, onImportSave = { importSave.launch(arrayOf("*/*")) },
                             onSendLogs = { scope.launch { Share.file(this@MainActivity, Share.bugReport(applicationContext), "application/zip", "Send logs") } }) {
                    showOptions = false
                }
            }
            if (showScum) ScumSheet(this@MainActivity) { showScum = false }
            pendingImport?.let { copy ->
                AlertDialog(
                    onDismissRequest = { pendingImport = null },
                    title = { Text("Load this save?") },
                    text = { Text("The game restarts from the imported save. Anything not exported or scummed from the current game is lost.") },
                    confirmButton = { TextButton(onClick = { Scum.restore(this@MainActivity, copy) }) { Text("Load") } },
                    dismissButton = { TextButton(onClick = { copy.file.delete(); pendingImport = null }) { Text("Cancel") } },
                )
            }
            if (showRest) RestSheet({ showRest = false }) { NarSilNative.nativeRequestRest(it) }
            if (showHelp) {
                DisposableEffect(Unit) {
                    router.intercept = { code -> if (code == Keys.ESCAPE) showHelp = false; code == Keys.ESCAPE }
                    onDispose { router.intercept = null }
                }
                HelpScreen(this@MainActivity) { showHelp = false }
            }
        }
    }

    /** One prompt's native screen. */
    @Composable
    private fun PromptUi(state: GameState, prompt: GameState.Prompt, answer: (Int) -> Unit) {
when (prompt) {
            is GameState.Prompt.Items -> TextScale(settings.itemScale / settings.buttonScale) { ItemSheet(prompt, state::colour, answer) }
            is GameState.Prompt.Check -> CheckDialog(prompt, answer)
            is GameState.Prompt.Choice -> ChoiceSheet(prompt, answer)
            is GameState.Prompt.Skills -> SkillsSheet(prompt, answer)
            is GameState.Prompt.Abilities -> AbilitiesSheet(prompt, answer)
            is GameState.Prompt.Smith -> SmithSheet(prompt, answer)
            is GameState.Prompt.Text -> TextPrompt(prompt) { answer(-1) }
        is GameState.Prompt.Quantity -> QuantityDialog(prompt, answer)
            is GameState.Prompt.Options -> OptionsScreen(prompt, answer, onAppSettings = { showOptions = true }.takeIf { LocalInPanel.current })
        is GameState.Prompt.Character -> {
            /* The dump is written in answer to CHARACTER_DUMP; the tab comes back once it exists. */
            LaunchedEffect(prompt) {
                if (shareDumpPending) { shareDumpPending = false; Share.file(this@MainActivity, Share.characterDump(this@MainActivity), "text/plain", "Share character dump") }
            }
            CharacterSheet(prompt.info, state, onDismiss = { answer(-1) }, actions = listOf(
                "Rename" to { answer(NarSilNative.CHARACTER_RENAME) },
                "Share dump" to { shareDumpPending = true; answer(NarSilNative.CHARACTER_DUMP) },
            ))
        }
            is GameState.Prompt.Knowledge -> KnowledgeScreen(prompt, state::colour, answer)
            is GameState.Prompt.Pack -> TextScale(settings.itemScale / settings.buttonScale) { PackSheet(prompt, state::colour, answer) }
            is GameState.Prompt.Screen -> ScreenSheet(prompt, answer, onScum = { showScum = true }.takeIf { settings.scumming })
            is GameState.Prompt.Aim, is GameState.Prompt.Direction -> Unit  // drawn by GameLayers over the map
        }
    }

    /**
     * The character panel: the open tab's screen; while some other prompt (a
     * yes/no, a text entry) is asked over it, the last tab screen, dimmed.
     */
    @Composable
    private fun PanelLayer(state: GameState, answer: (Int) -> Unit) {
        val prompt = state.prompt
        val live = state.panelTab >= 0
        var tab by remember { mutableIntStateOf(0) }
        var shown by remember { mutableStateOf<GameState.Prompt?>(null) }
        if (live) {
            tab = state.panelTab
            if (prompt?.isPanelContent() == true) shown = prompt
        }
        val interactive = prompt != null && prompt === shown
        /* Dimmed under a question asked over it; not in the moment between two tabs (no prompt yet). */
        val dimmed = prompt != null && prompt !== shown
        if (live) PanelHost(tab, answer) {
            shown?.let { c ->
                Box(Modifier.fillMaxSize()) {
                    PromptUi(state, c, if (interactive) answer else { _ -> })
                    if (dimmed) Box(Modifier.fillMaxSize().background(Color(0x55000000))
                                              .clickable(remember { MutableInteractionSource() }, indication = null) {})
                }
            }
        }
    }

    @Composable
    private fun GameLayers(state: GameState) {
        /* Landscape: status and actions move to a rail on the right, so the map gets the full height. */
        val rail = state.mapMode && LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).fillMaxHeight()) {
                /* At -more- a tap continues; otherwise it opens the scrollable history. */
                if (state.mapMode) TextScale(settings.messageScale) {
                    MessageStrip(state, settings.messageLines) { if (state.messageLine.contains("-more-")) router.special(Keys.ENTER) else showMessages = true }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    AndroidView(
                        factory = { ctx -> TerminalView(ctx, state, router).also { terminal = it } },
                        modifier = Modifier.fillMaxSize(),
                    )
                    AndroidView(
                        factory = { ctx -> MapView(ctx, state, router, { settings.swipeRuns }, { settings.tiles }).also { mapView = it } },
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
                    if (state.mapMode && settings.edgeSwipePanel) EdgeSwipeHandle(Modifier.align(Alignment.CenterEnd))
                }
                if (state.mapMode && !rail) TextScale(settings.statusScale) {
                    if (dpadMode() == Settings.DPAD_DOCKED) Row(Modifier.background(Color(0xFF141414)), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { StatusStrip(state, stacked = true, buzzOnLowHealth = settings.lowHealthBuzz) }
                        Box(Modifier.padding(4.dp)) { Dpad(settings.dpadSizeDp, { dpad(state, it) }) }
                    } else StatusStrip(state, stacked = false, buzzOnLowHealth = settings.lowHealthBuzz)
                }
                if (state.mapMode && !rail) TextScale(settings.buttonScale) { ActionBar(barActions(state)) { showMore = true } }
                if (!state.mapMode) TextScale(settings.buttonScale) { KeyBar(state.waiting, router, { terminal?.toggleKeyboard() }) { showKeys = true } }
            }
            if (rail) Column(Modifier.width(260.dp).fillMaxHeight().background(Color(0xFF141414))) {
                TextScale(settings.statusScale) { StatusStrip(state, stacked = true, buzzOnLowHealth = settings.lowHealthBuzz) }
                TextScale(settings.buttonScale) { ActionRail(barActions(state), Modifier.weight(1f)) { showMore = true } }
                if (dpadMode() == Settings.DPAD_DOCKED) Box(Modifier.fillMaxWidth().padding(4.dp), contentAlignment = Alignment.Center) {
                    Dpad(settings.dpadSizeDp.coerceAtMost(150f), { dpad(state, it) })
                }
            }
        }
    }

    private fun barActions(state: GameState) = contextActions(state.status) + coreActions(state.status) { showRest = true }

    /** The d-pad as set (off, docked or floating); docked is beside the status in portrait, in the side column in landscape. */
    @Composable
    private fun dpadMode(): Int = settings.dpad

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

    /** Native game state is process-global and cannot be re-initialised; end the process. */
    /** Options → Export save: save, then hand a copy to the share sheet. */
    private fun exportSave() {
        scope.launch {
            Scum.export(applicationContext) { Toast.makeText(this@MainActivity, it, Toast.LENGTH_SHORT).show() }
                ?.let { Share.file(this@MainActivity, it, "application/octet-stream", "Export save") }
        }
    }

    /** Save scumming's quick copy (Options → Save scumming). */
    private fun scumAction(): BarAction? = BarAction("Scum") {
        scope.launch { Toast.makeText(this@MainActivity, Scum.take(applicationContext), Toast.LENGTH_SHORT).show() }
    }.takeIf { settings.scumming }

    private fun exitApp() {
        finishAndRemoveTask()
        Process.killProcess(Process.myPid())
    }
}
