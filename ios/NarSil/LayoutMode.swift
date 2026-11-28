import SwiftUI

/*
 Layout mode (☰ → Adjust layout): the boundaries between the panes of the main screen grow gold handles that are
 dragged to resize them, with a few presets and a left-handed mirror.  The sizes are fractions of the screen and are
 kept in the user defaults, so they carry across rotation and other devices.  (Settings.kt on Android.)
*/
/// A preset: landscape columns (shares of the width), portrait status section (share of the height, -1 as tall as its content),
/// message area (shares of the height).
struct LayoutPreset {
    let name: String
    let rail: Double, buttons: Double, bottom: Double, messagesPortrait: Double, messagesLandscape: Double, pad: Double
}

enum LayoutPresets {
    static let defaultRail = 0.19, defaultButtons = 0.108, defaultPad = 0.34, defaultMessagesPortrait = 0.12, defaultMessagesLandscape = 0.14
    static let all = [
        LayoutPreset(name: "Compact", rail: 0.17, buttons: 0.095, bottom: 0.15, messagesPortrait: 0.08, messagesLandscape: 0.10, pad: 0.28),
        LayoutPreset(name: "Balanced", rail: defaultRail, buttons: defaultButtons, bottom: -1, messagesPortrait: defaultMessagesPortrait, messagesLandscape: defaultMessagesLandscape, pad: defaultPad),
        LayoutPreset(name: "Big", rail: 0.24, buttons: 0.14, bottom: 0.27, messagesPortrait: 0.16, messagesLandscape: 0.20, pad: 0.5),
    ]

    static func clamp(_ v: Double, _ lo: Double, _ hi: Double) -> Double { min(max(v, lo), hi) }
}

/// A handle on a boundary: a thin bar to drag.  `horizontalBar` lies across the screen (dragged up and down); otherwise it stands
/// upright (dragged left and right).  `onDrag` gets each step of movement along the drag axis.
struct ResizeHandle: View {
    let horizontalBar: Bool
    /// In a row only as tall as its content (portrait, beside the d-pad) the handle takes a length, or it would stretch the row.
    var length: CGFloat?
    let onDrag: (CGFloat) -> Void
    @State private var last: CGFloat = 0

    var body: some View {
        ZStack {
            Palette.gold.opacity(0.14)
            RoundedRectangle(cornerRadius: 2).fill(Palette.gold).frame(width: horizontalBar ? 40 : 4, height: horizontalBar ? 4 : 40)
        }
        .frame(width: horizontalBar ? nil : 24, height: horizontalBar ? 24 : length)
        .contentShape(Rectangle())
        .gesture(DragGesture(minimumDistance: 0, coordinateSpace: .global)
            .onChanged { v in
                let t = horizontalBar ? v.translation.height : v.translation.width
                onDrag(t - last); last = t
            }
            .onEnded { _ in last = 0 })
    }
}

/// A round gold grip, dragged to resize the floating d-pad from its corner; `onDrag` gets each step of horizontal movement.
struct CornerGrip: View {
    let onDrag: (CGFloat) -> Void
    @State private var last: CGFloat = 0

    var body: some View {
        Circle().fill(Palette.gold).frame(width: 32, height: 32)
            .gesture(DragGesture(minimumDistance: 0, coordinateSpace: .global)
                .onChanged { v in onDrag(v.translation.width - last); last = v.translation.width }
                .onEnded { _ in last = 0 })
    }
}

/// The strip over the map while laying out: the presets, the mirror, Reset and Done, as small as will hold them.
struct LayoutBar: View {
    @Binding var rail: Double
    @Binding var buttons: Double
    @Binding var split: Double
    @Binding var pad: Double
    @Binding var bottom: Double
    @Binding var messagesPortrait: Double
    @Binding var messagesLandscape: Double
    @Binding var mirror: Bool
    let done: () -> Void

    private func apply(_ p: LayoutPreset) {
        rail = p.rail; buttons = p.buttons; bottom = p.bottom; messagesPortrait = p.messagesPortrait; messagesLandscape = p.messagesLandscape
        split = -1; pad = p.pad
    }
    private func matches(_ p: LayoutPreset) -> Bool {
        rail == p.rail && buttons == p.buttons && bottom == p.bottom && messagesPortrait == p.messagesPortrait && messagesLandscape == p.messagesLandscape
            && split < 0 && pad == p.pad
    }

    var body: some View {
        VStack(spacing: 2) {
            HStack(spacing: 4) {
                ForEach(LayoutPresets.all, id: \.name) { p in
                    Button(p.name) { apply(p) }
                        .font(.system(size: 11)).padding(.horizontal, 8).padding(.vertical, 5)
                        .background(matches(p) ? Palette.gold.opacity(0.25) : Palette.raised, in: Capsule())
                        .foregroundColor(matches(p) ? Palette.gold : .white).buttonStyle(.plain)
                }
            }
            HStack(spacing: 6) {
                Toggle("Left-handed", isOn: $mirror).font(.system(size: 11)).fixedSize().scaleEffect(0.8)
                Button("Reset") { apply(LayoutPresets.all[1]); mirror = false }.font(.system(size: 11)).foregroundColor(Palette.gold)
                Button(action: done) { Text("Done").font(.system(size: 11, weight: .semibold)).padding(.horizontal, 10).padding(.vertical, 5) }
                    .background(Palette.gold, in: Capsule()).foregroundColor(Palette.onGold).buttonStyle(.plain)
            }
        }
        .padding(.horizontal, 8).padding(.vertical, 3).foregroundColor(.white)
        .background(Palette.sheet, in: RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(Palette.hairline, lineWidth: 1))
        .padding(4)
    }
}

/// The height of the status pane, so the first drag of the status/d-pad handle starts from where it is.
struct StatusHeightKey: PreferenceKey {
    static let defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = max(value, nextValue()) }
}
