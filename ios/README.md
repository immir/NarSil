# NarSil for iOS (in progress)

Plan: `../plans/ios.md`.  A SwiftUI app over the same C bridge as Android (`../bridge`).

**Status:** the Phase 2 skeleton is written but has never been compiled (no Mac or Xcode on the dev machine).
Expect to fix a few compile errors on the first build; known risks are listed below.

| File | Role |
|---|---|
| `build-core.sh` | builds the core and bridge for device and simulator into `build/NarSilCore.xcframework` |
| `project.yml` | XcodeGen spec; `xcodegen generate` makes `NarSil.xcodeproj` (not committed) |
| `NarSil/NBConstants.swift` | generated from `bridge/narsil_bridge.h` (`bridge/gen-swift-constants.py`); the build checks it is current |
| `NarSil/Native.swift` | thin wrappers over the bridge's C API (Swift side of `NarSilNative.kt`) |
| `NarSil/Model.swift`, `Parse.swift` | the UI's types and the pure parsers (ports of `GameState.kt` types and `Parse.kt`) |
| `NarSil/GameState.swift` | polls the bridge once per display frame, only when a generation counter moves |
| `NarSil/GameLauncher.swift` | starts the game: data from the bundle's `lib/`, saves in `Documents/data` (visible in Files) |
| `NarSil/NarSilApp.swift` | app entry; saves when the app goes to the background |
| `NarSil/RootView.swift` | bring-up screen (status, messages, prompt, a d-pad); replaced in Phase 3 |
| `NarSilTests/ParseTests.swift` | the Android parser tests, run on the same recorded fixtures |

## Building
See [BUILDING.md](BUILDING.md): GitHub Actions (workflow "iOS port") or Xcode on a Mac, and how to install on the iPhone.

## Things to check on the first compile
- `nb_state` is both a C enum and a function: if `nb_state()` is ambiguous in `Native.state`, rename the enum in the header.
- C enums import as structs (`.rawValue`); anonymous enums (`NB_PROMPT_CAP`) import as `Int`.
- `nb_config` is built with its memberwise initialiser and a non-capturing log closure.
- The `lib/` copy script runs before compile; `ENABLE_USER_SCRIPT_SANDBOXING` is off so it may write the bundle.
- `cmake/ios.toolchain.cmake` is ios-cmake (BSD-3), taken from the NarSil-continuous-ios checkout.
