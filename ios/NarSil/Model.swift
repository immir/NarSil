import Foundation

/// What the game publishes, as the UI's types.  Mirrors GameState.kt (Android); Parse.swift fills them.

struct Point: Equatable { let x: Int; let y: Int }

struct Item: Equatable {
    let index: Int, section: Int
    let label: Character
    let glyph: Int, attr: Int, weightTenthsLb: Int
    let name: String
}
struct PackAction: Equatable { let index: Int; let valid: Bool; let label: String }
struct Target: Equatable { let x: Int, y: Int; let inRange: Bool; let name: String }
struct Message: Equatable { let attr: Int, count: Int; let text: String }
struct Neighbour: Equatable { let dir: Int, x: Int, y: Int; let what: String }
struct Choice: Equatable { let index: Int; let label: String, description: String }
struct Skill: Equatable { let index: Int; let name: String; let base: Int, total: Int, spent: Int, nextCost: Int }

struct SmithItem: Equatable { let name: String; let difficulty: Int, max: Int; let drains: Bool; let turns: Int, turnsLeft: Int }
struct SmithCost: Equatable { let label: String; let state: Int }
struct SmithOption: Equatable { let index: Int, state: Int; let label: String, detail: String }

enum KnowRow: Equatable {
    case group(String)
    case entry(index: Int, glyph: Int, attr: Int, name: String, note: String)
}

enum OptionRow: Equatable {
    case heading(String)
    case toggle(answer: Int, on: Bool, locked: Bool, description: String, name: String)
    case number(base: Int, label: String, value: Int, min: Int, max: Int, detail: String)
    case link(answer: Int, label: String, detail: String)
}

struct ScreenOption: Equatable { let index: Int, state: Int; let label: String, summary: String, description: String }
struct BirthStat: Equatable {
    let index: Int; let name: String
    let value: Int, spent: Int, nextCost: Int
    let canSell: Bool, canBuy: Bool
}
struct NamedValue: Equatable { let name: String; let value: String }

struct Ability: Equatable {
    let index: Int, skill: Int, state: Int, points: Int, cost: Int
    let prereqs: String, name: String, description: String
}
/// A skill tab: abilities need `base` points (without stat or equipment modifiers).
struct AbilitySkill: Equatable { let name: String; let base: Int, total: Int }

struct Monster: Equatable { let name: String, state: String, recall: String }
struct SheetRow: Equatable { let label: String, value: String; let attr: Int; let detail: String }
struct SheetSection: Equatable { var title: String; var rows: [SheetRow]; var text: String? }
struct LookEntry: Equatable { let x: Int, y: Int; let summary: String }

/// Read-only descriptions the player asked for; dismissable, the game is not waiting on them.
enum Info: Equatable {
    case inspect(x: Int, y: Int, monster: Monster?, objects: [String], terrain: String?, targetable: Bool, isTarget: Bool)
    case lookList([LookEntry])
    case character([SheetSection])
}

struct ItemsPrompt: Equatable { let title: String; let items: [Item] }
/// The whole pack; `expanded` (an item index, or -1) shows its actions and description.
struct PackPrompt: Equatable {
    let expanded: Int, focus: Int, weight: Int, limit: Int
    let items: [Item]
    let actions: [PackAction]
    let description: String
    let resumed: Bool
}
struct AimPrompt: Equatable { let range: Int; let current: Point?; let targets: [Target] }
struct DirectionPrompt: Equatable { let allowHere: Bool; let neighbours: [Neighbour] }
struct ChoicePrompt: Equatable { let title: String; let options: [Choice] }
struct SkillsPrompt: Equatable { let expLeft: Int; let skills: [Skill] }
struct SmithPrompt: Equatable {
    let view: String, title: String
    let notes: [String]
    let item: SmithItem?
    let costs: [SmithCost]
    let description: [String]
    let options: [SmithOption]
}
/// The knowledge browser: `rows` in order; `expanded` (an entry index or -1) has `text`.
struct KnowledgePrompt: Equatable {
    let view: String, title: String
    let expanded: Int
    let rows: [KnowRow]
    let text: String
    let actions: [(Int, String)]
    static func == (a: KnowledgePrompt, b: KnowledgePrompt) -> Bool {
        a.view == b.view && a.title == b.title && a.expanded == b.expanded && a.rows == b.rows && a.text == b.text
            && a.actions.map { "\($0.0)\t\($0.1)" } == b.actions.map { "\($0.0)\t\($0.1)" }
    }
}
/// How many (e.g. of a stack to drop): 0...max, starting at `initial`.
struct QuantityPrompt: Equatable { let title: String; let max: Int, initial: Int }
struct OptionsPrompt: Equatable { let page: String, title: String; let rows: [OptionRow] }
struct TextPrompt: Equatable { let title: String, initial: String; let maxBytes: Int; let random: Bool }
struct ScreenPrompt: Equatable {
    let stage: String, title: String
    let paragraphs: [String]
    let options: [ScreenOption]
    let stats: [BirthStat]
    let pointsLeft: Int, pointsTotal: Int
    let skills: [NamedValue], combat: [NamedValue]
    let verse: [String]
}
struct AbilitiesPrompt: Equatable { let expLeft: Int; let skills: [AbilitySkill]; let abilities: [Ability] }

/// The game's open question; mirrors enum nb_prompt_kind.
enum Prompt: Equatable {
    case items(ItemsPrompt)
    case pack(PackPrompt)
    case check(String)
    case aim(AimPrompt)
    case direction(DirectionPrompt)
    case choice(ChoicePrompt)
    case skills(SkillsPrompt)
    case smith(SmithPrompt)
    case knowledge(KnowledgePrompt)
    case quantity(QuantityPrompt)
    case character([SheetSection])
    case options(OptionsPrompt)
    case text(TextPrompt)
    case screen(ScreenPrompt)
    case abilities(AbilitiesPrompt)
}

struct Foe: Equatable { let x: Int, y: Int; let name: String; let healthPct: Int, stance: Int, morale: Int }

struct Status: Equatable {
    var hp = 0, maxHp = 0, voice = 0, maxVoice = 0, depth = 0, exp = 0, context = 0
    var foe: Foe?
    var newExp = 0, hpWarn = 0

    func has(_ flag: Int) -> Bool { context & flag != 0 }
    /// Below the game's hitpoint warning (tenths of maximum health).
    var lowHp: Bool { maxHp > 0 && hpWarn > 0 && hp * 10 < maxHp * hpWarn }
}
