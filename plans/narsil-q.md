# NarSil and NarSil-Q in one app (proposal, for a later decision)

> **Status:** proposal only, nothing implemented. Written 2026-11-13 on branch `android`.
> Saved here for a later decision; nothing in it is implemented yet.

- **NarSil**: the current game, a rewrite of Sil 1.3 on modern Angband (4.2) with rune-based identification.
- **NarSil-Q**: NarSil changed to follow Sil-Q's content and rules. Sil-Q is a separate continuation of Sil 1.3
  (github.com/sil-quirk/sil-q, 1.5.0 / 1.5.1-beta). Internal id: `silq`.

---

## 1. Background: how Sil-Q differs from NarSil

NarSil = Sil 1.3 plus rune identification. Sil-Q forked from the same Sil 1.3, so they share a common
ancestor. Almost none of Sil-Q's gameplay changes are in NarSil; NarSil does already ship MicroChasm's
tiles. Both use the same GPL-2 / Angband dual licence, so Sil-Q's data and code can be reused with credit.

| Area | Difference | Kind of work |
|---|---|---|
| Abilities | Every skill except Stealth differs. Melee: +Impale, Smite; −Momentum, Throwing Mastery. Archery: +Ambush, Deadly Hail, Fletchery, Puncture, Rout; −Careful Shot, Flaming Arrows, Precision, Rapid Fire. Perception: +Alchemy, Outwit, Quick Study; −Eye for Detail, Item Lore, Lore-Master. Will: +Formidable, Indomitable, Oath, Vengeance; −Clarity, Critical Resistance, Hardiness, Mind Over Body. Smithing: Expertise replaces Artistry. Songs: +Challenge, Delvings, Staunching, Thresholds; −Aulë, Estë, Sharpness. Several shared abilities rebalanced. | Largest part: code per ability, data for descriptions and prerequisites |
| Creatures | +15 (attercops, spectres, wraiths, phantoms, brood spiders, orc skirmishers, human and elven thralls, orc thrallmaster, Lug, Balcmeg, Aulë, crownless Morgoth…); −5 (deathblades, unmourned, orc thieves…) | Mostly data; thralls asking for help and escorts rallying are code |
| Objects | +23 / −14 kinds (new jewellery, Antidote, staffs of Shadows and Dismay, Mallorn torches, hand axes; no sceptres, throwing axes or filthy rags); many stat, weight and drop-depth changes | Data plus a few effects |
| Specials / artefacts | +29 / −10 specials, +24 / −15 artefacts; new properties Resist Bleeding, Medic, Avoid Traps, Cumbersome, Standfast, Accuracy, Betrayal curse | Data, plus small code per property |
| Houses | Adds Houseless; renames Falathrim and Haleth | Data |
| Smithing | Costs overhauled; Artistry's effects available by default; forges guaranteed at 100'/300'/500' with at least 3 uses (NarSil guarantees forges on a "drought" timer instead) | Tuning in `obj-smith.c`, forge rules in `gen-cave.c` |
| Small mechanics | Arrows break half as often; staffs need no Will check; floor items give no light; healing items identify themselves; searchable skeletons; start with a curved sword; minimum-depth display; score bonuses | Small changes |
| Large mechanics | Morgoth's anger; adaptive depth timing; horns hit a cone | Moderate, needs care |
| Identification | Sil-Q keeps Sil's (faster); NarSil uses runes | Keep runes in both games |

## 2. Proposed design: one engine, two data sets, chosen at launch

Two facts make this practical:
- **NarSil checks abilities by name** (116 `player_active_ability(p, "…")` calls in `src/`). The engine can
  implement both games' abilities and properties, and each game's data decides which exist.
- **All content loads from `lib/gamedata/*.txt`** through `ANGBAND_DIR_GAMEDATA` (`init_file_paths()` in
  `src/init.c`), using the lib path the bridge passes in.

The design:
1. **One native library.** Sil-Q features are either *inert unless the data uses them* (new abilities, songs,
   properties, curses, monster flags) or *rule switches* where Sil-Q changes shared behaviour (arrow
   breakage, staff Will checks, floor light, forge guarantees, Morgoth's anger, reworked formulas such as
   Whirlwind Attack and Finesse). The switches live in a new `rules:` section of `constants.txt`, read into
   `z_info`. NarSil's defaults reproduce today's behaviour exactly.
2. **Data:** `lib/` stays vanilla NarSil, untouched. `lib-silq/gamedata/` holds only the files that differ,
   converted from Sil-Q's `lib/edit/*.txt` into NarSil's format. Gradle packages both;
   `GameLauncher.installIfStale()` builds `filesDir/lib-silq` from `lib` plus the overlay.
