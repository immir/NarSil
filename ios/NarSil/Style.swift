import SwiftUI

/// The colours of the prompt screens (the Android ones, so both ports look alike).
enum Palette {
    static let gold = Color(rgb: 0xF3D27A)
    static let sub = Color(rgb: 0x9A9AA0)
    static let body = Color(rgb: 0xD0D0D4)
    static let card = Color(rgb: 0x1E1E22)
    static let sheet = Color(rgb: 0x1C1C1E)
    static let screen = Color(rgb: 0x121214)
    static let splash = Color(rgb: 0x0E0E10)
    static let verse = Color(rgb: 0x8FC3FF)
    static let summary = Color(rgb: 0x90CAF9)
    static let good = Color(rgb: 0x81C784)
    static let bad = Color(rgb: 0xE57373)
    static let divider = Color(rgb: 0x2C2C2E)
    static let text = Color(rgb: 0xE6E6EA)
    /// Text and icons on a gold fill.
    static let onGold = Color(rgb: 0x241A00)
    static let warn = Color(rgb: 0xFFB74D)
    /// Switched off, locked, out of reach.
    static let dim = Color(rgb: 0x5A5A60)
    /// The strips (messages, status, buttons) and raised buttons.
    static let chrome = Color(rgb: 0x141414)
    static let raised = Color(rgb: 0x26262B)
    /// Blue means pressed; gold means selected or active.
    static let pressed = Color(rgb: 0x3A5F8A)
    /// A faint edge where one surface meets another.
    static let hairline = Color.white.opacity(0.10)
}

extension Color {
    init(rgb: UInt32) {
        self.init(red: Double((rgb >> 16) & 0xFF) / 255, green: Double((rgb >> 8) & 0xFF) / 255, blue: Double(rgb & 0xFF) / 255)
    }
}

/// The character a glyph codepoint draws as.
func glyphString(_ glyph: Int) -> String { Unicode.Scalar(UInt32(max(glyph, 0))).map { String(Character($0)) } ?? " " }

/// "12.3 lb" from tenths of a pound.
func pounds(_ tenths: Int) -> String { "\(tenths / 10).\(tenths % 10) lb" }

/// A panel tab's header: a title, with Back (or Close outside the panel's main page) on the left.
struct ScreenHeader: View {
    let title: String
    var subtitle: String?
    var back: (() -> Void)?
    var backLabel = "‹ Back"
    var trailing: String?

    var body: some View {
        HStack(spacing: 4) {
            if let back { Button(backLabel, action: back) }
            VStack(alignment: .leading, spacing: 0) {
                if let subtitle { Text(subtitle).scaledFont(12).foregroundColor(Palette.sub) }
                Text(title).scaledFont(20, weight: .semibold).foregroundColor(Palette.gold).lineLimit(1)
            }
            .padding(.leading, back == nil ? 12 : 4)
            Spacer()
            if let trailing { Text(trailing).scaledFont(13).foregroundColor(Palette.sub) }
        }
        .padding(.horizontal, 8).padding(.top, 8).padding(.bottom, 4)
    }
}

extension View {
    /// A card or dialog: its fill, rounded, with the faint edge.
    func cardSurface(_ fill: Color = Palette.card, radius: CGFloat = 10) -> some View {
        background(fill).clipShape(RoundedRectangle(cornerRadius: radius))
            .overlay(RoundedRectangle(cornerRadius: radius).strokeBorder(Palette.hairline, lineWidth: 1))
    }
}

/// The app's prominent button: gold with dark text (the system's would be its blue).
struct GoldButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var enabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .fontWeight(.semibold).foregroundColor(Palette.onGold)
            .padding(.horizontal, 16).padding(.vertical, 8).frame(minHeight: 36)
            .background(Palette.gold.opacity(enabled ? (configuration.isPressed ? 0.75 : 1) : 0.35), in: RoundedRectangle(cornerRadius: 10))
    }
}
