# NarSil-Q data

NarSil-Q is NarSil played with the content and rules of **Sil-Q**
(<https://github.com/sil-quirk/sil-q>), a continuation of Sil 1.3 by the Sil-Q team
(see Sil-Q's `lib/docs/CHANGELOG.md` for its many contributors). Sil-Q and NarSil
share the same licence (GPL-2 / the Angband licence); the Sil-Q content here is
converted from Sil-Q 1.5.1's `lib/edit/*.txt` with credit and thanks.

This directory is an **overlay**: it holds only the files that differ from `../lib`.
The game's lib dir for NarSil-Q is `../lib` with these files copied over it
(`tools/merge-lib.sh`, also used by the Android and iOS builds).

What differs so far (milestone 1):

- `gamedata/constants.txt`: `game:silq` (its own savefiles) and Sil-Q's rules:
  items on the floor shed no light (only the Iron Crown does); staffs need no Will
  check; guaranteed forges on the first level at or below 100', 300' and 500', with
  three or four uses, instead of NarSil's forge "drought" timer.
- `gamedata/race.txt`: elves have bow proficiency and song affinity instead of blade
  proficiency and bow affinity; the Edain start with a curved sword.
- `gamedata/house.txt`: Sil-Q's names for the Falathrim and the House of Haleth.

Still to come: Sil-Q's score bonuses (with its oaths); Sil-Q's creatures, objects, specials and artefacts; its abilities and
songs; Morgoth's anger, the adaptive depth clock, thralls and cone-shaped horns.
