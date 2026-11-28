import SwiftUI

/*
 Layout mode (☰ → Adjust layout): the boundaries between the panes of the main screen grow gold handles that are
 dragged to resize them, with a few presets and a left-handed mirror.  The sizes are fractions of the screen and are
 kept in the user defaults, so they carry across rotation and other devices.  (Settings.kt on Android.)
*/
enum LayoutPresets {
    /// Name, right column and button column as fractions of the width.
    static let all: [(name: String, rail: Double, buttons: Double)] = [("Compact", 0.17, 0.095), ("Balanced", 0.19, 0.108), ("Big", 0.24, 0.14)]
    static let defaultRail = 0.19, defaultButtons = 0.108

    static func clamp(_ v: Double, _ lo: Double, _ hi: Double) -> Double { min(max(v, lo), hi) }
}

/// A handle on a boundary: a thin bar to drag.  `horizontalBar` lies across the screen (dragged up and down); otherwise it stands
/// upright (dragged left and right).  `onDrag` gets each step of movement along the drag axis.
struct ResizeHandle: View {
    let horizontalBar: Bool
    let onDrag: (CGFloat) -> Void
    @State private var last: CGFloat = 0

    var body: some View {
        ZStack {
            Palette.gold.opacity(0.14)
            RoundedRectangle(cornerRadius: 2).fill(Palette.gold).frame(width: horizontalBar ? 40 : 4, height: horizontalBar ? 4 : 40)
        }
        .frame(width: horizontalBar ? nil : 24, height: horizontalBar ? 24 : nil)
        .contentShape(Rectangle())
        .gesture(DragGesture(minimumDistance: 0, coordinateSpace: .global)
            .onChanged { v in
                let t = horizontalBar ? v.translation.height : v.translation.width
                onDrag(t - last); last = t
            }
            .onEnded { _ in last = 0 })
    }
}

/// The card over the map while laying out: the presets, the mirror, Reset and Done.
struct LayoutBar: View {
    @Binding var rail: Double
    @Binding var buttons: Double
    @Binding var split: Double
    @Binding var pad: Double
    @Binding var mirror: Bool
    let done: () -> Void

    private func apply(_ p: (name: String, rail: Double, buttons: Double)) { rail = p.rail; buttons = p.buttons; split = -1; pad = -1 }
    private func matches(_ p: (name: String, rail: Double, buttons: Double)) -> Bool { rail == p.rail && buttons == p.buttons && split < 0 && pad < 0 }

    var body: some View {
        VStack(spacing: 8) {
            Text("Drag the gold handles to resize the panes").scaledFont(13).foregroundColor(Palette.sub)
            HStack(spacing: 8) {
                ForEach(LayoutPresets.all, id: \.name) { p in
                    Button(p.name) { apply(p) }
                        .scaledFont(14).padding(.horizontal, 12).frame(minHeight: 34)
                        .background(matches(p) ? Palette.gold.opacity(0.25) : Color.white.opacity(0.14), in: Capsule())
                        .overlay { if matches(p) { Capsule().strokeBorder(Palette.gold, lineWidth: 1.5) } }
                        .foregroundColor(matches(p) ? Palette.gold : .white).buttonStyle(.plain)
                }
                Toggle("Left-handed", isOn: $mirror).scaledFont(14).fixedSize()
            }
            HStack(spacing: 12) {
                Button("Reset") { apply(LayoutPresets.all[1]); mirror = false }
                Button("Done", action: done).buttonStyle(GoldButtonStyle())
            }
        }
        .padding(12).foregroundColor(.white)
        .background(Palette.sheet, in: RoundedRectangle(cornerRadius: 14))
        .overlay(RoundedRectangle(cornerRadius: 14).strokeBorder(Palette.hairline, lineWidth: 1))
        .padding(12)
    }
}

/// The height of the status pane, so the first drag of the status/d-pad handle starts from where it is.
struct StatusHeightKey: PreferenceKey {
    static let defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = max(value, nextValue()) }
}
