import Foundation

/// Reads what the game publishes (the formats documented in narsil_bridge.h and the narsil_*.c files)
/// into the UI's types.  Pure, so the XCTests can feed it the recordings made by android/gen-fixtures.sh.
/// A line that does not have the shape the game documents makes the whole record parse to nil.
enum Parse {
    private struct Malformed: Error {}

    /// `s.split('\t', limit)`: at most `limit` fields, the last keeping any further tabs.
    private static func fields(_ s: String, _ limit: Int = Int.max) -> [String] {
        let all = s.components(separatedBy: "\t")
        guard all.count > limit else { return all }
        return Array(all[..<(limit - 1)]) + [all[(limit - 1)...].joined(separator: "\t")]
    }

    private static func lines(_ s: String) -> [String] { s.components(separatedBy: "\n") }
    private static func nonEmpty(_ ls: [String]) -> [String] { ls.filter { !$0.isEmpty } }
    private static func trimmed(_ s: String) -> String { s.trimmingCharacters(in: .whitespacesAndNewlines) }
    private static func after(_ s: String, _ prefix: String) -> String { String(s.dropFirst(prefix.count)) }
    /// The lines carrying `tag`, with the tag removed, as one trimmed text.
    private static func text(_ ls: [String], tag: String) -> String {
        trimmed(ls.filter { $0.hasPrefix(tag + "\t") }.map { after($0, tag + "\t") }.joined(separator: "\n"))
    }

    private static func int(_ f: [String], _ i: Int) throws -> Int {
        guard i < f.count, let v = Int(f[i]) else { throw Malformed() }
        return v
    }
    private static func int(_ f: [String], _ i: Int, or d: Int) -> Int { i < f.count ? (Int(f[i]) ?? d) : d }
    private static func str(_ f: [String], _ i: Int) -> String { i < f.count ? f[i] : "" }
    private static func flag(_ f: [String], _ i: Int) -> Bool { i < f.count && f[i] == "1" }
    private static func char(_ f: [String], _ i: Int) throws -> Character {
        guard i < f.count, let c = f[i].first else { throw Malformed() }
        return c
    }
    private static func line(_ ls: [String], _ i: Int) throws -> String {
        guard i < ls.count else { throw Malformed() }
        return ls[i]
    }

    /// See nb_prompt() in narsil_bridge.h for each kind's layout.  `raw` is "<kind>\n<text>".
    static func prompt(_ raw: String) -> Prompt? {
        guard let nl = raw.firstIndex(of: "\n"), let kind = Int(raw[..<nl]) else { return nil }
        let body = String(raw[raw.index(after: nl)...])
        return try? prompt(kind: kind, body: body)
    }

