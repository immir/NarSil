import SwiftUI

/* The game's questions as native screens (Prompts.kt, ItemSheet.kt, the text prompt of SmithSheet.kt). */

/// A bottom sheet over a dimmed map; tapping outside cancels.
struct BottomSheet<Content: View>: View {
    let maxHeight: CGFloat
    let cancel: () -> Void
    @ViewBuilder let content: Content

    var body: some View {
        ZStack(alignment: .bottom) {
            Color.black.opacity(0.6).ignoresSafeArea().onTapGesture(perform: cancel)
            content
                .frame(maxWidth: .infinity, maxHeight: maxHeight)
                .background(Palette.sheet)
                .clipShape(UnevenRoundedRectangle(topLeadingRadius: 16, topTrailingRadius: 16))
        }
        .preferredColorScheme(.dark)
    }
}

/// get_check: yes/no.  Dismissal counts as no, as Escape does in the textui.
struct CheckDialog: View {
    let question: String
    let answer: (Int) -> Void

    var body: some View {
        ZStack {
            Color.black.opacity(0.6).ignoresSafeArea().onTapGesture { answer(0) }
            VStack(spacing: 16) {
                Text(question.replacingOccurrences(of: "[y/n]", with: "").trimmingCharacters(in: .whitespaces))
                    .scaledFont(17).foregroundColor(.white).multilineTextAlignment(.leading)
                    .frame(maxWidth: .infinity, alignment: .leading)
                HStack {
                    Spacer()
                    Button("No") { answer(0) }.buttonStyle(.bordered)
                    Button("Yes") { answer(1) }.buttonStyle(.borderedProminent)
                }
            }
            .padding(20).background(Palette.sheet).clipShape(RoundedRectangle(cornerRadius: 16)).padding(32)
        }
        .preferredColorScheme(.dark)
    }
}

/// Pick one of several options (songs, ...): big rows with their descriptions.
struct ChoiceSheet: View {
    let prompt: ChoicePrompt
    let answer: (Int) -> Void

    var body: some View {
        BottomSheet(maxHeight: 600, cancel: { answer(-1) }) {
            VStack(spacing: 0) {
                HStack {
                    Text(prompt.title).scaledFont(17, weight: .semibold).foregroundColor(.white)
                    Spacer()
                    Button("Cancel") { answer(-1) }
                }.padding(.horizontal, 16).padding(.vertical, 12)
                ScrollView {
                    VStack(spacing: 0) {
                        ForEach(prompt.options, id: \.index) { o in
                            Button { answer(o.index) } label: {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(o.label).scaledFont(16, weight: .medium).foregroundColor(.white)
                                    if !o.description.isEmpty { Text(o.description).scaledFont(13).foregroundColor(Palette.sub) }
                                }
                                .frame(maxWidth: .infinity, alignment: .leading).padding(.horizontal, 16).padding(.vertical, 10)
                            }.buttonStyle(.plain)
                        }
                    }
                }
            }
        }
    }
}

/// get_quantity: −/+, a slider for larger stacks, All, OK; dismissing cancels.
struct QuantityDialog: View {
    let prompt: QuantityPrompt
    let answer: (Int) -> Void
    @State private var n = 1

    var body: some View {
        ZStack {
            Color.black.opacity(0.6).ignoresSafeArea().onTapGesture { answer(-1) }
            VStack(spacing: 12) {
                Text(prompt.title).scaledFont(17, weight: .semibold).foregroundColor(.white)
                HStack(spacing: 12) {
                    Button("−") { n = max(1, n - 1) }.buttonStyle(.bordered).disabled(n <= 1)
                    Text("\(n)").scaledFont(24, weight: .semibold).foregroundColor(.white).frame(maxWidth: .infinity)
                    Button("+") { n = min(prompt.max, n + 1) }.buttonStyle(.bordered).disabled(n >= prompt.max)
                }
                if prompt.max > 5 { Slider(value: Binding(get: { Double(n) }, set: { n = max(1, min(prompt.max, Int($0.rounded()))) }), in: 1...Double(prompt.max)) }
                Text("of \(prompt.max)").scaledFont(13).foregroundColor(Palette.sub)
                HStack {
                    Button("All \(prompt.max)") { answer(prompt.max) }
                    Button("Cancel") { answer(-1) }
                    Spacer()
                    Button("OK") { answer(n) }.buttonStyle(.borderedProminent)
                }
            }
            .padding(20).background(Palette.sheet).clipShape(RoundedRectangle(cornerRadius: 16)).padding(32)
        }
        .preferredColorScheme(.dark)
        .onAppear { n = max(1, min(prompt.max, prompt.initial)) }
    }
}

