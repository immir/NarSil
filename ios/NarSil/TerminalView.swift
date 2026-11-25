import SwiftUI
import UIKit

/// Fallback for any textui screen the native UI does not cover: draws the game's character grid, turns taps,
/// long presses and swipes into mouse clicks and direction keys (TerminalView.kt).
struct TerminalScreen: View {
    @ObservedObject var game: GameState
    @StateObject private var keys = TerminalKeys()

    var body: some View {
        VStack(spacing: 0) {
            TerminalSurface(game: game, keys: keys)
            KeyBar(waiting: game.waiting, keys: keys)
        }
        .background(Color.black.ignoresSafeArea())
    }
}

/// Angband keycodes for arrows (src/ui-event.h).
private enum Arrow { static let down = 0x80, left = 0x81, right = 0x82, up = 0x83 }

/// Every key source (the soft keyboard, the key bar) goes through here, so the sticky Ctrl latch applies to all of them.
@MainActor
final class TerminalKeys: ObservableObject {
    @Published var ctrl = false
    fileprivate weak var view: TerminalUIView?

    /// A typed character; with Ctrl latched, a control character (as main-gcu delivers them: raw ASCII, no modifier).
    func char(_ cp: Int) {
        let code = ctrl && (0x40...0x7F).contains(cp) ? cp & 0x1F : cp
        ctrl = false
        Native.pushKey(code)
    }
    func special(_ code: Int) { ctrl = false; Native.pushKey(code) }

    func toggleKeyboard() {
        guard let v = view else { return }
        if v.isFirstResponder { v.resignFirstResponder() } else { v.becomeFirstResponder() }
    }
}

struct TerminalSurface: UIViewRepresentable {
    @ObservedObject var game: GameState
    let keys: TerminalKeys

    func makeUIView(context: Context) -> TerminalUIView { let v = TerminalUIView(game: game, keys: keys); keys.view = v; return v }
    func updateUIView(_ v: TerminalUIView, context: Context) { _ = game.termVersion; v.setNeedsDisplay() }
}

final class TerminalUIView: UIView, UIKeyInput {
    private let game: GameState
    private let keys: TerminalKeys
    private let painter = GlyphPainter()
    private var origin = CGPoint.zero

    /* The soft keyboard: raw characters, no suggestions. */
    var autocorrectionType: UITextAutocorrectionType = .no
    var autocapitalizationType: UITextAutocapitalizationType = .none
    var spellCheckingType: UITextSpellCheckingType = .no
    var keyboardType: UIKeyboardType = .asciiCapable
    override var canBecomeFirstResponder: Bool { true }
    var hasText: Bool { true }
    func insertText(_ text: String) {
        for u in text.unicodeScalars { if u == "\n" { keys.special(Keys.enter) } else { keys.char(Int(u.value)) } }
    }
    func deleteBackward() { keys.special(Keys.backspace) }

