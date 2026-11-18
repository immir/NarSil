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
    @Published private(set) var quickSlots: [QuickSlot] = []
    private var saveCount: UInt64?
    /// The game's colours, 0xRRGGBB by Angband colour index; refreshed with every map frame.
    private(set) var palette = [UInt32]()
    /// The game's own colours; `palette` is them through the chosen theme.
    private(set) var gamePalette = [UInt32]()
    var themeIndex = 0 { didSet { if themeIndex != oldValue { recolour(gamePalette, force: true) } } }

    private func recolour(_ game: [UInt32], force: Bool = false) {
        guard force || game != gamePalette else { return }
        gamePalette = game
        palette = Theme.byIndex(themeIndex).apply(game)
        mapVersion += 1
        termVersion += 1
    }
    /// The textui's top line: a prompt the native UI does not cover, or -more-.
    @Published private(set) var topLine = ""
    /// What a textui screen is waiting for, judged from its text; picks the key bar's buttons.
    enum Waiting { case menu, yesNo, more, anyKey, atSign }
    @Published private(set) var waiting = Waiting.menu
    /// Bumped whenever the textui grid changes.
    @Published private(set) var termVersion = 0
    private(set) var term = [Int32]()
    private(set) var termCols = 0, termRows = 0
    private(set) var termCursor: (x: Int, y: Int, visible: Bool) = (0, 0, false)
    private var termGen: UInt64 = 0
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

    private func pollTerm() {
        guard Native.termGeneration != termGen else { return }
        let snap = Native.termSnapshot(into: &term)
        guard snap.generation != 0 else { return }
        termGen = snap.generation; termCols = snap.cols; termRows = snap.rows; termCursor = snap.cursor
        recolour(Native.palette())
        let text = (0..<termRows).map { y in
            String(String.UnicodeScalarView((0..<termCols).compactMap { x in Unicode.Scalar(UInt32(max(0, term[2 * (y * termCols + x)]))) }))
        }
        func any(_ needle: String) -> Bool { text.contains { $0.range(of: needle, options: .caseInsensitive) != nil } }
        waiting = any("by typing the '@'") ? .atSign : any("[y/n]") ? .yesNo : any("-more-") ? .more : any("any key") ? .anyKey : .menu
        termVersion += 1
    }

    func poll() {
        pollTerm()
        let saves = Native.saveCount
        if saves != saveCount {
            if saveCount != nil { DispatchQueue.global(qos: .utility).async { Saves.noteSave() } }
            saveCount = saves
        }
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
                        newExp: m(NB.META_NEW_EXP), hpWarn: m(NB.META_HP_WARN), autoMore: m(NB.META_AUTO_MORE) != 0,
                        threats: m(NB.META_THREATS), threatsAlert: m(NB.META_THREATS_ALERT),
                        threatNearest: m(NB.META_THREAT_NEAREST), lightTurns: m(NB.META_LIGHT_TURNS))
        recolour(Native.palette())
        let quick = Native.quickSlots().split(separator: "\n").compactMap(Self.quickSlot)
        if quick != quickSlots { quickSlots = quick }
        let line = Native.topLine()
        if line != topLine { topLine = line }
        return true
    }

    /// The game's bottom status line as its separate pieces, each with its game colour (runs of 2+ spaces separate them).
    func statusSegments() -> [(String, UInt32)] {
        guard termRows > 0, term.count == 2 * termCols * termRows else { return [] }
        return GameState.splitStatusRow(term, y: termRows - 1, cols: termCols).map { ($0.0, colour($0.1 % 256 % 32)) }
    }

    /// One term row (cells of codepoint, attr) cut into its pieces: a run of two or more blanks ends one, a single
    /// blank stays inside it, and a piece takes the attr of its first character.  GameState.kt's splitStatusRow does
    /// the same (both are tested on the same row).
    static func splitStatusRow(_ term: [Int32], y: Int, cols: Int) -> [(String, Int)] {
        var out = [(String, Int)](), text = "", attr = 0, spaces = 0
        func flush() { if !text.isEmpty { out.append((text, attr)); text = "" } }
        for x in 0..<cols {
            let i = 2 * (y * cols + x)
            let cp = Int(term[i])
            if cp == 32 || cp == 0 { spaces += 1; if spaces >= 2 { flush() }; continue }
            if spaces == 1 && !text.isEmpty { text += " " }
            if text.isEmpty { attr = Int(term[i + 1]) }
            spaces = 0
            if let u = Unicode.Scalar(UInt32(cp)) { text.unicodeScalars.append(u) }
        }
        flush()
        return out
    }

    /// One published quick-slot line: "kind \t carried \t name".
    private static func quickSlot(_ line: Substring) -> QuickSlot? {
        let f: [Substring] = line.split(separator: "\t", maxSplits: 2, omittingEmptySubsequences: false)
        guard f.count == 3, let kind = Int(f[0]), let count = Int(f[1]) else { return nil }
        return QuickSlot(kind: kind, count: count, name: String(f[2]))
    }

    /// The game's colour `index` (an attr's low byte), white if unknown.
    func colour(_ index: Int) -> UInt32 { palette.indices.contains(index) ? palette[index] : 0xFFFFFF }

    /// Glyph and attr the map shows at (x, y), or nil off the map.
    func mapCell(x: Int, y: Int) -> (glyph: Int, attr: Int)? {
        let w = Int(meta[NB.META_WIDTH]), h = Int(meta[NB.META_HEIGHT])
        guard (0..<w).contains(x), (0..<h).contains(y) else { return nil }
        let i = NB.MAP_CELL_INTS * (y * w + x)
        return (Int(map[i]), Int(map[i + 1]) & 0xFFFF)
    }
}
