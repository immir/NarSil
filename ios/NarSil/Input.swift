import Foundation
import UIKit

/// Angband keycodes from src/ui-event.h (the same ones Keys.kt uses).
enum Keys {
    static let enter = 0x9C
    static let escape = 0xE000
    static let backspace = 0x9F
}

enum Direction {
    /// Swipe octant (0 = east, anticlockwise) to Angband keypad direction.
    static let octantToDir = [6, 9, 8, 7, 4, 1, 2, 3]

    /// Screen-space vector (y grows downward) to octant: 0 = east, anticlockwise in 45-degree steps.
    static func octant(dx: CGFloat, dy: CGFloat) -> Int {
        let steps = Int((atan2(-dy, dx) / (.pi / 4)).rounded())
        return ((steps % 8) + 8) % 8
    }

    /// Keypad direction of a step (dx, dy), each -1...1 (dy grows downward); 5 is "here".
    static func keypad(dx: Int, dy: Int) -> Int {
        assert((-1...1).contains(dx) && (-1...1).contains(dy))
        return 5 - 3 * dy + dx
    }

    /// The answer that aims at a grid.
    static func aimAt(x: Int, y: Int) -> Int {
        assert(x >= 0 && x < NB.AIM_GRID_STRIDE && y >= 0)
        return NB.AIM_GRID_BASE + y * NB.AIM_GRID_STRIDE + x
    }
}

extension UIColor {
    /// 0xRRGGBB.
    convenience init(rgb: UInt32, alpha: CGFloat = 1) {
        self.init(red: CGFloat((rgb >> 16) & 0xFF) / 255, green: CGFloat((rgb >> 8) & 0xFF) / 255,
                  blue: CGFloat(rgb & 0xFF) / 255, alpha: alpha)
    }
}
