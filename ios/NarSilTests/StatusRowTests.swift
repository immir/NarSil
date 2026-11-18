import XCTest
@testable import NarSil

/// The same row and expectation are in StatusRowTest.kt.
final class StatusRowTests: XCTestCase {
    func testRowIsCutAtRunsOfTwoBlanksAndTakesTheFirstAttr() {
        let text = "Light 3  Fed   Open floor  50'"
        var term = [Int32](repeating: 0, count: 2 * 40)
        for (x, c) in text.unicodeScalars.enumerated() { term[2 * x] = Int32(c.value); term[2 * x + 1] = Int32(100 + x) }
        let pieces = GameState.splitStatusRow(term, y: 0, cols: 40)
        XCTAssertEqual(pieces.map { $0.0 }, ["Light 3", "Fed", "Open floor", "50'"])
        XCTAssertEqual(pieces.map { $0.1 }, [100, 109, 115, 127])
    }

    func testAnEmptyRowHasNoPieces() {
        XCTAssertTrue(GameState.splitStatusRow([Int32](repeating: 0, count: 20), y: 0, cols: 10).isEmpty)
    }
}
