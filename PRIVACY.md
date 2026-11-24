# NarSil for mobile: privacy

The Android and iOS apps run the whole game on your device. They:

- use no network connection (the Android app does not request the INTERNET permission);
- collect, send and share no data, and contain no analytics or advertising;
- keep the savefile, scores and logs in the app's own storage;
- can, if you turn on Options → Record usage trace (off by default), keep a log in the app's own storage of which
  buttons, panels and tabs you use and when: event names, counts and times, the game's names for the actions you pick,
  an item's map symbol and how far runs go; never what you type, your character's name, item names or messages. It is sent nowhere; it only joins a bug report if you choose Send logs.

Files leave the device only when you choose Share or Send logs (a save export, the character dump, or a bug report
made of the app log, your savefile, the character dump and the usage trace if there is one); you pick where they go in the system share sheet.
Android also asks for the vibrate permission, for the optional buzzes (low health and combat).
