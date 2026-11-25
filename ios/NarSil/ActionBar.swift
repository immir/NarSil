import SwiftUI

/// Two thin concentric outlines inside a shape: the mark of a button that does more when held (holdMark in ActionBar.kt).
private struct HoldMark<S: InsettableShape>: ViewModifier {
    let shape: S
    let enabled: Bool

    func body(content: Content) -> some View {
        content.overlay {
            if enabled {
                ZStack {
                    shape.inset(by: 1.5).stroke(Color.white.opacity(0.25), lineWidth: 1)
                    shape.inset(by: 4.5).stroke(Color.white.opacity(0.25), lineWidth: 1)
                }.allowsHitTesting(false)
            }
        }
    }
}

/// One tappable action (ActionBar.kt).
struct BarAction: Identifiable {
    let id = UUID()
    let label: String
    var emphasised = false
    var active = false
    var longPress: (() -> Void)?
    var pin: Int?
    let run: () -> Void
}

private func act(_ id: Int) -> () -> Void { { Native.requestAction(id) } }

/// The commands that can be pinned to the bar (long-press one in ☰), by native action id.
private let pinnable: [Int: String] = [
    NB.ACT_INVENTORY: "Pack", NB.ACT_BASH: "Bash", NB.ACT_TUNNEL: "Tunnel", NB.ACT_DISARM: "Disarm", NB.ACT_EXCHANGE: "Exchange",
    NB.ACT_TRAVEL_DOWN: "Walk to ▼", NB.ACT_TRAVEL_UP: "Walk to ▲",
]

/// Pinned commands (☰ → long-press a command): tap runs it, long-press unpins.  Ones the context already offers are left out.
func pinnedActions(_ ids: [Int], shown: [BarAction], unpin: @escaping (Int) -> Void) -> [BarAction] {
    ids.compactMap { id in
        guard let label = pinnable[id], !shown.contains(where: { $0.label == label }) else { return nil }
        return BarAction(label: label, longPress: { unpin(id) }, run: act(id))
    }
}

/// Context actions for where the player stands, in the order a Sil player reaches for them.
func contextActions(_ s: Status) -> [BarAction] {
    var out = [BarAction]()
    if s.has(NB.CTX_DOWN_STAIRS) { out.append(BarAction(label: "Down ▼", emphasised: true, run: act(NB.ACT_GO_DOWN))) }
    if s.has(NB.CTX_UP_STAIRS) { out.append(BarAction(label: "Up ▲", emphasised: true, run: act(NB.ACT_GO_UP))) }
    if s.has(NB.CTX_FLOOR_ITEMS) { out.append(BarAction(label: s.has(NB.CTX_FLOOR_SPECIAL) ? "Pick up ✦" : "Pick up", emphasised: true, longPress: act(NB.ACT_FLOOR), run: act(NB.ACT_PICKUP))) }
    if s.has(NB.CTX_FORGE) { out.append(BarAction(label: "Forge", emphasised: true, run: act(NB.ACT_SMITH))) }
    if s.has(NB.CTX_CAN_FIRE) { out.append(BarAction(label: "Fire", emphasised: true, longPress: act(NB.ACT_FIRE_AIM), run: act(NB.ACT_FIRE_NEAREST))) }
    if s.has(NB.CTX_CAN_THROW) { out.append(BarAction(label: "Throw", emphasised: true, run: act(NB.ACT_THROW_NEAREST))) }
    if s.has(NB.CTX_ADJ_CLOSED_DOOR) || s.has(NB.CTX_ADJ_CHEST) { out.append(BarAction(label: "Open", emphasised: true, run: act(NB.ACT_OPEN))) }
    if s.has(NB.CTX_ADJ_SKELETON) { out.append(BarAction(label: "Search", emphasised: true, run: act(NB.ACT_SEARCH))) }
    if s.has(NB.CTX_ADJ_OPEN_DOOR) { out.append(BarAction(label: "Close", emphasised: true, run: act(NB.ACT_CLOSE))) }
    if s.has(NB.CTX_ADJ_TRAP) { out.append(BarAction(label: "Disarm", emphasised: true, run: act(NB.ACT_DISARM))) }
    if s.has(NB.CTX_ADJ_RUBBLE) { out.append(BarAction(label: "Tunnel", emphasised: true, run: act(NB.ACT_TUNNEL))) }
    return out
}

/// Pinned kinds (Pack → an item → Pin to bar): tap uses one, long-press unpins.
func quickActions(_ slots: [QuickSlot], unpin: @escaping (Int) -> Void) -> [BarAction] {
    slots.map { q in
        BarAction(label: "\(q.name) ×\(q.count)", active: q.count > 0, longPress: { unpin(q.kind) }) { if q.count > 0 { Native.requestUseKind(q.kind) } }
    }
}

