import SwiftUI

/// The knowledge browser (narsil_knowledge.c): the main page lists the sections; each section lists its entries
/// under group headings, and tapping one opens it in place with what is known.
struct KnowledgeView: View {
    let prompt: KnowledgePrompt
    let colour: (Int) -> UInt32
    let answer: (Int) -> Void
    @State private var query = ""

    private var main: Bool { prompt.view == "main" }
    private var entryCount: Int { prompt.rows.filter { if case .entry = $0 { return true } else { return false } }.count }
    private var searchable: Bool { !main && prompt.view != "scores" && entryCount > 8 }
    private var expandable: Bool { prompt.view != "history" && prompt.view != "scores" }

    /// The entries whose name or note contains the query, under the headings that still have any.
    private var rows: [KnowRow] {
        let q = query.trimmingCharacters(in: .whitespaces)
        guard !q.isEmpty else { return prompt.rows }
        var out = [KnowRow](), heading: KnowRow?
        for row in prompt.rows {
            switch row {
            case .group: heading = row
            case .entry(_, _, _, let name, let note):
                if name.localizedCaseInsensitiveContains(q) || note.localizedCaseInsensitiveContains(q) {
                    if let h = heading { out.append(h); heading = nil }
                    out.append(row)
                }
            }
        }
        return out
    }

    var body: some View {
        VStack(spacing: 0) {
            ScreenHeader(title: prompt.title, back: main ? nil : { answer(-1) }, trailing: main ? nil : "\(entryCount)")
            if searchable { TextField("Search", text: $query).textFieldStyle(.roundedBorder).padding(.horizontal, 12).padding(.vertical, 4) }
            if !main && entryCount == 0 {
                Text(prompt.view == "history" ? "Nothing has happened yet." : prompt.view == "scores" ? "No characters have ended yet." : "Nothing here yet.")
                    .scaledFont(14).foregroundColor(Palette.sub).padding(16)
            }
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 0) {
                    ForEach(Array(rows.enumerated()), id: \.offset) { _, row in
                        switch row {
                        case .group(let title):
                            Text(title).scaledFont(13).foregroundColor(Palette.sub).padding(.leading, 16).padding(.top, 14).padding(.bottom, 4)
                        case .entry(let index, let glyph, let attr, let name, let note):
                            if main { sectionCard(index, glyph, attr, name, note) } else { entryRow(index, glyph, attr, name, note) }
                        }
                    }
                    Color.clear.frame(height: 24)
                }
            }
        }
        .id(prompt.view)
    }

    private func glyphText(_ glyph: Int, _ attr: Int, size: CGFloat) -> some View {
        Text(glyphString(glyph)).scaledFont(size, design: .monospaced).foregroundColor(Color(rgb: colour(attr % 256 % 32))).frame(width: size + 4)
    }

    private func sectionCard(_ index: Int, _ glyph: Int, _ attr: Int, _ name: String, _ note: String) -> some View {
        Button { answer(index) } label: {
            HStack(spacing: 12) {
                glyphText(glyph, attr, size: 24)
                VStack(alignment: .leading) {
                    Text(name).scaledFont(16, weight: .semibold).foregroundColor(Palette.text)
                    if !note.isEmpty { Text(note).scaledFont(13).foregroundColor(Palette.sub) }
                }
                Spacer()
                Text("›").scaledFont(20).foregroundColor(Palette.sub)
            }
            .frame(minHeight: 64).padding(.horizontal, 14).padding(.vertical, 10)
            .cardSurface(Palette.card).contentShape(Rectangle())
        }.buttonStyle(.plain).padding(.horizontal, 12).padding(.vertical, 4)
    }

    private func entryRow(_ index: Int, _ glyph: Int, _ attr: Int, _ name: String, _ note: String) -> some View {
        let open = index == prompt.expanded
        return VStack(alignment: .leading, spacing: 0) {
            Button { if expandable { answer(open ? NB.KNOW_COLLAPSE : index) } } label: {
                HStack(spacing: 10) {
                    glyphText(glyph, attr, size: 20)
                    Text(name).scaledFont(15, weight: open ? .semibold : .regular).foregroundColor(Palette.text)
                        .lineLimit(open || !expandable ? 4 : 2).multilineTextAlignment(.leading)
                    Spacer()
                    if !note.isEmpty { Text(note).scaledFont(12).foregroundColor(Palette.sub) }
                }.frame(minHeight: 46).padding(.horizontal, 16).padding(.vertical, 6).contentShape(Rectangle())
            }.buttonStyle(.plain)
            if open && !prompt.text.isEmpty {
                Text(colouredText(prompt.text, colour: colour)).scaledFont(14).foregroundColor(Palette.body).padding(.leading, 50).padding(.trailing, 16).padding(.bottom, 12)
            }
            if open && !prompt.actions.isEmpty {
                HStack(spacing: 8) {
                    ForEach(Array(prompt.actions.enumerated()), id: \.offset) { _, a in Button(a.1) { answer(a.0) }.buttonStyle(.bordered) }
                }.padding(.leading, 50).padding(.trailing, 16).padding(.bottom, 12)
            }
            Palette.divider.frame(height: 1).padding(.leading, 50)
        }
        .background(open ? openRowColour : Color.clear)
    }
}

private let openRowColour = Palette.raised
