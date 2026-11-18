import SwiftUI

/// What is at one grid: the monster (with its recall: what the player has learnt), objects, terrain.
struct InspectSheet: View {
    @ObservedObject var game: GameState
    let x: Int, y: Int
    let monster: Monster?
    let objects: [String]
    let terrain: String?
    let targetable: Bool, isTarget: Bool
    let dismiss: () -> Void

    var body: some View {
        BottomSheet(maxHeight: 600, cancel: dismiss) {
            ScrollView {
                VStack(alignment: .leading, spacing: 10) {
                    if let m = monster {
                        HStack {
                            glyph(size: 28)
                            VStack(alignment: .leading) {
                                Text(m.name).scaledFont(18, weight: .semibold).foregroundColor(.white)
                                if !m.state.isEmpty { Text(m.state).scaledFont(14).foregroundColor(Color(rgb: 0xFFB74D)) }
                            }
                            Spacer()
                            if isTarget { Button("◎ Clear target") { Native.requestTarget(x: -1, y: -1); dismiss() }.tint(Color(rgb: 0xFF8A80)) }
                            else if targetable { Button("Target") { Native.requestTarget(x: x, y: y) }.buttonStyle(.bordered) }
                        }
                        if !m.recall.isEmpty { Text(m.recall).scaledFont(14).foregroundColor(Color(rgb: 0xC8C8CC)) }
                    }
                    ForEach(Array(objects.enumerated()), id: \.offset) { _, o in Text(o).scaledFont(16).foregroundColor(.white) }
                    if let t = terrain { Text(t).scaledFont(14).foregroundColor(Palette.sub) }
                    if monster == nil && objects.isEmpty && terrain == nil { Text("You know nothing about that place.").foregroundColor(Palette.sub) }
                    HStack(spacing: 8) {
                        Spacer()
                        if game.atPrompt {
                            Button("Show path") { dismiss(); Native.requestPreview(x: x, y: y) }.buttonStyle(.bordered)
                            Button("Go there") { dismiss(); Native.requestTravel(x: x, y: y) }.buttonStyle(.bordered)
                        }
                        Button("Close", action: dismiss)
                    }
                }.padding(16)
            }
        }
    }

    private func glyph(size: CGFloat) -> some View {
        let cell = game.mapCell(x: x, y: y)
        return Text(cell.map { glyphString($0.glyph) } ?? " ").scaledFont(size, design: .monospaced)
            .foregroundColor(Color(rgb: cell.map { game.colour($0.attr % 256 % 32) } ?? 0xFFFFFF)).frame(width: size + 6)
    }
}

/// Everything interesting in view, nearest first; tap one to inspect it.
struct LookSheet: View {
    @ObservedObject var game: GameState
    let entries: [LookEntry]
    let dismiss: () -> Void

    var body: some View {
        BottomSheet(maxHeight: 600, cancel: dismiss) {
            VStack(spacing: 0) {
                HStack {
                    Text("In view").scaledFont(17, weight: .semibold).foregroundColor(.white)
                    Spacer()
                    Button("Close", action: dismiss)
                }.padding(.horizontal, 16).padding(.top, 12).padding(.bottom, 4)
                if entries.isEmpty { Text("Nothing of interest.").foregroundColor(Palette.sub).padding(16) }
                ScrollView {
                    LazyVStack(spacing: 0) {
                        ForEach(Array(entries.enumerated()), id: \.offset) { _, e in
                            Button { Native.requestInspect(x: e.x, y: e.y) } label: {
                                HStack(spacing: 8) {
                                    let cell = game.mapCell(x: e.x, y: e.y)
                                    Text(cell.map { glyphString($0.glyph) } ?? " ").scaledFont(24, design: .monospaced)
                                        .foregroundColor(Color(rgb: cell.map { game.colour($0.attr % 256 % 32) } ?? 0xFFFFFF)).frame(width: 30)
                                    Text(e.summary).scaledFont(16).foregroundColor(.white)
                                    Spacer()
                                }.frame(minHeight: 48).padding(.horizontal, 16).contentShape(Rectangle())
                            }.buttonStyle(.plain)
                            Palette.divider.frame(height: 1).padding(.leading, 52)
                        }
                    }
                }
            }
        }
    }
}

/// The message log, newest at the bottom and scrolled there; scroll up for history.
struct MessagesSheet: View {
    @ObservedObject var game: GameState
    let dismiss: () -> Void

    var body: some View {
        BottomSheet(maxHeight: 700, cancel: dismiss) {
            VStack(spacing: 0) {
                HStack {
                    Text("Messages").scaledFont(17, weight: .semibold).foregroundColor(.white)
                    Spacer()
                    Button("Close", action: dismiss)
                }.padding(.horizontal, 16).padding(.top, 12).padding(.bottom, 4)
                if game.messages.isEmpty { Text("No messages yet.").foregroundColor(Palette.sub).padding(16) }
                ScrollViewReader { proxy in
                    ScrollView {
                        LazyVStack(alignment: .leading, spacing: 2) {
                            ForEach(Array(game.messages.enumerated()), id: \.offset) { i, m in
                                Text(m.count > 1 ? "\(m.text) (x\(m.count))" : m.text).scaledFont(15)
                                    .foregroundColor(Color(rgb: game.colour(m.attr % 256 % 32)))
                                    .padding(.horizontal, 16).frame(maxWidth: .infinity, alignment: .leading).id(i)
                            }
                            Color.clear.frame(height: 12)
                        }
                    }
                    .onAppear { if !game.messages.isEmpty { proxy.scrollTo(game.messages.count - 1, anchor: .bottom) } }
                }
            }
        }
    }
}

/// The character sheet: identity, stats, skills, combat, progress, history.
struct CharacterView: View {
    @ObservedObject var game: GameState
    let sections: [SheetSection]
    var actions: [(String, () -> Void)] = []

    var body: some View {
        VStack(spacing: 0) {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    Text(sections.first?.title ?? "Character").scaledFont(18, weight: .semibold).foregroundColor(.white)
                        .padding(.horizontal, 16).padding(.top, 12).padding(.bottom, 4)
                    ForEach(Array(sections.enumerated()), id: \.offset) { i, sec in
                        if i > 0 { Text(sec.title).scaledFont(13).foregroundColor(Palette.sub).padding(.leading, 16).padding(.top, 14).padding(.bottom, 2) }
                        ForEach(Array(sec.rows.enumerated()), id: \.offset) { _, r in
                            HStack {
                                VStack(alignment: .leading) {
                                    Text(r.label).scaledFont(15).foregroundColor(.white)
                                    if !r.detail.isEmpty { Text(r.detail).scaledFont(12).foregroundColor(Color(rgb: 0x8A8A90)) }
                                }
                                Spacer()
                                Text(r.value).scaledFont(15, weight: .semibold).foregroundColor(Color(rgb: game.colour(r.attr % 256 % 32)))
                            }.padding(.horizontal, 16).padding(.vertical, 3)
                        }
                        if let t = sec.text { Text(t).scaledFont(14).foregroundColor(Color(rgb: 0xC8C8CC)).padding(.horizontal, 16).padding(.vertical, 4) }
                    }
                }
            }
            HStack(spacing: 8) {
                ForEach(Array(actions.enumerated()), id: \.offset) { _, a in
                    Button { a.1() } label: { Text(a.0).frame(maxWidth: .infinity) }.buttonStyle(.bordered)
                }
            }.padding(12)
        }
    }
}
