import SwiftUI

/// Stages whose options are chosen once (race, house, sex): a random pick and birth options are offered.
private let choiceStages: Set<String> = ["race", "house", "sex"]

/// Birth, death, welcome and tutorial-note screens (narsil_birth.c), ported from ScreenSheet.kt.
struct ScreenSheet: View {
    let p: ScreenPrompt
    let answer: (Int) -> Void
    var onScum: (() -> Void)?

    var body: some View {
        switch p.stage {
        case "welcome": Welcome(p: p, answer: answer)
        case "note": NoteCard(p: p, answer: answer)
        case "poem": Poem(p: p, answer: answer)
        default: Birth(p: p, answer: answer, onScum: onScum)
        }
    }
}

private struct Birth: View {
    let p: ScreenPrompt
    let answer: (Int) -> Void
    let onScum: (() -> Void)?
    private var canGoBack: Bool { !["death", "race", "quickstart"].contains(p.stage) }

    var body: some View {
        ZStack {
            Palette.screen.ignoresSafeArea()
            VStack(spacing: 0) {
                ScreenHeader(title: p.title, back: canGoBack ? { answer(-1) } : nil,
                             accessory: choiceStages.contains(p.stage) ? AnyView(Button("Random") { answer(NB.SCREEN_RANDOM) }.padding(.trailing, 12)) : nil)
                ScrollView {
                    VStack(alignment: .leading, spacing: 0) {
                        ForEach(Array(p.paragraphs.enumerated()), id: \.offset) { _, t in
                            Text(t).scaledFont(15).foregroundColor(Palette.body)
                                .padding(.horizontal, 16).padding(.vertical, 4).frame(maxWidth: .infinity, alignment: .leading)
                        }
                        if p.stage == "stats" {
                            Text("Points left: \(p.pointsLeft) of \(p.pointsTotal)").scaledFont(15).foregroundColor(Palette.gold)
                                .padding(.horizontal, 16).padding(.vertical, 8)
                            ForEach(p.stats, id: \.index) { s in StatRow(s: s, answer: answer) }
                            if !p.skills.isEmpty { Figures(p: p) }
                        } else {
                            ForEach(p.options, id: \.index) { o in OptionCard(o: o, answer: answer) }
                        }
                        Color.clear.frame(height: 12)
                    }
                }
                HStack(spacing: 8) {
                    Spacer()
                    if choiceStages.contains(p.stage) || p.stage == "quickstart" {
                        Button("Birth options") { answer(NB.SCREEN_OPTIONS) }
                    }
                    if p.stage == "stats" {
                        Button("Reset") { answer(NB.SKILL_RESET) }
                        Button("Accept") { answer(NB.SKILL_ACCEPT) }.buttonStyle(GoldButtonStyle())
                    }
                    if p.stage == "death", FileManager.default.fileExists(atPath: Saves.characterDump.path) {
                        Button("Share dump") { ShareSheet.present([Saves.characterDump]) }
                    }
                    if p.stage == "death", let onScum { Button("Restore scum save", action: onScum).buttonStyle(GoldButtonStyle()) }
                    if p.stage == "text" { Button("Done") { answer(-1) }.buttonStyle(GoldButtonStyle()) }
                }
                .padding(12)
            }
        }
        .preferredColorScheme(.dark)
    }
}

private struct OptionCard: View {
    let o: ScreenOption
    let answer: (Int) -> Void

    var body: some View {
        let enabled = o.state != 0
        Button { answer(o.index) } label: {
            VStack(alignment: .leading, spacing: 4) {
                Text(o.label).scaledFont(17, weight: .semibold).foregroundColor(enabled ? .white : Color(rgb: 0x55555A))
                if !o.summary.isEmpty { Text(o.summary).scaledFont(13).foregroundColor(Palette.summary) }
                if !o.description.isEmpty { Text(o.description).scaledFont(13).foregroundColor(Palette.sub) }
            }
            .frame(maxWidth: .infinity, minHeight: 48, alignment: .leading).padding(14)
            .cardSurface(Palette.card)
        }
        .buttonStyle(.plain).disabled(!enabled)
        .padding(.horizontal, 12).padding(.vertical, 5)
    }
}

private struct StatRow: View {
    let s: BirthStat
    let answer: (Int) -> Void