3. **App:**
   - Settings stores the game (`narsil` / `silq`).
   - `GameLauncher.ensureStarted()` passes the matching lib dir and a separate data dir per game
     (`filesDir/data`, `filesDir/data-silq`). Saves, scores, prefs, lore and scum copies stay separate.
   - The engine is process-global, so switching games restarts the app, reusing `Scum.restore()`'s
     restart path.
   - Players pick the game on the native welcome screen (`native_welcome()` in `narsil_birth.c`) or under
     ☰ → Options.
   - Savefiles carry a game tag, and the engine refuses a mismatched save.
4. **Native screens:** no protocol changes. They already show whatever the data defines. Additions: the
   game picker, the game's name on the welcome and status screens, and a minimum-depth meta field.

### Phases
1. **Quick wins (about a session):** houses, stat tweaks, small mechanics behind rule switches, forge
   guarantees, score bonuses, minimum depth on the status strip.
2. **Content (a few sessions):** creatures, objects, specials and artefacts, plus the new properties.
3. **Abilities and songs (the largest part):** one skill at a time.
4. **Large mechanics:** Morgoth's anger, adaptive depth timing, thralls, cone-shaped horns.

### Critical files
- Engine: `src/init.c` and `z_info` (rule switches); `src/player-attack.c`, `src/player-calcs.c`,
  `src/obj-smith.c`, `src/gen-cave.c`, `src/mon-*.c`, `src/effects*.c`
- Data: `lib-silq/gamedata/*.txt` (new)
- App: `android/app/build.gradle.kts`; `GameLauncher.kt`, `Settings.kt`, `MainActivity.kt`,
  `ScreenSheet.kt`, `Scum.kt` (all in `android/app/src/main/kotlin/org/narsil/android/`)
- Bridge: `narsil_bridge.c` (game in the config), `narsil_birth.c` (picker), `narsil_native.c`
  (minimum-depth meta), all in `bridge/`

## 3. Benefits

- **One engine.** Fixes to the engine, bridge and native screens reach both games at once; no second copy to
  drift out of date.
- **All the phone work carries over.** NarSil-Q needs only a game picker and a minimum-depth line.
- **Vanilla stays clean.** NarSil plays exactly as now, and upstream NarSil updates still merge into the
  shared engine and data.
- **Both games are tested together.** The harness runs every scenario against both data sets, and a
  "NarSil unchanged" check catches leaks between them.
- **Licensing is clear.** Same dual licence.
- **It can ship in steps.** NarSil-Q can be released after phase 1 and grow from there.
- **Rune identification is kept**, so the native Knowledge screen works as-is.

## 4. Risks and mitigations

| Risk | Mitigation |
|---|---|
| **Changes leak into vanilla.** Code for a shared ability or rule changes NarSil by mistake. | NarSil's default rule values reproduce today's behaviour; the harness compares NarSil's output with the current baseline. |
| **NarSil-Q only approximates Sil-Q.** Mechanics re-implemented on a different engine; NarSil's combat, stealth and identification already differ slightly from Sil 1.3. Reworked abilities, Morgoth's anger and depth timing may balance differently. | Present it as "NarSil with Sil-Q's design" and credit Sil-Q. Check each feature against Sil-Q's source and manual. |
| **Engine complexity grows** with name checks and switches. | Group Sil-Q-specific code; prefer data-driven flags over scattered conditions. |
| **Upstream merges get harder** in shared files (combat, smithing, level generation). NarSil's release pace is unknown. | Keep Sil-Q changes small and grouped; merge upstream regularly. |
| **Data conversion errors.** Sil-Q's files use the older Sil format and must be converted by hand; mistakes cause subtle bugs, not crashes. | Convert one file at a time with harness checks; compare item, creature and ability counts against Sil-Q. |
| **Saves loaded in the wrong game** could fail or corrupt. | Separate data dirs, plus a game tag in the savefile that the engine checks. |
| **Effort is uneven.** Phases 3–4 are most of the work; until they're done NarSil-Q is "partial Sil-Q". | Label NarSil-Q's completeness in the app; ship phases as they land. |
| **Switching games restarts the app.** | Acceptable; reuse the restart path that scum restore already uses. |

## 5. Open questions for the decision
- Is "NarSil with Sil-Q's design", with balance not identical to Sil-Q, acceptable?
- Ship NarSil-Q after phase 1, or only once abilities (phase 3) are done?
- Should the Sil-Q authors be contacted, as a courtesy or for collaboration?
- Does this belong only on the Android branch, or is it worth offering upstream?

## 6. Verification (when implemented)
- `android/host-test.sh` runs every scenario for both `lib` and `lib-silq`; NarSil's output must not change.
- Per-phase checks, e.g. "Impale" appears under Melee only with `lib-silq`; Sil-Q-only objects appear in
  the knowledge browser.
- Upstream `run-tests` passes on vanilla data.
- On the phone: make a character in each game, confirm separate saves and scum copies, and confirm that
  switching games restarts into the right one.
