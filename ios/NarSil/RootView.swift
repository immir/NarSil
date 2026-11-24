import SwiftUI

/// The map screen: status above, map, messages and the action bar below, the d-pad docked beside the status
/// in portrait and in a side column in landscape (MainActivity.kt's map layout).  Prompts and the panel are
/// not ported yet: while one is open a bar offers Cancel so the game can never be stuck.
struct RootView: View {
    @EnvironmentObject var game: GameState
    @AppStorage("tiles") private var tiles = false
    @AppStorage("swipeMode") private var swipeMode = SwipeMode.initial
    @State private var showMore = false
    @State private var showRest = false
    @State private var showMessages = false
    @State private var showSettings = false
    @State private var showHelp = false
    @State private var showScum = false
    @State private var importing = false
    @State private var pendingImport: Saves.Copy?
    @State private var toast: String?
    @State private var crashed = Saves.pendingCrash
    @AppStorage("scumming") private var scumming = false
    @AppStorage("buttonScale") private var buttonScale = 1.0
    @AppStorage("messageScale") private var messageScale = 1.0
    @AppStorage("itemScale") private var itemScale = 1.0
    @AppStorage("statusScale") private var statusScale = 1.0
    @AppStorage("theme") private var theme = 0
    @AppStorage("seenTips") private var seenTips = false
    @AppStorage(QuickPins.key) private var quickKinds = ""
    @AppStorage(QuickPins.actsKey) private var quickActs = ""
    @State private var lastHp = 0
    @AppStorage("itemDetails") private var itemDetails = false
    @AppStorage("messageLines") private var messageLines = 3
    @AppStorage("dpad") private var pad = PadMode.docked.rawValue
    @AppStorage("lowHealthBuzz") private var lowHealthBuzz = true
    @AppStorage("combatBuzz") private var combatBuzz = true
    @AppStorage("traceUsage") private var traceUsage = false
    @State private var lastFeel = 0
    @AppStorage("panelTabs") private var panelTabs = TabStyle.iconsAndNames.rawValue
    @StateObject private var abilities = AbilitiesNav()
    private let map = MapHandle()

    @AppStorage("dpadSize") private var dpadSize = 140.0
    @AppStorage("dpadX") private var padX = 1.0
    @AppStorage("dpadY") private var padY = 1.0
    private var padSize: CGFloat { CGFloat(dpadSize) }

