#!/usr/bin/env python3
"""Drift guard: every NB_* enum value in narsil_bridge.h must equal the Kotlin constant of the same name
(NB_PROMPT_SMITH -> PROMPT_SMITH) in NarSilNative.kt.  The two are written by hand; a mismatch once left a
prompt kind unreadable by the app.  Exit status 1 and a list on any disagreement."""
import re
import sys
from pathlib import Path

root = Path(__file__).resolve().parent / "app/src/main"  # header lives in ../bridge
header = (root.parents[2].parent / "bridge/narsil_bridge.h").read_text()
kotlin = (root / "kotlin/org/narsil/android/NarSilNative.kt").read_text()


def strip_comments(s: str) -> str:
    s = re.sub(r"/\*.*?\*/", "", s, flags=re.S)
    return re.sub(r"//[^\n]*", "", s)


def evaluate(expr: str, env: dict[str, int]) -> int:
    expr = expr.strip().replace("shl", "<<")
    expr = re.sub(r"\b([A-Z][A-Z0-9_]+)\b", lambda m: str(env[m.group(1)]) if m.group(1) in env else m.group(0), expr)
    if re.search(r"[A-Za-z_]", expr):
        raise ValueError(f"unresolved: {expr}")
    return int(eval(expr, {"__builtins__": {}}))  # digits and operators only, checked above


def c_values() -> dict[str, int]:
    out: dict[str, int] = {}
    for body in re.findall(r"enum\s*\w*\s*\{(.*?)\};", strip_comments(header), flags=re.S):
        nxt = 0
        for item in (i.strip() for i in body.split(",")):
            if not item:
                continue
            name, _, expr = (p.strip() for p in item.partition("="))
            if not name.startswith("NB_"):
                continue
            nxt = evaluate(expr, out) if expr else nxt
            out[name] = nxt
            nxt += 1
    return out


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
