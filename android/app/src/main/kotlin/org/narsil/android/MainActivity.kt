package org.narsil.android

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import android.os.Bundle
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.RowScope
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.activity.compose.setContent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
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
    /** ☰ → Adjust layout: handles on the pane boundaries (LayoutMode.kt). */
    private var layoutMode by mutableStateOf(false)
    private var showMessages by mutableStateOf(false)
    private var showOptions by mutableStateOf(false)
    private var showKeys by mutableStateOf(false)
    private var showScum by mutableStateOf(false)
    private var showHelp by mutableStateOf(false)
    private var showRest by mutableStateOf(false)
    private var shareDumpPending = false
    private var pendingImport by mutableStateOf<Scum.Copy?>(null)
    private val importSave = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { scope.launch {
            runCatching { Scum.import(applicationContext, it) }
                .onSuccess { copy -> pendingImport = copy }
                .onFailure { e -> Toast.makeText(this@MainActivity, e.message ?: "Can't read that file.", Toast.LENGTH_LONG).show() }
        } }
    }
    private val scope = MainScope()
    private lateinit var settings: Settings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Haptics.init(applicationContext)
        settings = Settings(applicationContext)
        Trace.start(GameLauncher.dataDir(applicationContext), settings.traceUsage, traceHeader())
        crash = Share.pendingCrash(applicationContext)
        onBackPressedDispatcher.addCallback(this) { router.special(Keys.ESCAPE) }
        setContent {
            MaterialTheme(colorScheme = Ui.colorScheme(), typography = compactTypography()) { App() }
        }
    }

    private var crash by mutableStateOf<String?>(null)

    private fun traceHeader() = listOf("app" to "android", "game" to GameLauncher.game(applicationContext).data,
        "version" to (packageManager.getPackageInfo(packageName, 0).versionName ?: "?"), "sdk" to android.os.Build.VERSION.SDK_INT)

    /** Runs at the game's next safe point (top-level command prompt). */
    override fun onDestroy() {
        scope.cancel()
        game?.stop()
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        Trace.log("app.start")
        game?.start()
    }

    /** Saves, and stops polling the game while nothing is on screen. */
    override fun onStop() {
        super.onStop()
        Trace.log("app.stop")
        game?.stop()
        if (NarSilNative.nativeState() == NarSilNative.STATE_RUNNING) NarSilNative.nativeRequestSave()
    }

    @Composable
    private fun App() {
        var phase by remember { mutableStateOf<Phase>(Phase.Loading) }
        LaunchedEffect(settings.traceUsage) { Trace.setEnabled(settings.traceUsage, traceHeader()) }

        LaunchedEffect(Unit) {
            phase = runCatching { withContext(Dispatchers.IO) { GameLauncher.ensureStarted(applicationContext) } }
                .fold({ game = GameState().also { it.start() }; NarSilNative.nativeSetTiles(settings.tiles); NarSilNative.nativeSetItemDetails(settings.itemDetails); NarSilNative.nativeSetQuickKinds(settings.quickKinds.toIntArray()); Phase.Running }, { Phase.Failed(it.message ?: it.toString()) })
        }
        LaunchedEffect(phase) {
            var saves = -1L
            while (phase == Phase.Running) {
                if (NarSilNative.nativeState() == NarSilNative.STATE_EXITED) {
                    val message = NarSilNative.nativeExitMessage()
                    /* The welcome screen's "Play NarSil-Q instead" (or back): start again as the other game. */
                    if (message == NarSilNative.EXIT_SWITCH_GAME) GameLauncher.switchGame(applicationContext)
                    phase = Phase.Exited(message)
                }
                /* Each save the game makes (autosave, level change, leaving the app) may become a kept recent autosave. */
                NarSilNative.nativeSaveCount().let { n ->
                    if (n != saves) { if (saves >= 0) withContext(Dispatchers.IO) { Scum.noteSave(applicationContext) }; saves = n }
                }
                delay(500)
            }
        }

        /* Watches the initial pass of every touch without consuming it: a tick when a finger lifts having stayed put, so a drag (scrolling, panning) is silent. */
        val tapTick = Modifier.pointerInput(Unit) {
            awaitPointerEventScope {
                var from: Offset? = null
                while (true) {
                    val e = awaitPointerEvent(PointerEventPass.Initial)
                    val c = e.changes.singleOrNull()
                    when {
                        c == null -> from = null
                        c.changedToDown() -> from = c.position
                        from != null && (c.position - from!!).getDistance() > viewConfiguration.touchSlop -> from = null
                        c.changedToUp() && from != null -> { from = null; if (settings.tapBuzz) Haptics.tap() }
                    }
                }
            }
        }
        Box(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding().imePadding().then(tapTick)) {
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
            val answer: (Int) -> Unit = { value ->
                if (!state.holding) {  // while the held pack waits to reopen there is nothing to answer
                    state.answered(value)
                    NarSilNative.nativeAnswer(value)
                }
            }
            PanelLayer(state, answer)
            state.prompt?.let { prompt ->
                DisposableEffect(prompt) {
                    val release = router.intercepting { code -> answerByKey(prompt, code)?.let(answer); true }
                    onDispose { release() }
                }
                /* In the panel, its tab screens are drawn by PanelLayer; anything else is asked over it. */
                if (state.panelTab < 0 || !prompt.isPanelContent()) PromptUi(state, prompt, answer)
            }
            if (state.prompt == null) state.info?.let { info ->
                DisposableEffect(info) {
                    val release = router.intercepting { code -> if (code == Keys.ESCAPE) state.info = null; code == Keys.ESCAPE }
                    onDispose { release() }
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
                    val release = router.intercepting { code -> if (code == Keys.ESCAPE) showMessages = false; code == Keys.ESCAPE }
                    onDispose { release() }
                }
                TextScale(settings.messageScale / settings.buttonScale) { MessagesSheet(state) { showMessages = false } }
            }
            if (showMore) MoreSheet(moreActions(state.status, router, { showMessages = true }, { showHelp = true }, { mapView?.toggleOverview() },
                                                { settings.tiles = !settings.tiles }, settings.tiles,
                                                { showRest = true }.takeIf { settings.dpad != Settings.DPAD_OFF }, { terminal?.toggleKeyboard() }, settings.tabsOnTop, adjustLayout = { showMore = false; layoutMode = true }) +
                                    listOfNotNull(scumAction(), BarAction("Restore scum save", run = { showScum = true }).takeIf { settings.scumming }),
                                    settings.quickActs, settings::toggleQuickAct) { showMore = false }
            if (showKeys) MoreSheet(extraKeys(router)) { showKeys = false }
            if (showOptions) {
                DisposableEffect(Unit) {
                    val release = router.intercepting { code -> if (code == Keys.ESCAPE) showOptions = false; code == Keys.ESCAPE }
                    onDispose { release() }
                }
                OptionsSheet(settings, state.gamePalette, autoMore = state.status.autoMore,
                             autoMoreEnabled = state.atPrompt && state.prompt == null, onAutoMore = NarSilNative::nativeSetAutoMore,
                             onGameOptions = { NarSilNative.nativeRequestAction(NarSilNative.ACT_OPTIONS) },
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
            if (crash != null) {
                AlertDialog(
                    onDismissRequest = { Share.acknowledgeCrash(applicationContext); crash = null },
                    title = { Text("NarSil closed unexpectedly") },
                    text = { Text("The game stopped last time with a native error. Your game was saved at its last autosave. Sending the report helps fix it.") },
                    confirmButton = { TextButton(onClick = {
                        Share.acknowledgeCrash(applicationContext); crash = null
                        scope.launch { Share.file(this@MainActivity, Share.bugReport(applicationContext), "application/zip", "Send report") }
                    }) { Text("Send report") } },
                    dismissButton = { TextButton(onClick = { Share.acknowledgeCrash(applicationContext); crash = null }) { Text("Dismiss") } },
                )
            }
            if (!settings.seenTips && state.mapMode && state.atPrompt && state.prompt == null && state.panelTab < 0 && state.info == null)
                TipsCard(onHelp = { settings.seenTips = true; showHelp = true }) { settings.seenTips = true }
            if (showRest) RestSheet({ showRest = false }) { NarSilNative.nativeRequestRest(it) }
            if (showHelp) {
                DisposableEffect(Unit) {
                    val release = router.intercepting { code -> if (code == Keys.ESCAPE) showHelp = false; code == Keys.ESCAPE }
                    onDispose { release() }
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
                CharacterSheet(prompt.info, state, onDismiss = { answer(-1) }, actions = listOfNotNull(
                    "Rename" to { answer(NarSilNative.CHARACTER_RENAME) },
                    "Share dump" to { shareDumpPending = true; answer(NarSilNative.CHARACTER_DUMP) },
                    /* Only before the first turn: the build is what the character was made with. */
                    ("Save build" to { answer(NarSilNative.CHARACTER_SAVE_BUILD) }).takeIf { state.status.has(NarSilNative.CTX_BUILD_SAVABLE) },
                ))
            }
            is GameState.Prompt.Knowledge -> KnowledgeScreen(prompt, state::colour, answer)
            is GameState.Prompt.Pack -> TextScale(settings.itemScale / settings.buttonScale) { PackSheet(prompt, state::colour, answer, settings.quickKinds, settings::toggleQuick) }
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
        /* By identity: a reopened pack equal to the one last shown must still replace it. */
        var shown by remember { mutableStateOf<GameState.Prompt?>(null, referentialEqualityPolicy()) }
        if (live) {
            tab = state.panelTab
            if (prompt?.isPanelContent() == true) shown = prompt
        }
        val interactive = prompt?.isPanelContent() == true
        /* Dimmed under a question asked over it; not in the moment between two tabs (no prompt yet). */
        val dimmed = prompt != null && !interactive
        if (live) PanelHost(tab, settings.panelTabs, settings.tabsOnTop, settings.layoutMirror, answer) {
            (if (interactive) prompt else shown)?.let { c ->
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
        SideEffect { state.themeIndex = settings.theme }
        /* Landscape: the status moves to a rail on the right and the buttons run along the whole bottom edge. */
        val rail = state.mapMode && LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
        Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
        if (state.mapMode && settings.tabsOnTop && !rail) TabsBar(-1, settings.panelTabs) { i ->
            Trace.log("panel.request", "via" to "tabs", "tab" to i); NarSilNative.nativeRequestAction(TAB_ACTIONS[i])
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val widthPx = constraints.maxWidth.toFloat()
            val heightPx = constraints.maxHeight.toFloat()
            val screenHeight = maxHeight.value
            var sectionPx by remember { mutableFloatStateOf(0f) }
            val density = LocalDensity.current
            val mirror = settings.layoutMirror && rail
            val screenWidth = maxWidth.value
            val railWidth = (settings.layoutRail * screenWidth).dp
            val buttonsWidth = (settings.layoutButtons * screenWidth).dp
            val buttonsColumn: @Composable () -> Unit = {
                TextScale(settings.buttonScale) { ActionRail(barActions(state), buttonsWidth) { Trace.log("menu.open"); showMore = true } }
            }
            val mapColumn: @Composable RowScope.() -> Unit = {
            Column(Modifier.weight(1f).fillMaxHeight()) {
                /* At -more- a tap continues; otherwise it opens the scrollable history. */
                if (state.mapMode) TextScale(settings.messageScale) {
                    val messages = if (rail) settings.layoutMsgLandscape else settings.layoutMsgPortrait
                    MessageStrip(state, (messages * screenHeight).dp, panelButton = !settings.tabsOnTop) { if (state.messageLine.contains("-more-")) { Trace.log("more.tap"); router.special(Keys.ENTER) } else { Trace.log("messages.open", "via" to "strip"); showMessages = true } }
                }
                /* Dragged: the message area's height. */
                if (state.mapMode && layoutMode) ResizeHandle(horizontalBar = true) { dy ->
                    if (rail) settings.layoutMsgLandscape += dy / heightPx else settings.layoutMsgPortrait += dy / heightPx
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    AndroidView(
                        factory = { ctx -> TerminalView(ctx, state, router).also { terminal = it } },
                        modifier = Modifier.fillMaxSize(),
                    )
                    AndroidView(
                        factory = { ctx -> MapView(ctx, state, router, { settings.swipeMode }, { settings.tiles }, { settings.dpad != Settings.DPAD_OFF }).also { mapView = it } },
                        update = { it.visibility = if (state.mapMode) View.VISIBLE else View.GONE },
                        modifier = Modifier.fillMaxSize(),
                    )
                    (state.prompt as? GameState.Prompt.Aim)?.let { aim ->
                        Box(Modifier.align(Alignment.BottomCenter)) { AimBar(aim, NarSilNative::nativeAnswer) }
                    }
                    (state.prompt as? GameState.Prompt.Direction)?.let { d ->
                        Box(Modifier.align(Alignment.BottomCenter)) { DirectionBar(d, dpadMode() != Settings.DPAD_OFF, NarSilNative::nativeAnswer) }
                    }
                    if (state.mapMode && dpadMode() == Settings.DPAD_FLOATING) FloatingDpad(settings, padMarks(state.prompt), resizing = layoutMode) { dpad(state, it) }
                    /* At the top of the map, clear of the handles (they have their grips halfway down the boundaries). */
                    if (layoutMode) Box(Modifier.align(Alignment.TopCenter)) { LayoutBar(settings) { layoutMode = false } }
                    if (state.mapMode && settings.edgeSwipePanel) EdgeSwipeHandle(Modifier.align(if (mirror) Alignment.CenterStart else Alignment.CenterEnd))
                }
                if (state.mapMode && !rail) TextScale(settings.statusScale) {
                    if (dpadMode() == Settings.DPAD_DOCKED) Column {
                        /* Dragged: the height of this section; the d-pad grows with it, and the status scrolls if it no longer fits. */
                        val fixed = settings.layoutBottom > 0
                        val sectionDp = settings.layoutBottom * screenHeight
                        if (layoutMode) ResizeHandle(horizontalBar = true) { dy ->
                            settings.layoutBottom = (if (fixed) settings.layoutBottom else sectionPx / heightPx) - dy / heightPx
                        }
                        /* The d-pad's share of the width, and no taller than a section of fixed height. */
                        val padShareDp = settings.layoutPad * screenWidth - 8
                        val padDp = (if (fixed) minOf(sectionDp - 8, padShareDp) else padShareDp).coerceAtLeast(60f)
                        Row(Modifier.then(if (fixed) Modifier.height(sectionDp.dp) else Modifier.onSizeChanged { sectionPx = it.height.toFloat() }).background(Ui.Chrome),
                            verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f).then(if (fixed) Modifier.verticalScroll(rememberScrollState()) else Modifier)) {
                                StatusStrip(state, stacked = true, buzzOnLowHealth = settings.lowHealthBuzz, buzzInCombat = settings.combatBuzz)
                            }
                            /* Dragged: how the width is shared between the status and the d-pad. */
                            if (layoutMode) ResizeHandle(horizontalBar = false, length = if (fixed) sectionDp.dp else (sectionPx / density.density).dp) { dx -> settings.layoutPad -= dx / widthPx }
                            Box(Modifier.padding(4.dp)) { Dpad(padDp, { dpad(state, it) }, marked = padMarks(state.prompt)) }
                        }
                    } else StatusStrip(state, stacked = false, buzzOnLowHealth = settings.lowHealthBuzz, buzzInCombat = settings.combatBuzz)
                }
                if (state.mapMode && !rail) TextScale(settings.buttonScale) { ActionBar(barActions(state)) { Trace.log("menu.open"); showMore = true } }
                if (!state.mapMode) TextScale(settings.buttonScale) { KeyBar(state.waiting, router, { terminal?.toggleKeyboard() }) { showKeys = true } }
            }
            }
            val statusColumn: @Composable () -> Unit = {
                BoxWithConstraints(Modifier.width(railWidth).fillMaxHeight().background(Ui.Chrome)) {
                    val columnPx = constraints.maxHeight.toFloat()
                    var statusPx by remember { mutableFloatStateOf(0f) }
                    val split = settings.layoutSplit
                    val docked = dpadMode() == Settings.DPAD_DOCKED
                    Column(Modifier.fillMaxSize()) {
                        /* The status takes what it needs, or, once the split is dragged, a share of the column (and scrolls if that is less). */
                        Box(if (split > 0 && docked) Modifier.weight(split).verticalScroll(rememberScrollState()) else Modifier.onSizeChanged { statusPx = it.height.toFloat() }) {
                            TextScale(settings.statusScale) { StatusStrip(state, stacked = true, buzzOnLowHealth = settings.lowHealthBuzz, buzzInCombat = settings.combatBuzz) }
                        }
                        if (layoutMode && docked) ResizeHandle(horizontalBar = true) { dy ->
                            settings.layoutSplit = ((if (split > 0) split else statusPx / columnPx) + dy / columnPx)
                        }
                        /* Whatever is left under the status is the d-pad's: it grows to the largest square that fits. */
                        if (docked) BoxWithConstraints(Modifier.fillMaxWidth().weight(if (split > 0) 1 - split else 1f).padding(4.dp), contentAlignment = Alignment.Center) {
                            Dpad(minOf(maxWidth, maxHeight).value.coerceAtLeast(60f), { dpad(state, it) }, marked = padMarks(state.prompt))
                        } else Spacer(Modifier.weight(1f))
                    }
                }
            }
            val tabsColumn: @Composable () -> Unit = {
                if (settings.tabsOnTop) TabsRail(-1, onLeft = mirror) { i -> Trace.log("panel.request", "via" to "tabs", "tab" to i); NarSilNative.nativeRequestAction(TAB_ACTIONS[i]) }
            }
            Row(Modifier.fillMaxSize()) {
                when {
                    !rail -> mapColumn()
                    !mirror -> {
                        buttonsColumn()
                        if (layoutMode) ResizeHandle(horizontalBar = false) { dx -> settings.layoutButtons = settings.layoutButtons + dx / widthPx }
                        mapColumn()
                        if (layoutMode) ResizeHandle(horizontalBar = false) { dx -> settings.layoutRail = settings.layoutRail - dx / widthPx }
                        statusColumn(); tabsColumn()
                    }
                    else -> {
                        tabsColumn(); statusColumn()
                        if (layoutMode) ResizeHandle(horizontalBar = false) { dx -> settings.layoutRail = settings.layoutRail + dx / widthPx }
                        mapColumn()
                        if (layoutMode) ResizeHandle(horizontalBar = false) { dx -> settings.layoutButtons = settings.layoutButtons - dx / widthPx }
                        buttonsColumn()
                    }
                }
            }
        }
        }
        if (layoutMode) BackHandler { layoutMode = false }
        }
    }

    private fun barActions(state: GameState): List<BarAction> {
        val context = contextActions(state.status)
        return context + quickActions(state.quickSlots, settings::toggleQuick) +
            pinnedActions(settings.quickActs, context, settings::toggleQuickAct) + coreActions(state.status, settings.dpad != Settings.DPAD_OFF) { showRest = true }
    }

    /** The d-pad as set (off, docked or floating); docked is beside the status in portrait, in the side column in landscape. */
    @Composable
    private fun dpadMode(): Int = settings.dpad

    private fun dpad(state: GameState, a: PadAction) = applyPad(state, router, a)

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

    /** Native game state is process-global and cannot be re-initialised; end the process. */
    private fun exitApp() {
        finishAndRemoveTask()
        Process.killProcess(Process.myPid())
    }
}
