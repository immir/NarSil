import Foundation
import NarSilCore

/// Thin wrappers over narsil_bridge.h (the Swift side of NarSilNative.kt).  Main thread only; the game runs
/// on the bridge's own thread.
enum Native {
    static func start(libDir: String, dataDir: String, player: String, cols: Int, rows: Int) -> Bool {
        libDir.withCString { lib in
            dataDir.withCString { data in
                player.withCString { name in
                    var cfg = nb_config(lib_dir: lib, data_dir: data, player_name: name, cols: Int32(cols), rows: Int32(rows),
                                        log: { msg in if let msg { NSLog("narsil: %@", String(cString: msg)) } })
                    return nb_start(&cfg)
                }
            }
        }
    }

    static var state: Int { Int(nb_state().rawValue) }
    static var exitMessage: String { state == NB.EXITED ? nb_exit_message().map { String(cString: $0) } ?? "" : "" }

    // Input and saving.
    static func pushKey(_ code: Int, mods: Int = 0) { nb_push_key(UInt32(code), UInt8(mods)) }
    static func pushMouse(x: Int, y: Int, button: Int, mods: Int = 0) {
        guard (1...15).contains(button), (0...0x0F).contains(mods) else { return }
        nb_push_mouse(Int32(x), Int32(y), Int32(button), UInt8(mods))
    }
    static func requestSave() { nb_request_save() }
    static var saveCount: UInt64 { nb_save_count() }

    // Commands.
    static func requestAction(_ action: Int) {
        guard (0..<NB.ACT_COUNT).contains(action) else { return }
        nb_request_action(nb_action(rawValue: UInt32(action)))
    }
    static func requestMove(_ dir: Int, run: Bool) { nb_request_move(Int32(dir), run) }
    static func requestTravel(x: Int, y: Int) { nb_request_travel(Int32(x), Int32(y)) }
    static func requestInspect(x: Int, y: Int) { nb_request_inspect(Int32(x), Int32(y)) }
    static func requestTarget(x: Int, y: Int) { nb_request_target(Int32(x), Int32(y)) }
    static func requestRest(_ turnsOrMode: Int) { nb_request_rest(Int32(turnsOrMode)) }
    static func requestLookList() { nb_request_look_list() }
    static func requestPreview(x: Int, y: Int) { nb_request_preview(Int32(x), Int32(y)) }
    static func clearPreview() { nb_clear_preview() }
    static func setTiles(_ on: Bool) { nb_set_tiles(on) }

    // Answers to the open prompt.
    static func answer(_ value: Int) { nb_answer(Int32(value)) }
    static func answerItem(_ index: Int) { nb_answer_item(Int32(index)) }
    static func answerText(_ text: String) { text.withCString { nb_answer_text($0) } }

    // Generations: a snapshot is only worth taking when one moves.
    static var mapGeneration: UInt64 { nb_map_generation() }
    static var pathGeneration: UInt64 { nb_path_generation() }
    static var promptGeneration: UInt64 { nb_prompt_generation() }
    static var messagesGeneration: UInt64 { nb_messages_generation() }
    static var infoGeneration: UInt64 { nb_info_generation() }
    static var panelTab: Int { Int(nb_panel_tab()) }

    /// Fills `meta` (NB.META_COUNT ints) and `cells` (NB.MAP_CELL_INTS per grid); 0 if `cells` is too small for the level.
    static func mapSnapshot(meta: inout [Int32], cells: inout [Int32]) -> UInt64 {
        precondition(meta.count >= NB.META_COUNT)
        return meta.withUnsafeMutableBufferPointer { m in
            cells.withUnsafeMutableBufferPointer { c in nb_map_snapshot(m.baseAddress, c.baseAddress, c.count) }
        }
    }

    /// (x, y) pairs, the target first; returns the pair count.
    static func path(into xy: inout [Int32]) -> Int {
        let pairs = xy.count / 2
        return xy.withUnsafeMutableBufferPointer { Int(nb_path($0.baseAddress, pairs)) }
    }

    /// "<kind>\n<text>": see nb_prompt().
    static func prompt() -> String {
        var kind = 0
        let body = text(Int(NB_PROMPT_CAP)) { kind = Int(nb_prompt($0, $1).rawValue) }
        return "\(kind)\n\(body)"
    }
    static func messages() -> String { text(Int(NB_MESSAGES_CAP)) { _ = nb_messages($0, $1) } }
    static func info() -> String { text(32 * 1024) { _ = nb_info($0, $1) } }
    static func foeName() -> String { text(80) { _ = nb_foe_name($0, $1) } }

    /// Game text is UTF-8 but not trusted: invalid bytes become U+FFFD instead of failing.
    private static func text(_ cap: Int, _ fill: (UnsafeMutablePointer<CChar>, Int) -> Void) -> String {
        var buf = [CChar](repeating: 0, count: cap)
        buf.withUnsafeMutableBufferPointer { fill($0.baseAddress!, cap) }
        return String(decoding: buf.prefix { $0 != 0 }.map { UInt8(bitPattern: $0) }, as: UTF8.self)
    }
}
