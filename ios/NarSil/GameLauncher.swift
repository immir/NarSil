import Foundation

/// Starts the game thread.  The native game is process-global and cannot be restarted, so this runs once.
enum GameLauncher {
    static let cols = 80, rows = 24
    static let player = "PLAYER"
    /// nb_exit_message() after the welcome screen's "Play the other game" (NB_EXIT_SWITCH_GAME in narsil_bridge.h).
    static let exitSwitchGame = "switch-game"

    /// The two games one engine plays (GameLauncher.kt): each has its own read-only lib dir in the bundle (NarSil-Q's
    /// is lib with lib-silq copied over it, made by the build) and its own data dir, so characters, saves, scores and
    /// scum copies never mix.  `tag` is the savefile header (src/savefile.c).
    enum Game: String {
        case narsil, silq
        var title: String { self == .narsil ? "NarSil" : "NarSil-Q" }
        var lib: String { self == .narsil ? "lib" : "lib-silq" }
        var data: String { self == .narsil ? "data" : "data-silq" }
        var tag: String { self == .narsil ? "SaveUSIL" : "SaveUSLQ" }
        var other: Game { self == .narsil ? .silq : .narsil }
    }

    /// The game chosen on the welcome screen; fixed for the life of the process.
    static let game = Game(rawValue: UserDefaults.standard.string(forKey: "game") ?? "") ?? .narsil

    /// The read-only game data, copied into the app bundle by the build (see project.yml and xtool-prepare.sh).
    static var libDir: URL { Bundle.main.resourceURL!.appendingPathComponent(game.lib) }
    /// Saves and scores: in Documents, which the Files app shows (UIFileSharingEnabled).
    static var dataDir: URL {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        return docs.appendingPathComponent(game.data)
    }

    /// The welcome screen's "Play the other game": remembered for the next launch (iOS apps can't relaunch themselves).
    static func chooseOtherGame() { UserDefaults.standard.set(game.other.rawValue, forKey: "game") }

    @MainActor static func ensureStarted() {
        guard Native.state == NB.IDLE else { return }
        try? FileManager.default.createDirectory(at: dataDir, withIntermediateDirectories: true)
        AppLog.open()
        Saves.applyPendingRestore()
        let ok = Native.start(libDir: libDir.path, dataDir: dataDir.path, player: player, cols: cols, rows: rows)
        assert(ok, "nb_start failed")
    }
}
