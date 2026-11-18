import SwiftUI

/// One tappable action (ActionBar.kt).
struct BarAction: Identifiable {
    let id = UUID()
    let label: String
    var emphasised = false
    var active = false
    var longPress: (() -> Void)?
    let run: () -> Void
}

private func act(_ id: Int) -> () -> Void { { Native.requestAction(id) } }

/// Context actions for where the player stands, in the order a Sil player reaches for them.
func contextActions(_ s: Status) -> [BarAction] {
    var out = [BarAction]()
    if s.has(NB.CTX_DOWN_STAIRS) { out.append(BarAction(label: "Down ▼", emphasised: true, run: act(NB.ACT_GO_DOWN))) }
    if s.has(NB.CTX_UP_STAIRS) { out.append(BarAction(label: "Up ▲", emphasised: true, run: act(NB.ACT_GO_UP))) }
    if s.has(NB.CTX_FLOOR_ITEMS) { out.append(BarAction(label: "Pick up", emphasised: true, run: act(NB.ACT_PICKUP))) }
    if s.has(NB.CTX_FORGE) { out.append(BarAction(label: "Forge", emphasised: true, run: act(NB.ACT_SMITH))) }
    if s.has(NB.CTX_CAN_FIRE) { out.append(BarAction(label: "Fire", emphasised: true, run: act(NB.ACT_FIRE_NEAREST))) }
    if s.has(NB.CTX_CAN_THROW) { out.append(BarAction(label: "Throw", emphasised: true, run: act(NB.ACT_THROW_NEAREST))) }
    if s.has(NB.CTX_ADJ_CLOSED_DOOR) || s.has(NB.CTX_ADJ_CHEST) { out.append(BarAction(label: "Open", emphasised: true, run: act(NB.ACT_OPEN))) }
    if s.has(NB.CTX_ADJ_OPEN_DOOR) { out.append(BarAction(label: "Close", emphasised: true, run: act(NB.ACT_CLOSE))) }
    if s.has(NB.CTX_ADJ_TRAP) { out.append(BarAction(label: "Disarm", emphasised: true, run: act(NB.ACT_DISARM))) }
    if s.has(NB.CTX_ADJ_RUBBLE) { out.append(BarAction(label: "Tunnel", emphasised: true, run: act(NB.ACT_TUNNEL))) }
    return out
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
    if s.has(NB.CTX_DOWN_KNOWN) && !s.has(NB.CTX_DOWN_STAIRS) { out.append(BarAction(label: "Walk to ▼", run: act(NB.ACT_TRAVEL_DOWN))) }
    if s.has(NB.CTX_UP_KNOWN) && !s.has(NB.CTX_UP_STAIRS) { out.append(BarAction(label: "Walk to ▲", run: act(NB.ACT_TRAVEL_UP))) }
    out += [
        BarAction(label: "Messages", run: messages),
        BarAction(label: "Bash", run: act(NB.ACT_BASH)),
        BarAction(label: "Tunnel", run: act(NB.ACT_TUNNEL)),
        BarAction(label: "Disarm", run: act(NB.ACT_DISARM)),
        BarAction(label: "Exchange", run: act(NB.ACT_EXCHANGE)),
        BarAction(label: "All commands", run: { Native.pushKey(Keys.enter) }),
        BarAction(label: "Help", run: help),
        BarAction(label: "Options", run: act(NB.ACT_OPTIONS)),
        BarAction(label: tilesOn ? "Tiles: on" : "Tiles: off", run: toggleTiles),
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
                Button(action: onMore) { Text("☰").font(.system(size: 18)).padding(.horizontal, 14).frame(minHeight: 40) }
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
            .font(.system(size: 15, weight: action.emphasised ? .semibold : .regular))
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
    let dismiss: () -> Void

    var body: some View {
        VStack(spacing: 8) {
            ForEach(Array(stride(from: 0, to: actions.count, by: 2)), id: \.self) { i in
                HStack(spacing: 8) {
                    ForEach(actions[i..<min(i + 2, actions.count)]) { a in
                        Button { dismiss(); a.run() } label: {
                            Text(a.label).lineLimit(1).frame(maxWidth: .infinity, minHeight: 44)
                        }
                        .buttonStyle(.plain).background(Color.white.opacity(0.14), in: RoundedRectangle(cornerRadius: 10))
                    }
                    if i + 1 >= actions.count { Spacer().frame(maxWidth: .infinity) }
                }
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