/// Always present: the turn-by-turn Sil toolkit (the pack is in the panel, and ☰ → Pack can pin it here; unspent
/// experience is the pill in the status, which opens Skills).  `restOptions` opens the rest choices (long press on Rest).  With the
/// d-pad shown its centre key waits (tap) and rests (hold), so Wait and Rest leave the bar and the rest choices move to ☰.
func coreActions(_ s: Status, padShown: Bool, restOptions: @escaping () -> Void) -> [BarAction] {
    var out = padShown ? [] : [BarAction(label: "Rest", longPress: restOptions, run: act(NB.ACT_REST))]
    out.append(BarAction(label: "Explore", longPress: act(NB.ACT_EXPLORE_STEP), run: act(NB.ACT_EXPLORE)))
    if !padShown { out.append(BarAction(label: "Wait", run: act(NB.ACT_HOLD))) }
    out += [
        BarAction(label: s.has(NB.CTX_STEALTH) ? "Stealth ●" : "Stealth", active: s.has(NB.CTX_STEALTH), run: act(NB.ACT_STEALTH)),
        BarAction(label: s.has(NB.CTX_SINGING) ? "Song ♪" : "Song", active: s.has(NB.CTX_SINGING), run: act(NB.ACT_SONG)),
        BarAction(label: "Look", run: { Native.requestLookList() }),
        BarAction(label: "Again", run: act(NB.ACT_REPEAT)),
    ]
    return out
}

/// Less frequent but necessary: behind the ☰ button.  Pack, character, skills and the rest live in the panel (@).
func moreActions(_ s: Status, messages: @escaping () -> Void, toggleMap: @escaping () -> Void, toggleTiles: @escaping () -> Void, tilesOn: Bool, help: @escaping () -> Void,
                 restOptions: (() -> Void)? = nil, tabsOnTop: Bool = false) -> [BarAction] {
    var out = tabsOnTop ? [] : [BarAction(label: "Panel", run: act(NB.ACT_PANEL))]
    if let restOptions { out.append(BarAction(label: "Rest…", run: restOptions)) }
    out.append(BarAction(label: "Pack", pin: NB.ACT_INVENTORY, run: act(NB.ACT_INVENTORY)))
    out.append(BarAction(label: "Map", run: toggleMap))
    if s.has(NB.CTX_DOWN_KNOWN) && !s.has(NB.CTX_DOWN_STAIRS) { out.append(BarAction(label: "Walk to ▼", pin: NB.ACT_TRAVEL_DOWN, run: act(NB.ACT_TRAVEL_DOWN))) }
    if s.has(NB.CTX_UP_KNOWN) && !s.has(NB.CTX_UP_STAIRS) { out.append(BarAction(label: "Walk to ▲", pin: NB.ACT_TRAVEL_UP, run: act(NB.ACT_TRAVEL_UP))) }
    out += [
        BarAction(label: "Messages", run: messages),
        BarAction(label: "Bash", pin: NB.ACT_BASH, run: act(NB.ACT_BASH)),
        BarAction(label: "Tunnel", pin: NB.ACT_TUNNEL, run: act(NB.ACT_TUNNEL)),
        BarAction(label: "Disarm", pin: NB.ACT_DISARM, run: act(NB.ACT_DISARM)),
        BarAction(label: "Exchange", pin: NB.ACT_EXCHANGE, run: act(NB.ACT_EXCHANGE)),
        BarAction(label: "All commands", run: { Native.pushKey(Keys.enter) }),
        BarAction(label: "Help", run: help),
        BarAction(label: "Options", run: act(NB.ACT_OPTIONS)),
        BarAction(label: tilesOn ? "Tiles: on" : "Tiles: off", run: toggleTiles),
        BarAction(label: "Retire character", run: { Native.pushKey(Int(UnicodeScalar("Q").value)) }),
        BarAction(label: "Esc", run: { Native.pushKey(Keys.escape) }),
    ]
    return out
}

struct ActionBar: View {
    let actions: [BarAction]
    let onMore: () -> Void

    @State private var seen: [String] = []

    var body: some View {
        /* Context actions (stairs, Pick up, Fire, doors, traps) come first: when a new one appears, show it. */
        let context = actions.filter(\.emphasised).map(\.label)
        ScrollViewReader { proxy in
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 6) {
                    Color.clear.frame(width: 0, height: 0).id("start")
                    ForEach(actions) { BarButton(action: $0) }
                    Button(action: onMore) { Text("☰").scaledFont(18).padding(.horizontal, 14).frame(minHeight: 40) }
                        .buttonStyle(.plain).background(Color.white.opacity(0.14), in: Capsule())
                }
                .padding(.horizontal, 6).padding(.vertical, 6)
            }
            .onChange(of: context) { new in
                if new.contains(where: { !seen.contains($0) }) { withAnimation { proxy.scrollTo("start", anchor: .leading) } }
                seen = new
            }
        }
        /* A drag along the bar is a scroll: a sign it does not hold what the player wants. */
        .simultaneousGesture(DragGesture(minimumDistance: 12).onEnded { v in
            if abs(v.translation.width) > abs(v.translation.height) { Trace.log("bar.scroll", ("dx", Int(v.translation.width))) }
        })
        .background(Palette.chrome)
    }
}

