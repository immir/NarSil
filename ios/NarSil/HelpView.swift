import SwiftUI

/// Help: the touch-controls guide (shared with Android, TouchGuide.swift), the game's symbol and command
/// references from lib/help, and the online manual (HelpScreen.kt).
struct HelpView: View {
    let dismiss: () -> Void
    @State private var tab = 0
    private static let manual = URL(string: "https://narsil.readthedocs.io/en/latest/")!

    var body: some View {
        VStack(spacing: 0) {
            ScreenHeader(title: "Help", onClose: dismiss, accessory: AnyView(Link("Online manual ↗", destination: Self.manual)))
            Picker("", selection: $tab) {
                Text("Touch controls").tag(0); Text("Symbols").tag(1); Text("Keyboard").tag(2)
            }.pickerStyle(.segmented).padding(.horizontal, 12).padding(.bottom, 6)
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 0) {
                    switch tab {
                    case 0: guide
                    case 1: file("symbols.txt")
                    default: file("commands.txt")
                    }
                }.padding(.horizontal, 16).padding(.bottom, 24)
            }.id(tab)
        }
        .background(Palette.screen.ignoresSafeArea())
    }

    @ViewBuilder private var guide: some View {
        ForEach(touchGuide, id: \.0) { title, points in
            heading(title)
            ForEach(points, id: \.self) { p in
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Text("•").foregroundColor(Palette.sub)
                    Text(p).scaledFont(15).foregroundColor(Palette.body).fixedSize(horizontal: false, vertical: true)
                }.padding(.bottom, 6)
            }
        }
    }

    private func heading(_ t: String) -> some View {
        Text(t).scaledFont(16, weight: .semibold).foregroundColor(Palette.gold).padding(.top, 16).padding(.bottom, 6)
    }

    @ViewBuilder private func file(_ name: String) -> some View {
        ForEach(Array(HelpFile.blocks(name).enumerated()), id: \.offset) { _, b in
            switch b {
            case .heading(let t): heading(t)
            case .prose(let t): Text(t).scaledFont(14).foregroundColor(Palette.body).fixedSize(horizontal: false, vertical: true).padding(.vertical, 4)
            case .pre(let t):
                ScrollView(.horizontal, showsIndicators: false) {
                    Text(t).scaledFont(12, design: .monospaced).foregroundColor(Palette.body).fixedSize()
                }.padding(.vertical, 4)
            case .entry(let k, let t):
                HStack(alignment: .firstTextBaseline, spacing: 0) {
                    Text(k).scaledFont(15, design: .monospaced).foregroundColor(Palette.gold).frame(width: 44, alignment: .leading)
                    Text(t).scaledFont(14).foregroundColor(Palette.body).fixedSize(horizontal: false, vertical: true)
                }.padding(.vertical, 3)
            }
        }
    }
}

/// A help file from the game data, parsed out of its fixed-width layout (HelpScreen.kt's parseHelp).
enum HelpFile {
    enum Block { case heading(String), prose(String), pre(String), entry(String, String) }

    static func blocks(_ name: String) -> [Block] {
        let url = GameLauncher.libDir.appendingPathComponent("help/\(name)")
        guard let text = try? String(contentsOf: url, encoding: .utf8) else { return [.prose("(\(name) is missing)")] }
        return parse(text.components(separatedBy: "\n").filter { !$0.hasPrefix(".. menu::") })
    }

    private static func match(_ pattern: String, _ s: String) -> [String]? {
        guard let re = try? NSRegularExpression(pattern: pattern),
              let m = re.firstMatch(in: s, range: NSRange(s.startIndex..., in: s)) else { return nil }
        return (0..<m.numberOfRanges).map { m.range(at: $0).location == NSNotFound ? "" : String(s[Range(m.range(at: $0), in: s)!]) }
    }

    private static func entry(_ half: String) -> Block? {
        match(#"^\s*(\S{1,3})\s{2,}(\S.*?)\s*$"#, half).map { .entry($0[1], $0[2]) }
    }

    /// Where a two-column table line splits: a run of spaces, then "key  text" from column 20 on.
    private static func splitColumns(_ line: String) -> (String, String)? {
        guard let re = try? NSRegularExpression(pattern: #"\s{2,}(?=\S{1,3}\s{2,}\S)"#) else { return nil }
        let ns = line as NSString
        return re.matches(in: line, range: NSRange(location: 0, length: ns.length))
            .first { $0.range.location + $0.range.length >= 20 }
            .map { (ns.substring(to: $0.range.location), ns.substring(from: $0.range.location + $0.range.length)) }
    }

    private static func parse(_ lines: [String]) -> [Block] {
        var out = [Block](), prose = "", pre = [String](), inTable = false
        func flush() {
            if !prose.isEmpty { out.append(.prose(prose.trimmingCharacters(in: .whitespaces))); prose = "" }
            if !pre.isEmpty { out.append(.pre(pre.joined(separator: "\n"))); pre = [] }
        }
        var i = 0
        while i < lines.count {
            let line = lines[i]
            let next = i + 1 < lines.count ? lines[i + 1] : ""
            let table: [Block]? = splitColumns(line).flatMap { l, r in
                guard let a = entry(l), let b = entry(r) else { return nil }
                return [a, b]
            }
            if line.trimmingCharacters(in: .whitespaces).isEmpty { flush(); inTable = false }
            else if match(#"^[-=]{3,}\s*$"#, next) != nil && !line.hasPrefix(" ") {
                flush(); out.append(.heading(line.trimmingCharacters(in: .whitespaces))); i += 1
            }
            else if let table { flush(); out += table; inTable = true }
            else if inTable, let e = entry(line) { out.append(e) }
            else if inTable && line.hasPrefix("   ") { out.append(.entry(" ", line.trimmingCharacters(in: .whitespaces))) }
            else if line.hasPrefix(" ") { if !prose.isEmpty { flush() }; pre.append(line) }
            else { if !pre.isEmpty { flush() }; prose += line.trimmingCharacters(in: .whitespaces) + " " }
            i += 1
        }
        flush()
        return out.filter { if case .entry(_, "-") = $0 { return false } else { return true } }
    }
}

/// Shown once, the first time the map appears: the few gestures that matter most, and a way into Help.
struct TipsCard: View {
    let onHelp: () -> Void
    let dismiss: () -> Void

    var body: some View {
        ZStack {
            Color.black.opacity(0.6).ignoresSafeArea()
            VStack(alignment: .leading, spacing: 10) {
                Text(firstRunTitle).scaledFont(20, weight: .semibold).foregroundColor(Palette.gold)
                ForEach(firstRunTips, id: \.self) { t in
                    HStack(alignment: .firstTextBaseline, spacing: 8) {
                        Text("•").foregroundColor(Palette.sub)
                        Text(t).scaledFont(15).foregroundColor(Palette.body).fixedSize(horizontal: false, vertical: true)
                    }
                }
                HStack(spacing: 12) {
                    Spacer()
                    Button("Open Help", action: onHelp)
                    Button("Got it", action: dismiss).buttonStyle(GoldButtonStyle())
                }.padding(.top, 4)
            }
            .padding(20).background(Palette.sheet, in: RoundedRectangle(cornerRadius: 16)).padding(24)
        }
    }
}
