import SwiftUI

/* The status strip, message strip and their pieces, below and above the map (StatusViews.kt). */

private let stripBackground = Color(red: 0x14 / 255, green: 0x14 / 255, blue: 0x14 / 255)
private let amber = Color(red: 1, green: 0xCA / 255, blue: 0x28 / 255)
private let red = Color(red: 0xEF / 255, green: 0x53 / 255, blue: 0x50 / 255)
private let grey = Color(red: 0xB0 / 255, green: 0xB0 / 255, blue: 0xB0 / 255)

private func color(_ rgb: UInt32) -> Color {
    Color(red: Double((rgb >> 16) & 0xFF) / 255, green: Double((rgb >> 8) & 0xFF) / 255, blue: Double(rgb & 0xFF) / 255)
}

/// The last few messages (older ones dimmer, in their game colours), plus the textui's top line when it holds
/// something else: a prompt, or -more- (tap = Enter).
struct MessageStrip: View {
    @ObservedObject var game: GameState
    /// The height of the area: as many of the latest messages as fit, the newest at the bottom, older ones running off the top.
    let height: CGFloat
    let onPanel: () -> Void
    var panelButton = true
    let onMessages: () -> Void

    var body: some View {
        let messages = Array(game.messages.suffix(200).reversed())
        let line0 = game.topLine
        let more = line0.contains("-more-")
        /* The textui joins several messages on its top line; only show it when it is something else (a prompt). */
        let banner = (!line0.isEmpty && !more && !game.messages.contains { line0.contains($0.text) }) ? line0 : nil
        let resting = game.status.has(NB.CTX_RESTING)
        HStack(alignment: .top, spacing: 0) {
            /* Newest at the bottom; scrolls up through the older ones, and goes back to the newest when a message arrives. */
            ScrollViewReader { proxy in
                ScrollView(.vertical, showsIndicators: false) {
                    VStack(alignment: .leading, spacing: 0) {
                        ForEach(Array(messages.reversed().enumerated()), id: \.offset) { i, m in
                            let alpha = max(0.45, 1 - 0.12 * Double(messages.count - 1 - i))
                            Text(m.count > 1 ? "\(m.text) (x\(m.count))" : m.text)
                                .scaledFont(14).lineLimit(2)
                                .foregroundColor(color(game.colour(m.attr % 256 % 32)).opacity(alpha))
                        }
                        if let banner { Text(banner).scaledFont(14).lineLimit(2).foregroundColor(Color(red: 1, green: 0xD5 / 255, blue: 0x4F / 255)) }
                        if more { Text("▸ tap to continue").scaledFont(13).foregroundColor(Color(red: 1, green: 0xD5 / 255, blue: 0x4F / 255)) }
                        if resting { Text("Resting…").scaledFont(13).foregroundColor(Color(red: 0x8A / 255, green: 0xB4 / 255, blue: 0xF8 / 255)) }
                        Color.clear.frame(height: 1).id("end")
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 10).padding(.vertical, 4)
                }
                .defaultBottomAnchor()
                .onChange(of: game.messages.count) { _ in proxy.scrollTo("end", anchor: .bottom) }
                .onChange(of: game.messages.last.map { "\($0.text)\($0.count)" }) { _ in proxy.scrollTo("end", anchor: .bottom) }
                .onChange(of: banner) { _ in proxy.scrollTo("end", anchor: .bottom) }
                .onChange(of: more) { _ in proxy.scrollTo("end", anchor: .bottom) }
                .onAppear { proxy.scrollTo("end", anchor: .bottom) }
                .contentShape(Rectangle())
                .onTapGesture { if more { Trace.log("more.tap"); Native.pushKey(Keys.enter) } else { onMessages() } }
            }
            if panelButton { Button(action: onPanel) {
                PanelIcon(index: IconIndex.panel).stroke(style: StrokeStyle(lineWidth: 1.8 * 26 / 24, lineCap: .round, lineJoin: .round))
                    .frame(width: 26, height: 26)
                    .foregroundColor(Color(red: 0xF3 / 255, green: 0xD2 / 255, blue: 0x7A / 255))
                    .frame(width: 40, height: 40)
                    .background(Color(red: 0x26 / 255, green: 0x26 / 255, blue: 0x2B / 255), in: RoundedRectangle(cornerRadius: 10))
            }.buttonStyle(.plain).padding(4).accessibilityLabel("Character panel") }
        }
        .frame(height: height).clipped()
        .background(stripBackground)
    }
}

private extension View {
    /// Keeps a short list at the bottom of its box (a tall one scrolls to its end).
    @ViewBuilder func defaultBottomAnchor() -> some View {
        if #available(iOS 17.0, *) { self.defaultScrollAnchor(.bottom) } else { self }
    }
}

struct StatusStrip: View {
    @ObservedObject var game: GameState
    let stacked: Bool

