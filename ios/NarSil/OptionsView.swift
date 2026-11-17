import SwiftUI

/// The game's own options (narsil_options.c); changes apply at once.
struct OptionsView: View {
    let prompt: OptionsPrompt
    let answer: (Int) -> Void

    private var main: Bool { prompt.page == "main" }
    private var anyLocked: Bool { prompt.rows.contains { if case .toggle(_, _, true, _, _) = $0 { return true } else { return false } } }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ScreenHeader(title: prompt.title, back: main ? nil : { answer(-1) })
            if prompt.page == "birth" && anyLocked { note("Fixed once the character exists.") }
            if prompt.page == "cheat" { note("Switching one on marks this character as a cheater for good, even if you switch it off again.") }
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 0) {
                    ForEach(Array(prompt.rows.enumerated()), id: \.offset) { _, row in
                        switch row {
                        case .heading(let t):
                            Text(t).font(.system(size: 14, weight: .semibold)).foregroundColor(Palette.gold).padding(.leading, 16).padding(.top, 16).padding(.bottom, 6)
                        case .toggle(let a, let on, let locked, let description, let name): toggle(a, on, locked, description, name)
                        case .number(let base, let label, let value, let lo, let hi, let detail): NumberRow(base: base, label: label, value: value, lo: lo, hi: hi, detail: detail, answer: answer)
                        case .link(let a, let label, let detail): link(a, label, detail)
                        }
                    }
                    Color.clear.frame(height: 16)
                }
            }
        }
    }

    private func note(_ t: String) -> some View {
        Text(t).font(.system(size: 13)).foregroundColor(Color(rgb: 0xFFB74D)).padding(.horizontal, 16).padding(.vertical, 4)
    }

    private func card<C: View>(_ c: C) -> some View {
        c.frame(maxWidth: .infinity, alignment: .leading).background(Palette.card).clipShape(RoundedRectangle(cornerRadius: 10))
            .padding(.horizontal, 12).padding(.vertical, 3)
    }

    private func toggle(_ a: Int, _ on: Bool, _ locked: Bool, _ description: String, _ name: String) -> some View {
        card(HStack {
            VStack(alignment: .leading) {
                Text(description).font(.system(size: 15)).foregroundColor(locked ? Palette.sub : Color(rgb: 0xE6E6EA))
                Text(name).font(.system(size: 11)).foregroundColor(Color(rgb: 0x5A5A60))
            }
            Spacer()
            Toggle("", isOn: Binding(get: { on }, set: { _ in answer(a) })).labelsHidden().disabled(locked)
        }.frame(minHeight: 56).padding(.horizontal, 14).padding(.vertical, 6))
    }

    private func link(_ a: Int, _ label: String, _ detail: String) -> some View {
        Button { answer(a) } label: {
            card(HStack {
                VStack(alignment: .leading) {
                    Text(label).font(.system(size: 15)).foregroundColor(Color(rgb: 0xE6E6EA))
                    if !detail.isEmpty { Text(detail).font(.system(size: 12)).foregroundColor(Palette.sub) }
                }
                Spacer()
                Text("›").font(.system(size: 20)).foregroundColor(Palette.sub)
            }.frame(minHeight: 56).padding(.horizontal, 14).padding(.vertical, 8).contentShape(Rectangle()))
        }.buttonStyle(.plain)
    }
}

private struct NumberRow: View {
    let base: Int, label: String, value: Int, lo: Int, hi: Int, detail: String
    let answer: (Int) -> Void
    @State private var drag = 0.0

    private func set(_ v: Int) { if v != value { answer(base + max(lo, min(hi, v))) } }

    var body: some View {
        VStack(spacing: 4) {
            HStack(spacing: 8) {
                VStack(alignment: .leading) {
                    Text(label).font(.system(size: 15)).foregroundColor(Color(rgb: 0xE6E6EA))
                    Text(detail).font(.system(size: 12)).foregroundColor(Palette.sub)
                }
                Spacer()
                Button("−") { set(value - 1) }.buttonStyle(.bordered).disabled(value <= lo)
                Text("\(Int(drag.rounded()))").font(.system(size: 17, weight: .semibold)).foregroundColor(Color(rgb: 0xE6E6EA)).frame(minWidth: 36)
                Button("+") { set(value + 1) }.buttonStyle(.bordered).disabled(value >= hi)
            }
            if hi - lo > 20 {
                Slider(value: $drag, in: Double(lo)...Double(hi)) { editing in if !editing { set(Int(drag.rounded())) } }
            }
        }
        .padding(.horizontal, 14).padding(.vertical, 10)
        .background(Palette.card).clipShape(RoundedRectangle(cornerRadius: 10)).padding(.horizontal, 12).padding(.vertical, 3)
        .onAppear { drag = Double(value) }
        .onChange(of: value) { drag = Double($0) }
    }
}