private struct BarButton: View {
    let action: BarAction

    var body: some View {
        let fill: Color = action.emphasised ? Palette.gold : action.active ? Palette.card : Color.white.opacity(0.14)
        Text(action.label)
            .scaledFont(15, weight: action.emphasised ? .semibold : .regular)
            .foregroundColor(action.emphasised ? Palette.onGold : action.active ? Palette.gold : .white)
            .lineLimit(1)
            .padding(.horizontal, 14).frame(minHeight: 40)
            .background(fill, in: Capsule())
            .overlay { if action.active { Capsule().strokeBorder(Palette.gold, lineWidth: 1.5) } }
            .modifier(HoldMark(shape: Capsule(), enabled: action.longPress != nil))
            .contentShape(Capsule())
            .onTapGesture { Trace.log("bar.tap", ("act", action.label)); action.run() }
            .onLongPressGesture(minimumDuration: 0.5) { Trace.log("bar.longpress", ("act", action.label)); action.longPress?() }
    }
}

/// Grid of the less frequent actions; tapping one runs it and closes the sheet.
struct MoreSheet: View {
    let actions: [BarAction]
    var pinned: [Int] = []
    var onPin: (Int) -> Void = { _ in }
    let dismiss: () -> Void

    var body: some View {
        /* Scrolls when the rows do not all fit above the bottom edge (landscape). */
        ViewThatFits(in: .vertical) {
            grid
            ScrollView { grid }
        }
        .frame(maxWidth: .infinity)
        .background(Color(red: 0x1C / 255, green: 0x1C / 255, blue: 0x1E / 255))
    }

    private var grid: some View {
        VStack(spacing: 8) {
            ForEach(Array(stride(from: 0, to: actions.count, by: 2)), id: \.self) { i in
                HStack(spacing: 8) {
                    ForEach(actions[i..<min(i + 2, actions.count)]) { a in
                        Text((a.pin.map { pinned.contains($0) } == true ? "● " : "") + a.label).lineLimit(1).frame(maxWidth: .infinity, minHeight: 44)
                            .background(Color.white.opacity(0.14), in: RoundedRectangle(cornerRadius: 10))
                            .modifier(HoldMark(shape: RoundedRectangle(cornerRadius: 10), enabled: a.pin != nil))
                            .contentShape(Rectangle())
                            .onTapGesture { Trace.log("menu.pick", ("act", a.label)); dismiss(); a.run() }
                            .onLongPressGesture(minimumDuration: 0.5) { if let id = a.pin { Trace.log("menu.pin", ("act", a.label)); onPin(id) } }
                    }
                    if i + 1 >= actions.count { Spacer().frame(maxWidth: .infinity) }
                }
            }
            if actions.contains(where: { $0.pin != nil }) {
                Text("Long-press a command to pin it to the bar (●).").scaledFont(12).foregroundColor(Palette.sub)
            }
        }
        .padding(16).foregroundColor(.white)
        .frame(maxWidth: .infinity)
    }
}

/// Long press on Rest: the game's rest modes, or a number of turns.
struct RestSheet: View {
    let dismiss: () -> Void
    private static let choices: [(String, Int)] = [
        ("As needed", NB.REST_AS_NEEDED), ("Until health and voice are full", NB.REST_HP_AND_VOICE),
        ("Until health or voice is full", NB.REST_HP_OR_VOICE), ("10 turns", 10), ("50 turns", 50), ("100 turns", 100),
    ]

    var body: some View {
        ViewThatFits(in: .vertical) {
            choicesList
            ScrollView { choicesList }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(red: 0x1C / 255, green: 0x1C / 255, blue: 0x1E / 255))
    }

    private var choicesList: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("Rest").font(.headline).padding(.bottom, 8)
            ForEach(Self.choices, id: \.0) { label, turns in
                Button { dismiss(); Native.requestRest(turns) } label: {
                    Text(label).frame(maxWidth: .infinity, alignment: .leading).padding(.vertical, 12)
                }.buttonStyle(.plain)
            }
            Button("Cancel", action: dismiss).padding(.top, 8)
        }
        .padding(16).foregroundColor(.white)
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}
