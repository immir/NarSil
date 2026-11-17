import SwiftUI
import UIKit

/// What a d-pad gesture asks for; `dir` is a keypad direction.
enum PadAction: Equatable {
    case step(Int), run(Int), wait, rest
}

/// Flat 3x3 keypad (Dpad.kt).
///  - tap a direction: step; hold it: run
///  - centre: tap waits; drag out towards a direction runs that way; hold rests
struct Dpad: View {
    let size: CGFloat
    let onAction: (PadAction) -> Void

    @State private var lit = 0
    @State private var session: Session?

    private static let layout = [[7, 8, 9], [4, 5, 6], [1, 2, 3]]
    private static let holdSeconds = 0.45
    private static let swipeOut: CGFloat = 0.6  // key widths from the centre

    /// One touch, from down to up.
    private final class Session {
        let startKey: Int
        var fired = false
        var timer: Task<Void, Never>?
        init(startKey: Int) { self.startKey = startKey }
    }

    private var key: CGFloat { size / 3 }

    private func dirAt(_ p: CGPoint) -> Int {
        let row = min(max(Int(p.y / key), 0), 2), col = min(max(Int(p.x / key), 0), 2)
        return Self.layout[row][col]
    }

    private func fire(_ s: Session, _ a: PadAction) {
        s.fired = true
        s.timer?.cancel()
        if case .run = a { UIImpactFeedbackGenerator(style: .medium).impactOccurred() }
        if a == .rest { UIImpactFeedbackGenerator(style: .medium).impactOccurred() }
        onAction(a)
    }

    private var gesture: some Gesture {
        DragGesture(minimumDistance: 0)
            .onChanged { v in
                if let s = session {
                    guard s.startKey == 5, !s.fired else { return }
                    let c = CGPoint(x: size / 2, y: size / 2)
                    let dx = v.location.x - c.x, dy = v.location.y - c.y
                    if hypot(dx, dy) > Self.swipeOut * key {
                        let dir = Direction.octantToDir[Direction.octant(dx: dx, dy: dy)]
                        lit = dir
                        fire(s, .run(dir))
                    }
                    return
                }
                let s = Session(startKey: dirAt(v.startLocation))
                session = s
                lit = s.startKey
                s.timer = Task { @MainActor in
                    try? await Task.sleep(nanoseconds: UInt64(Self.holdSeconds * 1_000_000_000))
                    guard !Task.isCancelled, !s.fired else { return }
                    fire(s, s.startKey == 5 ? .rest : .run(s.startKey))
                }
            }
            .onEnded { _ in
                if let s = session, !s.fired { fire(s, s.startKey == 5 ? .wait : .step(s.startKey)) }
                session?.timer?.cancel()
                session = nil
                lit = 0
            }
    }

    var body: some View {
        Canvas { ctx, sz in
            let k = min(sz.width, sz.height) / 3, gap: CGFloat = 3
            for (row, dirs) in Self.layout.enumerated() {
                for (col, dir) in dirs.enumerated() {
                    let r = CGRect(x: CGFloat(col) * k + gap / 2, y: CGFloat(row) * k + gap / 2, width: k - gap, height: k - gap)
                    let shape = Path(roundedRect: r, cornerRadius: 6)
                    ctx.fill(shape, with: .color(dir == lit ? Color(red: 0x3A / 255, green: 0x5F / 255, blue: 0x8A / 255, opacity: 0.82)
                                                            : Color(red: 0x26 / 255, green: 0x26 / 255, blue: 0x2B / 255, opacity: 0.55)))
                    ctx.stroke(shape, with: .color(.white.opacity(0.19)), lineWidth: 1)
                    let ink = Color.white.opacity(dir == lit ? 1 : 0.8)
                    let mid = CGPoint(x: r.midX, y: r.midY)
                    if dir == 5 {
                        ctx.fill(Path(ellipseIn: CGRect(x: mid.x - k * 0.07, y: mid.y - k * 0.07, width: k * 0.14, height: k * 0.14)), with: .color(ink))
                    } else {
                        ctx.fill(Self.arrow(at: mid, dir: dir, key: k), with: .color(ink))
                    }
                }
            }
        }
        .frame(width: size, height: size)
        .contentShape(Rectangle())
        .gesture(gesture)
    }

    /// A small triangle pointing along `dir`.
    private static func arrow(at mid: CGPoint, dir: Int, key: CGFloat) -> Path {
        let angle: [Int: Double] = [6: 0, 3: 45, 2: 90, 1: 135, 4: 180, 7: 225, 8: 270, 9: 315]
        let a = key * 0.16
        let theta = (angle[dir] ?? 0) * .pi / 180
        let points = [CGPoint(x: a, y: 0), CGPoint(x: -a * 0.7, y: -a * 0.85), CGPoint(x: -a * 0.7, y: a * 0.85)].map {
            CGPoint(x: mid.x + $0.x * cos(theta) - $0.y * sin(theta), y: mid.y + $0.x * sin(theta) + $0.y * cos(theta))
        }
        var p = Path()
        p.move(to: points[0]); p.addLine(to: points[1]); p.addLine(to: points[2]); p.closeSubpath()
        return p
    }
}