    var body: some View {
        let s = game.status
        let hp = s.lowHp ? Color(red: 1, green: 0x3B / 255, blue: 0x30 / 255) : Color(red: 0xD0 / 255, green: 0x40 / 255, blue: 0x40 / 255)
        let voice = Color(red: 0x40 / 255, green: 0x80 / 255, blue: 0xE0 / 255)
        VStack(alignment: .leading, spacing: 4) {
            /* Most urgent first: the two meters side by side, then the target, then the chips (danger before information). */
            if stacked {
                HStack(spacing: 10) {
                    Meter(label: "HP", value: s.hp, max: s.maxHp, colour: hp, alarm: s.lowHp)
                    Meter(label: "Voice", value: s.voice, max: s.maxVoice, colour: voice, alarm: false)
                }
            } else {
                HStack(spacing: 12) {
                    Meter(label: "HP", value: s.hp, max: s.maxHp, colour: hp, alarm: s.lowHp)
                    Meter(label: "Voice", value: s.voice, max: s.maxVoice, colour: voice, alarm: false)
                    Text(depthText(s)).scaledFont(13)
                }
            }
            if let foe = s.foe { FoeLine(foe: foe, game: game) }
            Chips(game: game, depth: stacked ? depthText(s) : nil)
        }
        .foregroundColor(.white)
        .padding(.horizontal, 8).padding(.vertical, 4)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(stripBackground)
        .contentShape(Rectangle())
        .onTapGesture { Trace.log("panel.request", ("via", "status")); Native.requestAction(NB.ACT_CHARACTER) }
    }
}

private struct Meter: View {
    let label: String
    let value: Int, max: Int
    let colour: Color
    let alarm: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text("\(label) \(value)/\(max)" + (alarm ? "  ⚠" : ""))
                .scaledFont(12, weight: alarm ? .bold : .regular).foregroundColor(alarm ? colour : .white)
            GeometryReader { g in
                ZStack(alignment: .leading) {
                    Rectangle().fill(colour.opacity(0.25))
                    Rectangle().fill(colour).frame(width: g.size.width * (max > 0 ? min(1, CGFloat(value) / CGFloat(max)) : 0))
                }
            }.frame(height: 6)
        }
    }
}

/// Tracked monster: glyph, name, health, alertness/morale; tap to inspect.
private struct FoeLine: View {
    let foe: Foe
    let game: GameState
    private static let stanceNames = ["", "asleep", "unwary", "mindless", "fleeing", "confident", "aggressive"]

    var body: some View {
        let cell = game.mapCell(x: foe.x, y: foe.y)
        let health = foe.healthPct >= 60 ? Color(red: 0x66 / 255, green: 0xBB / 255, blue: 0x6A / 255)
            : foe.healthPct >= 25 ? amber : red
        let stance = Self.stanceNames.indices.contains(foe.stance) ? Self.stanceNames[foe.stance] : ""
        /* Two lines, so the name has the whole width: the glyph and name, then the health bar and the stance. */
        VStack(alignment: .leading, spacing: 3) {
            HStack(spacing: 8) {
                Text(cell.flatMap { Unicode.Scalar(UInt32($0.glyph)) }.map { String($0) } ?? "?")
                    .scaledFont(16, design: .monospaced).foregroundColor(color(game.colour((cell?.attr ?? 15) % 256 % 32)))
                Text(foe.name).scaledFont(13).lineLimit(2).frame(maxWidth: .infinity, alignment: .leading)
            }
            HStack(spacing: 8) {
                GeometryReader { g in
                    ZStack(alignment: .leading) {
                        Rectangle().fill(health.opacity(0.25))
                        Rectangle().fill(health).frame(width: g.size.width * CGFloat(min(max(foe.healthPct, 0), 100)) / 100)
                    }
                }.frame(height: 6)
                Text(stance + (foe.stance >= 4 ? " \(foe.morale)" : "")).scaledFont(12).lineLimit(1)
                    .foregroundColor(Color(red: 0x90 / 255, green: 0xCA / 255, blue: 0xF9 / 255))
            }
        }
        .padding(.top, 4)
        .contentShape(Rectangle())
        .onTapGesture { Native.requestInspect(x: foe.x, y: foe.y) }
    }
}

/// Experience to spend, the light, the game's own status-line pieces (hunger, fear, …) and monsters in view (StatusViews.kt).
private struct Chips: View {
    @ObservedObject var game: GameState
    let depth: String?

    /// Plain colours (the floor underfoot, a neutral note) carry no urgency.
    private func isGrey(_ rgb: UInt32) -> Bool {
        let r = Int((rgb >> 16) & 0xFF), g = Int((rgb >> 8) & 0xFF), b = Int(rgb & 0xFF)
        return max(r, g, b) - min(r, g, b) < 28
    }

