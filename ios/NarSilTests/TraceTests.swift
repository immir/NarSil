import XCTest
@testable import NarSil

/// The same records are in TraceTest.kt.
final class TraceTests: XCTestCase {
    func testARecordIsTimeEventAndFieldsSeparatedByTabs() {
        XCTAssertEqual(Trace.line(ms: 1500, event: "bar.tap", [("act", "Fire")]), "1500\tbar.tap\tact=Fire\n")
    }

    func testAnEventWithNoFieldsIsJustTimeAndEvent() {
        XCTAssertEqual(Trace.line(ms: 0, event: "app.start", []), "0\tapp.start\n")
    }

    func testTabsAndNewlinesInAValueCannotSplitARecord() {
        XCTAssertEqual(Trace.line(ms: 7, event: "bar.tap", [("act", "a\tb\nc")]), "7\tbar.tap\tact=a b c\n")
    }

    func testNumbersAndFlagsKeepTheirOrder() {
        XCTAssertEqual(Trace.line(ms: 42, event: "panel.close", [("open_ms", 4900), ("acted", false)]), "42\tpanel.close\topen_ms=4900\tacted=false\n")
    }
}
