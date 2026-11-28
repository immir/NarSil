import SwiftUI

/// How the d-pad is shown (Settings → Display and controls); the same choices as Android where the layout allows.
enum PadMode: Int { case off = 0, docked = 1, floating = 2 }
/// What a one-finger swipe on the map does; "swipeRuns" was the earlier on/off setting (on was Run).
enum SwipeMode: Int {
    case pan = 0, step = 1, run = 2
    static var initial: Int { (UserDefaults.standard.bool(forKey: "swipeRuns") ? SwipeMode.run : .step).rawValue }
}

/// The app's own settings (not the game's options), in the same order and words as Android's Options sheet.
/// Kept in UserDefaults; the game is told about the ones it needs.
struct SettingsSheet: View {
    @Binding var buttonScale: Double
    @Binding var messageScale: Double
    @Binding var itemScale: Double
    @Binding var statusScale: Double
    @Binding var theme: Int
    let gamePalette: [UInt32]
    @Binding var messageLines: Int
    @Binding var panelTabs: Int
    @Binding var tabsOnTop: Bool
    @Binding var tiles: Bool
    @Binding var pad: Int
    @Binding var swipeMode: Int
    let autoMore: Bool
    let autoMoreEnabled: Bool
    let onAutoMore: (Bool) -> Void
    @Binding var itemDetails: Bool
    @Binding var lowHealthBuzz: Bool
    @Binding var combatBuzz: Bool
    @Binding var tapBuzz: Bool
    @Binding var tapStrength: Double
    @Binding var fightStrength: Double
    @Binding var traceUsage: Bool
    @Binding var scumming: Bool
    let onExportSave: () -> Void
    let onImportSave: () -> Void
    let onSendLogs: () -> Void
    let onGameOptions: () -> Void
    let dismiss: () -> Void

    var body: some View {
        BottomSheet(maxHeight: 640, cancel: dismiss) {
            VStack(spacing: 0) {
                ScreenHeader(title: "Display and controls", onClose: dismiss)
                ScrollView {
                    VStack(spacing: 6) {
                        scale(UiText.buttonScale, $buttonScale)
                        scale(UiText.messageScale, $messageScale)
                        card(VStack(alignment: .leading, spacing: 6) {
                            Text(UiText.colours).scaledFont(15).foregroundColor(Palette.text)
                            ScrollView(.horizontal, showsIndicators: false) {
                                HStack(spacing: 8) {
                                    ForEach(Array(Theme.all.enumerated()), id: \.offset) { i, t in
                                        Button { theme = i } label: {
                                            Text(t.name).scaledFont(14).padding(.horizontal, 12).frame(minHeight: 32)
                                        }
                                        .buttonStyle(.plain).foregroundColor(theme == i ? .black : .white)
                                        .background(theme == i ? Palette.gold : Color.white.opacity(0.14), in: Capsule())
                                    }
                                }
                            }
                            HStack(spacing: 4) {
                                ForEach(Array(Theme.byIndex(theme).swatch(gamePalette).enumerated()), id: \.offset) { _, c in
                                    RoundedRectangle(cornerRadius: 6).fill(Color(rgb: c)).frame(width: 28, height: 28)
                                }
                            }
                            Text(UiText.coloursHint).scaledFont(12).foregroundColor(Palette.sub)
                        })
                        scale(UiText.itemScale, $itemScale)
                        scale(UiText.statusScale, $statusScale)
                        choice(UiText.messageLines, nil, $messageLines, [(2, "2"), (3, "3"), (4, "4"), (5, "5")])
                        choice(UiText.panelTabs, UiText.panelTabsHint, $panelTabs,
                               [(TabStyle.iconsAndNames.rawValue, "Icons and names"), (TabStyle.iconsOnly.rawValue, "Icons"), (TabStyle.names.rawValue, "Names")])
                        toggle(UiText.tabsOnTop, UiText.tabsOnTopHint, $tabsOnTop)
                        toggle(UiText.tiles, UiText.tilesHint, $tiles)
                        choice(UiText.dpad, UiText.dpadHint,
                               $pad, [(PadMode.off.rawValue, "Off"), (PadMode.docked.rawValue, "Beside status"), (PadMode.floating.rawValue, "Floating")])
                        choice(UiText.swipe, UiText.swipeHint,
                               $swipeMode, [(SwipeMode.pan.rawValue, "Pan"), (SwipeMode.step.rawValue, "Step"), (SwipeMode.run.rawValue, "Run")])
                        toggle(UiText.autoMore, UiText.autoMoreHint + (autoMoreEnabled ? "" : "  " + UiText.autoMoreLockedHint),
                               Binding(get: { autoMore }, set: onAutoMore), enabled: autoMoreEnabled)
                        toggle(UiText.itemDetails, UiText.itemDetailsHint, $itemDetails)
                        toggle(UiText.traceUsage, UiText.traceUsageHint, $traceUsage)
                        toggle(UiText.tapBuzz, UiText.tapBuzzHint, $tapBuzz)
                        if tapBuzz { strength(UiText.tapStrength, $tapStrength) { TapHaptics.sample() } }
                        toggle(UiText.combatBuzz, UiText.combatBuzzHint, $combatBuzz)
                        toggle(UiText.lowHealthBuzz, UiText.lowHealthBuzzHint, $lowHealthBuzz)
                        if combatBuzz || lowHealthBuzz { strength(UiText.fightStrength, $fightStrength) { UIImpactFeedbackGenerator(style: .medium).impactOccurred(intensity: min(1, 0.8 * fightStrength)) } }
                        toggle(UiText.scumming, UiText.scummingHint, $scumming)
                        card(VStack(alignment: .leading, spacing: 6) {
                            Text(UiText.saves).scaledFont(15).foregroundColor(Palette.text)
                            Text(UiText.savesHint).scaledFont(12).foregroundColor(Palette.sub)
                            HStack(spacing: 8) {
                                button("Export save") { dismiss(); onExportSave() }
                                button("Import save…") { dismiss(); onImportSave() }
                            }
                        })
                        card(VStack(alignment: .leading, spacing: 6) {
                            Text(UiText.problems).scaledFont(15).foregroundColor(Palette.text)
                            Text(UiText.problemsHint).scaledFont(12).foregroundColor(Palette.sub)
                            button("Send logs") { dismiss(); onSendLogs() }
                        })
                        Button("Game options…") { dismiss(); onGameOptions() }.padding(.top, 4)
                    }.padding(.bottom, 16)
                }
            }
        }
    }

