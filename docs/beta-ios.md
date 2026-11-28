# NarSil for iPhone: beta 0.5

Thanks for trying it. This build isn't on the App Store. You install it yourself with **Sideloadly**, signing it
with your own Apple ID. It's free, but iOS only trusts the install for **7 days** at a time.

## Installing (about 10 minutes, once)

You need:
- a Windows PC or a Mac;
- a USB cable for the iPhone;
- the file `NarSil.ipa`.

1. On Windows, install iTunes and iCloud from apple.com, not the Microsoft Store versions. Sideloadly needs
   them to talk to the phone. A Mac needs nothing extra.
2. Download Sideloadly from <https://sideloadly.io> and open it.
3. Plug in the iPhone, unlock it and tap **Trust** if asked.
4. Drag `NarSil.ipa` onto Sideloadly. Enter an Apple ID and press **Start**.
   - The Apple ID can be your own or a spare one. It's only used to sign the app.
   - If you have two-factor sign-in, it asks for the code.
   - Don't turn on any option to change the bundle ID. Keeping it means your saves survive re-installs.
5. On the iPhone, open Settings → Privacy & Security → **Developer Mode**, turn it on and let the phone restart.
   - The switch only appears after the first install attempt.
6. Open Settings → General → **VPN & Device Management**, tap your Apple ID and choose **Trust**.
7. Open NarSil.

## Every 7 days

When the app stops opening, plug the phone in and run Sideloadly again with the same `.ipa` and Apple ID.
Your characters and saves are kept. Sideloadly can also refresh it automatically over Wi-Fi when the computer
is on. See its settings.

## Two games in one app

- **NarSil** is the default.
- **NarSil-Q** is NarSil's engine playing all of Sil-Q 1.5.1: its houses, monsters, items, artefacts, abilities,
  songs, oaths, forges, thralls, Morgoth's anger and the depth clock. Its rules have been checked against Sil-Q's
  source. Choose "Play NarSil-Q instead" on the welcome screen. Its title is blue, so you can tell which game
  you're in.
- Each game keeps its own characters and saves.
- NarSil-Q's newest ports have had the least play, so reports on them are especially welcome. These include the
  songs (Delvings, Challenge, Thresholds), Impale, the horns, betrayal items and thralls. If something behaves
  differently from Sil-Q, that's worth reporting too.

## New in 0.5

- **Landscape:** a new layout. The map gets the full height, the buttons run down the left, and the status and d-pad
  sit in a column on the right with the panel tabs beside it.
- **Adjust layout (☰ → Adjust layout):** drag the gold guides to resize the panes, in portrait and landscape: the
  message area, the status/d-pad split, the button and status columns, the d-pad size. Compact, Balanced and Big
  presets, a left-handed mirror, and Reset. The guides are an overlay, so the panes keep their true size while you
  drag.
- **Messages:** scrollable, always pinned to the newest line (a new message scrolls you back to it). While a panel is
  open (using an item from the pack), new messages show in a small box that fades after a few seconds.
- **Status:** the target and the monsters in view come first, then effects and light; a fade and ▾ show when more is
  hidden below. The target's name now has the whole width.
- **Buzzes:** a tick on every tap (not on scroll or drag), a buzz when your blow lands, a heavier one when you are
  hit, and stronger ones for deaths, level changes and alarms. Options has strength sliders for the tap and the fight
  buzzes.
- **Lists:** tighter rows in the pack and other item lists.
- **NarSil-Q:** correct plurals ("wolves", not "wolfs").

## New in 0.4

- **Seeds:** every game has a seed, shown on the Character tab and the death screen. Tick "Choose the dungeon's
  seed" in the birth options to enter one; the same seed gives the same levels, items and chest contents.
- **Death screen:** "Share dump" sends the character dump (with the seed and your last messages).
- **Random names:** the name defaults to the last one you used, and Random gives names that fit your race.
- **Items underfoot:** the message marks items with properties still to find ({??}), and Pick up shows a ✦ when
  something underfoot is special. Long-press Pick up to see what is on the floor; long-press Fire to choose a
  target. A double border marks any button that does more when held.
- **Feedback:** the phone buzzes when your blow lands, more strongly on a kill, faintly on a miss (Options →
  Buzz in combat). Monster and item descriptions keep the game's colours.
- **Usage trace (off by default):** Options → "Record usage trace" keeps a log on the phone of which buttons and
  panels you use, never what you type or the game's text. It leaves the phone only if you choose Send logs. If
  you're willing, turn it on and send the logs after a play session: it shows which parts of the interface need
  work.

## Reporting problems

Please send:
1. the version, shown on the welcome screen as "Version 0.5 (engine …)";
2. what you did and what happened, plus a screenshot if you can;
3. the save file, if a character is affected.
   - Files app → On My iPhone → NarSil → `data` (NarSil) or `data-silq` (NarSil-Q) → `save`.

## Known gaps

- NarSil-Q deliberately leaves out a few things from Sil-Q:
  - **The staff of Self Knowledge:** it isn't in the game, because the abilities and character screens already
    show what it would.
  - **The light curse:** in Sil-Q it marks an item as cursed, keeps it out of "good" rolls, and lowers a cursed
    special's bonus when smithed. NarSil-Q's affected artefacts and specials have their other drawbacks but not
    the curse marking.
  - **The throne-room crowds:** the guards in Morgoth's throne room don't shy away from you. Sil-Q describes this
    but has no code for it beyond what NarSil already does.
  - **The +1 score:** NarSil-Q doesn't award Sil-Q's +1 score for not using fixed experience.
- A few things differ in how they're presented or in small details:
  - **Oaths:** you choose one through a series of yes/no questions rather than Sil-Q's menu. The oaths and
    their effects are Sil-Q's.
  - **Delvings:** it reveals nearby stairs, forges and traps the way Sil-Q's code intends. Sil-Q's version has
    an indexing slip that NarSil-Q doesn't copy.
  - **The horn of Blasting:** it may not break walls in exactly the same pattern as in Sil-Q.
- Expect rough edges. The game saves often, but keep a copy of a precious save.
