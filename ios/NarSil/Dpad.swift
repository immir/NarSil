import SwiftUI
import UIKit

/// What a d-pad gesture asks for; `dir` is a keypad direction.
enum PadAction: Equatable {
    case step(Int), run(Int), wait, rest
}

/// Flat 3x3 keypad (Dpad.kt).
///  - tap a direction: step; hold it: run
///  - centre: tap waits; drag out towards a direction runs that way; hold rests
///  - with `onMove`: hold the centre, then drag, to move the pad
struct Dpad: View {
    let size: CGFloat
    let onAction: (PadAction) -> Void
    var onMove: ((CGSize) -> Void)?

    @State private var lit = 0
    @State private var session: Session?

    private static let layout = [[7, 8, 9], [4, 5, 6], [1, 2, 3]]
    private static let holdSeconds = 0.45
    private static let swipeOut: CGFloat = 0.6  // key widths from the centre

    /// One touch, from down to up.
    private final class Session {
        let startKey: Int
        var fired = false
        var held = false
        var moved = false
        var last = CGPoint.zero
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
                    if s.held {
                        /* The pad moves under the finger, so the local position comes back to `last` after each move. */
                        let d = CGSize(width: v.location.x - s.last.x, height: v.location.y - s.last.y)
                        if abs(d.width) + abs(d.height) > 0.5 { s.moved = true; onMove?(d) }
                        return
                    }
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
                s.last = v.location
                s.timer = Task { @MainActor in
                    try? await Task.sleep(nanoseconds: UInt64(Self.holdSeconds * 1_000_000_000))
                    guard !Task.isCancelled, !s.fired else { return }
                    if s.startKey == 5 && onMove != nil {
                        s.held = true
                        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                    } else {
                        fire(s, s.startKey == 5 ? .rest : .run(s.startKey))
                    }
                }
            }
            .onEnded { _ in
                if let s = session, s.held { if !s.moved { fire(s, .rest) } }
                else if let s = session, !s.fired { fire(s, s.startKey == 5 ? .wait : .step(s.startKey)) }
                session?.timer?.cancel()
                session = nil
                lit = 0
            }
    }

    private static let keyLit = Color(red: 58.0 / 255.0, green: 95.0 / 255.0, blue: 138.0 / 255.0, opacity: 0.82)
    private static let keyIdle = Color(red: 38.0 / 255.0, green: 38.0 / 255.0, blue: 43.0 / 255.0, opacity: 0.55)

    var body: some View {
        let lit = self.lit
        return Canvas { ctx, sz in
            let k: CGFloat = min(sz.width, sz.height) / 3
            let gap: CGFloat = 3
            for (row, dirs) in Self.layout.enumerated() {
                for (col, dir) in dirs.enumerated() {
                    let r = CGRect(x: CGFloat(col) * k + gap / 2, y: CGFloat(row) * k + gap / 2, width: k - gap, height: k - gap)
                    let shape = Path(roundedRect: r, cornerRadius: 6)
                    let on = dir == lit
                    ctx.fill(shape, with: .color(on ? Self.keyLit : Self.keyIdle))
                    ctx.stroke(shape, with: .color(Color.white.opacity(0.19)), lineWidth: 1)
                    let ink = Color.white.opacity(on ? 1.0 : 0.8)
                    if dir == 5 {
                        let d: CGFloat = k * 0.14
                        ctx.fill(Path(ellipseIn: CGRect(x: r.midX - d / 2, y: r.midY - d / 2, width: d, height: d)), with: .color(ink))
                    } else {
                        ctx.fill(Self.arrow(at: CGPoint(x: r.midX, y: r.midY), dir: dir, key: k), with: .color(ink))
                    }
                }
            }
        }
        .frame(width: size, height: size)
        .contentShape(Rectangle())
        .gesture(gesture)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Movement pad")
        .accessibilityActions {
            ForEach(Self.names, id: \.0) { dir, name in
                Button("Step \(name)") { onAction(.step(dir)) }
                Button("Run \(name)") { onAction(.run(dir)) }
            }
            Button("Wait") { onAction(.wait) }
            Button("Rest") { onAction(.rest) }
        }
    }

    private static let names: [(Int, String)] = [(8, "north"), (9, "north-east"), (6, "east"), (3, "south-east"), (2, "south"), (1, "south-west"), (4, "west"), (7, "north-west")]

    /// A small triangle pointing along `dir`.
    private static func arrow(at mid: CGPoint, dir: Int, key: CGFloat) -> Path {
        let degrees: [Int: Double] = [6: 0, 3: 45, 2: 90, 1: 135, 4: 180, 7: 225, 8: 270, 9: 315]
        let theta = CGFloat((degrees[dir] ?? 0) * Double.pi / 180)
        let c = cos(theta), s = sin(theta)
        let a: CGFloat = key * 0.16
        let local: [CGPoint] = [CGPoint(x: a, y: 0), CGPoint(x: -a * 0.7, y: -a * 0.85), CGPoint(x: -a * 0.7, y: a * 0.85)]
        var p = Path()
        for (i, q) in local.enumerated() {
            let x: CGFloat = mid.x + q.x * c - q.y * s
            let y: CGFloat = mid.y + q.x * s + q.y * c
            if i == 0 { p.move(to: CGPoint(x: x, y: y)) } else { p.addLine(to: CGPoint(x: x, y: y)) }
        }
        p.closeSubpath()
        return p
    }
}
