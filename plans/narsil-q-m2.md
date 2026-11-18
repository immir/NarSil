# NarSil-Q milestone 2: Sil-Q's monsters, objects and artefacts

2026-11-18. Follows `plans/narsil-q.md` (shape) and milestone 1 (two games, rules, houses; done).

## What the data comparison shows
Sil-Q 1.5.1 (`~/sil-q/lib/edit`) against NarSil (`lib/gamedata`), by entry name:

| File | Sil-Q | NarSil | Shared | Notes |
|---|---|---|---|---|
| monster | 153 | 140 | 135 | shared monsters mostly differ in numbers (Wolf: perception 2 vs 4, bite +3 2d3 vs 1 2d4) |
| object | 196 | 146 | 125 | Sil-Q has more flavoured jewellery, notes, antidote, new potions/scrolls |
| artefact | 122 | 93 | 78 | |
| special | 73 | 53 | 41 | many new ego names |
| ability | 84 | 82 | 59 | milestone 3 |

- Formats differ: Sil-Q is the old `N:/W:/I:/A:/P:/B:/F:/D:` layout, NarSil is `name:/depth:/speed:...`. A converter is needed.
- Blow effects are almost all shared (HURT, WOUND, FIRE, POISON, DISARM, ...).
- Engine gaps for monsters. Flags: PEACEFUL, DROP_CHOSEN, KNOCK_BACK, NEVER_BLOW.
  Spells: RALLY, DIM, HATCH_SPIDER, THROW_WEB. Races such as ORC, TROLL, WOLF, SPIDER are keywords only.
- Some names are renamed (BR_FIRE is BRTH_FIRE, SNG_BIND is SNG_BINDING, EXCHANGE is EXCHANGE_PLACES,
  HURT_LIGHT is HURT_LITE, `SPELL_PCT_n` and `POW_n` replace `ranged-freq` / `spell-power`); the converter maps them.
- The engine looks up some monsters by name (Morgoth, Carcharoth, Oathwraith, Silent watcher, Orc champion, ...),
  so converted data must keep those names or the lookup must be adjusted.

## Steps (commit and push after each)
1. `tools/silq-convert.py monster` reads Sil-Q's `monster.txt` and writes NarSil format. It also has a `--diff`
   mode that prints, per shared monster, which fields differ, so changes can be reviewed.
2. Engine: the missing flags and spells, each off unless the data uses them (NarSil data never does):
   PEACEFUL, KNOCK_BACK, NEVER_BLOW, DROP_CHOSEN, RALLY, DIM, THROW_WEB, HATCH_SPIDER.
3. `lib-silq/gamedata/monster.txt`: the converted monsters, with new ones added (thralls, orc skirmisher,
   thrallmaster, Attercop, Brood spider, wraiths, Lug, Balcmeg, Aule). Checked by the harness: it loads,
   every name the engine looks up exists, and a fixed-seed run of levels 1-30 makes no errors.
4. Same converter for `object.txt`, `special.txt`, `artefact.txt`; new object properties (Resist Bleeding,
   Medic, Avoid Traps, Cumbersome, Standfast, Accuracy, Betrayal) added in the engine as each is needed.
5. Vaults and `names.txt` only if the converted monsters make a vault reference a missing name.

## Risks
- Monster index order matters to savefiles; NarSil-Q saves are new, so only NarSil is at risk, and it is untouched.
- Balance numbers are taken as Sil-Q has them; some depend on mechanics from milestone 4 (thrall escorts,
  Morgoth's anger), so those monsters are added last and may be inert until then.
- Fuzz and ASan harness runs (`GAME=silq`) must stay clean after each step.
