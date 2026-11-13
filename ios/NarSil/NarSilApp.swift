import SwiftUI
import UIKit

@main
struct NarSilApp: App {
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var game = GameState()

    init() { GameLauncher.ensureStarted() }

    var body: some Scene {
        WindowGroup {
            RootView().environmentObject(game).statusBarHidden(true)
        }
        .onChange(of: scenePhase) { phase in
            switch phase {
            case .active: game.start()
            case .inactive: break
            case .background:
                game.stop()
                Lifecycle.saveBeforeSuspend()
            @unknown default: break
            }
        }
    }
}

/// iOS suspends an app soon after it leaves the foreground, and may kill it without notice afterwards: ask for
/// a save and borrow background time until the game has made it.  (Android does the same in onStop.)
enum Lifecycle {
    @MainActor static func saveBeforeSuspend() {
        let before = Native.saveCount
        Native.requestSave()
        var task = UIBackgroundTaskIdentifier.invalid
        task = UIApplication.shared.beginBackgroundTask(withName: "narsil-save") {
            UIApplication.shared.endBackgroundTask(task)
        }
        Task {
            var waited = 0
            while Native.saveCount == before && waited < 80 {  // at most about four seconds
                try? await Task.sleep(nanoseconds: 50_000_000)
                waited += 1
            }
            UIApplication.shared.endBackgroundTask(task)
        }
    }
}
