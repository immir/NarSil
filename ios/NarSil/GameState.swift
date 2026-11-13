import Foundation
import QuartzCore
import SwiftUI

/// Calls `tick` once per display frame while running (the Swift side of Choreographer).
@MainActor
private final class FrameTicker: NSObject {
    private var link: CADisplayLink?
    private let tick: @MainActor () -> Void
    init(_ tick: @escaping @MainActor () -> Void) { self.tick = tick }
    @objc private func fire() { tick() }
    func start() {
        guard link == nil else { return }
        let l = CADisplayLink(target: self, selector: #selector(fire))
        l.add(to: .main, forMode: .common)
        link = l
    }
    func stop() { link?.invalidate(); link = nil }
}

/// The UI's copy of everything the game publishes, refreshed once per frame and only when a generation
/// counter moves.  SwiftUI observes the @Published fields; the map arrays are read directly by the map view,
/// which redraws when `mapVersion` changes.
@MainActor
final class GameState: ObservableObject {
    @Published private(set) var mapMode = false
    @Published private(set) var atPrompt = false
    @Published private(set) var status = Status()
    @Published private(set) var prompt: Prompt?
    /// The character panel tab the prompt belongs to, or -1.
    @Published private(set) var panelTab = -1
    @Published private(set) var messages: [Message] = []
    @Published var info: Info?
    /// Bumped whenever the map, the path or the aim targets change.
    @Published private(set) var mapVersion = 0

    private(set) var meta = [Int32](repeating: 0, count: NB.META_COUNT)
    private(set) var map = [Int32]()
    private var path = [Int32](repeating: 0, count: 2 * 600)
    private(set) var pathPairs = 0

    private var mapGen: UInt64 = 0, pathGen: UInt64 = 0, promptGen: UInt64 = 0, messagesGen: UInt64 = 0
    private var infoGen: UInt64?
    private var ticker: FrameTicker?

    /// Polls once per display frame while started; idempotent.
    func start() {
        if ticker == nil { ticker = FrameTicker { [weak self] in self?.poll() } }
        ticker?.start()
    }
    func stop() { ticker?.stop() }

    func pathPoint(_ i: Int) -> Point { Point(x: Int(path[2 * i]), y: Int(path[2 * i + 1])) }

    func poll() {
        var changed = pollMap()
        let pg = Native.pathGeneration
        if pg != pathGen { pathGen = pg; pathPairs = Native.path(into: &path); changed = true }
        let promptG = Native.promptGeneration
        if promptG != promptGen {
            promptGen = promptG
            prompt = Parse.prompt(Native.prompt())
            panelTab = Native.panelTab
            changed = true  // the map draws aim targets
        }
        if changed { mapVersion += 1 }
        let mg = Native.messagesGeneration
        if mg != messagesGen { messagesGen = mg; messages = Parse.messages(Native.messages()) }
        let ig = Native.infoGeneration
        if let seen = infoGen {
            if ig != seen { infoGen = ig; info = Parse.info(Native.info()) }
        } else {
            infoGen = ig  // ignore whatever was there before this UI existed
        }
    }

    private func pollMap() -> Bool {
        guard Native.mapGeneration != mapGen else { return false }
        var gen = Native.mapSnapshot(meta: &meta, cells: &map)
        if gen == 0 {
            map = [Int32](repeating: 0, count: NB.MAP_CELL_INTS * Int(meta[NB.META_WIDTH]) * Int(meta[NB.META_HEIGHT]))
            gen = Native.mapSnapshot(meta: &meta, cells: &map)
        }
        mapGen = gen
        func m(_ i: Int) -> Int { Int(meta[i]) }
        mapMode = m(NB.META_MAP_MODE) != 0
        atPrompt = m(NB.META_AT_PROMPT) != 0
        let foe = m(NB.META_FOE_X) < 0 ? nil : Foe(x: m(NB.META_FOE_X), y: m(NB.META_FOE_Y), name: Native.foeName(),
                                                    healthPct: m(NB.META_FOE_HEALTH), stance: m(NB.META_FOE_STANCE),
                                                    morale: m(NB.META_FOE_MORALE))
        status = Status(hp: m(NB.META_HP), maxHp: m(NB.META_MAX_HP), voice: m(NB.META_VOICE), maxVoice: m(NB.META_MAX_VOICE),
                        depth: m(NB.META_DEPTH), exp: m(NB.META_EXP), context: m(NB.META_CONTEXT), foe: foe,
                        newExp: m(NB.META_NEW_EXP), hpWarn: m(NB.META_HP_WARN))
        return true
    }

    /// Glyph and attr the map shows at (x, y), or nil off the map.
    func mapCell(x: Int, y: Int) -> (glyph: Int, attr: Int)? {
        let w = Int(meta[NB.META_WIDTH]), h = Int(meta[NB.META_HEIGHT])
        guard (0..<w).contains(x), (0..<h).contains(y) else { return nil }
        let i = NB.MAP_CELL_INTS * (y * w + x)
        return (Int(map[i]), Int(map[i + 1]) & 0xFFFF)
    }
}
