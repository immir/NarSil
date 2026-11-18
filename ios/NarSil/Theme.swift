import SwiftUI

/// Colour themes for the map and lists (Themes.kt; keep the two in step): each recolours the game's Angband
/// colours from a dozen base hues, and every hue that would be hard to read on black is lifted towards white
/// until it reaches `minLuminance`.  Black, the dark grey and the shade are never touched.
struct Theme {
    struct Hues { let fg, grey, dim, red, green, blue, yellow, orange, magenta, violet, cyan, brown: UInt32 }
    let name: String
    let hues: Hues?
    let minLuminance: Double

    static let all: [Theme] = [
        Theme(name: "Bright", hues: nil, minLuminance: 0.22),
        Theme(name: "Original", hues: nil, minLuminance: 0.0),
        Theme(name: "High contrast", hues: nil, minLuminance: 0.40),
        Theme(name: "Solarized", hues: Hues(fg: 0xFDF6E3, grey: 0x93A1A1, dim: 0x586E75, red: 0xDC322F, green: 0x859900, blue: 0x268BD2,
              yellow: 0xB58900, orange: 0xCB4B16, magenta: 0xD33682, violet: 0x6C71C4, cyan: 0x2AA198, brown: 0x9C6B30), minLuminance: 0.20),
        Theme(name: "Dracula", hues: Hues(fg: 0xF8F8F2, grey: 0xBFBFC8, dim: 0x6272A4, red: 0xFF5555, green: 0x50FA7B, blue: 0x6D9CFF,
              yellow: 0xF1FA8C, orange: 0xFFB86C, magenta: 0xFF79C6, violet: 0xBD93F9, cyan: 0x8BE9FD, brown: 0xC08A58), minLuminance: 0.22),
        Theme(name: "Gruvbox", hues: Hues(fg: 0xEBDBB2, grey: 0xA89984, dim: 0x665C54, red: 0xFB4934, green: 0xB8BB26, blue: 0x83A598,
              yellow: 0xFABD2F, orange: 0xFE8019, magenta: 0xD3869B, violet: 0xB16286, cyan: 0x8EC07C, brown: 0xBD7A3A), minLuminance: 0.22),
    ]
    static func byIndex(_ i: Int) -> Theme { all[min(max(i, 0), all.count - 1)] }

    private enum C {
        static let dark = 0, white = 1, slate = 2, orange = 3, red = 4, green = 5, blue = 6, umber = 7, lDark = 8, lWhite = 9
        static let lPurple = 10, yellow = 11, lRed = 12, lGreen = 13, lBlue = 14, lUmber = 15, purple = 16, violet = 17
        static let teal = 18, mud = 19, lYellow = 20, magenta = 21, lTeal = 22, lViolet = 23, lPink = 24, mustard = 25
        static let blueSlate = 26, deepLBlue = 27, shade = 28
    }
    private static let untouched: Set<Int> = [C.dark, C.lDark, C.shade]

    /// `game` is the game's own palette (0xRRGGBB per colour index).
    func apply(_ game: [UInt32]) -> [UInt32] {
        var p = game
        if let h = hues {
            func set(_ i: Int, _ rgb: UInt32) { if i < p.count { p[i] = rgb } }
            let l = { Theme.mix($0, 0xFFFFFF, 0.35) }
            set(C.white, h.fg); set(C.slate, h.grey); set(C.orange, h.orange); set(C.red, h.red)
            set(C.green, h.green); set(C.blue, h.blue); set(C.umber, h.brown); set(C.lDark, h.dim)
            set(C.lWhite, Theme.mix(h.fg, h.grey, 0.4)); set(C.lPurple, l(h.magenta)); set(C.yellow, h.yellow)
            set(C.lRed, l(h.red)); set(C.lGreen, l(h.green)); set(C.lBlue, l(h.blue))
            set(C.lUmber, l(h.brown)); set(C.purple, h.magenta); set(C.violet, h.violet); set(C.teal, h.cyan)
            set(C.mud, Theme.mix(h.brown, h.green, 0.5)); set(C.lYellow, l(h.yellow)); set(C.magenta, h.magenta)
            set(C.lTeal, l(h.cyan)); set(C.lViolet, l(h.violet)); set(C.lPink, Theme.mix(h.red, 0xFFFFFF, 0.5))
            set(C.mustard, Theme.mix(h.yellow, h.brown, 0.5)); set(C.blueSlate, Theme.mix(h.blue, h.fg, 0.5)); set(C.deepLBlue, l(h.blue))
        }
        return p.enumerated().map { i, c in Theme.untouched.contains(i) ? c : Theme.lift(c, minLuminance) }
    }

    /// A handful of colours for the options preview.
    func swatch(_ game: [UInt32]) -> [UInt32] {
        let p = apply(game)
        return [C.red, C.orange, C.yellow, C.green, C.teal, C.blue, C.violet, C.white].filter { $0 < p.count }.map { p[$0] }
    }

    private static func channel(_ rgb: UInt32, _ shift: UInt32) -> Double { Double((rgb >> shift) & 0xFF) }

    static func mix(_ a: UInt32, _ b: UInt32, _ t: Double) -> UInt32 {
        [16, 8, 0].reduce(UInt32(0)) { acc, s in
            (acc << 8) | UInt32(min(255, max(0, Int(channel(a, s) * (1 - t) + channel(b, s) * t))))
        }
    }

    /// Relative luminance (WCAG) of 0xRRGGBB.
    static func luminance(_ rgb: UInt32) -> Double {
        func lin(_ c: Double) -> Double { let v = c / 255; return v <= 0.03928 ? v / 12.92 : pow((v + 0.055) / 1.055, 2.4) }
        return 0.2126 * lin(channel(rgb, 16)) + 0.7152 * lin(channel(rgb, 8)) + 0.0722 * lin(channel(rgb, 0))
    }

    /// Mixes `rgb` towards white in small steps until it is at least `min` luminance.
    static func lift(_ rgb: UInt32, _ min: Double) -> UInt32 {
        (0...20).lazy.map { mix(rgb, 0xFFFFFF, Double($0) * 0.05) }.first { luminance($0) >= min } ?? 0xFFFFFF
    }
}

/// Text sizes (Options → the four scales): every `scaledFont` multiplies its size by the nearest `textScale`.
private struct TextScaleKey: EnvironmentKey { static let defaultValue: CGFloat = 1 }
extension EnvironmentValues {
    var textScale: CGFloat {
        get { self[TextScaleKey.self] }
        set { self[TextScaleKey.self] = newValue }
    }
}

/// The size is also scaled by the system's text size setting (Dynamic Type), up to a third larger so layouts hold;
/// `dynamic: false` leaves it out (the panel's tab names, which must fit one line).
private struct ScaledFont: ViewModifier {
    @Environment(\.textScale) private var scale
    @Environment(\.dynamicTypeSize) private var typeSize  // read so the view redraws when the setting changes
    let size: CGFloat, weight: Font.Weight, design: Font.Design, dynamic: Bool
    func body(content: Content) -> some View {
        let system = dynamic ? min(UIFontMetrics.default.scaledValue(for: size), size * 4 / 3) : size
        return content.font(.system(size: system * scale, weight: weight, design: design))
    }
}

extension View {
    func scaledFont(_ size: CGFloat, weight: Font.Weight = .regular, design: Font.Design = .default, dynamic: Bool = true) -> some View {
        modifier(ScaledFont(size: size, weight: weight, design: design, dynamic: dynamic))
    }
    func textScale(_ s: Double) -> some View { environment(\.textScale, CGFloat(s)) }
}
