import SwiftUI

/* The character panel: tabs over the pack, character, skills, abilities, knowledge, smithing and options,
   in from the right (Panel.kt).  The game owns the tabs; a tab tap answers PANEL_TAB + tab. */

private let tabTitles = ["Pack", "Character", "Skills", "Abilities", "Knowledge", "Smithing", "Options"]
private let tabIcons = ["bag.fill", "person.fill", "chart.bar.fill", "star.fill", "book.fill", "hammer.fill", "gearshape.fill"]

/// How the panel's tabs are drawn (Settings → Display and controls).
enum TabStyle: Int { case iconsAndNames = 0, iconsOnly = 1, names = 2 }

extension Prompt {
    /// The prompts that are a tab's own screen (anything else, e.g. a yes/no, is asked over it).
    var isPanelContent: Bool {
        switch self {
        case .pack, .character, .skills, .abilities, .knowledge, .smith, .options: return true
        default: return false
        }
    }
}

/// Which skill tab and ability are open; kept outside the sheet because learning an ability briefly
/// replaces it with the game's confirmation prompt.
final class AbilitiesNav: ObservableObject {
    @Published var tab = 0
    @Published var open: String?
}

/// The panel while the game has a tab open (`game.panelTab >= 0`): the last tab screen stays up, dimmed,
/// while some other question is asked over it.
struct PanelLayer: View {
    @ObservedObject var game: GameState
    @ObservedObject var abilities: AbilitiesNav
    let onAppSettings: () -> Void
    @State private var shown: Prompt?
    @State private var tab = 0
    /// The tab asked for shows at once; the game's answer (its new screen) follows.
    @State private var asked = -1

    var body: some View {
        let live = game.panelTab >= 0
        Group {
            if live {
                let interactive = game.prompt != nil && game.prompt == shown
                let dimmed = game.prompt != nil && game.prompt != shown
                host(interactive: interactive, dimmed: dimmed)
            }
        }
        .onChange(of: game.panelTab) { t in if t >= 0 { tab = t }; asked = -1 }
        .onChange(of: game.prompt) { p in if game.panelTab >= 0, let p, p.isPanelContent { shown = p } }
        .onAppear { if game.panelTab >= 0 { tab = game.panelTab; if let p = game.prompt, p.isPanelContent { shown = p } } }
    }

    private func host(interactive: Bool, dimmed: Bool) -> some View {
        let current = asked >= 0 ? asked : tab
        let send: (Int) -> Void = { a in
            if a >= NB.PANEL_TAB && a < NB.PANEL_CLOSE { asked = a - NB.PANEL_TAB }
            Native.answer(a)
        }
        return PanelHost(tab: current, answer: send) {
            ZStack {
                if let c = shown { PanelContent(prompt: c, game: game, abilities: abilities, onAppSettings: onAppSettings, answer: interactive ? send : { _ in }) }
                if dimmed { Color.black.opacity(0.33) }
            }
        }
    }
}

private struct PanelHost<Content: View>: View {
    @AppStorage("panelTabs") private var tabStyle = TabStyle.iconsAndNames.rawValue
    let tab: Int
    let answer: (Int) -> Void
    @ViewBuilder let content: Content

    /// Icons fit the panel's width without scrolling; the names are the fallback for those who prefer words.
    @ViewBuilder private var tabStrip: some View {
        let style = TabStyle(rawValue: tabStyle) ?? .iconsAndNames
        if style == .names {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 0) {
                    ForEach(Array(tabTitles.enumerated()), id: \.offset) { i, t in
                        Button { if i != tab { answer(NB.PANEL_TAB + i) } } label: {
                            Text(t).font(.system(size: 14, weight: i == tab ? .semibold : .regular))
                                .foregroundColor(i == tab ? Palette.gold : Palette.sub)
                                .padding(.horizontal, 12).padding(.vertical, 12)
                                .overlay(alignment: .bottom) { if i == tab { Palette.gold.frame(height: 2) } }
                        }.buttonStyle(.plain)
                    }
                }.padding(.horizontal, 4)
            }
        } else {
            HStack(spacing: 0) {
                ForEach(Array(tabTitles.enumerated()), id: \.offset) { i, t in
                    Button { if i != tab { answer(NB.PANEL_TAB + i) } } label: {
                        VStack(spacing: 2) {
                            Image(systemName: tabIcons[i]).font(.system(size: 18))
                            if style == .iconsAndNames { Text(t).font(.system(size: 9)).lineLimit(1).minimumScaleFactor(0.7) }
                        }
                        .foregroundColor(i == tab ? Palette.gold : Palette.sub)
                        .frame(maxWidth: .infinity, minHeight: 44).padding(.top, 4)
                        .overlay(alignment: .bottom) { if i == tab { Palette.gold.frame(height: 2) } }
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain).accessibilityLabel(t)
                }
            }.padding(.horizontal, 4)
        }
    }

    var body: some View {
        GeometryReader { g in
            let width = g.size.width > g.size.height ? g.size.width * 0.58 : g.size.width * 0.94
            ZStack(alignment: .trailing) {
                Color.black.opacity(0.47).ignoresSafeArea().onTapGesture { answer(NB.PANEL_CLOSE) }
                VStack(spacing: 0) {
                    HStack(spacing: 0) {
                        tabStrip
                        Button("✕") { answer(NB.PANEL_CLOSE) }.font(.system(size: 18)).padding(.horizontal, 12)
                    }
                    content.frame(maxWidth: .infinity, maxHeight: .infinity)
                        .simultaneousGesture(DragGesture(minimumDistance: 40).onEnded { v in
                            guard abs(v.translation.width) > 2 * abs(v.translation.height) else { return }
                            if v.translation.width < 0, tab + 1 < NB.TAB_COUNT { answer(NB.PANEL_TAB + tab + 1) }
                            else if v.translation.width > 0 { answer(tab > 0 ? NB.PANEL_TAB + tab - 1 : NB.PANEL_CLOSE) }
                        })
                }
                .frame(width: width).frame(maxHeight: .infinity).background(Palette.sheet)
                .clipShape(UnevenRoundedRectangle(topLeadingRadius: 16, bottomLeadingRadius: 16))
            }
        }
        .preferredColorScheme(.dark)
    }
}

