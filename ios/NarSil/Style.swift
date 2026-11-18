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
