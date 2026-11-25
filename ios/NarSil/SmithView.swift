import SwiftUI

/// Smithing option states (narsil_smith.c's enum opt_state).
private enum Opt { static let off = 0, on = 1, maybe = 2, chosen = 3, dear = 4, artistry = 5 }

private let bg = Palette.screen
private let cardHi = Palette.raised
private let line = Color(rgb: 0x2E2E34)
private let text = Palette.text
private let dim = Palette.dim
private let ok = Palette.good
private let warn = Palette.warn
private let bad = Palette.bad
private let chosenColour = Palette.summary

private let viewNames = ["tval": "Base item", "sval": "Base item", "special": "Enchant", "artefact": "Artifice",
                        "property": "Artifice", "ability": "Artifice", "numbers": "Numbers", "melt": "Melt"]

/// The smithing screen (narsil_smith.c): the design pinned on top, then the current menu.  On the main menu the
/// steps are tiles and "Make it" is the button at the bottom.  Back returns to the previous menu.
struct SmithView: View {
    let prompt: SmithPrompt
    let answer: (Int) -> Void

    private var main: Bool { prompt.view == "main" }

    var body: some View {
        let make = main ? prompt.options.first { $0.label == "Make it" } : nil
        VStack(spacing: 0) {
            ScreenHeader(title: prompt.title, subtitle: main ? nil : viewNames[prompt.view].map { "Smithing · \($0)" }, back: main ? nil : { answer(-1) })
            ForEach(Array(prompt.notes.enumerated()), id: \.offset) { _, n in banner(n) }
            DesignCard(item: prompt.item, costs: prompt.costs, description: prompt.description)
            ScrollView {
                LazyVStack(spacing: 0) {
                    if main {
                        ForEach(Array(chunks(prompt.options.filter { $0.index != make?.index }, 2).enumerated()), id: \.offset) { _, row in tileRow(row) }
                    } else if prompt.view == "numbers" {
                        ForEach(Array(chunks(prompt.options, 2).enumerated()), id: \.offset) { _, pair in numbersRow(pair) }
                    } else {
                        ForEach(prompt.options, id: \.index) { o in optionRow(o, toggles: prompt.view == "property" || prompt.view == "ability") }
                    }
                    Color.clear.frame(height: 12)
                }
            }
            if let make { makeBar(make) }
        }
        .id("\(prompt.view)/\(prompt.title)")
    }

    private func chunks<T>(_ a: [T], _ n: Int) -> [[T]] { stride(from: 0, to: a.count, by: n).map { Array(a[$0..<min($0 + n, a.count)]) } }

    private func banner(_ t: String) -> some View {
        Text(t).scaledFont(13).foregroundColor(warn).padding(.horizontal, 10).padding(.vertical, 6).frame(maxWidth: .infinity, alignment: .leading)
            .background(warn.opacity(0.10)).clipShape(RoundedRectangle(cornerRadius: 8)).padding(.horizontal, 12).padding(.vertical, 2)
    }

    private func colour(_ state: Int) -> Color {
        switch state {
        case Opt.off: return dim
        case Opt.maybe: return warn
        case Opt.chosen: return chosenColour
        case Opt.dear: return Palette.sub
        case Opt.artistry: return bad
        default: return text
        }
    }

    @ViewBuilder private func badge(_ state: Int) -> some View {
        switch state {
        case Opt.maybe: Pill(text: "needs ability", colour: warn)
        case Opt.dear: Pill(text: "can't afford", colour: Palette.sub)
        case Opt.artistry: Pill(text: "needs Artistry", colour: bad)
        default: EmptyView()
        }
    }

    private func tileRow(_ row: [SmithOption]) -> some View {
        HStack(alignment: .top, spacing: 0) {
            ForEach(row, id: \.index) { o in tile(o).padding(4) }
            if row.count == 1 { Color.clear.frame(maxWidth: .infinity) }
        }.padding(.horizontal, 8)
    }