/// One tab's screen.
private struct PanelContent: View {
    let prompt: Prompt
    @ObservedObject var game: GameState
    @ObservedObject var abilities: AbilitiesNav
    let onAppSettings: () -> Void
    let answer: (Int) -> Void

    var body: some View {
        switch prompt {
        case .skills(let p): SkillsView(prompt: p, answer: answer)
        case .abilities(let p): AbilitiesView(prompt: p, nav: abilities, answer: answer)
        case .pack(let p): PackView(prompt: p, colour: { game.colour($0) }, answer: answer)
        case .character(let sections):
            CharacterView(game: game, sections: sections, actions: [("Rename", { answer(NB.CHARACTER_RENAME) }), ("Share dump", { answer(NB.CHARACTER_DUMP) })])
        case .knowledge(let p): KnowledgeView(prompt: p, colour: { game.colour($0) }, answer: answer)
        case .smith(let p): SmithView(prompt: p, answer: answer)
        case .options(let p): OptionsView(prompt: p, onAppSettings: onAppSettings, answer: answer)
        default:
            VStack(spacing: 8) {
                Spacer()
                Text("This tab isn't ported to iOS yet.").foregroundColor(Palette.sub)
                Spacer()
            }
        }
    }
}

/// Spend experience on skills.  Every change is the game's own buy/sell command; Cancel undoes this session, Accept keeps it.
struct SkillsView: View {
    let prompt: SkillsPrompt
    let answer: (Int) -> Void

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 6) {
                Text("Skills").font(.system(size: 18, weight: .semibold)).foregroundColor(.white)
                Text("Experience to spend: \(prompt.expLeft)").font(.system(size: 14)).foregroundColor(Color(rgb: 0xFFD54F))
                ForEach(prompt.skills, id: \.index) { sk in
                    HStack(spacing: 8) {
                        VStack(alignment: .leading) {
                            Text(sk.name).font(.system(size: 16)).foregroundColor(.white)
                            Text("base \(sk.base)" + (sk.spent > 0 ? "  (+\(sk.spent) exp)" : "")).font(.system(size: 12)).foregroundColor(Palette.sub)
                        }
                        Spacer()
                        Text("\(sk.total)").font(.system(size: 20, weight: .semibold)).foregroundColor(.white).padding(.trailing, 4)
                        Button("−") { answer(NB.SKILL_SELL + sk.index) }.buttonStyle(.bordered).disabled(sk.spent <= 0)
                        Button("+ \(sk.nextCost)") { answer(NB.SKILL_BUY + sk.index) }.buttonStyle(.borderedProminent)
                            .disabled(sk.nextCost > prompt.expLeft)
                    }.frame(minHeight: 48)
                }
                HStack(spacing: 8) {
                    Spacer()
                    Button("Reset") { answer(NB.SKILL_RESET) }
                    Button("Cancel") { answer(-1) }.buttonStyle(.bordered)
                    Button("Accept") { answer(NB.SKILL_ACCEPT) }.buttonStyle(.borderedProminent)
                }.padding(.top, 8)
            }.padding(16)
        }
    }
}

private func abilityOwned(_ a: Ability) -> Bool { a.state >= NB.ABILITY_ON }

private func abilityColour(_ a: Ability) -> Color {
    switch a.state {
    case NB.ABILITY_ON: return .white
    case NB.ABILITY_OFF, NB.ABILITY_ITEM_OFF: return Color(rgb: 0xEF9A9A)
    case NB.ABILITY_ITEM_ON: return Color(rgb: 0xA5D6A7)
    case NB.ABILITY_AVAILABLE: return Color(rgb: 0x90CAF9)
    default: return Color(rgb: 0x77777D)
    }
}

