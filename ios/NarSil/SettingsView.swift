import SwiftUI

/// How the d-pad is shown (Settings → Display and controls); the same choices as Android where the layout allows.
enum PadMode: Int { case off = 0, docked = 1 }

/// The app's own settings (not the game's options), in the same order and words as Android's Options sheet.
/// Kept in UserDefaults; the game is told about the ones it needs.
struct SettingsSheet: View {
    @Binding var messageLines: Int
    @Binding var panelTabs: Int
    @Binding var tiles: Bool
    @Binding var pad: Int
    @Binding var swipeRuns: Bool
    let autoMore: Bool
    let autoMoreEnabled: Bool
    let onAutoMore: (Bool) -> Void
    @Binding var itemDetails: Bool
    @Binding var lowHealthBuzz: Bool
    let dismiss: () -> Void

    var body: some View {
        BottomSheet(maxHeight: 640, cancel: dismiss) {
            VStack(spacing: 0) {
                HStack {
                    Text("Options").font(.system(size: 17, weight: .semibold)).foregroundColor(.white)
                    Spacer()
                    Button("Done", action: dismiss)
                }.padding(.horizontal, 16).padding(.vertical, 12)
                ScrollView {
                    VStack(spacing: 6) {
                        choice("Messages shown", nil, $messageLines, [(2, "2"), (3, "3"), (4, "4"), (5, "5")])
                        choice("Panel tabs", "Icons fit the panel without scrolling; names are the scrolling strip.", $panelTabs,
                               [(TabStyle.iconsAndNames.rawValue, "Icons and names"), (TabStyle.iconsOnly.rawValue, "Icons"), (TabStyle.names.rawValue, "Names")])
                        toggle("Tiles on the map", "MicroChasm's pictures instead of letters. Lists and menus keep the letters.", $tiles)
                        choice("D-pad", "Tap a key to step; hold it, or drag from the centre key towards it, to run.  Centre: tap to wait, hold to rest.  Beside status sits under the status in portrait and in the side column in landscape.",
                               $pad, [(PadMode.off.rawValue, "Off"), (PadMode.docked.rawValue, "Beside status")])
                        toggle("Swipe on the map runs", "Otherwise a swipe takes one step.", $swipeRuns)
                        toggle("Skip -more- prompts", "Messages are not held for a tap to continue; the message log keeps all of them. This is the game's own \"Automatically clear -more- prompts\" option, saved with the character."
                               + (autoMoreEnabled ? "" : "  Change it from the map, or in Game options → User interface."),
                               Binding(get: { autoMore }, set: onAutoMore), enabled: autoMoreEnabled)
                        toggle("Item details in the pack", "Says what an ego item gives in general, and what each ability an item grants does, when you open it in the pack.", $itemDetails)
                        toggle("Buzz when health is low", "Below the game's hitpoint warning (Game options → Hitpoint warning).", $lowHealthBuzz)
                    }.padding(.bottom, 16)
                }
            }
        }
    }

    private func card<C: View>(_ c: C) -> some View {
        c.padding(.horizontal, 14).padding(.vertical, 10).frame(maxWidth: .infinity, alignment: .leading)
            .background(Palette.card).clipShape(RoundedRectangle(cornerRadius: 10)).padding(.horizontal, 12)
    }

    private func toggle(_ title: String, _ detail: String, _ value: Binding<Bool>, enabled: Bool = true) -> some View {
        card(HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.system(size: 15)).foregroundColor(Color(rgb: 0xE6E6EA))
                Text(detail).font(.system(size: 12)).foregroundColor(Palette.sub)
            }
            Spacer()
            Toggle("", isOn: value).labelsHidden().disabled(!enabled)
        })
    }

    private func choice(_ title: String, _ detail: String?, _ value: Binding<Int>, _ options: [(Int, String)]) -> some View {
        card(VStack(alignment: .leading, spacing: 6) {
            Text(title).font(.system(size: 15)).foregroundColor(Color(rgb: 0xE6E6EA))
            Picker(title, selection: value) { ForEach(options, id: \.0) { Text($0.1).tag($0.0) } }.pickerStyle(.segmented)
            if let detail { Text(detail).font(.system(size: 12)).foregroundColor(Palette.sub) }
        })
    }
}
