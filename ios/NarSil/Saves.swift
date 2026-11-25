import SwiftUI
import UIKit

/// Save scumming, export/import and bug reports (Scum.kt and Share.kt).  The native game cannot be restarted
/// in-process, so a restore leaves a marker and closes the app; `applyPendingRestore` swaps the copy in on the
/// next launch, before the game thread starts.
enum Saves {
    struct Copy: Identifiable {
        let url: URL
        let auto: Bool
        var id: String { url.lastPathComponent }
        var date: Date { (try? url.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast }
        var label: String { Saves.labelFormat.string(from: date) }
    }

    private static let keep = 12
    private static let autoKeep = 3
    private static let autoGap: TimeInterval = 5 * 60
    private static let marker = "restore"
    static let cantSave = "Can't save right now: finish what you're doing first."

    private static let fm = FileManager.default
    static var saveFile: URL { GameLauncher.dataDir.appendingPathComponent("save/\(GameLauncher.player)") }
    static var characterDump: URL { GameLauncher.dataDir.appendingPathComponent("user/character-dump.txt") }
    private static var dir: URL {
        let d = GameLauncher.dataDir.appendingPathComponent("scum")
        try? fm.createDirectory(at: d, withIntermediateDirectories: true)
        return d
    }
    private static var exports: URL {
        let d = fm.temporaryDirectory.appendingPathComponent("exports")
        try? fm.removeItem(at: d)
        try? fm.createDirectory(at: d, withIntermediateDirectories: true)
        return d
    }

    private static func stamp(_ format: String, _ date: Date = Date()) -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = format
        return f.string(from: date)
    }
    fileprivate static let labelFormat: DateFormatter = {
        let f = DateFormatter()
        f.setLocalizedDateFormatFromTemplate("EEE d MMM HH:mm:ss")
        return f
    }()

    private static func files(prefix: String, auto: Bool) -> [Copy] {
        ((try? fm.contentsOfDirectory(at: dir, includingPropertiesForKeys: [.contentModificationDateKey])) ?? [])
            .filter { $0.lastPathComponent.hasPrefix(prefix) }
            .sorted { $0.lastPathComponent > $1.lastPathComponent }
            .map { Copy(url: $0, auto: auto) }
    }
    static var copies: [Copy] { files(prefix: "\(GameLauncher.player)-", auto: false) }
    static var autosaves: [Copy] { files(prefix: "auto-\(GameLauncher.player)-", auto: true) }
    /// Manual copies and autosaves together, newest first.
    static var all: [Copy] { (copies + autosaves).sorted { $0.date > $1.date } }

    private static func sameBytes(_ a: URL, _ b: URL) -> Bool {
        guard let x = fm.contents(atPath: a.path), let y = fm.contents(atPath: b.path) else { return false }
        return x == y
    }

    /// After the game has saved: keep a copy unless the newest kept one is under five minutes old, so the few
    /// copies reach back a quarter of an hour or more.
    static func noteSave(now: Date = Date()) {
        guard fm.fileExists(atPath: saveFile.path) else { return }
        if let newest = autosaves.first, now.timeIntervalSince(newest.date) < autoGap { return }
        if copies.contains(where: { sameBytes($0.url, saveFile) }) { return }
        try? fm.copyItem(at: saveFile, to: dir.appendingPathComponent("auto-\(GameLauncher.player)-\(stamp("yyyyMMdd-HHmmss", now))"))
        autosaves.dropFirst(autoKeep).forEach { try? fm.removeItem(at: $0.url) }
    }

    /// Asks the game to save (it can only at the command prompt); true once it has.
    @MainActor static func saveNow() async -> Bool {
        let before = Native.saveCount
        Native.requestSave()
        for _ in 0..<60 where Native.saveCount == before { try? await Task.sleep(nanoseconds: 50_000_000) }
        return Native.saveCount != before
    }