/// A line of text (an artefact's name, an inscription); empty or Cancel keeps the default.
struct TextPromptView: View {
    let prompt: TextPrompt
    let answer: (Int) -> Void
    @State private var text = ""

    var body: some View {
        ZStack {
            Color.black.opacity(0.6).ignoresSafeArea().onTapGesture { answer(-1) }
            VStack(spacing: 12) {
                Text(prompt.title).scaledFont(17, weight: .semibold).foregroundColor(.white)
                TextField("", text: $text, axis: prompt.maxBytes > 40 ? .vertical : .horizontal)
                    .lineLimit(prompt.maxBytes > 40 ? 4...6 : 1...1)
                    .textFieldStyle(.roundedBorder)
                    .onChange(of: text) { t in
                        let flat = t.replacingOccurrences(of: "\n", with: " ")
                        if flat.utf8.count > prompt.maxBytes { text = String(decoding: Array(flat.utf8.prefix(prompt.maxBytes)), as: UTF8.self) }
                        else if flat != t { text = flat }
                    }
                HStack {
                    if prompt.random { Button("Random") { answer(NB.TEXT_RANDOM) } }
                    Button("Cancel") { answer(-1) }
                    Spacer()
                    Button("OK") { Native.answerText(text.trimmingCharacters(in: .whitespaces)) }.buttonStyle(.borderedProminent)
                }
            }
            .padding(20).background(Palette.sheet).clipShape(RoundedRectangle(cornerRadius: 16)).padding(24)
        }
        .preferredColorScheme(.dark)
        .onAppear { text = prompt.initial }
        /* Asked again with another default (the Random button's name): the field follows. */
        .onChange(of: prompt.initial) { text = $0 }
    }
}

private let sectionTitles = [0: "Inventory", 1: "Equipment", 2: "On the floor", 3: "Throwing"]
/// Display order: throwables first when offered (they are what the command wants).
private let sectionOrder = [3, 0, 1, 2]

/// Native replacement for the textui item menu: large rows, the game's own glyph and colour for each item.
struct ItemSheet: View {
    let prompt: ItemsPrompt
    let colour: (Int) -> UInt32
    let answer: (Int) -> Void

    private var sections: [(Int, [Item])] {
        Dictionary(grouping: prompt.items, by: \.section).sorted { (sectionOrder.firstIndex(of: $0.key) ?? 9) < (sectionOrder.firstIndex(of: $1.key) ?? 9) }
    }

    var body: some View {
        BottomSheet(maxHeight: 560, cancel: { answer(-1) }) {
            VStack(spacing: 0) {
                HStack {
                    Text(prompt.title).scaledFont(17, weight: .semibold).foregroundColor(.white)
                    Spacer()
                    Button("Cancel") { answer(-1) }
                }.padding(.horizontal, 16).padding(.vertical, 12)
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 0) {
                        ForEach(sections, id: \.0) { section, items in
                            Text(sectionTitles[section] ?? "").scaledFont(13).foregroundColor(Palette.sub)
                                .padding(.leading, 16).padding(.top, 10).padding(.bottom, 4)
                            ForEach(items, id: \.index) { item in row(item) }
                        }
                        Color.clear.frame(height: 12)
                    }
                }
            }
        }
    }

    private func row(_ item: Item) -> some View {
        Button { answer(item.index) } label: {
            HStack(spacing: 12) {
                Text(String(Unicode.Scalar(UInt32(item.glyph)).map(Character.init) ?? " "))
                    .scaledFont(22, design: .monospaced).foregroundColor(Color(rgb: colour(item.attr % 256 % 32))).frame(width: 24)
                Text(item.label == "-" ? "" : "\(item.label))").scaledFont(14, design: .monospaced).foregroundColor(Palette.sub).frame(width: 24)
                Text(item.name).scaledFont(16).foregroundColor(specialColour(item.special) ?? .white).lineLimit(2)
                Spacer()
                if item.weightTenthsLb > 0 {
                    Text("\(item.weightTenthsLb / 10).\(item.weightTenthsLb % 10) lb").scaledFont(13).foregroundColor(Palette.sub)
                }
            }
            .frame(minHeight: 52).padding(.horizontal, 16)
        }.buttonStyle(.plain)
    }
}