    init(game: GameState, keys: TerminalKeys) {
        self.game = game
        self.keys = keys
        super.init(frame: .zero)
        backgroundColor = .black
        isOpaque = true
        let tap = UITapGestureRecognizer(target: self, action: #selector(tapped(_:)))
        let hold = UILongPressGestureRecognizer(target: self, action: #selector(held(_:)))
        let pan = UIPanGestureRecognizer(target: self, action: #selector(panned(_:)))
        pan.maximumNumberOfTouches = 1
        [tap, hold, pan].forEach(addGestureRecognizer)
    }
    required init?(coder: NSCoder) { fatalError("not used") }

    /// Largest font for which the grid fits; the grid is centred.
    private func fit() {
        guard game.termCols > 0, game.termRows > 0, bounds.width > 0 else { return }
        painter.setCellHeight(bounds.height / CGFloat(game.termRows))
        if painter.cellW * CGFloat(game.termCols) > bounds.width {
            painter.setCellHeight(painter.cellH * bounds.width / (painter.cellW * CGFloat(game.termCols)))
        }
        origin = CGPoint(x: (bounds.width - CGFloat(game.termCols) * painter.cellW) / 2,
                         y: (bounds.height - CGFloat(game.termRows) * painter.cellH) / 2)
    }

    override func layoutSubviews() { super.layoutSubviews(); fit(); setNeedsDisplay() }

    override func draw(_ rect: CGRect) {
        guard let ctx = UIGraphicsGetCurrentContext() else { return }
        ctx.setFillColor(UIColor.black.cgColor)
        ctx.fill(bounds)
        let cols = game.termCols, rows = game.termRows
        guard cols > 0, rows > 0, !game.palette.isEmpty, game.term.count == 2 * cols * rows else { return }
        if painter.cellH == 0 { fit() }
        painter.setPalette(game.palette)
        for y in 0..<rows {
            for x in 0..<cols {
                let i = 2 * (y * cols + x)
                painter.drawCell(ctx, x: origin.x + CGFloat(x) * painter.cellW, y: origin.y + CGFloat(y) * painter.cellH,
                                 glyph: Int(game.term[i]), attr: Int(game.term[i + 1]))
            }
        }
        let c = game.termCursor
        if c.visible {
            ctx.setFillColor(UIColor.yellow.cgColor)
            ctx.fill(CGRect(x: origin.x + CGFloat(c.x) * painter.cellW, y: origin.y + CGFloat(c.y + 1) * painter.cellH - painter.cellH / 8,
                            width: painter.cellW, height: painter.cellH / 8))
        }
    }

    private func cell(at p: CGPoint) -> (Int, Int)? {
        guard painter.cellW > 0, painter.cellH > 0 else { return nil }
        let x = Int(floor((p.x - origin.x) / painter.cellW)), y = Int(floor((p.y - origin.y) / painter.cellH))
        return (0..<game.termCols).contains(x) && (0..<game.termRows).contains(y) ? (x, y) : nil
    }

    @objc private func tapped(_ g: UITapGestureRecognizer) {
        if let (x, y) = cell(at: g.location(in: self)) { Native.pushMouse(x: x, y: y, button: 1) }
    }

    @objc private func held(_ g: UILongPressGestureRecognizer) {
        guard g.state == .began, let (x, y) = cell(at: g.location(in: self)) else { return }
        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
        Native.pushMouse(x: x, y: y, button: 2)
    }

    /// A swipe is a direction: arrows for the cardinals (menus scroll), keypad digits for the diagonals.
    @objc private func panned(_ g: UIPanGestureRecognizer) {
        guard g.state == .ended else { return }
        let t = g.translation(in: self)
        guard hypot(t.x, t.y) >= 2 * painter.cellW else { return }
        let o = Direction.octant(dx: t.x, dy: t.y)
        let arrows = [0: Arrow.right, 2: Arrow.up, 4: Arrow.left, 6: Arrow.down]
        Native.pushKey(arrows[o] ?? Int(UnicodeScalar("0").value) + Direction.octantToDir[o])
    }
}

/// Bottom bar for the remaining textui screens: only what the screen is waiting for; the rarer keys behind ⋯.
struct KeyBar: View {
    let waiting: GameState.Waiting
    @ObservedObject var keys: TerminalKeys
    @State private var more = false

    private var choices: [(String, Int)] {
        switch waiting {
        case .yesNo: return [("Yes", Int(UnicodeScalar("y").value)), ("No", Int(UnicodeScalar("n").value))]
        case .more, .anyKey: return [("Continue", Keys.enter)]
        case .atSign: return [("Confirm (@)", Int(UnicodeScalar("@").value)), ("Cancel", Keys.escape)]
        case .menu: return [("Back", Keys.escape), ("OK", Keys.enter)]
        }
    }

    @ViewBuilder private func key(_ k: (String, Int), prominent: Bool) -> some View {
        if prominent {
            Button { keys.special(k.1) } label: { Text(k.0).frame(maxWidth: .infinity, minHeight: 36) }.buttonStyle(GoldButtonStyle())
        } else {
            Button { keys.special(k.1) } label: { Text(k.0).frame(maxWidth: .infinity, minHeight: 36) }.buttonStyle(.bordered)
        }
    }

    private func small(_ label: String, _ run: @escaping () -> Void) -> some View {
        Button(action: run) { Text(label).frame(maxWidth: .infinity, minHeight: 32) }.buttonStyle(.bordered)
    }

    var body: some View {
        VStack(spacing: 6) {
            if more {
                HStack(spacing: 6) {
                    small("Esc") { keys.special(Keys.escape) }
                    small("Enter") { keys.special(Keys.enter) }
                    small(keys.ctrl ? "Ctrl ●" : "Ctrl") { keys.ctrl.toggle() }
                    small("⌫") { keys.special(Keys.backspace) }
                    small("Space") { keys.char(0x20) }
                }
            }
            HStack(spacing: 6) {
                key(choices[0], prominent: waiting != .menu)
                if choices.count > 1 { key(choices[1], prominent: choices[1].0 == "OK") }
                Button("⌨") { keys.toggleKeyboard() }.buttonStyle(.bordered).frame(minHeight: 36)
                Button("⋯") { more.toggle() }.buttonStyle(.bordered).frame(minHeight: 36)
            }
        }
        .padding(6).background(Color(rgb: 0x101012))
    }
}
