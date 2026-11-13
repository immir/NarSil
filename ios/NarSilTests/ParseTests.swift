import XCTest
@testable import NarSil

/// The app's side of the bridge: every fixture is what the game really published (android/gen-fixtures.sh runs
/// the host harness); the parsers must read it into the UI's types.  The same fixtures run in ParseTest.kt.
final class ParseTests: XCTestCase {
    private func fixture(_ name: String) throws -> String {
        let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: name, withExtension: nil, subdirectory: "fixtures"),
                                "no fixture \(name)")
        return try String(contentsOf: url, encoding: .utf8)
    }
    private func prompt(_ name: String) throws -> Prompt {
        try XCTUnwrap(Parse.prompt(try fixture("\(name).prompt")), "\(name) parsed to nothing")
    }

    func testEveryPromptFixtureParses() throws {
        let dir = try XCTUnwrap(Bundle(for: Self.self).resourceURL).appendingPathComponent("fixtures")
        let names = try FileManager.default.contentsOfDirectory(atPath: dir.path).filter { $0.hasSuffix(".prompt") }
        XCTAssertGreaterThanOrEqual(names.count, 25, "fixtures missing (run android/gen-fixtures.sh)")
        for n in names { XCTAssertNotNil(Parse.prompt(try fixture(n)), "\(n) did not parse") }
    }

    func testWelcome() throws {
        guard case .screen(let s) = try prompt("welcome") else { return XCTFail("not a screen") }
        XCTAssertEqual(s.stage, "welcome")
        XCTAssertFalse(s.verse.isEmpty)
        XCTAssertEqual(s.options.map(\.label), ["Begin", "Tutorial"])
    }

    func testBirthStages() throws {
        func screen(_ n: String) throws -> ScreenPrompt {
            guard case .screen(let s) = try prompt(n) else { throw XCTSkip("\(n) is not a screen") }
            return s
        }
        XCTAssertEqual(try screen("birth_race").stage, "race")
        XCTAssertGreaterThanOrEqual(try screen("birth_race").options.count, 3)
        XCTAssertEqual(try screen("birth_house").stage, "house")
        XCTAssertEqual(try screen("birth_sex").stage, "sex")
        guard case .text = try prompt("birth_name") else { return XCTFail("name is not a text prompt") }
        guard case .skills = try prompt("birth_skills") else { return XCTFail("skills is not a skills prompt") }
    }

    func testBirthStatsShowWhatTheyFeed() throws {
        guard case .screen(let s) = try prompt("birth_stats") else { return XCTFail("not a screen") }
        XCTAssertEqual(s.stage, "stats")
        XCTAssertEqual(s.stats.count, 4)
        XCTAssertGreaterThan(s.pointsTotal, 0)
        XCTAssertTrue(s.skills.contains { $0.name == "Melee" })
        XCTAssertEqual(s.combat.map(\.name), ["Attack", "Defence", "Health", "Voice"])
    }

    func testDeathScreen() throws {
        guard case .screen(let s) = try prompt("death") else { return XCTFail("not a screen") }
        XCTAssertEqual(s.stage, "death")
        XCTAssertTrue(s.options.contains { $0.label == "New character" })
    }

    func testPackAndItsExpansion() throws {
        guard case .pack(let closed) = try prompt("pack"), case .pack(let open) = try prompt("pack_expanded")
        else { return XCTFail("not packs") }
        XCTAssertEqual(closed.expanded, -1)
        XCTAssertTrue(!closed.items.isEmpty && closed.weight > 0 && closed.limit > closed.weight)
        XCTAssertGreaterThanOrEqual(open.expanded, 0)
        XCTAssertTrue(open.actions.contains { $0.label == "Drop" })
        XCTAssertFalse(open.description.isEmpty)
    }

    func testCommandPrompts() throws {
        guard case .items(let items) = try prompt("items") else { return XCTFail("items") }
        XCTAssertFalse(items.items.isEmpty)
        guard case .check(let q) = try prompt("check") else { return XCTFail("check") }
        XCTAssertTrue(q.contains("retire"))
        guard case .choice(let c) = try prompt("choice_ignore") else { return XCTFail("choice") }
        XCTAssertGreaterThanOrEqual(c.options.count, 2)
        guard case .aim = try prompt("aim") else { return XCTFail("aim") }
        guard case .direction = try prompt("direction") else { return XCTFail("direction") }
        guard case .quantity(let qty) = try prompt("quantity") else { return XCTFail("quantity") }
        XCTAssertTrue(qty.max >= 2 && qty.initial == 1)
        guard case .text(let t) = try prompt("text") else { return XCTFail("text") }
        XCTAssertTrue(t.title.contains("Inscribe") && t.maxBytes > 0)
    }

    func testPanelTabs() throws {
        guard case .character(let sections) = try prompt("character") else { return XCTFail("character") }
        XCTAssertTrue(sections.contains { $0.title == "Skills" })
        guard case .skills(let sk) = try prompt("skills") else { return XCTFail("skills") }
        XCTAssertEqual(sk.skills.count, 8)
        guard case .abilities(let ab) = try prompt("abilities") else { return XCTFail("abilities") }
        XCTAssertEqual(ab.skills.count, 8)
        XCTAssertGreaterThan(ab.abilities.count, 40)
        guard case .smith(let sm) = try prompt("smith_main") else { return XCTFail("smith") }
        XCTAssertEqual(sm.view, "main")
        XCTAssertEqual(sm.options.count, 6)
    }

    func testKnowledge() throws {
        guard case .knowledge(let main) = try prompt("knowledge_main"),
              case .knowledge(let objects) = try prompt("knowledge_objects"),
              case .knowledge(let scores) = try prompt("knowledge_scores") else { return XCTFail("not knowledge") }
        func entries(_ k: KnowledgePrompt) -> Int { k.rows.filter { if case .entry = $0 { return true } else { return false } }.count }
        func groups(_ k: KnowledgePrompt) -> Int { k.rows.filter { if case .group = $0 { return true } else { return false } }.count }
        XCTAssertEqual(main.view, "main")
        XCTAssertGreaterThanOrEqual(entries(main), 8)
        XCTAssertEqual(objects.view, "objects")
        XCTAssertTrue(groups(objects) > 0 && entries(objects) > 0)
        XCTAssertEqual(scores.view, "scores")
    }

    func testOptions() throws {
        guard case .options(let o) = try prompt("options_main") else { return XCTFail("options") }
        XCTAssertEqual(o.page, "main")
        func has(_ test: (OptionRow) -> Bool) -> Bool { o.rows.contains(where: test) }
        XCTAssertTrue(has { if case .toggle = $0 { return true } else { return false } })
        XCTAssertTrue(has { if case .number = $0 { return true } else { return false } })
        XCTAssertTrue(has { if case .link = $0 { return true } else { return false } })
    }

    func testInfoRecords() throws {
        guard case .inspect(_, _, _, _, let terrain, _, _)? = Parse.info(try fixture("inspect.info")) else { return XCTFail("inspect") }
        XCTAssertEqual(terrain, "Open floor")
        guard case .lookList? = Parse.info(try fixture("look.info")) else { return XCTFail("look") }
    }

    func testMessagesDropSeparators() {
        let m = Parse.messages("1\t1\tYou hit it.\n1\t2\t====\n1\t1\t\n7\t3\tIt dies.")
        XCTAssertEqual(m.map(\.text), ["You hit it.", "It dies."])
        XCTAssertEqual(m.last?.attr, 7)
    }

    func testUnknownOrEmptyInputIsNothing() {
        XCTAssertNil(Parse.prompt(""))
        XCTAssertNil(Parse.prompt("0\n"))
        XCTAssertNil(Parse.info("Nonsense\t1"))
    }
}
