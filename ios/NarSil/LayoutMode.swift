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

/// One boundary that can be dragged (`horizontal` set), or an area that must keep its own touches, such as the card (`horizontal` nil).
struct Guide {
    static let zoneID = UUID()
    let id: UUID
    let horizontal: Bool?
    let frame: CGRect
    var onDrag: (CGFloat) -> Void = { _ in }
}

struct GuideKey: PreferenceKey {
    static var defaultValue: [Guide] = []
    static func reduce(value: inout [Guide], nextValue: () -> [Guide]) { value += nextValue() }
}

/// A handle on a boundary.  `horizontalBar` lies across the screen (dragged up and down); otherwise it stands upright
/// (dragged left and right).  `onDrag` gets each step of movement along the drag axis.  It takes no room in the layout: it only
/// reports where its boundary lies, and `GuideLayer` draws and drags it, so what is being resized keeps its true size.
/// In a row only as tall as its content (portrait, beside the d-pad) it takes a `length`.
struct ResizeHandle: View {
    let horizontalBar: Bool
    var length: CGFloat?
    let onDrag: (CGFloat) -> Void
    @State private var id = UUID()

    var body: some View {
        Group {
            if horizontalBar { Color.clear.frame(maxWidth: .infinity).frame(height: 0) }
            else if let length { Color.clear.frame(width: 0, height: length) }
            else { Color.clear.frame(width: 0).frame(maxHeight: .infinity) }
        }
        .background(GeometryReader { g in
            Color.clear.preference(key: GuideKey.self, value: [Guide(id: id, horizontal: horizontalBar, frame: g.frame(in: .named("guides")), onDrag: onDrag)])
        })
    }
}

/// The translucent guides over everything, and the one gesture that drags them: a touch beginning within reach of a guide
/// (and not on the card) takes that guide.
struct GuideLayer: View {
    let guides: [Guide]
    @State private var active: UUID?
    @State private var last: CGFloat = 0
    private let reach: CGFloat = 18

    private var movable: [Guide] { guides.filter { $0.horizontal != nil } }

    private func strip(_ g: Guide) -> CGRect {
        g.horizontal == true ? CGRect(x: g.frame.minX, y: g.frame.minY - reach, width: g.frame.width, height: 2 * reach)
                             : CGRect(x: g.frame.minX - reach, y: g.frame.minY, width: 2 * reach, height: g.frame.height)
    }

    /// The strips, less the areas that keep their own touches (the strip is cut into the pieces around each).
    private var touchable: Path {
        let zones = guides.filter { $0.horizontal == nil }.map(\.frame)
        let pieces = zones.reduce(movable.map(strip)) { rects, zone in rects.flatMap { Self.cut($0, around: zone) } }
        return pieces.reduce(Path()) { var p = $0; p.addRect($1); return p }
    }

    private static func cut(_ r: CGRect, around z: CGRect) -> [CGRect] {
        guard r.intersects(z) else { return [r] }
        let i = r.intersection(z)
        return [CGRect(x: r.minX, y: r.minY, width: r.width, height: i.minY - r.minY),
                CGRect(x: r.minX, y: i.maxY, width: r.width, height: r.maxY - i.maxY),
                CGRect(x: r.minX, y: i.minY, width: i.minX - r.minX, height: i.height),
                CGRect(x: i.maxX, y: i.minY, width: r.maxX - i.maxX, height: i.height)].filter { $0.width > 0 && $0.height > 0 }
    }

    var body: some View {
        ZStack {
            Canvas { ctx, _ in
                movable.forEach { g in
                    let f = g.frame
                    let horizontal = g.horizontal == true
                    let band = horizontal ? CGRect(x: f.minX, y: f.minY - 9, width: f.width, height: 18) : CGRect(x: f.minX - 9, y: f.minY, width: 18, height: f.height)
                    let line = horizontal ? CGRect(x: f.minX, y: f.minY - 1, width: f.width, height: 2) : CGRect(x: f.minX - 1, y: f.minY, width: 2, height: f.height)
                    let grip = horizontal ? CGRect(x: f.midX - 20, y: f.minY - 2.5, width: 40, height: 5) : CGRect(x: f.minX - 2.5, y: f.midY - 20, width: 5, height: 40)
                    ctx.fill(Path(band), with: .color(Palette.gold.opacity(0.16)))
                    ctx.fill(Path(line), with: .color(Palette.gold.opacity(0.7)))
                    ctx.fill(Path(roundedRect: grip, cornerRadius: 2.5), with: .color(Palette.gold))
                }
            }.allowsHitTesting(false)
            Color.clear.contentShape(touchable)
                .gesture(DragGesture(minimumDistance: 0, coordinateSpace: .local)
                    .onChanged { v in
                        if active == nil {
                            active = movable.min { distance(v.startLocation, $0) < distance(v.startLocation, $1) }?.id
                            last = 0
                        }
                        guard let g = movable.first(where: { $0.id == active }) else { return }
                        let t = g.horizontal == true ? v.translation.height : v.translation.width
                        g.onDrag(t - last); last = t
                    }
                    .onEnded { _ in active = nil; last = 0 })
        }
    }

    private func distance(_ p: CGPoint, _ g: Guide) -> CGFloat { g.horizontal == true ? abs(p.y - g.frame.minY) : abs(p.x - g.frame.minX) }
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
        .background(GeometryReader { g in
            Color.clear.preference(key: GuideKey.self, value: [Guide(id: Guide.zoneID, horizontal: nil, frame: g.frame(in: .named("guides")))])
        })
    }
}

/// The height of the status pane, so the first drag of the status/d-pad handle starts from where it is.
struct StatusHeightKey: PreferenceKey {
    static let defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = max(value, nextValue()) }
}
