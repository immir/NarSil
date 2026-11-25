import SwiftUI

/// The two ways off a screen: Back goes up one level (a chevron), Close leaves the sheet or question (a ✕).
/// Android's Chrome.kt mirrors this: Back at the start of a header, Close at its end, the same size and look everywhere.
enum NavKind { case back, close }

struct NavIcon: View {
    let kind: NavKind
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Canvas { ctx, size in
                let s = size.width / 24
                var p = Path()
                switch kind {
                case .back: p.move(to: CGPoint(x: 15 * s, y: 5 * s)); p.addLine(to: CGPoint(x: 8 * s, y: 12 * s)); p.addLine(to: CGPoint(x: 15 * s, y: 19 * s))
                case .close:
                    p.move(to: CGPoint(x: 6 * s, y: 6 * s)); p.addLine(to: CGPoint(x: 18 * s, y: 18 * s))
                    p.move(to: CGPoint(x: 18 * s, y: 6 * s)); p.addLine(to: CGPoint(x: 6 * s, y: 18 * s))
                }
                ctx.stroke(p, with: .color(Palette.sub), style: StrokeStyle(lineWidth: 2 * s, lineCap: .round, lineJoin: .round))
            }
            .frame(width: 24, height: 24)
            .frame(width: 48, height: 48).contentShape(Rectangle())
        }
        .buttonStyle(.plain).accessibilityLabel(kind == .back ? "Back" : "Close")
    }
}

/// The title row of a screen or sheet: Back at the start when it is a level down, Close at the end when it can be left
/// (inside the panel its tabs do that), and whatever else belongs beside the title in between.
struct ScreenHeader: View {
    let title: String
    var subtitle: String?
    var subtitleColour = Palette.sub
    var back: (() -> Void)?
    var onClose: (() -> Void)?
    var trailing: String?
    var accessory: AnyView?

    var body: some View {
        HStack(spacing: 0) {
            if let back { NavIcon(kind: .back, action: back) } else { Color.clear.frame(width: 16, height: 1) }
            VStack(alignment: .leading, spacing: 0) {
                Text(title).scaledFont(20, weight: .semibold).foregroundColor(Palette.gold).lineLimit(1)
                if let subtitle { Text(subtitle).scaledFont(13).foregroundColor(subtitleColour) }
            }
            Spacer(minLength: 8)
            if let trailing { Text(trailing).scaledFont(13).foregroundColor(Palette.sub).padding(.trailing, 8) }
            if let accessory { accessory }
            if let onClose { NavIcon(kind: .close, action: onClose) } else { Color.clear.frame(width: 8, height: 1) }
        }
        .frame(minHeight: 52)
    }
}