    var body: some View {
        let s = game.status
        let turns = s.lightTurns
        let segments = game.statusSegments().filter { $0.0.range(of: #"^\d+'?\s*(ft)?$"#, options: .regularExpression) == nil }
        let light = segments.first { $0.0.hasPrefix("Light") }
        let others = segments.filter { !$0.0.hasPrefix("Light") }
        /* Information (hunger satisfied, the floor underfoot) goes after what needs attention (poison, fear, and the like). */
        let info = others.filter { $0.0.hasPrefix("Fed") || $0.0.hasPrefix("Full") || isGrey($0.1) }
        let effects = others.filter { !($0.0.hasPrefix("Fed") || $0.0.hasPrefix("Full") || isGrey($0.1)) }
        FlowLayout(spacing: 6) {
            if s.threats > 0 { Chip(icon: IconIndex.threats, text: "\(s.threats) · \(s.threatNearest) away", colour: s.threatsAlert > 0 ? red : grey) { Native.requestLookMonsters() } }
            ForEach(Array(effects.enumerated()), id: \.offset) { _, seg in Chip(text: seg.0, colour: Color(rgb: seg.1)) }
            if light != nil || turns >= 0 {
                let colour = turns >= 0 && turns < 100 ? red : turns >= 0 && turns < 300 ? amber : light.map { Color(rgb: $0.1) } ?? grey
                Chip(icon: IconIndex.light, text: [light?.0, turns >= 0 ? "\(turns)t" : nil].compactMap { $0 }.joined(separator: " · "), colour: colour)
            }
            if s.newExp > 0 { Chip(icon: IconIndex.exp, text: "\(s.newExp)", colour: s.newExp >= 100 ? Color(red: 0x90 / 255, green: 0xCA / 255, blue: 0xF9 / 255) : grey) { Native.requestAction(NB.ACT_SKILLS) } }
            if let depth { Chip(text: depth, colour: Color(white: 0.88)) }
            ForEach(Array(info.enumerated()), id: \.offset) { _, seg in Chip(text: seg.0, colour: Color(rgb: seg.1)) }
        }
    }
}

/// Lays its children out in rows, wrapping when a row is full.
struct FlowLayout: Layout {
    var spacing: CGFloat = 6

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        rows(subviews, width: proposal.width ?? .infinity).size
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        for (i, p) in rows(subviews, width: bounds.width).origins.enumerated() {
            subviews[i].place(at: CGPoint(x: bounds.minX + p.x, y: bounds.minY + p.y), proposal: .unspecified)
        }
    }

    private func rows(_ subviews: Subviews, width: CGFloat) -> (origins: [CGPoint], size: CGSize) {
        var origins = [CGPoint](), x: CGFloat = 0, y: CGFloat = 0, rowH: CGFloat = 0, maxX: CGFloat = 0
        for v in subviews {
            let s = v.sizeThatFits(.unspecified)
            if x > 0 && x + s.width > width { x = 0; y += rowH + spacing; rowH = 0 }
            origins.append(CGPoint(x: x, y: y))
            x += s.width + spacing
            rowH = max(rowH, s.height)
            maxX = max(maxX, x - spacing)
        }
        return (origins, CGSize(width: maxX, height: y + rowH))
    }
}

private struct Chip: View {
    var icon: Int?
    let text: String
    let colour: Color
    var onTap: (() -> Void)?

    var body: some View {
        HStack(spacing: 4) {
            if let icon { PanelIcon(index: icon).stroke(style: StrokeStyle(lineWidth: 1.5, lineCap: .round, lineJoin: .round)).frame(width: 12, height: 12) }
            Text(text).scaledFont(12).lineLimit(1)
        }
        .foregroundColor(colour)
            .padding(.horizontal, 8).padding(.vertical, 3)
            .background(colour.opacity(0.16), in: RoundedRectangle(cornerRadius: 8))
            .onTapGesture { onTap?() }
    }
}

/// The depth, and in NarSil-Q (as in Sil-Q) the minimum depth the depth clock now allows going up to.
func depthText(_ s: Status) -> String {
    GameLauncher.game == .silq && s.minDepth > 0 ? "\(s.depth * 50) ft · min \(s.minDepth * 50) ft" : "\(s.depth * 50) ft"
}


/// A box that scrolls its content up and down, with a fade and ▾ at the bottom while there is more below, so it is clear that there is.
struct ScrollFade<Content: View>: View {
    @ViewBuilder let content: Content
    @State private var boxHeight: CGFloat = 0
    @State private var contentBottom: CGFloat = 0

    var body: some View {
        ScrollView(.vertical, showsIndicators: false) {
            content.background(GeometryReader { c in Color.clear.preference(key: ContentBottomKey.self, value: c.frame(in: .named("scrollFade")).maxY) })
        }
        .coordinateSpace(name: "scrollFade")
        .background(GeometryReader { b in Color.clear.onAppear { boxHeight = b.size.height }.onChange(of: b.size.height) { boxHeight = $0 } })
        .onPreferenceChange(ContentBottomKey.self) { contentBottom = $0 }
        .overlay(alignment: .bottom) {
            if contentBottom > boxHeight + 2 {
                Text("▾").font(.system(size: 13)).foregroundColor(Palette.gold).frame(maxWidth: .infinity).frame(height: 22, alignment: .bottom)
                    .background(LinearGradient(colors: [.clear, Palette.chrome], startPoint: .top, endPoint: .bottom)).allowsHitTesting(false)
            }
        }
    }
}

private struct ContentBottomKey: PreferenceKey {
    static let defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = nextValue() }
}