private func abilitySummary(_ a: Ability, _ skill: AbilitySkill, _ expLeft: Int) -> String {
    switch a.state {
    case NB.ABILITY_ON: return "Learnt"
    case NB.ABILITY_OFF: return "Learnt · switched off"
    case NB.ABILITY_ITEM_ON: return "From equipment"
    case NB.ABILITY_ITEM_OFF: return "From equipment · switched off"
    case NB.ABILITY_AVAILABLE: return a.cost <= expLeft ? "Learn for \(a.cost) exp" : "\(a.cost) exp (you have \(expLeft))"
    default:
        let parts = [skill.base < a.points ? "needs \(a.points) base \(skill.name), you have \(skill.base)" : nil,
                     a.cost < 0 && !a.prereqs.isEmpty ? "needs \(a.prereqs)" : nil].compactMap { $0 }
        return parts.joined(separator: " · ").prefix(1).uppercased() + parts.joined(separator: " · ").dropFirst()
    }
}

/// do_cmd_abilities() natively: skills as tabs, abilities as rows; tap to read, then learn or switch.
struct AbilitiesView: View {
    let prompt: AbilitiesPrompt
    @ObservedObject var nav: AbilitiesNav
    let answer: (Int) -> Void

    var body: some View {
        let tab = min(max(nav.tab, 0), max(prompt.skills.count - 1, 0))
        VStack(alignment: .leading, spacing: 0) {
            VStack(alignment: .leading) {
                Text("Abilities").font(.system(size: 18, weight: .semibold)).foregroundColor(.white)
                Text("Experience to spend: \(prompt.expLeft)").font(.system(size: 13)).foregroundColor(Color(rgb: 0xFFD54F))
            }.padding(.horizontal, 16).padding(.top, 12)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 0) {
                    ForEach(Array(prompt.skills.enumerated()), id: \.offset) { i, sk in
                        let learnt = prompt.abilities.filter { $0.skill == i && ($0.state == NB.ABILITY_ON || $0.state == NB.ABILITY_OFF) }.count
                        Button { nav.tab = i; nav.open = nil } label: {
                            Text(learnt > 0 ? "\(sk.name) \(learnt)" : sk.name).font(.system(size: 14, weight: i == tab ? .semibold : .regular))
                                .foregroundColor(i == tab ? Palette.gold : Palette.sub).padding(.horizontal, 12).padding(.vertical, 10)
                                .overlay(alignment: .bottom) { if i == tab { Palette.gold.frame(height: 2) } }
                        }.buttonStyle(.plain)
                    }
                }.padding(.horizontal, 8)
            }
            if prompt.skills.indices.contains(tab) {
                let sk = prompt.skills[tab]
                Text("\(sk.name): base \(sk.base)" + (sk.total != sk.base ? " (\(sk.total) with bonuses)" : "") + ". Abilities depend on base points.")
                    .font(.system(size: 12)).foregroundColor(Palette.sub).padding(.horizontal, 16).padding(.vertical, 6)
            }
            ScrollView {
                LazyVStack(spacing: 0) {
                    ForEach(prompt.abilities.filter { $0.skill == tab }, id: \.index) { a in
                        row(a, prompt.skills.indices.contains(a.skill) ? prompt.skills[a.skill] : AbilitySkill(name: "", base: 0, total: 0))
                    }
                }
            }
        }
    }

    private func row(_ a: Ability, _ skill: AbilitySkill) -> some View {
        let open = nav.open == a.name
        return VStack(alignment: .leading, spacing: 0) {
            VStack(alignment: .leading, spacing: 2) {
                HStack {
                    Text(a.name).font(.system(size: 16, weight: abilityOwned(a) ? .semibold : .regular)).foregroundColor(abilityColour(a))
                    Spacer()
                    Text("\(a.points)").font(.system(size: 13)).foregroundColor(Color(rgb: 0x77777D))
                }
                Text(abilitySummary(a, skill, prompt.expLeft)).font(.system(size: 12)).foregroundColor(Palette.sub)
                if open {
                    Text(a.description).font(.system(size: 14)).foregroundColor(Palette.body).padding(.top, 6)
                    if !a.prereqs.isEmpty && !abilityOwned(a) { Text("Requires \(a.prereqs)").font(.system(size: 13)).foregroundColor(Palette.sub).padding(.top, 4) }
                    HStack {
                        switch a.state {
                        case NB.ABILITY_AVAILABLE:
                            Button("Learn (\(a.cost) exp)") { answer(a.index) }.buttonStyle(.borderedProminent).disabled(a.cost > prompt.expLeft)
                        case NB.ABILITY_ON, NB.ABILITY_ITEM_ON: Button("Switch off") { answer(a.index) }.buttonStyle(.bordered)
                        case NB.ABILITY_OFF, NB.ABILITY_ITEM_OFF: Button("Switch on") { answer(a.index) }.buttonStyle(.bordered)
                        default: EmptyView()
                        }
                    }.padding(.top, 8)
                }
            }
            .padding(.horizontal, 16).padding(.vertical, 8).frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(Rectangle()).onTapGesture { nav.open = open ? nil : a.name }
            Palette.divider.frame(height: 1)
        }
    }
}