    /// Saves, then keeps a dated copy.  Returns a line for the user either way.
    @MainActor static func take() async -> String {
        guard await saveNow() else { return cantSave }
        try? fm.copyItem(at: saveFile, to: dir.appendingPathComponent("\(GameLauncher.player)-\(stamp("yyyyMMdd-HHmmss"))"))
        autosaves.filter { sameBytes($0.url, saveFile) }.forEach { try? fm.removeItem(at: $0.url) }
        copies.dropFirst(keep).forEach { try? fm.removeItem(at: $0.url) }
        return "Scum save taken."
    }

    /// Saves, then copies the savefile to a dated file for sharing; nil if the game can't save right now.
    @MainActor static func export() async -> URL? {
        guard await saveNow() else { return nil }
        let out = exports.appendingPathComponent("NarSil-save-\(stamp("yyyy-MM-dd-HHmm")).sav")
        return (try? fm.copyItem(at: saveFile, to: out)) != nil ? out : nil
    }

    /// Copies a savefile the user picked in as a scum copy, ready for `restore`.
    static func importSave(from url: URL) -> Copy? {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        let dest = dir.appendingPathComponent("\(GameLauncher.player)-\(stamp("yyyyMMdd-HHmmss"))")
        guard (try? fm.copyItem(at: url, to: dest)) != nil else { return nil }
        guard isSavefile(dest) else { try? fm.removeItem(at: dest); return nil }
        return Copy(url: dest, auto: false)
    }

    /// A savefile of `game`: "Save" + the variant name (src/savefile.c), "USIL" for NarSil, "USLQ" for NarSil-Q.
    static func isSavefile(_ url: URL, of game: GameLauncher.Game = GameLauncher.game) -> Bool {
        guard let h = try? FileHandle(forReadingFrom: url) else { return false }
        defer { try? h.close() }
        return (try? h.read(upToCount: 8)) == Data(game.tag.utf8)
    }

    /// Marks `copy` to be restored and closes the app; the copy is loaded when it is opened again.
    static func restore(_ copy: Copy) -> Never {
        try? copy.id.write(to: dir.appendingPathComponent(marker), atomically: true, encoding: .utf8)
        exit(0)
    }

    /// Before the game starts: swap in a copy marked by `restore`.
    static func applyPendingRestore() {
        let m = dir.appendingPathComponent(marker)
        guard let name = try? String(contentsOf: m, encoding: .utf8) else { return }
        let copy = dir.appendingPathComponent(name.trimmingCharacters(in: .whitespacesAndNewlines))
        if isSavefile(copy) {
            // The save being replaced is kept as the newest autosave, so a restore can be undone.
            if isSavefile(saveFile), !sameBytes(saveFile, copy) {
                try? fm.copyItem(at: saveFile, to: dir.appendingPathComponent("auto-\(GameLauncher.player)-\(stamp("yyyyMMdd-HHmmss"))"))
                autosaves.dropFirst(autoKeep).forEach { try? fm.removeItem(at: $0.url) }
            }
            try? fm.createDirectory(at: saveFile.deletingLastPathComponent(), withIntermediateDirectories: true)
            try? fm.removeItem(at: saveFile)
            try? fm.copyItem(at: copy, to: saveFile)
        }
        try? fm.removeItem(at: m)
    }

