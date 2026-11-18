import SwiftUI
import UIKit

/// Keyboard answers while a native prompt is open (KeyInput.kt's answerByKey; all other keys are swallowed):
/// Esc cancels; item letters pick by label; y/n answer checks; keypad digits, arrows, ' (nearest) and t/5
/// (current target) answer aims.
func answerByKey(_ prompt: Prompt, _ code: Int) -> Int? {
    let esc = code == Keys.escape
    let digit = (Int(UnicodeScalar("1").value)...Int(UnicodeScalar("9").value)).contains(code) ? code - Int(UnicodeScalar("0").value) : nil
    let arrow: Int? = [Keys.arrowUp: 8, Keys.arrowDown: 2, Keys.arrowLeft: 4, Keys.arrowRight: 6][code]
    func ch(_ c: Character) -> Int { Int(c.unicodeScalars.first!.value) }
    switch prompt {
    case .items(let p):
        return esc ? -1 : p.items.first { $0.label != "-" && $0.label.unicodeScalars.first.map { Int($0.value) } == code }?.index
    case .check:
        if code == ch("y") || code == ch("Y") { return 1 }
        return code == ch("n") || code == ch("N") || esc ? 0 : nil
    case .screen(let p): return esc && p.stage != "death" ? -1 : nil
    case .skills: return esc ? -1 : code == Keys.enter ? NB.SKILL_ACCEPT : nil
    case .choice(let p):
        if esc { return -1 }
        let i = code - ch("a")
        return p.options.indices.contains(i) ? i : nil
    case .direction(let p):
        if esc { return -1 }
        if let d = digit { return d != 5 || p.allowHere ? d : nil }
        return arrow
    case .aim:
        if esc { return -1 }
        if let d = digit { return d == 5 ? NB.AIM_CURRENT : d }
        if code == ch("'") { return NB.AIM_NEAREST }
        if code == ch("t") { return NB.AIM_CURRENT }
        return arrow
    case .abilities, .smith, .text, .options, .pack, .knowledge, .character, .quantity:
        return esc ? -1 : nil
    }
}

/// A hardware keyboard: an invisible first responder that turns key presses into Angband keys.  At the map the
/// game gets them as typed; while a native prompt is open `answerByKey` gets first refusal.  The textui
/// screens have their own responder (TerminalView), so this one only listens in map mode.
struct HardwareKeys: UIViewRepresentable {
    let active: Bool
    let onKey: (Int, Int) -> Void

    func makeUIView(context: Context) -> KeyCatcher { KeyCatcher() }

    func updateUIView(_ v: KeyCatcher, context: Context) {
        v.onKey = onKey
        v.active = active
        DispatchQueue.main.async {
            if active, !v.isFirstResponder, v.window != nil { v.becomeFirstResponder() }
            else if !active, v.isFirstResponder { v.resignFirstResponder() }
        }
    }

    final class KeyCatcher: UIView {
        var onKey: (Int, Int) -> Void = { _, _ in }
        var active = false
        override var canBecomeFirstResponder: Bool { active }

        override func pressesBegan(_ presses: Set<UIPress>, with event: UIPressesEvent?) {
            var unhandled = Set<UIPress>()
            for p in presses {
                if let k = p.key, let code = Self.code(k) { onKey(code, 0) } else { unhandled.insert(p) }
            }
            if !unhandled.isEmpty { super.pressesBegan(unhandled, with: event) }
        }

        private static func code(_ k: UIKey) -> Int? {
            switch k.keyCode {
            case .keyboardEscape: return Keys.escape
            case .keyboardReturnOrEnter, .keypadEnter: return Keys.enter
            case .keyboardDeleteOrBackspace: return Keys.backspace
            case .keyboardTab: return Keys.tab
            case .keyboardUpArrow: return Keys.arrowUp
            case .keyboardDownArrow: return Keys.arrowDown
            case .keyboardLeftArrow: return Keys.arrowLeft
            case .keyboardRightArrow: return Keys.arrowRight
            default: break
            }
            if k.modifierFlags.contains(.control), let c = k.charactersIgnoringModifiers.unicodeScalars.first, c.isASCII {
                return Keys.ctrl(Int(c.value))
            }
            guard let c = k.characters.unicodeScalars.first, k.characters.unicodeScalars.count == 1, c.value >= 0x20, c.value < 0x7F else { return nil }
            return Int(c.value)
        }
    }
}
