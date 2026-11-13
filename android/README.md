# NarSil for Android

Status: playable on a phone, mobile-first, in progress (2026-11-13).  Screenshots are in the
[top-level README](../README.md#android-port-this-fork).

The game's core (the `src/` tree, unmodified apart from small front-end hooks) runs on its own thread behind a
platform-neutral C bridge (`../bridge`).  Everything you play with day to day is native touch UI; the few screens that
are not yet replaced still work in a zoomable terminal view underneath.  The same bridge is what the iOS port uses
([`../ios`](../ios/README.md)).

## What is native

| Area | How |
|---|---|
| Map | Whole-level snapshot from `map_info()`; letters or MicroChasm tiles (Options → Tiles, with terrain under monsters and alert/glow overlays); pinch zoom, two-finger pan, follow camera, level overview |
| Moving | Tap adjacent = step/attack; tap far = ghost path (game's `find_path`), tap again = go; swipe = step or run; d-pad (docked, floating or off) with run |
| Inspect | Long press: monster state and lore recall, objects, terrain, and **Target**; **Look** lists what is in view |
| Aiming | Tap a monster or grid, swipe, or Fire/Throw at the nearest; the current target stays marked on the map |
| Action bar | Context buttons (Down/Up and travel to a known stair, Pick up, Fire, Throw, Open/Close, Disarm, Tunnel, Skills when experience waits) plus Rest (long-press for modes), Wait, Stealth, Song, Pack, Look, Again; ☰ for the rest |
| The panel | One screen with tabs: **Pack**, **Character**, **Skills**, **Abilities**, **Knowledge**, **Smithing**, **Options**; swipe between tabs, opened from the action bar, the ☰ menu, `i`/`e`, or a swipe from the right edge |
| Pack | Inventory, equipment and floor in one list; expand an item for its description and actions; stays open after equip, eat and the like, and closes only for an action that needs a map target |
| Character and skills | Stats and skills with where each total comes from; rename and share a character dump; spend experience with undo; abilities per skill with what each still needs |
| Knowledge | Creatures, objects (with unknown flavours), artefacts, ego items, runes (inscribe from here), terrain, traps, history, hall of fame; searchable |
| Smithing | Design an item (base, enchant, artifice, numbers, melt) and Make it at a forge; the design survives tab switches |
| Birth and death | Welcome, race, house, sex, stats (live attack/defence/skill effect), skills, name; the tutorial with its notes; the death screen with history and scores |
| Prompts | Items, yes/no, aim, direction, choice (ignore menu), how many, text entry (inscribe, notes) |
| Options | The game's own options as switches and sliders, plus the app's: text sizes, messages shown, d-pad, tiles, buzz when health is low, swipes, skip `-more-`, save scumming, export/import of the save, send logs |
| Status | HP/Voice bars, depth, status line, tracked foe (health, stance, morale), message strip and a full message log |
| Saving | When you leave the app (also with a panel open), each new level, and every so often; never in the middle of a command's own question, which waits |
| Help | A touch guide, kept in step with the screens |

## Layout

| Path | Role |
|---|---|
| `../bridge/narsil_bridge.{h,c}` | Public C API; game thread, z-term backend, key/mouse queue, double-buffered frames, safe-point saves, autosave |
| `../bridge/narsil_native.c` | Map/status/messages snapshots, path preview, travel, one-tap actions, inspect/look, requests from the UI |
| `../bridge/narsil_prompt.c` | Native prompt hooks and the panel (pack, quantity, text, choice, skills, abilities) |
| `../bridge/narsil_{smith,knowledge,options,birth,tiles}.c` | The smithing, knowledge, options, birth/death/tutorial and tile pieces |
| `../bridge/host_harness.c` | Desktop driver: scripted keys, taps, actions and prompt answers |
| `app/src/main/cpp/narsil_jni.c` | JNI glue (strings decoded safely: no `NewStringUTF` on game text) |
| `app/src/main/kotlin/.../` | `GameState` (per-vsync poller), `Parse` (the pure parsers), `MapView`, `Panel` and the tab screens (`PackSheet`, `KnowledgeScreen`, `SmithSheet`, `AbilitiesSheet`, `OptionsScreen`), `ScreenSheet` (birth/death), bars, `Settings`, `Scum`, `Share`, `TerminalView` |
| `check-bridge-constants.py` | Drift guard: every `NB_*` constant in the header must equal its Kotlin twin; runs in Gradle `preBuild` |
| `gen-fixtures.sh`, `app/src/test/` | Records what the real game publishes (one file per prompt kind) and JVM tests that parse it |

## Design points

- **Threads.** The game blocks in `TERM_XTRA_EVENT` or in a native prompt; the UI never blocks.  Lock order: bridge
  lock, then native lock.  The UI polls generation counters once per frame and takes a snapshot only when one moved.
- **Typed commands, not keystrokes.** Taps and action-bar buttons become requests handled at the top-level command
  prompt (wake with a null key, then queue `CMD_*` or open the screen in place), independent of keyset and keymaps.
  Requests away from the prompt are dropped.
- **The panel is owned by the C side.** A tab switch is just an answer to the open prompt, so no screen closes and
  reopens; an action that runs a command (equip, Make it) closes it, and it reopens at the next command prompt, with
  `-more-` skipped meanwhile.  The app keeps the old screen up for that moment so it does not flash the map.
- **Native prompts defer to the textui while a keymap expands** (`inkey_next`).
- **Saves** run only at a safe point: waiting for a top-level command, or at a native screen opened from there.
  The tutorial never saves.
- **quit()** ends only the game thread; the UI then closes the process.
- **No animations.** Panels and sheets open and close instantly.

## Host tests (no Android SDK needed)

    ./host-test.sh          # ASan + UBSan
    TSAN=1 ./host-test.sh   # ThreadSanitizer
    MSAN=1 ./host-test.sh   # MemorySanitizer (clang)

About thirty scenarios cover saving and reloading, touch, the native map and tiles, every native prompt and panel
tab, birth, the tutorial and death, actions, inspect/look and skill spending.  `NARSIL_PLAYER=PLAYER` lets the harness
load a savefile copied off a device.  `./gen-fixtures.sh` re-records the fixtures the JVM tests read
(`./gradlew testDebugUnitTest`).

## Android build

Needs SDK platform 34, build-tools, NDK 26.1.10909125 and CMake 3.22.1. Point `local.properties` at the SDK
(`sdk.dir=/path/to/sdk`), then run

    ./gradlew :app:assembleDebug
    adb install -r app/build/outputs/apk/debug/app-debug.apk

Install only while the app is not in the foreground: installing over a running app force-stops it without saving.
Plugin versions match the NetHack mobile build (AGP 8.2.0, Kotlin 1.9.20, Gradle 8.4). Upgrade them all together.

## Not yet native

A few rarely used screens still use the zoomable terminal view (pinch to zoom, two-finger pan, tap = click): the
"All commands" menu, keymaps, colours and preference files.
