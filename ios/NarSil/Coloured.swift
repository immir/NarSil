import SwiftUI

/// Description text with the game's colours (monster recall, item and knowledge text): runs the bridge wraps as
/// U+0003, '@' + the game's colour index, text, U+0004 (see NB_COLOUR_BEGIN in narsil_bridge.h).
/// Ported from Coloured.kt.
func colouredText(_ raw: String, colour: (Int) -> UInt32) -> AttributedString {
    let begin: Character = "\u{03}", end: Character = "\u{04}"
    var result = AttributedString()
    var rest = raw[...]
    while let start = rest.firstIndex(of: begin) {
        result += AttributedString(String(rest[..<start]))
        let afterBegin = rest.index(after: start)
        guard afterBegin < rest.endIndex, let code = rest[afterBegin].asciiValue else { rest = rest[afterBegin...]; continue }
        let textStart = rest.index(after: afterBegin)
        let stop = rest[textStart...].firstIndex(of: end) ?? rest.endIndex
        var run = AttributedString(String(rest[textStart..<stop]))
        run.foregroundColor = Color(rgb: colour(Int(code) - 64))
        result += run
        rest = stop < rest.endIndex ? rest[rest.index(after: stop)...] : rest[stop...]
    }
    result += AttributedString(String(rest))
    return result
}