    var body: some View {
        HStack(spacing: 8) {
            VStack(alignment: .leading) {
                Text(s.name).scaledFont(16).foregroundColor(.white)
                Text(s.spent > 0 ? "\(s.spent) point\(s.spent == 1 ? "" : "s") spent" : "").scaledFont(12).foregroundColor(Palette.sub)
            }
            Spacer()
            Text("\(s.value)").scaledFont(20, weight: .semibold).foregroundColor(.white).padding(.trailing, 4)
            Button("−") { answer(NB.SKILL_SELL + s.index) }.buttonStyle(.bordered).disabled(!s.canSell)
            Button("+ \(s.nextCost)") { answer(NB.SKILL_BUY + s.index) }.buttonStyle(GoldButtonStyle()).disabled(!s.canBuy)
        }
        .frame(minHeight: 56).padding(.horizontal, 16)
    }
}

/// First signed number in a figure ("(+3, 1d8)" → 3, "[+2, 1-4]" → 2).
private func lead(_ v: String) -> Int? {
    guard let r = v.range(of: "-?[0-9]+", options: .regularExpression) else { return nil }
    return Int(v[r])
}

/// Skills and combat as the stats now give them; what the last purchase changed is coloured.
private struct Figures: View {
    let p: ScreenPrompt
    @State private var previous: [String: String] = [:]

    private var all: [NamedValue] { p.skills + p.combat }
    private func delta(_ f: NamedValue) -> Int { previous[f.name].map { (lead(f.value) ?? 0) - (lead($0) ?? 0) } ?? 0 }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("Skills").scaledFont(14).foregroundColor(Palette.gold)
            flow(p.skills)
            Text("Combat").scaledFont(14).foregroundColor(Palette.gold)
            flow(p.combat)
        }
        .padding(12).frame(maxWidth: .infinity, alignment: .leading)
        .cardSurface(Palette.card)
        .padding(.horizontal, 12).padding(.vertical, 8)
        .onChange(of: all) { new in previous = Dictionary(new.map { ($0.name, $0.value) }, uniquingKeysWith: { $1 }) }
        .onAppear { previous = Dictionary(all.map { ($0.name, $0.value) }, uniquingKeysWith: { $1 }) }
    }

    /// Wrapped rows of figures (two per row is enough at phone width).
    private func flow(_ items: [NamedValue]) -> some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 150), alignment: .leading)], alignment: .leading, spacing: 4) {
            ForEach(items, id: \.name) { f in figure(f) }
        }
    }

    private func figure(_ f: NamedValue) -> some View {
        let d = delta(f)
        let tint: Color = d > 0 ? Palette.good : d < 0 ? Palette.bad : .white
        return HStack(spacing: 4) {
            Text(f.name).scaledFont(13).foregroundColor(Palette.sub)
            Text(f.value).scaledFont(14, weight: .semibold).foregroundColor(tint)
            if d != 0 { Text(d > 0 ? "▲" : "▼").scaledFont(11).foregroundColor(tint) }
        }
    }
}

/// The splash screen: the verse, the welcome, the version, and Continue or New game.  Landscape: two columns, the button on the left.
private struct Welcome: View {
    let p: ScreenPrompt
    let answer: (Int) -> Void

    private var title: some View {
        Text(p.title).scaledFont(34, weight: .semibold).tracking(2).foregroundColor(GameLauncher.game == .silq ? Color(rgb: 0x7AD3F3) : Palette.gold)
    }
    private var verse: some View {
        ForEach(Array(p.verse.enumerated()), id: \.offset) { _, v in
            Text(v).font(.system(size: 16).italic()).foregroundColor(Palette.verse).multilineTextAlignment(.center)
        }
    }
    private var welcome: some View {
        ForEach(Array(p.paragraphs.dropLast().enumerated()), id: \.offset) { _, t in
            Text(t).scaledFont(16).foregroundColor(Palette.body).multilineTextAlignment(.center).padding(.vertical, 4)
        }
    }
    @ViewBuilder private func begin(_ inset: CGFloat) -> some View {
        if let first = p.options.first {
            Button { answer(first.index) } label: { Text(first.label).scaledFont(17).frame(maxWidth: .infinity, minHeight: 44) }
                .buttonStyle(GoldButtonStyle()).padding(.horizontal, inset)
        }
    }
    @ViewBuilder private var others: some View {
        ForEach(Array(p.options.dropFirst()), id: \.index) { o in
            Button(o.label) { answer(o.index) }.padding(.top, 8)
            if !o.summary.isEmpty { Text(o.summary).scaledFont(12).foregroundColor(Palette.sub).multilineTextAlignment(.center) }
        }
        if let last = p.paragraphs.last { Text(last).scaledFont(12).foregroundColor(Palette.sub).padding(.top, 16) }
    }