/// How special items stand out: ego items in cyan, artefacts in gold, items with properties still to discover in lilac.
func specialColour(_ tier: Int) -> Color? {
    switch tier {
    case 1: return Color(rgb: 0x5CE1E6)
    case 2: return Color(rgb: 0xFFB000)
    case 3: return Color(rgb: 0xD7A6FF)
    default: return nil
    }
}

/// Non-modal: the map stays live underneath (tap a monster or any grid, swipe a direction).
struct AimBar: View {
    let prompt: AimPrompt
    let answer: (Int) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(prompt.targets.isEmpty ? "Aim: swipe a direction or tap a grid" : "Aim: tap a target, or swipe a direction")
                .scaledFont(14).foregroundColor(.white)
            if !prompt.targets.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 6) {
                        ForEach(Array(prompt.targets.enumerated()), id: \.offset) { _, t in
                            Button(t.name) { answer(Direction.aimAt(x: t.x, y: t.y)) }.buttonStyle(.bordered)
                                .tint(Color(rgb: 0xFF8A80).opacity(t.inRange ? 1 : 0.5))
                        }
                    }
                }
            }
            HStack(spacing: 8) {
                if prompt.current != nil { Button("Target") { answer(NB.AIM_CURRENT) }.buttonStyle(.bordered) }
                if !prompt.targets.isEmpty { Button("Nearest") { answer(NB.AIM_NEAREST) }.buttonStyle(.bordered) }
                Button("Cancel") { answer(-1) }.buttonStyle(.bordered)
            }
        }
        .padding(.horizontal, 12).padding(.vertical, 8).frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(rgb: 0x1C1C1E).opacity(0.93))
    }
}

private let directionArrows = [1: "↙", 2: "↓", 3: "↘", 4: "←", 6: "→", 7: "↖", 8: "↑", 9: "↗"]

/// The d-pad keys a direction prompt suggests: its interesting neighbours, and the centre when "here" is allowed.
func padMarks(_ p: Prompt?) -> Set<Int> {
    guard case .direction(let d)? = p else { return [] }
    return Set(d.neighbours.map(\.dir)).union(d.allowHere ? [5] : [])
}

/// Adjacent-direction prompt: the map stays live (tap a neighbour or swipe). With the d-pad shown its keys for
/// the interesting neighbours light up and this bar only names them; otherwise they are buttons.
struct DirectionBar: View {
    let prompt: DirectionPrompt
    let padShown: Bool
    let answer: (Int) -> Void

    private var named: String {
        (prompt.neighbours.map { "\(directionArrows[$0.dir] ?? "") \($0.what)" } + (prompt.allowHere ? ["• here"] : []))
            .joined(separator: " · ")
    }

    var body: some View {
        if padShown {
            HStack {
                Text(named.isEmpty ? "Which way? Use the d-pad, or tap a neighbouring square" : "Which way? \(named)")
                    .scaledFont(14).foregroundColor(.white).frame(maxWidth: .infinity, alignment: .leading)
                Button("Cancel") { answer(-1) }.buttonStyle(.bordered)
            }
            .padding(.horizontal, 12).padding(.vertical, 4)
            .background(Color(rgb: 0x1C1C1E).opacity(0.93))
        } else {
            chips
        }
    }

    private var chips: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("Which way? Tap a neighbouring square or swipe").scaledFont(14).foregroundColor(.white)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(prompt.neighbours, id: \.dir) { n in
                        Button("\(n.what) \(directionArrows[n.dir] ?? "")") { answer(n.dir) }.buttonStyle(.bordered)
                    }
                    if prompt.allowHere { Button("Here") { answer(5) }.buttonStyle(.bordered) }
                    Button("Cancel") { answer(-1) }.buttonStyle(.bordered)
                }
            }
        }
        .padding(.horizontal, 12).padding(.vertical, 8).frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(rgb: 0x1C1C1E).opacity(0.93))
    }
}
