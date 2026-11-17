import XCTest
@testable import NarSil

/// The touch input's arithmetic, checked against the Android versions (Keys.kt, Prompts.kt).
final class InputTests: XCTestCase {
    func testOctantsCountAnticlockwiseFromEast() {
        XCTAssertEqual(Direction.octant(dx: 10, dy: 0), 0)    // east
        XCTAssertEqual(Direction.octant(dx: 10, dy: -10), 1)  // north-east (y grows downward)
        XCTAssertEqual(Direction.octant(dx: 0, dy: -10), 2)   // north
        XCTAssertEqual(Direction.octant(dx: -10, dy: 0), 4)   // west
        XCTAssertEqual(Direction.octant(dx: 0, dy: 10), 6)    // south
        XCTAssertEqual(Direction.octant(dx: 10, dy: 10), 7)   // south-east
    }

    func testSwipesBecomeKeypadDirections() {
        XCTAssertEqual(Direction.octantToDir[Direction.octant(dx: 10, dy: 0)], 6)
        XCTAssertEqual(Direction.octantToDir[Direction.octant(dx: 0, dy: -10)], 8)
        XCTAssertEqual(Direction.octantToDir[Direction.octant(dx: -10, dy: 10)], 1)
    }

    func testKeypadDirectionOfAStep() {
        XCTAssertEqual(Direction.keypad(dx: 0, dy: 0), 5)
        XCTAssertEqual(Direction.keypad(dx: 1, dy: 0), 6)
        XCTAssertEqual(Direction.keypad(dx: -1, dy: -1), 7)
        XCTAssertEqual(Direction.keypad(dx: 0, dy: 1), 2)
    }

    func testAimAnswerEncodesTheGrid() {
        XCTAssertEqual(Direction.aimAt(x: 3, y: 2), NB.AIM_GRID_BASE + 2 * NB.AIM_GRID_STRIDE + 3)
    }
}