    var body: some View {
        GeometryReader { g in
            ZStack {
                Palette.splash.ignoresSafeArea()
                if g.size.width > g.size.height {
                    HStack(spacing: 32) {
                        ScrollView { VStack(spacing: 4) { title; Color.clear.frame(height: 12); verse; Color.clear.frame(height: 16); begin(24); others }.frame(maxWidth: .infinity, minHeight: g.size.height) }
                        ScrollView { VStack(spacing: 4) { welcome }.frame(maxWidth: .infinity, minHeight: g.size.height) }
                    }.padding(.horizontal, 28)
                } else {
                    ScrollView {
                        VStack(spacing: 4) {
                            title.padding(.top, 28); verse
                            Color.clear.frame(height: 20); welcome
                            Color.clear.frame(height: 28); begin(48); others
                        }
                        .padding(.horizontal, 28).padding(.bottom, 32).frame(maxWidth: .infinity)
                    }
                }
            }
        }
        .preferredColorScheme(.dark)
    }
}

/// A poem (entering the dungeon, the throne room): the verse with its indentation, then Continue.  Landscape: verse beside the button.
private struct Poem: View {
    let p: ScreenPrompt
    let answer: (Int) -> Void

    private var title: some View {
        Text(p.title).scaledFont(24, weight: .semibold).tracking(1).foregroundColor(GameLauncher.game == .silq ? Color(rgb: 0x7AD3F3) : Palette.gold)
    }
    private var verse: some View {
        VStack(alignment: .leading, spacing: 4) {
            ForEach(Array(p.verse.enumerated()), id: \.offset) { _, v in
                let line = v.drop(while: { $0 == " " })
                Text(String(line)).font(.system(size: 16).italic()).foregroundColor(Palette.verse)
                    .padding(.leading, CGFloat(v.count - line.count) * 6)
            }
        }
    }
    private var next: some View {
        Button { answer(p.options.first?.index ?? 0) } label: {
            Text(p.options.first?.label ?? "Continue").scaledFont(17).frame(maxWidth: .infinity, minHeight: 44)
        }.buttonStyle(GoldButtonStyle())
    }

    var body: some View {
        GeometryReader { g in
            ZStack {
                Palette.splash.ignoresSafeArea()
                if g.size.width > g.size.height {
                    HStack(spacing: 32) {
                        ScrollView { verse.frame(maxWidth: .infinity, minHeight: g.size.height) }
                        VStack(spacing: 24) { title; next }.frame(width: g.size.width * 0.3)
                    }.padding(.horizontal, 28)
                } else {
                    ScrollView {
                        VStack(spacing: 0) {
                            title.padding(.top, 32).padding(.bottom, 24)
                            verse
                            next.padding(.horizontal, 64).padding(.top, 36)
                        }
                        .padding(.horizontal, 24).padding(.bottom, 32).frame(maxWidth: .infinity)
                    }
                }
            }
        }
        .preferredColorScheme(.dark)
    }
}

/// A tutorial note: a card at the bottom over the map, which stays visible.
private struct NoteCard: View {
    let p: ScreenPrompt
    let answer: (Int) -> Void

    var body: some View {
        VStack {
            Spacer()
            VStack(alignment: .leading, spacing: 8) {
                Text(p.title).scaledFont(18, weight: .semibold).foregroundColor(Palette.gold)
                ScrollView {
                    VStack(alignment: .leading, spacing: 6) {
                        ForEach(Array(p.paragraphs.enumerated()), id: \.offset) { _, t in
                            /* Diagrams (the direction keys) keep their spacing. */
                            let art = t.contains("  ") || t.contains("\\") || t.contains("|")
                            Text(t).font(art ? .system(size: 13, design: .monospaced) : .system(size: 15)).foregroundColor(Palette.body)
                        }
                    }.frame(maxWidth: .infinity, alignment: .leading)
                }
                .frame(maxHeight: 360)
                Button(p.options.first?.label ?? "Continue") { answer(0) }.buttonStyle(GoldButtonStyle()).frame(maxWidth: .infinity, alignment: .trailing)
            }
            .padding(16).cardSurface(Palette.card, radius: 16).padding(.horizontal, 8)
        }
        .background(Color.black.opacity(0.33).ignoresSafeArea())
        .preferredColorScheme(.dark)
    }
}
