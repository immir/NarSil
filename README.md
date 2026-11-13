# NarSil 1.4.0

NarSil 1.4.0 is an almost-faithful rewrite of Sil 1.3.0 using modern Angband code, with changes to identifying of objects and the way the dungeon is perceived.

An attempt at remaining faithful to Sil 1.3.0 is maintained on the [NarSil 1.3 branch](https://github.com/NickMcConnell/NarSil/tree/1.3).

---

## Android port (this fork)

This fork's default branch, `android`, is a mobile-first Android port of NarSil: the game's core runs unmodified on its own thread, and everything you touch is a native screen instead of the text terminal. The details are in [`android/README.md`](android/README.md); the plans are in [`plans/`](plans/).

<table>
<tr>
<td align="center" width="25%"><img src="android/screenshots/map.png" alt="The map with status, d-pad and action bar" width="240"><br><sub><b>Map.</b> Tap to step or walk, long-press to inspect; status, d-pad and one-tap actions underneath.</sub></td>
<td align="center" width="25%"><img src="android/screenshots/pack.png" alt="The pack" width="240"><br><sub><b>Pack.</b> Inventory and equipment in one list, one row expanded at a time.</sub></td>
<td align="center" width="25%"><img src="android/screenshots/pack-actions.png" alt="An item's actions" width="240"><br><sub><b>Item actions.</b> Take off, Drop, Throw, Inscribe, Ignore; the pack stays open afterwards.</sub></td>
<td align="center" width="25%"><img src="android/screenshots/character.png" alt="The character tab" width="240"><br><sub><b>Character.</b> Stats and every skill, with where each total comes from.</sub></td>
</tr>
<tr>
<td align="center"><img src="android/screenshots/skills.png" alt="Spending experience on skills" width="240"><br><sub><b>Skills.</b> Spend experience, undo until you accept.</sub></td>
<td align="center"><img src="android/screenshots/abilities.png" alt="Abilities by skill" width="240"><br><sub><b>Abilities.</b> One tab per skill, with what each one still needs.</sub></td>
<td align="center"><img src="android/screenshots/knowledge.png" alt="Knowledge: creature lore" width="240"><br><sub><b>Knowledge.</b> Creatures, objects, runes, terrain and more, with lore and search.</sub></td>
<td align="center"><img src="android/screenshots/smithing.png" alt="The smithing screen" width="240"><br><sub><b>Smithing.</b> Design an item; Make it at a forge.</sub></td>
</tr>
<tr>
<td align="center"><img src="android/screenshots/game-options.png" alt="Game options" width="240"><br><sub><b>Game options.</b> The game's own options as switches and sliders.</sub></td>
<td align="center"><img src="android/screenshots/app-options.png" alt="App options" width="240"><br><sub><b>App options.</b> Text sizes, d-pad style, tiles, swipes, save export and import.</sub></td>
<td align="center"><img src="android/screenshots/menu.png" alt="The menu" width="240"><br><sub><b>Menu.</b> Everything not on the action bar, with Panel at the top.</sub></td>
<td align="center"><img src="android/screenshots/keyboard.png" alt="The on-screen keyboard" width="240"><br><sub><b>Keyboard.</b> Any command by key, when you want one.</sub></td>
</tr>
<tr>
<td colspan="4" align="center"><img src="android/screenshots/landscape.png" alt="Landscape layout" width="720"><br><sub><b>Landscape.</b> Status and actions move beside the map so it gets the full height.</sub></td>
</tr>
</table>

Build and test instructions: [`android/README.md`](android/README.md). An iOS port (SwiftUI over the same C bridge) is in progress: [`ios/README.md`](ios/README.md) and [`plans/ios.md`](plans/ios.md).
