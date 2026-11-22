.. _Seeds:

=====
Seeds
=====

Every game has a **seed**, a number from 1 to 4294967295.  The seed fixes the dungeon: the same seed,
played on the same version of the same game, gives the same levels, with the same items and monsters in
the same places, however you play.  Combat, monster behaviour and everything else that happens as you play
stays random.  A seed is how you share a game ("this seed has a Spear of Doriath on 200 feet") or compare
how two characters handled the same dungeon.

The seed
========

A new character gets a random seed.  To choose one, turn on the birth option "Choose the dungeon's seed"
(``birth_choose_seed``) before the name is asked; you are then asked for a seed after the name.  Type a number,
or press Enter for a random one.  From the command line, ``-S<seed>`` starts new characters with that seed and
skips the question, whether or not the option is on.

The seed is shown on the character sheet (``@``), in the character dump, and on the death screen, below
the house.  The character dump also names the game and its version.  A seed only gives the same dungeon on
the same game (NarSil or NarSil-Q), the same version and the same data, so quote all three when you share it.
Savefiles keep their seed.  A savefile from before seeds existed is given a new one when it is loaded.

How levels follow the seed
==========================

Levels in NarSil are not permanent: every time you take a staircase to a depth, a level is made.  The seed
fixes each of them.  The first level you are given at a depth is always the same, the second (if you go
up and come back down) is always the same second one, and so on, up to 255 visits to a depth.

Reloading a save and taking the same staircase again gives the same level.  What you have found or killed
can change what a later level holds.  Artefacts that already exist are never made twice and uniques that are
dead are never made again, but each object and monster is generated on its own (what a monster carries comes
from that monster's own stream), so such a change only changes that one thing: it does not shuffle the rest of
the level.  The exception is a room that needs living uniques, such as a pit of them: with some of them dead it
may be built differently, and then that level's terrain differs.  A monster's drop is fixed by the seed too, so
the unique on 350 feet carries the same thing whenever you meet it.  So is what a chest holds: the chest is given
a seed when it is made and keeps it, so it opens onto the same things whenever you open it, even if you carry it
somewhere else first.

The seed catalog
================

The spoiler front end can list what a seed holds, so that you can pick a seed to play or to share::

    ./narsil -mspoil -- -l catalog.txt -S 424242 -D 12 -V 3

``-l`` names the output file, ``-S`` is the seed or a range of them such as ``1-100``, ``-D`` the deepest
level to list (default all), ``-V`` the number of visits to each depth (default 3) and ``-P 0`` leaves out the
provisos (below), which is much faster.  Each seed is a section, and each level of it that has anything notable
is a heading ("250 ft v2" is the second level made at 250 feet) over one line for each of:

* ``item (x,y)``: an artefact, an ego item or an out-of-depth item on the floor;
* ``chest (x,y)``: what a chest holds, when anything in it is notable (the chest itself is not listed unless it is);
* ``forge (x,y)`` and ``vault NAME``: a forge, and a greater vault;
* ``boss NAME (x,y)``: a unique, with what it carries and what it will drop; ``mon NAME (x,y)`` is an
  out-of-depth monster, with the same.

Items have random properties in Sil, so after an item's name comes ``= `` and a summary of what it gives you:
modifiers (``+2 stealth``), sustains and protections, resists, slays and brands, abilities and the like, and then
``bad:`` and its drawbacks (a curse, a vulnerability, a negative modifier).  Whatever the plain kind has anyway,
such as a lantern's light, is left out.  For example ``a Set of Gauntlets of Treachery (-1) [+0,1d1] <-1> {cursed} =
Opportunist; bad: -1 strength, cursed``.

A weapon or bow also shows its weight, as in ``the Greatsword 'Glend' (-1,3d6) [+1] <+1> 12.0 lb``.  Ordinary and
ego weapons have their weight rolled around the base, and the weight matters: Strength adds damage sides only up
to the weight in pounds, and a lighter weapon (or bow) scores critical hits more often.

Each level is shown as for a character diving straight down, one after the other, with nothing yet found and no
unique yet dead.  The same command with the same seed always writes the same catalog.

Provisos
--------

What a game has done changes later levels: a unique that has been killed is not made again, and neither is an
artefact that exists.  So after each seed's levels comes a list, for each unique it meets, of what the *other*
levels become once that unique has been killed (at any one of the levels it is met on) and the artefacts it
carried or dropped have been made.  The levels are generated again with that state, so the lists are exact:
``- line`` is something that is gone, ``+ line`` something new.  The unique's own other appearances are simply
gone and are not repeated.  Where the level's terrain itself comes out different (a pit of uniques, say, built
without the one that was killed) the whole level is shown again as it then is.  One kill at a time is
considered, not combinations.

For NarSil-Q, use the launcher, which sets up NarSil-Q's data first::

    tools/narsil-q.sh -mspoil -- -l catalog.txt -S 424242 -D 12