    private static func prompt(kind: Int, body: String) throws -> Prompt? {
        let ls = lines(body)
        switch kind {
        case NB.PROMPT_ITEM:
            let items = try nonEmpty(Array(ls.dropFirst())).enumerated().map { i, l -> Item in
                let f = fields(l, 6)
                return Item(index: i, section: try int(f, 0), label: try char(f, 1), glyph: try int(f, 2),
                            attr: try int(f, 3), weightTenthsLb: try int(f, 4), name: str(f, 5))
            }
            return .items(ItemsPrompt(title: trimmed(try line(ls, 0)), items: items))
        case NB.PROMPT_CHECK:
            return .check(trimmed(ls.joined(separator: " ")))
        case NB.PROMPT_AIM:
            let head = fields(try line(ls, 1), 3)
            let cx = try int(head, 1), cy = try int(head, 2)
            let targets = try nonEmpty(Array(ls.dropFirst(2))).map { l -> Target in
                let f = fields(l, 4)
                return Target(x: try int(f, 0), y: try int(f, 1), inRange: f.count > 2 && f[2] == "1", name: str(f, 3))
            }
            return .aim(AimPrompt(range: try int(head, 0), current: cx >= 0 ? Point(x: cx, y: cy) : nil, targets: targets))
        case NB.PROMPT_CHOICE:
            let options = nonEmpty(Array(ls.dropFirst())).enumerated().map { i, l -> Choice in
                let f = fields(l, 2)
                return Choice(index: i, label: str(f, 0), description: str(f, 1))
            }
            return .choice(ChoicePrompt(title: trimmed(try line(ls, 0)), options: options))
        case NB.PROMPT_SKILLS:
            let skills = try nonEmpty(Array(ls.dropFirst())).enumerated().map { i, l -> Skill in
                let f = fields(l, 5)
                return Skill(index: i, name: str(f, 0), base: try int(f, 1), total: try int(f, 2),
                             spent: try int(f, 3), nextCost: try int(f, 4))
            }
            return .skills(SkillsPrompt(expLeft: int(fields(try line(ls, 0), 2), 1, or: 0), skills: skills))
        case NB.PROMPT_SMITH:
            let head = fields(try line(ls, 0))
            let recs = nonEmpty(Array(ls.dropFirst())).map { fields($0) }
            func of(_ tag: String) -> [[String]] { recs.filter { $0.first == tag } }
            let item = try of("#I").first.map { f in
                SmithItem(name: str(f, 1), difficulty: try int(f, 2), max: try int(f, 3), drains: f.count > 4 && f[4] == "1",
                          turns: try int(f, 5), turnsLeft: try int(f, 6))
            }
            return .smith(SmithPrompt(
                view: str(head, 1), title: str(head, 2),
                notes: of("#N").map { str($0, 1) }, item: item,
                costs: try of("#C").map { SmithCost(label: str($0, 1), state: try int($0, 2)) },
                description: of("#D").map { str($0, 1) },
                options: try of("#O").enumerated().map { i, f in
                    SmithOption(index: i, state: try int(f, 1), label: str(f, 2), detail: str(f, 3))
                }))
        case NB.PROMPT_TEXT:
            return .text(TextPrompt(title: try line(ls, 0), initial: ls.count > 1 ? ls[1] : "",
                                    maxBytes: ls.count > 2 ? (Int(ls[2]) ?? 19) : 19, random: ls.count > 3 && ls[3] == "1"))
        case NB.PROMPT_KNOWLEDGE:
            let head = fields(try line(ls, 0))
            var n = 0
            var rows: [KnowRow] = []
            for l in ls.dropFirst() {
                let f = fields(l, 5)
                switch f[0] {
                case "#G":
                    let t = str(f, 1)
                    rows.append(.group(t.prefix(1).uppercased() + t.dropFirst()))
                case "#E":
                    rows.append(.entry(index: n, glyph: try int(f, 1), attr: try int(f, 2), name: str(f, 3), note: str(f, 4)))
                    n += 1
                default: break
                }
            }
            let actions = try ls.filter { $0.hasPrefix("#A\t") }.map { l -> (Int, String) in
                let f = fields(l, 3)
                return (try int(f, 1), str(f, 2))
            }
            return .knowledge(KnowledgePrompt(view: str(head, 1), title: str(head, 2), expanded: try int(head, 3),
                                              rows: rows, text: text(ls, tag: "#T"), actions: actions))
        case NB.PROMPT_PACK:
            let head = fields(try line(ls, 0))
            let recs = nonEmpty(Array(ls.dropFirst())).map { fields($0, 7) }
            let items = try recs.filter { $0.first == "#I" }.enumerated().map { i, f in
                Item(index: i, section: try int(f, 1), label: try char(f, 2), glyph: try int(f, 3),
                     attr: try int(f, 4), weightTenthsLb: try int(f, 5), name: str(f, 6))
            }
            let actions = recs.filter { $0.first == "#A" }.enumerated().map { i, f in
                PackAction(index: i, valid: flag(f, 1), label: str(f, 2))
            }
            return .pack(PackPrompt(expanded: try int(head, 1), focus: try int(head, 2), weight: try int(head, 3),
                                    limit: try int(head, 4), items: items, actions: actions,
                                    description: text(ls, tag: "#D"), resumed: flag(head, 5)))
        case NB.PROMPT_QUANTITY:
            return .quantity(QuantityPrompt(title: try line(ls, 0), max: ls.count > 1 ? (Int(ls[1]) ?? 1) : 1,
                                            initial: ls.count > 2 ? (Int(ls[2]) ?? 1) : 1))
        case NB.PROMPT_CHARACTER:
            guard case .character(let sections)? = info(body) else { return nil }
            return .character(sections)
        case NB.PROMPT_OPTIONS:
            let head = fields(try line(ls, 0))
            let rows = try nonEmpty(Array(ls.dropFirst())).map { fields($0) }.compactMap { f -> OptionRow? in
                switch f[0] {
                case "#H": return .heading(str(f, 1))
                case "#O": return .toggle(answer: try int(f, 1), on: flag(f, 2), locked: flag(f, 3), description: str(f, 4), name: str(f, 5))
                case "#N": return .number(base: try int(f, 1), label: str(f, 2), value: try int(f, 3), min: try int(f, 4),
                                          max: try int(f, 5), detail: str(f, 6))
                case "#L": return .link(answer: try int(f, 1), label: str(f, 2), detail: str(f, 3))
                default: return nil
                }
            }
            return .options(OptionsPrompt(page: str(head, 1), title: str(head, 2), rows: rows))
        case NB.PROMPT_SCREEN:
            let head = fields(try line(ls, 0))
            let recs = nonEmpty(Array(ls.dropFirst())).map { fields($0) }
            func of(_ tag: String) -> [[String]] { recs.filter { $0.first == tag } }
            func pairs(_ tag: String) -> [NamedValue] { of(tag).map { NamedValue(name: str($0, 1), value: str($0, 2)) } }
            let points = of("#P").first
            return .screen(ScreenPrompt(
                stage: str(head, 1), title: str(head, 2),
                paragraphs: of("#H").map { str($0, 1) },
                options: try of("#O").enumerated().map { i, f in
                    ScreenOption(index: i, state: try int(f, 1), label: str(f, 2), summary: str(f, 3), description: str(f, 4))
                },
                stats: try of("#S").enumerated().map { i, f in
                    BirthStat(index: i, name: str(f, 1), value: try int(f, 2), spent: try int(f, 3), nextCost: try int(f, 4),
                              canSell: flag(f, 5), canBuy: flag(f, 6))
                },
                pointsLeft: try points.map { try int($0, 1) } ?? 0, pointsTotal: try points.map { try int($0, 2) } ?? 0,
                skills: pairs("#K"), combat: pairs("#C"), verse: of("#V").map { str($0, 1) }))
        case NB.PROMPT_ABILITIES:
            let skills = try line(ls, 1).components(separatedBy: "\t").map { s -> AbilitySkill in
                let f = s.components(separatedBy: "/")
                return AbilitySkill(name: f[0], base: int(f, 1, or: 0), total: int(f, 2, or: 0))
            }
            let abilities = try nonEmpty(Array(ls.dropFirst(2))).enumerated().map { i, l -> Ability in
                let f = fields(l, 7)
                return Ability(index: i, skill: try int(f, 0), state: try int(f, 1), points: try int(f, 2), cost: try int(f, 3),
                               prereqs: str(f, 4), name: str(f, 5), description: str(f, 6))
            }
            return .abilities(AbilitiesPrompt(expLeft: int(fields(try line(ls, 0), 2), 1, or: 0), skills: skills, abilities: abilities))
        case NB.PROMPT_DIRECTION:
            let neighbours = try nonEmpty(Array(ls.dropFirst(2))).map { l -> Neighbour in
                let f = fields(l, 4)
                return Neighbour(dir: try int(f, 0), x: try int(f, 1), y: try int(f, 2), what: str(f, 3))
            }
            return .direction(DirectionPrompt(allowHere: ls.count > 1 && trimmed(ls[1]) == "1", neighbours: neighbours))
        default:
            return nil
        }
    }

