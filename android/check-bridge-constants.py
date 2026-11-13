#!/usr/bin/env python3
"""Drift guard: every NB_* enum value in narsil_bridge.h must equal the Kotlin constant of the same name
(NB_PROMPT_SMITH -> PROMPT_SMITH) in NarSilNative.kt.  The two are written by hand; a mismatch once left a
prompt kind unreadable by the app.  Exit status 1 and a list on any disagreement."""
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "bridge"))
from nbconst import c_values, evaluate, strip_comments  # noqa: E402

root = Path(__file__).resolve().parent / "app/src/main"
kotlin = (root / "kotlin/org/narsil/android/NarSilNative.kt").read_text()


def kotlin_values() -> dict[str, int]:
    out: dict[str, int] = {}
    for name, expr in re.findall(r"const val (\w+)\s*(?::\s*\w+)?\s*=\s*([^\n]+)", strip_comments(kotlin)):
        try:
            out[name] = evaluate(expr, out)
        except (ValueError, SyntaxError):
            pass
    return out


c, k = c_values(), kotlin_values()
checked, bad = 0, []
for name, value in c.items():
    short = name[3:]
    if short in k:
        checked += 1
        if k[short] != value:
            bad.append(f"{name} = {value} in narsil_bridge.h, but {short} = {k[short]} in NarSilNative.kt")
print(f"bridge constants: {checked} compared, {len(c)} in the header, {len(k)} in Kotlin")
if checked < 80:
    bad.append(f"only {checked} constants matched by name; the checker's name mapping has probably broken")
for line in bad:
    print("DRIFT:", line)
sys.exit(1 if bad else 0)