    private func tile(_ o: SmithOption) -> some View {
        let enabled = o.state != Opt.off
        return Button { answer(o.index) } label: {
            VStack(alignment: .leading, spacing: 4) {
                Text(o.label).scaledFont(16, weight: .semibold).foregroundColor(colour(o.state))
                if !o.detail.isEmpty { Text(o.detail).scaledFont(12).foregroundColor(enabled ? Palette.sub : dim).lineLimit(3) }
                badge(o.state)
            }
            .frame(maxWidth: .infinity, minHeight: 92, alignment: .topLeading).padding(12)
            .cardSurface(enabled ? cardHi : Palette.card).contentShape(Rectangle())
        }.buttonStyle(.plain).disabled(!enabled)
    }

    private func makeBar(_ o: SmithOption) -> some View {
        let enabled = o.state != Opt.off
        return VStack(spacing: 6) {
            if !enabled && !o.detail.isEmpty { Text(o.detail).scaledFont(12).foregroundColor(Palette.sub).frame(maxWidth: .infinity, alignment: .leading) }
            Button { answer(o.index) } label: {
                Text("Make it").scaledFont(17, weight: .semibold).foregroundColor(Color(rgb: 0x241A00)).frame(maxWidth: .infinity, minHeight: 52)
                    .background(Palette.gold.opacity(enabled ? 1 : 0.4)).clipShape(RoundedRectangle(cornerRadius: 10))
            }.buttonStyle(.plain).disabled(!enabled)
        }.padding(.horizontal, 12).padding(.vertical, 10).background(Color(rgb: 0x17171A))
    }

    private func optionRow(_ o: SmithOption, toggles: Bool) -> some View {
        let enabled = o.state != Opt.off, chosen = o.state == Opt.chosen
        return Button { answer(o.index) } label: {
            HStack(spacing: 10) {
                if toggles {
                    Text(chosen ? "✓" : "").scaledFont(12, weight: .bold).foregroundColor(bg).frame(width: 20, height: 20)
                        .background(Circle().fill(chosen ? chosenColour : Color.clear))
                        .overlay(Circle().stroke(enabled ? Palette.sub : dim, lineWidth: chosen ? 0 : 1.5))
                }
                VStack(alignment: .leading, spacing: 2) {
                    Text(o.label).scaledFont(15, weight: chosen ? .semibold : .regular).foregroundColor(colour(o.state))
                    if !o.detail.isEmpty { Text(o.detail).scaledFont(12).foregroundColor(Palette.sub).lineLimit(3) }
                }
                Spacer()
                badge(o.state)
            }
            .frame(minHeight: 52).padding(.horizontal, 12).padding(.vertical, 8)
            .cardSurface(chosen ? chosenColour.opacity(0.10) : Palette.card).contentShape(Rectangle())
        }.buttonStyle(.plain).disabled(!enabled).padding(.horizontal, 12).padding(.vertical, 3)
    }

    /// An increase/decrease pair: "Attack bonus  +3  [−] [+]".
    private func numbersRow(_ pair: [SmithOption]) -> some View {
        let inc = pair[0], dec = pair.count > 1 ? pair[1] : nil
        let name = inc.label.hasPrefix("Increase ") ? String(inc.label.dropFirst(9)).capitalizedFirst : inc.label
        let usable = inc.state != Opt.off || (dec.map { $0.state != Opt.off } ?? false)
        return HStack(spacing: 8) {
            VStack(alignment: .leading) {
                Text(name).scaledFont(15).foregroundColor(usable ? text : dim)
                HStack(spacing: 4) {
                    if let d = dec, d.state != Opt.on && d.state != Opt.off { badge(d.state) }
                    if inc.state != Opt.on && inc.state != Opt.off { badge(inc.state) }
                }
            }
            Spacer()
            Text(inc.detail).scaledFont(17, weight: .semibold).foregroundColor(text).padding(.horizontal, 4)
            if let d = dec { Button("−") { answer(d.index) }.buttonStyle(.bordered).tint(colour(d.state)).disabled(d.state == Opt.off) }
            Button("+") { answer(inc.index) }.buttonStyle(.bordered).tint(colour(inc.state)).disabled(inc.state == Opt.off)
        }
        .frame(minHeight: 56).padding(.horizontal, 12)
        .cardSurface(Palette.card).padding(.horizontal, 12).padding(.vertical, 3)
    }
}

