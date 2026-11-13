#!/usr/bin/env python3
"""Writes NBConstants.swift from the NB_* enums in narsil_bridge.h, so the iOS app's constants cannot drift
(NB_PROMPT_SMITH -> NB.PROMPT_SMITH).  Usage: gen-swift-constants.py OUT.swift"""
import sys
from pathlib import Path

from nbconst import c_values

out = Path(sys.argv[1])
lines = ["// Generated from bridge/narsil_bridge.h by gen-swift-constants.py; do not edit.", "", "enum NB {"]
lines += [f"    static let {name[3:]} = {value}" for name, value in c_values().items()]
lines += ["}", ""]
out.write_text("\n".join(lines))
print(f"wrote {len(c_values())} constants to {out}")