    var body: some View {
        GeometryReader { g in
            let rail = g.size.width > g.size.height
            ZStack(alignment: .bottom) {
                Color.black.ignoresSafeArea()
                HardwareKeys(active: keysActive, onKey: hardwareKey).frame(width: 0, height: 0)
                if rail { landscape } else { portrait }
                if !game.mapMode { TerminalScreen(game: game) }
                promptLayer
                PanelLayer(game: game, abilities: abilities, onAppSettings: { showSettings = true })
                infoLayer
                if let toast {
                    Text(toast).scaledFont(14).foregroundColor(.white).padding(.horizontal, 16).padding(.vertical, 10)
                        .background(Color(rgb: 0x333338), in: Capsule()).padding(.bottom, 120).allowsHitTesting(false)
                }
                if let message = game.exited { ExitedCard(message: message) }
                if showMore { sheet(MoreSheet(actions: more, pinned: QuickPins.decode(quickActs), onPin: { quickActs = QuickPins.toggled(quickActs, $0) }, dismiss: { showMore = false })) }
                if showRest { sheet(RestSheet(dismiss: { showRest = false })) }
            }
        }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.data, .item]) { result in
            if case .success(let url) = result {
                pendingImport = Saves.importSave(from: url)
                if pendingImport == nil {
                    let game = GameLauncher.game
                    let other = (try? Data(contentsOf: url).prefix(8)) == Data(game.other.tag.utf8)
                    say(other ? "That is a \(game.other.title) save: switch games on the welcome screen to load it."
                              : "That file is not a \(game.title) savefile.")
                }
            }
        }
        .alert("Load this save?", isPresented: Binding(get: { pendingImport != nil }, set: { if !$0 { pendingImport = nil } }), presenting: pendingImport) { c in
            Button("Load and close the app", role: .destructive) { Saves.restore(c) }
            Button("Cancel", role: .cancel) { try? FileManager.default.removeItem(at: c.url) }
        } message: { _ in
            Text("NarSil closes; open it again to play from the imported save. Anything not exported or scummed from the current game is lost.")
        }
        .alert("NarSil closed unexpectedly", isPresented: $crashed) {
            Button("Send report") { Saves.acknowledgeCrash(); ShareSheet.present(Saves.reportFiles) }
            Button("Dismiss", role: .cancel) { Saves.acknowledgeCrash() }
        } message: {
            Text("The game stopped last time with a native error. Your game was saved at its last autosave. Sending the report helps fix it.")
        }
        .textScale(buttonScale)
        .onChange(of: theme) { game.themeIndex = $0 }
        .onChange(of: quickKinds) { Native.setQuickKinds(QuickPins.decode($0)) }
        .onAppear { game.themeIndex = theme; Native.setQuickKinds(QuickPins.decode(quickKinds)); Native.setTiles(tiles); Native.setItemDetails(itemDetails); Trace.setEnabled(traceUsage, header: GameLauncher.traceHeader); game.start() }
        .onChange(of: tiles) { Native.setTiles($0) }
        .onChange(of: traceUsage) { Trace.setEnabled($0, header: GameLauncher.traceHeader) }
        .onChange(of: itemDetails) { Native.setItemDetails($0) }
        .onChange(of: game.status.lowHp) { low in if low && lowHealthBuzz { UINotificationFeedbackGenerator().notificationOccurred(.warning) } }
        .onChange(of: game.status.feelSeq) { seq in
            /* A tick when the player's blow lands, a stronger one for a kill, a faint one for a miss. */
            defer { lastFeel = seq }
            guard combatBuzz, seq != lastFeel else { return }
            let bits = game.status.feelBits
            if bits & NB.FEEL_KILL != 0 { UIImpactFeedbackGenerator(style: .heavy).impactOccurred() }
            else if bits & NB.FEEL_HIT != 0 { UIImpactFeedbackGenerator(style: .medium).impactOccurred() }
            else if bits & NB.FEEL_MISS != 0 { UIImpactFeedbackGenerator(style: .soft).impactOccurred(intensity: 0.4) }
        }
        .onChange(of: game.status.hp) { hp in
            /* A short pulse when one turn costs a fifth or more of maximum health. */
            if lowHealthBuzz, game.status.maxHp > 0, lastHp - hp >= max(1, game.status.maxHp / 5) { UIImpactFeedbackGenerator(style: .heavy).impactOccurred() }
            lastHp = hp
        }
    }

    private var portrait: some View {
        VStack(spacing: 0) {
            MessageStrip(game: game, lines: messageLines, onPanel: { Trace.log("panel.request", ("via", "button")); Native.requestAction(NB.ACT_PANEL) }, onMessages: { Trace.log("messages.open", ("via", "strip")); showMessages = true }).textScale(messageScale)
            MapView(game: game, handle: map, swipeMode: swipeMode, tiles: tiles).overlay(floatingPad)
            HStack(alignment: .center, spacing: 0) {
                StatusStrip(game: game, stacked: true).textScale(statusScale)
                if pad == PadMode.docked.rawValue { Dpad(size: padSize, onAction: padAction).padding(4) }
            }.background(Color(red: 0x14 / 255, green: 0x14 / 255, blue: 0x14 / 255))
            promptBar
            ActionBar(actions: bar) { Trace.log("menu.open"); showMore = true }
        }
    }

    private var landscape: some View {
        HStack(spacing: 0) {
            VStack(spacing: 0) {
                MessageStrip(game: game, lines: messageLines, onPanel: { Trace.log("panel.request", ("via", "button")); Native.requestAction(NB.ACT_PANEL) }, onMessages: { Trace.log("messages.open", ("via", "strip")); showMessages = true }).textScale(messageScale)
                MapView(game: game, handle: map, swipeMode: swipeMode, tiles: tiles).overlay(floatingPad)
                promptBar
                ActionBar(actions: bar) { Trace.log("menu.open"); showMore = true }
            }
            VStack(spacing: 0) {
                StatusStrip(game: game, stacked: true).textScale(statusScale)
                Spacer(minLength: 0)
                if pad == PadMode.docked.rawValue { Dpad(size: min(padSize, 150), onAction: padAction).padding(4) }
            }
            .frame(width: 260).background(Color(red: 0x14 / 255, green: 0x14 / 255, blue: 0x14 / 255))
        }
    }

    /// Settings → D-pad → Floating: over the map, moved by holding its centre and dragging; the position is kept
    /// as a fraction of the free space.
    @ViewBuilder private var floatingPad: some View {
        if pad == PadMode.floating.rawValue && game.mapMode {
            GeometryReader { g in
                let freeW = max(g.size.width - padSize, 1), freeH = max(g.size.height - padSize, 1)
                Dpad(size: padSize, onAction: padAction) { d in
                    padX = min(max(padX + Double(d.width / freeW), 0), 1)
                    padY = min(max(padY + Double(d.height / freeH), 0), 1)
                }
                .offset(x: CGFloat(padX) * freeW, y: CGFloat(padY) * freeH)
            }
        }
    }

    private var bar: [BarAction] {
        let context = contextActions(game.status)
        return context + quickActions(game.quickSlots) { quickKinds = QuickPins.toggled(quickKinds, $0) }
            + pinnedActions(QuickPins.decode(quickActs), shown: context) { quickActs = QuickPins.toggled(quickActs, $0) }
            + coreActions(game.status, padShown: pad != PadMode.off.rawValue) { showRest = true }
    }
    private var more: [BarAction] {
        moreActions(game.status, messages: { showMore = false; showMessages = true }, toggleMap: { map.toggleOverview() }, toggleTiles: { tiles.toggle(); Native.setTiles(tiles) }, tilesOn: tiles, help: { showHelp = true },
                    restOptions: pad != PadMode.off.rawValue ? { showMore = false; showRest = true } : nil)
            + (scumming ? [BarAction(label: "Scum", run: { Task { say(await Saves.take()) } }),
                           BarAction(label: "Restore scum save", run: { showScum = true })] : [])
    }

    private func say(_ line: String) {
        toast = line
        DispatchQueue.main.asyncAfter(deadline: .now() + 2.5) { if toast == line { toast = nil } }
    }

    /// The prompt's own screen, over the map; aim and direction prompts live in `promptBar` so the map stays usable.
    @ViewBuilder private var promptLayer: some View {
        switch game.prompt {
        case .screen(let p)?: ScreenSheet(p: p, answer: Native.answer, onScum: scumming ? { showScum = true } : nil)
        case .items(let p)?: ItemSheet(prompt: p, colour: { game.colour($0) }, answer: Native.answer).textScale(itemScale)
        case .check(let q)?: CheckDialog(question: q, answer: Native.answer)
        case .choice(let p)?: ChoiceSheet(prompt: p, answer: Native.answer)
        case .quantity(let p)?: QuantityDialog(prompt: p, answer: Native.answer)
        case .text(let p)?: TextPromptView(prompt: p, answer: Native.answer)
        /* Asked outside the panel (spending experience at birth, an ability confirmation): a sheet. */
        case .skills(let p)? where game.panelTab < 0:
            BottomSheet(maxHeight: 640, cancel: {}) { SkillsView(prompt: p, answer: Native.answer) }
        case .abilities(let p)? where game.panelTab < 0:
            BottomSheet(maxHeight: 640, cancel: { Native.answer(-1) }) { AbilitiesView(prompt: p, nav: abilities, answer: Native.answer) }
        default: EmptyView()
        }
    }

    /// Read-only descriptions the player asked for (long-press inspect, the look list, the character sheet); the game is not waiting on them.
    @ViewBuilder private var infoLayer: some View {
        if game.prompt == nil, let info = game.info {
            let close = { game.info = nil }
            switch info {
            case .inspect(let x, let y, let monster, let objects, let terrain, let targetable, let isTarget):
                InspectSheet(game: game, x: x, y: y, monster: monster, objects: objects, terrain: terrain, targetable: targetable, isTarget: isTarget, dismiss: close)
            case .lookList(let entries): LookSheet(game: game, entries: entries, dismiss: close)
            case .character(let sections):
                BottomSheet(maxHeight: 700, cancel: close) {
                    VStack(spacing: 0) {
                        CharacterView(game: game, sections: sections, actions: [("Open in panel", { close(); Native.requestAction(NB.ACT_CHARACTER) }), ("Close", close)])
                    }
                }
            }
        }
        if showSettings { SettingsSheet(buttonScale: $buttonScale, messageScale: $messageScale, itemScale: $itemScale, statusScale: $statusScale,
                          theme: $theme, gamePalette: game.gamePalette, messageLines: $messageLines, panelTabs: $panelTabs, tiles: $tiles, pad: $pad, padSize: $dpadSize, swipeMode: $swipeMode,
                          autoMore: game.status.autoMore, autoMoreEnabled: game.atPrompt && game.prompt == nil, onAutoMore: Native.setAutoMore,
                          itemDetails: $itemDetails, lowHealthBuzz: $lowHealthBuzz, combatBuzz: $combatBuzz, traceUsage: $traceUsage, scumming: $scumming,
                          onExportSave: { Task { if let url = await Saves.export() { ShareSheet.present([url]) } else { say(Saves.cantSave) } } },
                          onImportSave: { importing = true },
                          onSendLogs: { ShareSheet.present(Saves.reportFiles) }) { showSettings = false } }
        if showScum { ScumSheet { showScum = false } }
        if showMessages { MessagesSheet(game: game) { showMessages = false }.textScale(messageScale) }
        if !seenTips && game.mapMode && game.atPrompt && game.prompt == nil && game.panelTab < 0 && game.info == nil {
            TipsCard(onHelp: { seenTips = true; showHelp = true }) { seenTips = true }
        }
        if showHelp { HelpView { showHelp = false } }
    }

    private var promptName: String {
        guard let p = game.prompt else { return "none" }
        return String(String(describing: p).prefix { $0 != "(" })
    }

    /// Aim and direction choices under the map; prompts without a screen yet get a way out.
    @ViewBuilder private var promptBar: some View {
        switch game.prompt {
        case .aim(let p)?: AimBar(prompt: p, answer: Native.answer)
        case .direction(let p)?: DirectionBar(prompt: p, answer: Native.answer)
        case nil, .screen?, .items?, .check?, .choice?, .quantity?, .text?: EmptyView()
        case .skills? where game.panelTab < 0, .abilities? where game.panelTab < 0: EmptyView()
        case let p? where p.isPanelContent && game.panelTab >= 0: EmptyView()
        default:
            Button("Close the open prompt (\(promptName)) — map \(game.mapMode ? "on" : "off")") { Native.answer(-1) }
                .frame(maxWidth: .infinity, minHeight: 44).background(Color(red: 0.35, green: 0.2, blue: 0.2)).foregroundColor(.white)
        }
    }

    private func sheet<V: View>(_ content: V) -> some View {
        ZStack(alignment: .bottom) {
            Color.black.opacity(0.6).ignoresSafeArea().onTapGesture { showMore = false; showRest = false }
            content.clipShape(RoundedRectangle(cornerRadius: 16))
        }
    }

    /// The hardware keyboard listens at the map, but never while a text field might want it (text and number
    /// prompts, the panel's search boxes) or an app sheet is open.
    private var keysActive: Bool {
        guard game.mapMode, game.panelTab < 0, game.info == nil, !showSettings, !showHelp, !showMessages, !showScum else { return false }
        switch game.prompt {
        case .text?, .quantity?: return false
        default: return true
        }
    }

    /// A key from a hardware keyboard: answers an open native prompt (or is swallowed), else goes to the game.
    private func hardwareKey(_ code: Int, _ mods: Int) {
        if let p = game.prompt {
            if let a = answerByKey(p, code) { Native.answer(a) }
        } else {
            Native.pushKey(code, mods: mods)
        }
    }

    /// A d-pad gesture: answers an open aim or direction prompt; at the map prompt steps, runs, waits or rests;
    /// elsewhere (-more-) it sends the keypad digit.
    private func padAction(_ a: PadAction) {
        Trace.log("pad", ("kind", { () -> String in switch a { case .step: return "step"; case .run: return "run"; case .wait: return "wait"; case .rest: return "rest" } }()))
        let dir: Int
        switch a {
        case .step(let d), .run(let d): dir = d
        default: dir = 5
        }
        switch game.prompt {
        case .aim?: Native.answer(dir == 5 ? NB.AIM_CURRENT : dir)
        case .direction(let d)?: if dir != 5 || d.allowHere { Native.answer(dir) }
        case nil:
            if !game.atPrompt { Native.pushKey(Int(UnicodeScalar("0").value) + dir) }
            else if a == .wait { Native.requestAction(NB.ACT_HOLD) }
            else if a == .rest { Native.requestAction(NB.ACT_REST) }
            else { Native.requestMove(dir, run: { if case .run = a { return true } else { return false } }()) }
        default: break
        }
    }
}

/// The game thread has ended.  After the welcome screen's "Play the other game" the choice is saved, and the player
/// opens the app again to play it: iOS apps can't relaunch themselves.
private struct ExitedCard: View {
    let message: String
    private var switching: Bool { message == GameLauncher.exitSwitchGame }

    var body: some View {
        ZStack {
            Color.black.opacity(0.85).ignoresSafeArea()
            VStack(spacing: 16) {
                Text(switching ? "Open NarSil again to play \(GameLauncher.game.other.title)." : (message.isEmpty ? "The game has ended." : message))
                    .scaledFont(17).foregroundColor(.white).multilineTextAlignment(.center)
                Button("Close") { exit(0) }.buttonStyle(.borderedProminent)
            }.padding(24)
        }
        .onAppear { if switching { GameLauncher.chooseOtherGame() } }
    }
}
