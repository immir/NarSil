import UIKit

/// A light tick under every finger-down, like the system keyboard: one recognizer on the window that never
/// recognises anything, so it only watches and does not take touches from the controls.
final class TapHaptics: UIGestureRecognizer {
    private static let generator = UIImpactFeedbackGenerator(style: .light)

    @MainActor static func install() {
        guard let window = UIApplication.shared.connectedScenes.compactMap({ ($0 as? UIWindowScene)?.keyWindow }).first,
              !(window.gestureRecognizers ?? []).contains(where: { $0 is TapHaptics }) else { return }
        let watcher = TapHaptics(target: nil, action: nil)
        watcher.cancelsTouchesInView = false
        watcher.delaysTouchesBegan = false
        watcher.delaysTouchesEnded = false
        window.addGestureRecognizer(watcher)
        generator.prepare()
    }

    /// One tick at the strength chosen in Options (a multiple of the default; the motor tops out at full intensity).
    @MainActor static func sample() {
        let scale = UserDefaults.standard.object(forKey: "tapStrength") as? Double ?? 1
        generator.impactOccurred(intensity: min(1, 0.6 * scale))
    }

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent) {
        if UserDefaults.standard.object(forKey: "tapBuzz") as? Bool ?? true { Self.sample() }
        state = .failed
    }
}
