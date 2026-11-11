# NarSil for Android: spike

Status: spike (2026-11-11, Immir). Proves the build, data paths, threading and
save model. The UI is a terminal fallback, not the mobile-first design yet.

## Layout

| Path | Role |
|---|---|
| `app/src/main/cpp/narsil_bridge.{h,c}` | Platform-neutral: game thread, z-term backend, key queue, double-buffered frames, safe-point saves |
| `app/src/main/cpp/narsil_jni.c` | JNI glue for `org.narsil.android.NarSilNative` |
| `app/src/main/cpp/host_harness.c` | Desktop driver: runs a key script against the bridge |
| `app/src/main/cpp/CMakeLists.txt` | One build: NDK gives `libnarsil.so`; the host gives `narsil_host` |
| `app/src/main/kotlin/...` | `TerminalView` (grid render + IME/hardware keys), key bar, asset install, lifecycle |

The core is every `src/*.c` except `main*.c`/`snd-*.c`, so nothing upstream changes.

## Design points

- **Threads.** The game runs on its own 8 MB-stack thread and blocks in
  `TERM_XTRA_EVENT` on a condvar. The UI thread never blocks. It pushes keys,
  and on each vsync it copies a frame only if the frame generation has changed.
- **Frames** are published at `TERM_XTRA_FRESH` from a private buffer, so the UI never sees a half-drawn screen.
- **Saves** (`onStop`) are requests. The game thread does them only while it
  is inside `cmd_get_hook(CTX_GAME)`, where no command is half-executed. A
  request made anywhere else (birth, `-more-`, mid-turn) waits for the next
  command prompt. The save skips `save_game()`'s message flush, which could
  bring up a `-more-` prompt while the app is in the background.
- **quit()** ends only the game thread, not the process. The UI then shows the
  exit message and closes the process, since the core's globals can't be
  initialised a second time.

## Host tests (no Android SDK needed)

    ./host-test.sh          # ASan + UBSan
    TSAN=1 ./host-test.sh   # ThreadSanitizer

These cover new game, a save deferred during birth, moving, an explicit save,
quit, and reloading the savefile.

## Android build

Needs SDK platform 34, build-tools, NDK 26.1.10909125 and CMake 3.22.1. Point
`local.properties` at the SDK (`sdk.dir=/path/to/sdk`), then run

    ./gradlew :app:assembleDebug
    adb install -r app/build/outputs/apk/debug/app-debug.apk

Plugin versions match the NetHack mobile build (AGP 8.2.0, Kotlin 1.9.20,
Gradle 8.4). Upgrade them all together.

## Known spike limits

- Fixed 80x24 grid scaled to fit. It's small in portrait; landscape is fine.
- Text only: no tiles, sound or subwindows.
- Tapping the terminal toggles the soft keyboard; Back sends Escape.
- Builds (debug and release, arm64-v8a and x86_64) but hasn't run on a device or emulator yet.
