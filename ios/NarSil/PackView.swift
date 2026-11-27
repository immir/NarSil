import SwiftUI

private let sectionTitles = [0: "Inventory", 1: "Equipment", 2: "On the floor", 3: "Throwing"]
private let sectionOrder = [0, 1, 2, 3]
private let openRow = Palette.raised

/// The pack (narsil_prompt.c, publish_pack()): every item in one list.  Tapping an item opens it in place with
/// its description and actions; tapping another closes it and opens that one.  After an action the game reopens the pack.
struct PackView: View {
    let prompt: PackPrompt
    let colour: (Int) -> UInt32
    let answer: (Int) -> Void
    @AppStorage(QuickPins.key) private var quickKinds = ""

    private var sections: [(Int, [Item])] {
        Dictionary(grouping: prompt.items, by: \.section).sorted { (sectionOrder.firstIndex(of: $0.key) ?? 9) < (sectionOrder.firstIndex(of: $1.key) ?? 9) }
    }
    private func rowID(_ i: Item) -> String { "\(i.section)/\(i.label)/\(i.name)" }

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    VStack(alignment: .leading) {
                        Text("Pack").scaledFont(17, weight: .semibold).foregroundColor(.white)
                        Text("Carrying \(pounds(prompt.weight)) of \(pounds(prompt.limit))").scaledFont(12)
                            .foregroundColor(prompt.weight > prompt.limit ? Palette.warn : Palette.sub)
                    }.padding(.horizontal, 16).padding(.top, 10)
                    LazyVStack(alignment: .leading, spacing: 0) {
                        ForEach(sections, id: \.0) { section, items in
                            Text(sectionTitles[section] ?? "").scaledFont(13).foregroundColor(Palette.sub)
                                .padding(.leading, 16).padding(.top, 12).padding(.bottom, 4).id("h\(section)")
                            ForEach(items, id: \.index) { item in row(item).id(rowID(item)) }
                        }
                        Color.clear.frame(height: 24)
                    }
                }
            }
            .onAppear {
                /* A fresh open starts at its section; a reopen after an action finds the open item. */
                if let open = prompt.items.first(where: { $0.index == prompt.expanded }) { proxy.scrollTo(rowID(open), anchor: .center) }
                else if !prompt.resumed, sections.contains(where: { $0.0 == prompt.focus }) { proxy.scrollTo("h\(prompt.focus)", anchor: .top) }
            }
        }
    }

    private func row(_ item: Item) -> some View {
        let open = item.index == prompt.expanded
        return VStack(alignment: .leading, spacing: 0) {
            Button { answer(open ? NB.PACK_COLLAPSE : NB.PACK_EXPAND + item.index) } label: {
                HStack(spacing: 12) {
                    Text(glyphString(item.glyph)).scaledFont(22, design: .monospaced)
                        .foregroundColor(Color(rgb: colour(item.attr % 256 % 32))).frame(width: 24)
                    Text(verbatim: item.label == "-" ? "" : "\(item.label))").scaledFont(14, design: .monospaced).foregroundColor(Palette.sub).frame(width: 24)
                    Text(item.name).scaledFont(16, weight: open ? .semibold : .regular).foregroundColor(specialColour(item.special) ?? .white)
                        .lineLimit(open ? 3 : 2).multilineTextAlignment(.leading)
                    Spacer()
                    if item.weightTenthsLb > 0 { Text(pounds(item.weightTenthsLb)).scaledFont(13).foregroundColor(Palette.sub) }
                }
                .frame(minHeight: 40).padding(.horizontal, 16).contentShape(Rectangle())
            }.buttonStyle(.plain)
            if open {
                VStack(alignment: .leading, spacing: 10) {
                    if !prompt.description.isEmpty { Text(colouredText(prompt.description, colour: colour)).scaledFont(14).foregroundColor(Palette.body) }
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 90), alignment: .leading)], alignment: .leading, spacing: 6) {
                        ForEach(prompt.actions, id: \.index) { a in
                            Button(a.label) { answer(NB.PACK_ACT + item.index * NB.PACK_ACT_STRIDE + a.index) }
                                .buttonStyle(.bordered).disabled(!a.valid).lineLimit(1)
                        }
                        /* Quick slots are kept by the app, not the game: this toggles the kind on the action bar. */
                        if prompt.kind >= 0 {
                            Button(QuickPins.decode(quickKinds).contains(prompt.kind) ? "Unpin from bar" : "Pin to bar") {
                                quickKinds = QuickPins.toggled(quickKinds, prompt.kind)
                            }.buttonStyle(.bordered).tint(Palette.gold).lineLimit(1)
                        }
                    }
                }.padding(.leading, 68).padding(.trailing, 16).padding(.bottom, 12)
            }
            Palette.divider.frame(height: 1).padding(.leading, 68)
        }
        .background(open ? openRow : Color.clear)
    }
}
