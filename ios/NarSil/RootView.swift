import SwiftUI

/// The map screen: status above, map, messages and the action bar below, the d-pad docked beside the status
/// in portrait and in a side column in landscape (MainActivity.kt's map layout).  Prompts and the panel are
/// not ported yet: while one is open a bar offers Cancel so the game can never be stuck.
struct RootView: View {
    @EnvironmentObject var game: GameState
    @AppStorage("tiles") private var tiles = false
    @AppStorage("swipeRuns") private var swipeRuns = false
    @State private var showMore = false
    @State private var showRest = false
    private let map = MapHandle()

    private var padSize: CGFloat { 140 }

    var body: some View {
        GeometryReader { g in
            let rail = g.size.width > g.size.height
            ZStack(alignment: .bottom) {
                Color.black.ignoresSafeArea()
                if rail { landscape } else { portrait }
                if showMore { sheet(MoreSheet(actions: more, dismiss: { showMore = false })) }
                if showRest { sheet(RestSheet(dismiss: { showRest = false })) }
            }
        }
        .onAppear { Native.setTiles(tiles); game.start() }
    }

    private var portrait: some View {
        VStack(spacing: 0) {
            MessageStrip(game: game, lines: 3, onPanel: { Native.requestAction(NB.ACT_PANEL) })
            MapView(game: game, handle: map, swipeRuns: swipeRuns, tiles: tiles)
            HStack(alignment: .center, spacing: 0) {
                StatusStrip(game: game, stacked: true)
                Dpad(size: padSize, onAction: pad).padding(4)
            }.background(Color(red: 0x14 / 255, green: 0x14 / 255, blue: 0x14 / 255))
            promptBar
            ActionBar(actions: bar) { showMore = true }
        }
    }

    private var landscape: some View {
        HStack(spacing: 0) {
            VStack(spacing: 0) {
                MessageStrip(game: game, lines: 2, onPanel: { Native.requestAction(NB.ACT_PANEL) })
                MapView(game: game, handle: map, swipeRuns: swipeRuns, tiles: tiles)
                promptBar
                ActionBar(actions: bar) { showMore = true }
            }
            VStack(spacing: 0) {
                StatusStrip(game: game, stacked: true)
                Spacer(minLength: 0)
                Dpad(size: min(padSize, 150), onAction: pad).padding(4)
            }
            .frame(width: 260).background(Color(red: 0x14 / 255, green: 0x14 / 255, blue: 0x14 / 255))
        }
    }

    private var bar: [BarAction] { contextActions(game.status) + coreActions(game.status) { showRest = true } }
    private var more: [BarAction] {
        moreActions(game.status, toggleMap: { map.toggleOverview() }, toggleTiles: { tiles.toggle(); Native.setTiles(tiles) }, tilesOn: tiles)
    }

    /// Anything but aiming and direction prompts has no screen yet: offer a way out.
    @ViewBuilder private var promptBar: some View {
        switch game.prompt {
        case nil, .aim?, .direction?: EmptyView()
        default:
            Button("Close the open prompt") { Native.answer(-1) }
                .frame(maxWidth: .infinity, minHeight: 44).background(Color(red: 0.35, green: 0.2, blue: 0.2)).foregroundColor(.white)
        }
    }

    private func sheet<V: View>(_ content: V) -> some View {
        ZStack(alignment: .bottom) {
            Color.black.opacity(0.6).ignoresSafeArea().onTapGesture { showMore = false; showRest = false }
            content.clipShape(RoundedRectangle(cornerRadius: 16))
        }
    }

    /// A d-pad gesture: answers an open aim or direction prompt; at the map prompt steps, runs, waits or rests;
    /// elsewhere (-more-) it sends the keypad digit.
    private func pad(_ a: PadAction) {
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
