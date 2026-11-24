#!/usr/bin/env python3
"""Summarises usage traces (Options -> Record usage trace; Trace.kt / Trace.swift).

Usage: trace-summary.py FILE...   FILE is a trace.log, or a Send logs zip holding trace logs.

A trace line is `ms TAB event TAB key=value ...`; a `session` event starts each session.  Prints, per file group:
how the player gets around (taps, swipes, d-pad, bar, menu), how the panel is used (visits, time open, tabs, visits
with no action taken) and the friction patterns: the panel reopened within 10 s of closing, the action bar scrolled,
a bar or menu action followed by another within a second."""
import sys
import zipfile
from collections import Counter
from statistics import median

REOPEN_MS = 10_000
QUICK_MS = 1_000
# Release builds before the trace spelled names out traced R8's renamed classes (the 0.3 mapping)
RENAMED = {"Z0": "Screen", "X0": "Pack", "a1": "Skills", "O0": "Abilities", "R0": "Check", "W0": "Options", "c1": "Text",
           "Y0": "Quantity", "T0": "Direction", "U0": "Items", "Q0": "Character", "V0": "Knowledge", "w0": "Inspect",
           "x0": "LookList"}


def read(path):
    if zipfile.is_zipfile(path):
        with zipfile.ZipFile(path) as z:
            for name in sorted(n for n in z.namelist() if n.startswith("trace") and n.endswith(".log")):
                yield from z.read(name).decode("utf-8", "replace").splitlines()
    else:
        with open(path, encoding="utf-8", errors="replace") as f:
            yield from f.read().splitlines()


def parse(lines):
    """Sessions as lists of (ms, event, fields)."""
    sessions = []
    for line in lines:
        parts = line.split("\t")
        if len(parts) < 2 or not parts[0].isdigit():
            continue
        fields = dict(p.split("=", 1) for p in parts[2:] if "=" in p)
        for key in ("kind", "prompt"):
            if key in fields:
                fields[key] = RENAMED.get(fields[key], fields[key])
        if parts[1] == "session" or not sessions:
            sessions.append([])
        sessions[-1].append((int(parts[0]), parts[1], fields))
    return sessions


def summarise(sessions):
    events = [e for s in sessions for e in s]
    counts = Counter(e for _, e, _ in events)
    play_ms = sum(s[-1][0] - s[0][0] for s in sessions if s)
    print(f"{len(sessions)} sessions, {play_ms / 60000:.1f} minutes, {len(events)} events")

    def kinds(event, key="kind"):
        return Counter(f.get(key, "?") for _, e, f in events if e == event)

    print("\nmap input:", dict(kinds("map.tap")), "hold", counts["map.hold"], "swipe", dict(kinds("map.swipe")))
    print("d-pad:", dict(kinds("pad")))
    runs = [f for _, e, f in events if e == "run.end"]
    for path, title in (("false", "runs"), ("true", "walks along a path")):
        these = [f for f in runs if f.get("path") == path]
        if these:
            steps = [int(f.get("steps", 0)) for f in these]
            print(f"{title}: {len(these)}, median {median(steps)} steps, 1 step or none: {sum(n <= 1 for n in steps)};",
                  "stopped by", dict(Counter(f.get("why", "?") for f in these).most_common()))
    print("action bar taps:", counts["bar.tap"], "scrolls:", counts["bar.scroll"], "long-presses:", counts["bar.longpress"])
    print("menu opens:", counts["menu.open"], "picks:", counts["menu.pick"])
    for title, key in (("most used bar actions", "bar.tap"), ("most used menu actions", "menu.pick")):
        top = Counter(f.get("act", "?") for _, e, f in events if e == key).most_common(8)
        if top:
            print(f"{title}:", ", ".join(f"{a} {n}" for a, n in top))

    opens = [(t, f) for t, e, f in events if e == "panel.open"]
    closes = [f for _, e, f in events if e == "panel.close"]
    requests = kinds("panel.request", "via")
    print(f"\npanel opens: {len(opens)} (by {dict(requests)})")
    if closes:
        durations = [int(f.get("open_ms", 0)) for f in closes]
        idle = sum(1 for f in closes if f.get("acted") == "false")
        print(f"time open: median {median(durations) / 1000:.1f} s; visits with no action taken: {idle} of {len(closes)}")
    print("tabs shown:", dict(Counter(f.get("tab", "?") for _, e, f in events if e in ("panel.open", "tab.show"))))
    labelled = Counter(f"{f['label']} ({f.get('symbol', '?')})" for _, e, f in events if e == "pack.action" and "label" in f)
    if labelled:
        print("pack actions:", ", ".join(f"{a} {n}" for a, n in labelled.most_common(10)))

    print("\nfriction:")
    reopened, quick = [], 0
    for s in sessions:
        last_close = None
        for t, e, f in s:
            if e == "panel.close":
                last_close = t
            elif e == "panel.open" and last_close is not None and t - last_close < REOPEN_MS:
                reopened.append(t - last_close)
        presses = [(t, f.get("act")) for t, e, f in s if e in ("bar.tap", "menu.pick")]
        quick += sum(1 for (a, _), (b, _) in zip(presses, presses[1:]) if b - a < QUICK_MS)
    if reopened:
        print(f"  panel reopened within {REOPEN_MS // 1000} s of closing: {len(reopened)} times (median {median(reopened) / 1000:.1f} s)")
    if quick:
        print(f"  {quick} bar/menu actions within {QUICK_MS // 1000} s of the previous one")
    if counts["bar.scroll"]:
        print(f"  the action bar was scrolled {counts['bar.scroll']} times")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    summarise(parse(line for path in sys.argv[1:] for line in read(path)))
