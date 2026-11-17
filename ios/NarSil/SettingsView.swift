import SwiftUI

/// The app's own settings (not the game's options): how the map and controls behave.  Kept in UserDefaults.
struct SettingsSheet: View {
    @Binding var tiles: Bool
    @Binding var swipeRuns: Bool
    @Binding var itemDetails: Bool
    @Binding var panelTabs: Int
    let dismiss: () -> Void

    var body: some View {
        BottomSheet(maxHeight: 560, cancel: dismiss) {
            VStack(spacing: 0) {
                HStack {
                    Text("Display and controls").font(.system(size: 17, weight: .semibold)).foregroundColor(.white)
                    Spacer()
                    Button("Done", action: dismiss)
                }.padding(.horizontal, 16).padding(.vertical, 12)
                ScrollView {
                    VStack(spacing: 6) {
                        VStack(alignment: .leading, spacing: 6) {
                            Text("Panel tabs").font(.system(size: 15)).foregroundColor(Color(rgb: 0xE6E6EA))
                            Picker("Panel tabs", selection: $panelTabs) {
                                Text("Icons and names").tag(TabStyle.iconsAndNames.rawValue)
                                Text("Icons").tag(TabStyle.iconsOnly.rawValue)
                                Text("Names").tag(TabStyle.names.rawValue)
                            }.pickerStyle(.segmented)
                        }
                        .padding(.horizontal, 14).padding(.vertical, 10).frame(maxWidth: .infinity, alignment: .leading)
                        .background(Palette.card).clipShape(RoundedRectangle(cornerRadius: 10)).padding(.horizontal, 12)
                        row("Tiles", "Draw the map with the MicroChasm tiles instead of letters.", $tiles)
                        row("Swipe to run", "A one-finger swipe on the map runs instead of walking one step.", $swipeRuns)
                        row("Item details in the pack", "Say what an ego item gives and describe abilities when an item is opened.", $itemDetails)
                    }.padding(.bottom, 16)
                }
            }
        }
    }

    private func row(_ title: String, _ detail: String, _ value: Binding<Bool>) -> some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.system(size: 15)).foregroundColor(Color(rgb: 0xE6E6EA))
                Text(detail).font(.system(size: 12)).foregroundColor(Palette.sub)
            }
            Spacer()
            Toggle("", isOn: value).labelsHidden()
        }
        .padding(.horizontal, 14).padding(.vertical, 10).frame(maxWidth: .infinity, alignment: .leading)
        .background(Palette.card).clipShape(RoundedRectangle(cornerRadius: 10)).padding(.horizontal, 12)
    }
}
