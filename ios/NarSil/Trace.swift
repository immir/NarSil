import Foundation

/// The usage trace (Options → Record usage trace): one line per interaction, kept on the device and shared only by
/// Send logs.  Lines are `ms-since-start TAB event TAB key=value TAB key=value`; the vocabulary is shared with the
/// Android app (Trace.kt) and read by tools/trace-summary.py.  Never text the player typed, no names or messages.
enum Trace {
    private static let lock = NSLock()
    private static let queue = DispatchQueue(label: "trace", qos: .utility)
    private static let rotateBytes: UInt64 = 8 << 20
    private static var enabledFlag = false
    private static var url: URL?
    private static var started = DispatchTime.now()

    /// Set by the game state: classifies an answer the UI sent (the prompt it answered is known there).
    nonisolated(unsafe) static var answerHook: ((Int) -> Void)?

    static var enabled: Bool { lock.withLock { enabledFlag } }

    /// One record: milliseconds since the session began, the event, then its fields in the order given.
    static func line(ms: Int, event: String, _ fields: [(String, Any)]) -> String {
        let clean = { (s: String) in s.replacingOccurrences(of: "\t", with: " ").replacingOccurrences(of: "\n", with: " ") }
        return ([String(ms), event] + fields.map { "\($0.0)=\(clean("\($0.1)"))" }).joined(separator: "\t") + "\n"
    }

    /// Opens the trace file (a big one is rotated aside) and starts a session record if recording is on.
    static func start(dir: URL, on: Bool, header: [(String, Any)]) {
        let file = dir.appendingPathComponent("trace.log")
        let size = (try? FileManager.default.attributesOfItem(atPath: file.path)[.size] as? UInt64) ?? 0
        if size > rotateBytes {
            let previous = dir.appendingPathComponent("trace.previous.log")
            try? FileManager.default.removeItem(at: previous)
            try? FileManager.default.moveItem(at: file, to: previous)
        }
        lock.withLock { url = file; started = .now() }
        setEnabled(on, header: header, force: true)
    }

    static func setEnabled(_ on: Bool, header: [(String, Any)], force: Bool = false) {
        let changed = lock.withLock { () -> Bool in
            let was = enabledFlag
            enabledFlag = on
            if on && (force || !was) { started = .now() }
            return on && (force || !was)
        }
        if changed { log("session", header) }
    }

    static func log(_ event: String, _ fields: (String, Any)...) { log(event, fields) }

    static func log(_ event: String, _ fields: [(String, Any)]) {
        let (target, ms) = lock.withLock { () -> (URL?, Int) in
            (enabledFlag ? url : nil, Int(DispatchTime.now().uptimeNanoseconds - started.uptimeNanoseconds) / 1_000_000)
        }
        guard let target else { return }
        let text = line(ms: ms, event: event, fields)
        queue.async {
            if let h = try? FileHandle(forWritingTo: target) {
                defer { try? h.close() }
                _ = try? h.seekToEnd()
                try? h.write(contentsOf: Data(text.utf8))
            } else {
                try? Data(text.utf8).write(to: target)
            }
        }
    }

    /// The trace files that exist, for the bug-report share.
    static func files(in dir: URL) -> [URL] {
        ["trace.previous.log", "trace.log"].map { dir.appendingPathComponent($0) }.filter { FileManager.default.fileExists(atPath: $0.path) }
    }
}
