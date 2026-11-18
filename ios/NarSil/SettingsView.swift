import SwiftUI

/// How the d-pad is shown (Settings → Display and controls); the same choices as Android where the layout allows.
enum PadMode: Int { case off = 0, docked = 1, floating = 2 }

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
    @Binding var tiles: Bool
    @Binding var pad: Int
    @Binding var padSize: Double
    @Binding var swipeRuns: Bool
    let autoMore: Bool
    let autoMoreEnabled: Bool
    let onAutoMore: (Bool) -> Void
    @Binding var itemDetails: Bool
    @Binding var lowHealthBuzz: Bool
    @Binding var scumming: Bool
    let onExportSave: () -> Void
    let onImportSave: () -> Void
    let onSendLogs: () -> Void
    let dismiss: () -> Void

    var body: some View {
        BottomSheet(maxHeight: 640, cancel: dismiss) {
            VStack(spacing: 0) {
                HStack {
                    Text("Options").scaledFont(17, weight: .semibold).foregroundColor(.white)
                    Spacer()
                    Button("Done", action: dismiss)
                }.padding(.horizontal, 16).padding(.vertical, 12)
                ScrollView {
                    VStack(spacing: 6) {
                        scale("Buttons and menus", $buttonScale)
                        scale("Messages", $messageScale)
                        card(VStack(alignment: .leading, spacing: 6) {
                            Text("Colours").scaledFont(15).foregroundColor(Color(rgb: 0xE6E6EA))
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
                            Text("Bright lifts the game's dark blues and reds so they read on black; Original is the game's own palette.").scaledFont(12).foregroundColor(Palette.sub)
                        })
                        scale("Inventory and items", $itemScale)
                        scale("Status", $statusScale)
                        choice("Messages shown", nil, $messageLines, [(2, "2"), (3, "3"), (4, "4"), (5, "5")])
                        choice("Panel tabs", "Icons fit the panel without scrolling; names are the scrolling strip.", $panelTabs,
                               [(TabStyle.iconsAndNames.rawValue, "Icons and names"), (TabStyle.iconsOnly.rawValue, "Icons"), (TabStyle.names.rawValue, "Names")])
                        toggle("Tiles on the map", "MicroChasm's pictures instead of letters. Lists and menus keep the letters.", $tiles)
                        choice("D-pad", "Tap a key to step; hold it, or drag from the centre key towards it, to run.  Centre: tap to wait, hold to rest; on a floating pad hold the centre then drag to move it.  Beside status sits under the status in portrait and in the side column in landscape.",
                               $pad, [(PadMode.off.rawValue, "Off"), (PadMode.docked.rawValue, "Beside status"), (PadMode.floating.rawValue, "Floating")])
                        if pad != PadMode.off.rawValue {
                            card(VStack(alignment: .leading, spacing: 2) {
                                Text("D-pad size  \(Int(padSize)) pt").scaledFont(15).foregroundColor(Color(rgb: 0xE6E6EA))
                                Slider(value: $padSize, in: 100...220, step: 5)
                            })
                        }
                        toggle("Swipe on the map runs", "Otherwise a swipe takes one step.", $swipeRuns)
                        toggle("Skip -more- prompts", "Messages are not held for a tap to continue; the message log keeps all of them. This is the game's own \"Automatically clear -more- prompts\" option, saved with the character."
                               + (autoMoreEnabled ? "" : "  Change it from the map, or in Game options → User interface."),
                               Binding(get: { autoMore }, set: onAutoMore), enabled: autoMoreEnabled)
                        toggle("Item details in the pack", "Says what an ego item gives in general, and what each ability an item grants does, when you open it in the pack.", $itemDetails)
                        toggle("Buzz when health is low", "Below the game's hitpoint warning (Game options → Hitpoint warning), and a short pulse when one turn costs a fifth of your health.", $lowHealthBuzz)
                        toggle("Save scumming (for testing)", "Adds Scum (keep a copy of your save) and Restore scum save to the ☰ menu, and Restore to the death screen. Restore also lists the last three autosaves, at least five minutes apart.", $scumming)
                        card(VStack(alignment: .leading, spacing: 6) {
                            Text("Saves").scaledFont(15).foregroundColor(Color(rgb: 0xE6E6EA))
                            Text("Export sends a copy of your save to another app (Files, mail…). Import loads one back, replacing the current game.").scaledFont(12).foregroundColor(Palette.sub)
                            HStack(spacing: 8) {
                                button("Export save") { dismiss(); onExportSave() }
                                button("Import save…") { dismiss(); onImportSave() }
                            }
                        })
                        card(VStack(alignment: .leading, spacing: 6) {
                            Text("Problems").scaledFont(15).foregroundColor(Color(rgb: 0xE6E6EA))
                            Text("Send logs shares the app's log, your save and character dump, for a bug report.").scaledFont(12).foregroundColor(Palette.sub)
                            button("Send logs") { dismiss(); onSendLogs() }
                        })
                    }.padding(.bottom, 16)
                }
            }
        }
    }

    private func scale(_ title: String, _ value: Binding<Double>) -> some View {
        card(VStack(alignment: .leading, spacing: 2) {
            Text("\(title)  \(Int((value.wrappedValue * 100).rounded()))%").scaledFont(15).foregroundColor(Color(rgb: 0xE6E6EA))
            Slider(value: value, in: 0.8...1.8, step: 0.05)
        })
    }

    private func button(_ title: String, _ action: @escaping () -> Void) -> some View {
        Button(action: action) { Text(title).scaledFont(15).padding(.horizontal, 14).frame(minHeight: 36) }
            .buttonStyle(.plain).foregroundColor(.white).background(Color.white.opacity(0.14), in: Capsule())
    }

    private func card<C: View>(_ c: C) -> some View {
        c.padding(.horizontal, 14).padding(.vertical, 10).frame(maxWidth: .infinity, alignment: .leading)
            .background(Palette.card).clipShape(RoundedRectangle(cornerRadius: 10)).padding(.horizontal, 12)
    }

    private func toggle(_ title: String, _ detail: String, _ value: Binding<Bool>, enabled: Bool = true) -> some View {
        card(HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(title).scaledFont(15).foregroundColor(Color(rgb: 0xE6E6EA))
                Text(detail).scaledFont(12).foregroundColor(Palette.sub)
            }
            Spacer()
            Toggle("", isOn: value).labelsHidden().disabled(!enabled)
        })
    }

    private func choice(_ title: String, _ detail: String?, _ value: Binding<Int>, _ options: [(Int, String)]) -> some View {
        card(VStack(alignment: .leading, spacing: 6) {
            Text(title).scaledFont(15).foregroundColor(Color(rgb: 0xE6E6EA))
            Picker(title, selection: value) { ForEach(options, id: \.0) { Text($0.1).tag($0.0) } }.pickerStyle(.segmented)
            if let detail { Text(detail).scaledFont(12).foregroundColor(Palette.sub) }
        })
    }
}