private extension String {
    var capitalizedFirst: String { prefix(1).uppercased() + dropFirst() }
}

/// A small coloured label.
private struct Pill: View {
    let text: String
    let colour: Color
    var body: some View {
        Text(text).scaledFont(11).foregroundColor(colour).lineLimit(1).padding(.horizontal, 6).padding(.vertical, 2)
            .background(colour.opacity(0.14)).clipShape(RoundedRectangle(cornerRadius: 6))
    }
}

private struct DesignCard: View {
    let item: SmithItem?
    let costs: [SmithCost]
    let description: [String]
    @State private var expanded = false

    var body: some View {
        Group {
            if let item {
                VStack(alignment: .leading, spacing: 8) {
                    Text(item.name).scaledFont(17, weight: .semibold).foregroundColor(text)
                    meter(item)
                    HStack {
                        Text("Difficulty \(item.difficulty)").scaledFont(13, weight: .semibold).foregroundColor(tone(item))
                        Text("  ·  your skill \(item.max)").scaledFont(13).foregroundColor(Palette.sub)
                        Spacer()
                        Text(item.turnsLeft > 0 ? "\(item.turnsLeft) of \(item.turns) turns left" : "\(item.turns) turns").scaledFont(13).foregroundColor(Palette.sub)
                    }
                    if item.drains { Text("Beyond your skill: making it drains Smithing.").scaledFont(12).foregroundColor(chosenColour) }
                    if !costs.isEmpty {
                        LazyVGrid(columns: [GridItem(.adaptive(minimum: 110), alignment: .leading)], alignment: .leading, spacing: 4) {
                            ForEach(Array(costs.enumerated()), id: \.offset) { _, c in costPill(c) }
                        }
                    }
                    if !description.isEmpty {
                        if expanded { ForEach(Array(description.enumerated()), id: \.offset) { _, d in Text(d).scaledFont(13).foregroundColor(Palette.body) } }
                        else { Text("\(description[0])  ▸").scaledFont(13).foregroundColor(Palette.sub).lineLimit(1) }
                    }
                }.padding(14)
            } else {
                Text("Choose a base item to begin.").scaledFont(14).foregroundColor(Palette.sub).padding(14)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading).cardSurface(Palette.card)
        .padding(.horizontal, 12).padding(.vertical, 8)
        .onTapGesture { if !description.isEmpty { expanded.toggle() } }
    }

    private func tone(_ i: SmithItem) -> Color { i.drains ? chosenColour : i.difficulty > i.max ? warn : ok }

    @ViewBuilder private func costPill(_ c: SmithCost) -> some View {
        switch c.state {
        case 0: Pill(text: "\(c.label) · short", colour: bad)
        case 2: Pill(text: "\(c.label) · needs ability", colour: warn)
        case 3: Pill(text: "drains \(c.label)", colour: chosenColour)
        default: Pill(text: c.label, colour: text)
        }
    }

    /// Difficulty against skill: the fill is the difficulty, the tick is your skill.
    private func meter(_ item: SmithItem) -> some View {
        let scale = Double(max(item.difficulty, item.max, 1)) * 1.15
        return GeometryReader { g in
            ZStack(alignment: .leading) {
                RoundedRectangle(cornerRadius: 4).fill(line)
                RoundedRectangle(cornerRadius: 4).fill(tone(item)).frame(width: g.size.width * min(max(Double(item.difficulty) / scale, 0), 1))
                Rectangle().fill(text).frame(width: 2).offset(x: g.size.width * min(max(Double(item.max) / scale, 0), 1) - 2)
            }
        }.frame(height: 8)
    }
}
