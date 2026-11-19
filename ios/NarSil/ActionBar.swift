import SwiftUI

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
    NB.ACT_BASH: "Bash", NB.ACT_TUNNEL: "Tunnel", NB.ACT_DISARM: "Disarm", NB.ACT_EXCHANGE: "Exchange",
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
    if s.has(NB.CTX_FLOOR_ITEMS) { out.append(BarAction(label: "Pick up", emphasised: true, run: act(NB.ACT_PICKUP))) }
    if s.has(NB.CTX_FORGE) { out.append(BarAction(label: "Forge", emphasised: true, run: act(NB.ACT_SMITH))) }
    if s.has(NB.CTX_CAN_FIRE) { out.append(BarAction(label: "Fire", emphasised: true, run: act(NB.ACT_FIRE_NEAREST))) }
    if s.has(NB.CTX_CAN_FLETCH) { out.append(BarAction(label: "Fletch", run: act(NB.ACT_FLETCH))) }
    if s.has(NB.CTX_CAN_THROW) { out.append(BarAction(label: "Throw", emphasised: true, run: act(NB.ACT_THROW_NEAREST))) }
    if s.has(NB.CTX_ADJ_CLOSED_DOOR) || s.has(NB.CTX_ADJ_CHEST) { out.append(BarAction(label: "Open", emphasised: true, run: act(NB.ACT_OPEN))) }
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

/// Always present: the turn-by-turn Sil toolkit.  `restOptions` opens the rest choices (long press on Rest).
func coreActions(_ s: Status, restOptions: @escaping () -> Void) -> [BarAction] {
    var out = [
        BarAction(label: "Rest", longPress: restOptions, run: act(NB.ACT_REST)),
        BarAction(label: "Wait", run: act(NB.ACT_HOLD)),
        BarAction(label: s.has(NB.CTX_STEALTH) ? "Stealth ●" : "Stealth", active: s.has(NB.CTX_STEALTH), run: act(NB.ACT_STEALTH)),
        BarAction(label: s.has(NB.CTX_SINGING) ? "Song ♪" : "Song", active: s.has(NB.CTX_SINGING), run: act(NB.ACT_SONG)),
        BarAction(label: "Pack", run: act(NB.ACT_INVENTORY)),
        BarAction(label: "Look", run: { Native.requestLookList() }),
        BarAction(label: "Again", run: act(NB.ACT_REPEAT)),
    ]
    if s.newExp >= 100 { out.append(BarAction(label: "Skills (\(s.newExp))", active: true, run: act(NB.ACT_SKILLS))) }
    return out
}

/// Less frequent but necessary: behind the ☰ button.  Pack, character, skills and the rest live in the panel (@).
func moreActions(_ s: Status, messages: @escaping () -> Void, toggleMap: @escaping () -> Void, toggleTiles: @escaping () -> Void, tilesOn: Bool, help: @escaping () -> Void) -> [BarAction] {
    var out = [
        BarAction(label: "Panel", run: act(NB.ACT_PANEL)),
        BarAction(label: "Map", run: toggleMap),
    ]
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

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 6) {
                ForEach(actions) { BarButton(action: $0) }
                Button(action: onMore) { Text("☰").scaledFont(18).padding(.horizontal, 14).frame(minHeight: 40) }
                    .buttonStyle(.plain).background(Color.white.opacity(0.14), in: Capsule())
            }
            .padding(.horizontal, 6).padding(.vertical, 6)
        }
        .background(Color(red: 0x10 / 255, green: 0x10 / 255, blue: 0x12 / 255))
    }
}

private struct BarButton: View {
    let action: BarAction

    var body: some View {
        let fill: Color = action.emphasised ? .accentColor : action.active ? Color(red: 0x3A / 255, green: 0x5F / 255, blue: 0x8A / 255) : Color.white.opacity(0.14)
        Text(action.label)
            .scaledFont(15, weight: action.emphasised ? .semibold : .regular)
            .foregroundColor(.white)
            .lineLimit(1)
            .padding(.horizontal, 14).frame(minHeight: 40)
            .background(fill, in: Capsule())
            .contentShape(Capsule())
            .onTapGesture { action.run() }
            .onLongPressGesture(minimumDuration: 0.5) { action.longPress?() }
    }
}

/// Grid of the less frequent actions; tapping one runs it and closes the sheet.
struct MoreSheet: View {
    let actions: [BarAction]
    var pinned: [Int] = []
    var onPin: (Int) -> Void = { _ in }
    let dismiss: () -> Void

    var body: some View {
        VStack(spacing: 8) {
            ForEach(Array(stride(from: 0, to: actions.count, by: 2)), id: \.self) { i in
                HStack(spacing: 8) {
                    ForEach(actions[i..<min(i + 2, actions.count)]) { a in
                        Text((a.pin.map { pinned.contains($0) } == true ? "● " : "") + a.label).lineLimit(1).frame(maxWidth: .infinity, minHeight: 44)
                            .background(Color.white.opacity(0.14), in: RoundedRectangle(cornerRadius: 10))
                            .contentShape(Rectangle())
                            .onTapGesture { dismiss(); a.run() }
                            .onLongPressGesture(minimumDuration: 0.5) { if let id = a.pin { onPin(id) } }
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
        .background(Color(red: 0x1C / 255, green: 0x1C / 255, blue: 0x1E / 255))
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
        .background(Color(red: 0x1C / 255, green: 0x1C / 255, blue: 0x1E / 255))
    }
}
