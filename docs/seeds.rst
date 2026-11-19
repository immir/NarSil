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

When you create a character, after the name, you are asked for a seed.  Type a number, or press Enter for a
random one.  From the command line, ``-S<seed>`` starts new characters with that seed and skips the question.

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
dead are never made again, but each object and monster is generated on its own, so such a change only removes
that one thing: it does not shuffle the rest of the level.  A monster's drop is fixed by the seed too, so the
unique on 350 feet carries the same thing whenever you meet it.

The seed catalog
================

The spoiler front end can list what a seed holds, so that you can pick a seed to play or to share::

    ./narsil -mspoil -- -l catalog.txt -S 424242 -D 12 -V 3

``-l`` names the output file, ``-S`` is the seed or a range of them such as ``1-100``, ``-D`` the deepest
level to list (default all) and ``-V`` the number of visits to each depth (default 3).  For every depth and visit
the catalog gives:

* the artefacts, ego items and out-of-depth items on the floor, with their grids;
* forges, and the name of a greater vault;
* uniques and out-of-depth monsters, with what each carries and what it will drop.

Each level is shown as for a character diving straight down, one after the other, with nothing yet found and
no unique yet dead; the catalog does not follow what a particular game changes along the way.  The same
command with the same seed always writes the same catalog.

For NarSil-Q, use the launcher, which sets up NarSil-Q's data first::

    tools/narsil-q.sh -mspoil -- -l catalog.txt -S 424242 -D 12

Ready-made lists
================

``docs/seed-lists/`` holds catalogs for seeds 1 to 100, levels 1 to 12 (50 to 600 feet), three visits each, one
file per game::

    narsil-seeds-1-100.txt
    narsil-q-seeds-1-100.txt

Search them for what you want (``Doriath``, ``Iron Crown``, ``forge``, a unique's name) and play that seed.
Each file's first line names the game and version it was made with, and it only holds for that one.  If the
game has changed since, regenerate them with ``tools/gen-seed-lists.sh`` (a built desktop game is needed), or
make a different range, depth or visit count with its ``FIRST``, ``LAST``, ``DEPTH`` and ``VISITS`` settings.