    /// Asks the game for a fresh character dump (it writes it in answer to CHARACTER_DUMP), then shares it.
    @MainActor static func shareDump(_ answer: (Int) -> Void) {
        let modified = { (try? characterDump.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast }
        let before = modified()
        answer(NB.CHARACTER_DUMP)
        Task {
            for _ in 0..<60 where modified() == before { try? await Task.sleep(nanoseconds: 50_000_000) }
            if modified() != before { ShareSheet.present([characterDump]) }
        }
    }

    private static var crashNote: URL { GameLauncher.dataDir.appendingPathComponent("crash.txt") }
    private static var crashKept: URL { GameLauncher.dataDir.appendingPathComponent("crash.previous.txt") }

    /// The bridge's signal handler leaves crash.txt when the game dies natively (narsil_crash.c).
    static var pendingCrash: Bool { fm.fileExists(atPath: crashNote.path) }

    /// Keeps the note for the next report and stops asking about it.
    static func acknowledgeCrash() {
        try? fm.removeItem(at: crashKept)
        try? fm.moveItem(at: crashNote, to: crashKept)
    }

    /// For a bug report: the app's log, the crash note, the savefile and the character dump, whichever exist.
    static var reportFiles: [URL] {
        ([AppLog.url, AppLog.previous, crashKept, saveFile, characterDump] + Trace.files(in: GameLauncher.dataDir)).filter { fm.fileExists(atPath: $0.path) }
    }
}

/// The game's log lines, kept in a file for bug reports (Android uses logcat); the previous run's log is kept too.
enum AppLog {
    static var url: URL { GameLauncher.dataDir.appendingPathComponent("narsil.log") }
    static var previous: URL { GameLauncher.dataDir.appendingPathComponent("narsil.previous.log") }
    private static var handle: FileHandle?
    private static let lock = NSLock()

    static func open() {
        let fm = FileManager.default
        try? fm.removeItem(at: previous)
        try? fm.moveItem(at: url, to: previous)
        fm.createFile(atPath: url.path, contents: nil)
        handle = try? FileHandle(forWritingTo: url)
    }

    /// From any thread (the game thread logs).
    static func append(_ line: String) {
        NSLog("narsil: %@", line)
        lock.lock(); defer { lock.unlock() }
        handle?.write(Data((line + "\n").utf8))
    }
}

/// The system share sheet for files (mail, Files, AirDrop…).
enum ShareSheet {
    @MainActor static func present(_ urls: [URL]) {
        guard !urls.isEmpty,
              let root = UIApplication.shared.connectedScenes.compactMap({ ($0 as? UIWindowScene)?.keyWindow }).first?.rootViewController
        else { return }
        let top = sequence(first: root) { $0.presentedViewController }.reduce(root) { $1 }
        let vc = UIActivityViewController(activityItems: urls, applicationActivities: nil)
        vc.popoverPresentationController?.sourceView = top.view
        top.present(vc, animated: false)
    }
}

/// The scum copies and autosaves, newest first: tap one to go back to it.
struct ScumSheet: View {
    let dismiss: () -> Void
    @State private var all = Saves.all
    @State private var chosen: Saves.Copy?

    var body: some View {
        BottomSheet(maxHeight: 560, cancel: dismiss) {
            VStack(alignment: .leading, spacing: 0) {
                ScreenHeader(title: "Restore a scum save", onClose: dismiss)
                Text("Your saves in white, the game's autosaves in blue.").scaledFont(13).foregroundColor(Palette.sub).padding(.horizontal, 16)
                ScrollView {
                    VStack(spacing: 0) {
                        if all.isEmpty {
                            Text("None yet. Use Scum in ☰ while playing; autosaves appear every few minutes.")
                                .scaledFont(14).foregroundColor(Palette.sub).padding(16)
                        }
                        ForEach(all) { c in
                            HStack {
                                Text(c.auto ? "\(c.label)  (auto)" : c.label).scaledFont(15)
                                    .foregroundColor(c.auto ? Color(rgb: 0x6EB1FF) : .white)
                                Spacer()
                                if !c.auto { Button("Delete") { try? FileManager.default.removeItem(at: c.url); all = Saves.all } }
                            }
                            .padding(.horizontal, 16).frame(minHeight: 48).contentShape(Rectangle())
                            .onTapGesture { chosen = c }
                        }
                    }
                }
            }
        }
        .alert("Restore this save?", isPresented: Binding(get: { chosen != nil }, set: { if !$0 { chosen = nil } }), presenting: chosen) { c in
            Button("Restore and close the app", role: .destructive) { Saves.restore(c) }
            Button("Cancel", role: .cancel) {}
        } message: { c in
            Text("\(c.label)\n\nNarSil closes; open it again to play from this copy. Anything since is lost.")
        }
    }
}