    /// See nb_request_inspect() in narsil_bridge.h.
    static func info(_ raw: String) -> Info? { try? infoRecord(raw) }

    private static func infoRecord(_ raw: String) throws -> Info? {
        let ls = lines(raw)
        let head = fields(try line(ls, 0))
        switch head[0] {
        case "Look":
            return .lookList(try nonEmpty(Array(ls.dropFirst())).map { l in
                let f = fields(l, 3)
                return LookEntry(x: try int(f, 0), y: try int(f, 1), summary: str(f, 2))
            })
        case "Character":
            var sections: [SheetSection] = []
            for l in nonEmpty(Array(ls.dropFirst())) {
                let f = fields(l)
                switch f[0] {
                case "#S": sections.append(SheetSection(title: str(f, 1), rows: [], text: nil))
                case "#T": if !sections.isEmpty { sections[sections.count - 1].text = str(f, 1) }
                default:
                    if !sections.isEmpty && f.count >= 3 {
                        sections[sections.count - 1].rows.append(SheetRow(label: f[0], value: f[1], attr: int(f, 2, or: 1), detail: str(f, 3)))
                    }
                }
            }
            return .character(sections)
        case "Inspect":
            var monster: Monster?
            var objects: [String] = []
            var terrain: String?
            var i = 1
            while i < ls.count {
                let f = fields(ls[i])
                switch f[0] {
                case "#M":
                    let bodyLines = Array(ls.dropFirst(i + 1).prefix { !$0.hasPrefix("#") })
                    monster = Monster(name: str(f, 1), state: str(f, 2), recall: trimmed(bodyLines.joined(separator: "\n")))
                    i += bodyLines.count
                case "#O": objects.append(str(f, 1))
                case "#F": terrain = str(f, 1)
                default: break
                }
                i += 1
            }
            return .inspect(x: try int(head, 1), y: try int(head, 2), monster: monster, objects: objects, terrain: terrain,
                            targetable: flag(head, 3), isTarget: flag(head, 4))
        default:
            return nil
        }
    }

    /// Drops the textui's blank and "=====" separator lines.
    static func messages(_ raw: String) -> [Message] {
        lines(raw).compactMap { l in
            let f = fields(l, 3)
            let t = trimmed(str(f, 2))
            guard !t.isEmpty, !t.allSatisfy({ $0 == "=" }), let attr = Int(str(f, 0)), let count = Int(str(f, 1)) else { return nil }
            return Message(attr: attr, count: count, text: t)
        }
    }
}
