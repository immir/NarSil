import UIKit

/// Draws Angband (codepoint, attr) cells on a fixed grid (GlyphPainter.kt).  The system's monospaced font
/// is used; cells are as wide as its widest ASCII glyph, in case a fallback font is proportional.
final class GlyphPainter {
    private(set) var cellW: CGFloat = 0
    private(set) var cellH: CGFloat = 0
    private var font = UIFont.monospacedSystemFont(ofSize: 12, weight: .regular)
    private var attributes = [Int: [NSAttributedString.Key: Any]]()
    private(set) var palette = [UInt32]()

    private static let shade = 28, maxColours = 32, bgSame = 1, bgDark = 2

    /// Font size chosen so a cell is `height` points tall.
    func setCellHeight(_ height: CGFloat) {
        assert(height > 0)
        let probe = UIFont.monospacedSystemFont(ofSize: 100, weight: .regular)
        font = UIFont.monospacedSystemFont(ofSize: 100 * height / probe.lineHeight, weight: .regular)
        cellH = font.lineHeight
        cellW = (0x21...0x7E).map { (String(UnicodeScalar($0)!) as NSString).size(withAttributes: [.font: font]).width }.max() ?? cellH / 2
        attributes.removeAll()
    }

    func setPalette(_ p: [UInt32]) {
        guard p != palette else { return }
        palette = p
        attributes.removeAll()
    }

    func rgb(_ index: Int) -> UInt32 { palette.indices.contains(index) ? palette[index] : 0xFFFFFF }

    /// attr = colour + 256 * background mode (z-color.h: BG_BLACK, BG_SAME, BG_DARK).
    func drawCell(_ ctx: CGContext, x: CGFloat, y: CGFloat, glyph: Int, attr: Int) {
        let colour = attr % 256 % Self.maxColours
        let fg = rgb(colour)
        switch attr / 256 {
        case Self.bgSame: fill(ctx, fg, x, y)
        case Self.bgDark: fill(ctx, rgb(Self.shade), x, y)
        default: break
        }
        guard glyph != 0x20, glyph > 0, let u = Unicode.Scalar(UInt32(glyph)) else { return }
        let attrs = attributes[colour] ?? {
            let a: [NSAttributedString.Key: Any] = [.font: font, .foregroundColor: UIColor(rgb: fg)]
            attributes[colour] = a
            return a
        }()
        let s = String(u) as NSString
        let w = s.size(withAttributes: attrs).width
        s.draw(at: CGPoint(x: x + (cellW - w) / 2, y: y), withAttributes: attrs)
    }

    private func fill(_ ctx: CGContext, _ rgb: UInt32, _ x: CGFloat, _ y: CGFloat) {
        ctx.setFillColor(UIColor(rgb: rgb).cgColor)
        ctx.fill(CGRect(x: x, y: y, width: cellW, height: cellH))
    }
}
