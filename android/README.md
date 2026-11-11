# NarSil for Android

Status: playable, mobile-first, in progress (2026-11-11, Immir).

The game's core runs unmodified on its own thread. Everything you play with
day to day is native touch UI; any textui screen not yet replaced still works
in a zoomable terminal view underneath.

## What is native

| Area | How |
|---|---|
| Map | Whole-level snapshot from `map_info()` (game's own glyphs/lighting); pinch zoom, two-finger pan, follow camera |
| Moving | Tap adjacent = step/attack; tap far = ghost path (game's `find_path`), tap again = go; swipe = step |
| Inspect | Long press: monster state + lore recall, objects, terrain; **Look** lists what is in view (monsters first) |
| Prompts | Item selection, item actions (with description), yes/no, aiming (tap a monster/grid, swipe), adjacent direction, songs, skills |
| Action bar | Context (Down/Up, Pick up, Fire, Throw, Open/Close, Disarm, Tunnel, Skills when experience waits) + Rest, Wait, Stealth, Song, Pack, Look; ☰ for the rest |
| Status | HP/Voice bars, depth, status line, tracked foe (health, Sleeping/Unwary/...+morale), 3-line message history; tap or tap the map at -more- |
| Saving | On leaving the app, plus autosave each new level and every 100 commands |

## Layout

| Path | Role |
|---|---|
| `app/src/main/cpp/narsil_bridge.{h,c}` | Public C API; game thread, z-term backend, key/mouse queue, double-buffered frames, safe-point saves, autosave |
| `app/src/main/cpp/narsil_native.c` | Map/status/messages snapshots, path preview, travel, one-tap actions, inspect/look |
| `app/src/main/cpp/narsil_prompt.c` | Native prompt hooks (item, actions, check, aim, direction, songs, skills) |
| `app/src/main/cpp/narsil_jni.c` | JNI glue (strings decoded safely: no `NewStringUTF` on game text) |
| `app/src/main/cpp/host_harness.c` | Desktop driver: scripted keys, taps, actions and prompt answers |
| `app/src/main/kotlin/...` | `GameState` (per-vsync poller), `MapView`, `TerminalView`, sheets and bars |

## Design points

- **Threads.** The game blocks in `TERM_XTRA_EVENT` or in a native prompt;
  the UI never blocks.  Lock order: bridge lock, then native lock.
- **Typed commands, not keystrokes.** Taps and action-bar buttons become
  requests handled at the top-level command prompt (wake with a null key,
  then queue `CMD_*` or open the textui screen in place) -- independent of
  keyset and keymaps.  Requests away from the prompt are dropped.
- **Native prompts defer to the textui while a keymap expands**
  (`inkey_next`), e.g. the original keyset maps `6` to walk + `6`.
- **Saves** run only at a safe point (waiting for a top-level command).
- **quit()** ends only the game thread; the UI then closes the process.

## Host tests (no Android SDK needed)

    ./host-test.sh          # ASan + UBSan
    TSAN=1 ./host-test.sh   # ThreadSanitizer

    MSAN=1 ./host-test.sh   # MemorySanitizer (clang)

Scenarios cover saving/reloading, touch, the native map, every native prompt,
actions, inspect/look and skill spending.  `NARSIL_PLAYER=PLAYER` lets the
harness load a savefile copied off a device.

## Android build

Needs SDK platform 34, build-tools, NDK 26.1.10909125 and CMake 3.22.1. Point
`local.properties` at the SDK (`sdk.dir=/path/to/sdk`), then run

    ./gradlew :app:assembleDebug
    adb install -r app/build/outputs/apk/debug/app-debug.apk

Plugin versions match the NetHack mobile build (AGP 8.2.0, Kotlin 1.9.20,
Gradle 8.4). Upgrade them all together.

## Not yet native

Birth, death screen, abilities (Tab), smithing, help and options use the
zoomable terminal view (pinch to zoom, two-finger pan, tap = click).
