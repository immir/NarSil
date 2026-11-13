"""Reads the NB_* enum constants out of narsil_bridge.h (shared by the Android drift check and the Swift generator)."""
import re
from pathlib import Path

HEADER = Path(__file__).resolve().parent / "narsil_bridge.h"


def strip_comments(s: str) -> str:
    s = re.sub(r"/\*.*?\*/", "", s, flags=re.S)
    return re.sub(r"//[^\n]*", "", s)


def evaluate(expr: str, env: dict[str, int]) -> int:
    expr = expr.strip().replace("shl", "<<")
    expr = re.sub(r"\b([A-Z][A-Z0-9_]+)\b", lambda m: str(env[m.group(1)]) if m.group(1) in env else m.group(0), expr)
    if re.search(r"[A-Za-z_]", expr):
        raise ValueError(f"unresolved: {expr}")
    return int(eval(expr, {"__builtins__": {}}))  # digits and operators only, checked above


def c_values(header: str | None = None) -> dict[str, int]:
    header = header if header is not None else HEADER.read_text()
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
