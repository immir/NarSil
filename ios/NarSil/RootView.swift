import SwiftUI

/// Bring-up screen: shows that the bridge works end to end (state, messages, prompts, moves).
/// The real UI replaces this in Phase 3 (map, action bar, panel; see plans/ios.md).
struct RootView: View {
    @EnvironmentObject var game: GameState

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("HP \(game.status.hp)/\(game.status.maxHp)  Voice \(game.status.voice)/\(game.status.maxVoice)  Depth \(game.status.depth)")
                .font(.system(.body, design: .monospaced))
            Text(game.mapMode ? (game.atPrompt ? "Map, waiting for a command" : "Map") : "No map")
            ScrollView {
                VStack(alignment: .leading) {
                    ForEach(Array(game.messages.suffix(8).enumerated()), id: \.offset) { _, m in
                        Text(m.text).font(.system(.footnote, design: .monospaced))
                    }
                }.frame(maxWidth: .infinity, alignment: .leading)
            }
            if let prompt = game.prompt {
                Text("Prompt: \(String(describing: prompt).prefix(80))")
                Button("Cancel prompt") { Native.answer(-1) }
            }
            Grid {
                ForEach([[7, 8, 9], [4, 5, 6], [1, 2, 3]], id: \.self) { row in
                    GridRow {
                        ForEach(row, id: \.self) { dir in
                            Button(dir == 5 ? "·" : "\(dir)") {
                                if dir == 5 { Native.requestAction(NB.ACT_HOLD) } else { Native.requestMove(dir, run: false) }
                            }
                            .frame(width: 64, height: 48).background(.quaternary)
                        }
                    }
                }
            }
            Button("Save") { Native.requestSave() }
        }
        .padding()
        .onAppear { game.start() }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    }
}
