#!/usr/bin/env python3
"""Writes NBConstants.swift from the NB_* enums in narsil_bridge.h, so the iOS app's constants cannot drift
(NB_PROMPT_SMITH -> NB.PROMPT_SMITH).
Usage: gen-swift-constants.py OUT.swift          write it
       gen-swift-constants.py --check OUT.swift  exit 1 if OUT.swift is out of date"""
import sys
from pathlib import Path

from nbconst import c_values

check = sys.argv[1] == "--check"
out = Path(sys.argv[-1])
constants = c_values()
text = "\n".join(["// Generated from bridge/narsil_bridge.h by gen-swift-constants.py; do not edit.", "", "enum NB {"]
                  + [f"    static let {name[3:]} = {value}" for name, value in constants.items()] + ["}", ""])
if check:
    if not out.exists() or out.read_text() != text:
        print(f"{out} is out of date: run bridge/gen-swift-constants.py {out}")
        sys.exit(1)
    print(f"{out}: {len(constants)} constants up to date")
else:
    out.write_text(text)
    print(f"wrote {len(constants)} constants to {out}")