    /// A buzz-strength slider: 20% to 400% of the default, with a sample when the finger lifts.
    private func strength(_ title: String, _ value: Binding<Double>, sample: @escaping () -> Void) -> some View {
        card(VStack(alignment: .leading, spacing: 2) {
            Text("\(title)  \(Int((value.wrappedValue * 100).rounded()))%").scaledFont(15).foregroundColor(Palette.text)
            Slider(value: value, in: 0.2...4, step: 0.1, onEditingChanged: { editing in if !editing { sample() } })
        })
    }

    private func scale(_ title: String, _ value: Binding<Double>) -> some View {
        card(VStack(alignment: .leading, spacing: 2) {
            Text("\(title)  \(Int((value.wrappedValue * 100).rounded()))%").scaledFont(15).foregroundColor(Palette.text)
            Slider(value: value, in: 0.8...1.8, step: 0.05)
        })
    }

    private func button(_ title: String, _ action: @escaping () -> Void) -> some View {
        Button(action: action) { Text(title).scaledFont(15).padding(.horizontal, 14).frame(minHeight: 36) }
            .buttonStyle(.plain).foregroundColor(.white).background(Color.white.opacity(0.14), in: Capsule())
    }

    private func card<C: View>(_ c: C) -> some View {
        c.padding(.horizontal, 14).padding(.vertical, 10).frame(maxWidth: .infinity, alignment: .leading)
            .cardSurface(Palette.card).padding(.horizontal, 12)
    }

    private func toggle(_ title: String, _ detail: String, _ value: Binding<Bool>, enabled: Bool = true) -> some View {
        card(HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(title).scaledFont(15).foregroundColor(Palette.text)
                Text(detail).scaledFont(12).foregroundColor(Palette.sub)
            }
            Spacer()
            Toggle("", isOn: value).labelsHidden().disabled(!enabled)
        })
    }

    private func choice(_ title: String, _ detail: String?, _ value: Binding<Int>, _ options: [(Int, String)]) -> some View {
        card(VStack(alignment: .leading, spacing: 6) {
            Text(title).scaledFont(15).foregroundColor(Palette.text)
            Picker(title, selection: value) { ForEach(options, id: \.0) { Text($0.1).tag($0.0) } }.pickerStyle(.segmented)
            if let detail { Text(detail).scaledFont(12).foregroundColor(Palette.sub) }
        })
    }
}
