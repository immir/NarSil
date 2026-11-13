# Building NarSil for iOS

Status: the native app skeleton (`ios/NarSil/`) has never been built on a Mac.  Two routes: GitHub Actions
(no Mac needed to *compile*, a Mac or PC needed to *install*), and Xcode on a Mac (compile, run, debug).

## What gets built
1. **The core** (`src/*.c`, the Sil game) plus the **bridge** (`bridge/`, the platform-neutral C API) as static
   libraries for iOS device and simulator, merged into `ios/build/NarSilCore.xcframework` by `ios/build-core.sh`.
2. **The app** (`ios/NarSil/*.swift`, SwiftUI) linking that xcframework.  Its Xcode project is generated from
   `ios/project.yml` by [XcodeGen](https://github.com/yonaskolb/XcodeGen); the `.xcodeproj` is not committed.
3. The build copies `lib/` game data into the app bundle, and checks `NBConstants.swift` against the bridge header.

## Route A: GitHub Actions (workflow "iOS port")
`.github/workflows/ios-port.yaml` runs on every push to `android` that touches `ios/`, `bridge/`, `src/`, `lib/`,
and on demand (Actions tab → iOS port → Run workflow, or `gh workflow run ios-port.yaml --ref android`).
It builds the xcframework, generates the project, runs the XCTests (the parser tests on the recorded fixtures)
on an iPhone simulator, and builds an **unsigned** `NarSil-ios.ipa`, kept for 14 days as a run artifact
(`gh run download <run-id>`).  Logs are uploaded as `build-logs` (also on failure).
Cost: macOS runners are free for public repos.

## Route B: Xcode on the Mac
One-time setup (the borrowed MacBook):
1. Install **Xcode** from the App Store (needs a recent macOS; about 15 GB plus simulators).  Open it once and accept
   the licence; then `sudo xcode-select -s /Applications/Xcode.app` and `xcodebuild -runFirstLaunch`.
2. Install **Homebrew** (https://brew.sh), then `brew install cmake xcodegen`.
3. Clone: `git clone -b android git@github.com:immir/NarSil.git` (or the https URL).

Build and run in the simulator:
```
cd NarSil/ios
./build-core.sh          # a few minutes; builds device and simulator libraries
xcodegen generate
open NarSil.xcodeproj    # pick an iPhone simulator, press Run (or Cmd-U to run the tests)
```
The simulator needs no Apple ID.  Command line: `xcodebuild test -project NarSil.xcodeproj -scheme NarSil
-destination 'platform=iOS Simulator,name=iPhone 16'` (use a simulator that `xcrun simctl list devices` shows).

## Putting it on the iPhone
**From Xcode (free Apple ID):**
1. Xcode → Settings → Accounts → add your Apple ID.  In `ios/project.yml` set `DEVELOPMENT_TEAM` (a free
   "Personal Team" id appears in Xcode's Signing settings), then `xcodegen generate` again, or set the team in the
   target's Signing & Capabilities tab.
2. Plug the iPhone in, trust the computer, enable **Settings → Privacy & Security → Developer Mode** (restarts the phone).
3. Pick the iPhone as the run destination, press Run.  On first launch trust the profile:
   Settings → General → VPN & Device Management → your Apple ID → Trust.
4. A free Apple ID's signing expires after **7 days**; run again from Xcode to renew.  Bundle id `org.narsil.NarSil`
   must be unique per Apple ID: if Xcode complains, change `PRODUCT_BUNDLE_IDENTIFIER` in `project.yml`.

**From the CI `.ipa`:** download `NarSil-ios.ipa`, open it in Sideloadly (or AltStore) on the Mac, sign in with the
Apple ID, install.  Same trust step and 7-day expiry.

## Where things live on the phone
- Game data: inside the app bundle (`lib/`, read-only).
- Saves and scores: the app's Documents folder (`data/`), visible in the Files app under "On My iPhone → NarSil"
  (`UIFileSharingEnabled`).  A save survives re-installing over the top; deleting the app deletes it.

## Troubleshooting
- `build-core.sh` fails in CMake or the compile: send me the tail of the output; the core's C compiles for iOS for the
  first time here, so expect a missing header or a platform guard.
- `libtool: ... no such file`: the library path pattern in `build-core.sh` (`bridge/Release-*/lib*.a`) assumes the Xcode
  generator's layout; list `ios/build/device/bridge/` and adjust.
- Swift compile errors: see "Things to check on the first compile" in `ios/README.md`.
- "Constants out of date": run `python3 bridge/gen-swift-constants.py ios/NarSil/NBConstants.swift`.
- Tests can't find fixtures: they come from `android/app/src/test/resources/fixtures` (a folder reference in
  `project.yml`); regenerate them with `android/gen-fixtures.sh` on Linux if the formats change.
