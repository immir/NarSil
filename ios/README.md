# NarSil for iOS (in progress)

Plan: `../plans/ios.md`.  A SwiftUI app over the same C bridge as Android (`../bridge`).

Status: scaffolding only.  Nothing here has been compiled yet (no Mac or Xcode on the dev machine):
- `build-core.sh` builds the game core and bridge for device and simulator into `build/NarSilCore.xcframework`.
- `NarSil/NBConstants.swift` is generated from `bridge/narsil_bridge.h`, so it cannot drift.
- `cmake/ios.toolchain.cmake` is ios-cmake (BSD-3), taken from the NarSil-continuous-ios checkout.
