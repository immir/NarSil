// swift-tools-version: 6.0
// The xtool build (Linux or macOS): `./xtool-prepare.sh && xtool dev`.  CI and Xcode use project.yml instead.
import Foundation
import PackageDescription

/// Every core source except the platform front ends and sound (as bridge/CMakeLists.txt); listed by name so SwiftPM sees no main.c.
let gameSources: [String] = try! FileManager.default
    .contentsOfDirectory(atPath: "Sources/NarSilGame/src")
    .filter { $0.hasSuffix(".c") && !$0.hasPrefix("main") && !$0.hasPrefix("snd-") }
    .sorted()
    .map { "src/" + $0 }

let package = Package(
    name: "NarSil",
    platforms: [.iOS(.v16), .macOS(.v13)],
    products: [.library(name: "NarSil", targets: ["NarSil"])],
    targets: [
        .target(
            name: "NarSilGame",
            path: "Sources/NarSilGame",
            exclude: ["src/cmake", "src/cocoa", "src/doc", "src/nds", "src/stats", "src/tests", "src/win"],
            sources: gameSources,
            publicHeadersPath: "include",
            cSettings: [.unsafeFlags(["-w", "-std=gnu99"])]
        ),
        .target(
            name: "NarSilCore",
            dependencies: ["NarSilGame"],
            path: "Sources/NarSilCore",
            exclude: ["bridge/CMakeLists.txt", "bridge/host_harness.c", "bridge/gen-swift-constants.py", "bridge/nbconst.py", "bridge/__pycache__"],
            sources: [
                "bridge/narsil_bridge.c", "bridge/narsil_native.c", "bridge/narsil_prompt.c", "bridge/narsil_smith.c",
                "bridge/narsil_birth.c", "bridge/narsil_options.c", "bridge/narsil_knowledge.c", "bridge/narsil_tiles.c",
                "bridge/narsil_combat.c",
            ],
            publicHeadersPath: "include",
            cSettings: [.define("NB_SYS_NAME", to: "\"ios\""), .headerSearchPath("bridge"), .headerSearchPath("../NarSilGame/src")]
        ),
        .target(
            name: "NarSil",
            dependencies: ["NarSilCore"],
            path: "NarSil",
            swiftSettings: [.swiftLanguageMode(.v5)]
        ),
    ]
)
