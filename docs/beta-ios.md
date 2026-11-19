# NarSil for iPhone: beta 0.3

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
- **NarSil-Q** is NarSil's engine playing Sil-Q's monsters, items, abilities and rules. Choose "Play NarSil-Q
  instead" on the welcome screen. Its title is blue, so you can tell which game you're in.
- Each game keeps its own characters and saves.
- NarSil-Q is new. Many of its rules were ported recently and haven't been played much, especially the songs
  (Delvings, Challenge, Thresholds), Impale, the horns, betrayal items and thralls.

## Reporting problems

Please send:
1. the version, shown on the welcome screen as "Version 0.3 (engine …)";
2. what you did and what happened, plus a screenshot if you can;
3. the save file, if a character is affected.
   - Files app → On My iPhone → NarSil → `data` (NarSil) or `data-silq` (NarSil-Q) → `save`.

## Known gaps

- NarSil-Q leaves out the staff of Self Knowledge, the light curse and the throne-room crowds. See
  `lib-silq/README.md` for why.
- Expect rough edges. The game saves often, but keep a copy of a precious save.