Ready-made lists
================

``docs/seed-lists/`` holds catalogs for seeds 1 to 1000, levels 1 to 12 (50 to 600 feet), three visits each, with
provisos, ten files of a hundred seeds for each game::

    narsil-seeds-1-100.txt  ...  narsil-seeds-901-1000.txt
    narsil-q-seeds-1-100.txt  ...  narsil-q-seeds-901-1000.txt

Search them for what you want (``Doriath``, ``Iron Crown``, ``forge``, a unique's name) and play that seed.
Each file's first line names the game and version it was made with, and it only holds for that one.  If the
game has changed since, regenerate them with ``tools/gen-seed-lists.sh`` (a built desktop game is needed; it runs
the files in parallel), or make a different range, depth or visit count with its ``FIRST``, ``LAST``, ``DEPTH``
and ``VISITS`` settings.

Artefacts and seeds: notes for the future
=========================================

Status: not implemented.  These are notes on how artefact generation interacts with seeds, and what could be
done about it.

How artefacts work now
----------------------

* An artefact is made at most once per game (the "created" flag).  Smithing, monster drops and level
  generation all set it.
* Leaving a level throws it away.  An artefact on it that the player has *seen* is lost for good
  (``prepare_next_level`` marks it created).  One the player never saw goes back into the pool
  (``uncreate_artifacts``) and can be rolled again on a later level.
* ``make_artifact`` and ``make_artifact_special`` skip created artefacts *before* making their random draws,
  so how many draws an object consumes, and so what the rest of that object becomes, depends on what has been
  made so far.
* ``too_many_artefacts()`` reads ``player->num_artefacts``, which nothing increments, so it never fires.

The "unseen returns to the pool" rule is a fairness rule: with non-persistent levels, a level the player left
should not silently consume something they never knew existed.  Losing a *seen* artefact is the pressure
that is part of the game's design, and is not something a seeding change should alter.

Effect on seeds
---------------

The seed fixes each object's own stream, but whether an object becomes an artefact, and which, still depends
on the created flags.  So a catalog entry is exact only for a character that has not yet made any artefact.
Later levels can lose a listed artefact, and an object that would have been one becomes a different item.
Every other thing on the level is unaffected, because objects and monsters draw from their own streams.

Plan A: draw first, filter last (recommended)
---------------------------------------------

Make "does this object become an artefact, and which one" a function of the seed only.  The created flag may
then remove an artefact, and never changes anything else.

1. In ``make_artifact``, run the depth check and the rarity roll for every candidate artefact of the object's
   kind, with no early exit, and record the first success.  Only then apply ``is_artifact_created``.  If the
   winner is already created, the object stays ordinary and does not fall through to another candidate.
2. Do the same in ``make_artifact_special`` (instant artefacts): draw for every special, record the first
   success, then filter.
3. Drawing everything unconditionally matters because the object's other rolls (``apply_magic`` and the rest)
   come from the same stream.
4. Tests (``src/tests/game/seed.c``): the same seeded level with a given artefact marked created and not
   created differs only in that artefact; the replaced object is ordinary; the same for an instant artefact.
5. Catalog (``src/wiz-seeds.c``): when one artefact appears at several depths or visits of a seed, mark every
   occurrence after the first, in dive order, as an alternative ("only one can be found").
6. Regenerate ``docs/seed-lists/`` and report how many entries changed.  Re-record any mobile baseline.

Size: about 40 lines in ``obj-make.c``, plus tests and the catalog note.  Side effect: late in a game, a
created artefact leaves an ordinary object where the old code could have picked another artefact, so
artefacts become slightly scarcer once many have been made.

Related: the forge cap
----------------------

Whether a level gets a forge also depends on state (the forge counters and drought, and for NarSil-Q the fixed
forge count).  The decision is made in the shared layout stream, so a state change can shift the rest of that
level's layout.  The same trick applies: always roll the forge decision, then apply the cap.  Measure first:
run a level with different forge states and compare layouts.  This touches ``gen-cave.c``.

Other designs considered
------------------------

* **B: lose nothing on leaving.**  Artefacts left behind stay available.  It removes the "seen and left"
  pressure and the lost-artefact bookkeeping, but makes artefacts easier to farm and changes how the game
  feels.  A gameplay proposal for upstream, not part of the seed work.
* **C: a fixed schedule per seed.**  Each artefact gets one home (depth and visit) when the game starts.
  This is the most Brogue-like and makes catalogs exact, with no created-flag logic during generation.  But
  revisits would stop being fresh levels, artefact frequency per depth would shift, and it is a redesign.
* **A': lose everything on leaving**, seen or not.  Simplest, and it stops regenerating levels to look for
  artefacts, but it silently costs the player things they never saw.  Not recommended.

A is compatible with a later B or C.  B and C change the game and should be proposed separately.
