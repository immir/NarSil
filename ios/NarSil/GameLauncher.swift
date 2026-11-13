import Foundation

/// Starts the game thread.  The native game is process-global and cannot be restarted, so this runs once.
enum GameLauncher {
    static let cols = 80, rows = 24
    static let player = "PLAYER"

    /// The read-only game data, copied into the app bundle's lib/ by the build (see project.yml).
    static var libDir: URL { Bundle.main.resourceURL!.appendingPathComponent("lib") }
    /// Saves and scores: in Documents, which the Files app shows (UIFileSharingEnabled).
    static var dataDir: URL {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        return docs.appendingPathComponent("data")
    }

    @MainActor static func ensureStarted() {
        guard Native.state == NB.IDLE else { return }
        try? FileManager.default.createDirectory(at: dataDir, withIntermediateDirectories: true)
        let ok = Native.start(libDir: libDir.path, dataDir: dataDir.path, player: player, cols: cols, rows: rows)
        assert(ok, "nb_start failed")
    }
}
