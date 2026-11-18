#!/usr/bin/env python3
"""Convert Sil-Q 1.5.1 data files to NarSil's format.

  silq-convert.py monster SILQ_EDIT_DIR NARSIL_GAMEDATA_DIR            > monster.txt
  silq-convert.py monster SILQ_EDIT_DIR NARSIL_GAMEDATA_DIR --diff     (field differences on shared names)

  silq-convert.py tiles NARSIL_GRAF_PREF LIB_SILQ_MONSTER_TXT > graf-mcm.prf   (monster tile lines for NarSil-Q's monsters)

Lines NarSil has and Sil-Q's format cannot express (drop:, drop-artifact:, song:, message-*:) are kept from
the NarSil entry of the same name. The output is committed; the build never reads Sil-Q's files.
"""
import re
import sys
from collections import OrderedDict

SPELL_RENAME = {"BRTH_FIRE": "BR_FIRE", "BRTH_COLD": "BR_COLD", "BRTH_DARK": "BR_DARK", "BRTH_POIS": "BR_POIS",
                "SNG_BINDING": "SNG_BIND", "SNG_PIERCING": "SNG_PIERCE"}
FLAG_RENAME = {"HURT_LITE": "HURT_LIGHT", "EXCHANGE_PLACES": "EXCHANGE"}
# Sil-Q's extended colours (letter + digit) on NarSil's 28-colour palette. The pairs seen in both games'
# shared monsters are exact (y1 Smaug, b1 Feanor, G1 horror, B1 Hithrauko, D1 Shelob); v1 and U1 are guesses.
COLOR_MAP = {"D1": "s", "y1": "Y", "b1": "Z", "B1": "Z", "G1": "T", "v1": "V", "U1": "M"}
# Spells the engine does not have yet; dropped (and reported) until each is implemented.
UNSUPPORTED_SPELLS = set()
KEEP_FROM_NARSIL = ("drop:", "drop-artifact:", "song:", "message-vis:", "message-invis:", "message-miss:")


def read_entries(path, start):
    """Entries as an ordered dict: name -> list of lines (without the start line)."""
    out, cur = OrderedDict(), None
    for line in open(path, errors="replace"):
        line = line.rstrip("\n")
        m = re.match(start, line)
        if m:
            cur = m.group(1).strip()
            out[cur] = []
        elif cur is not None and line and not line.startswith("#"):
            out[cur].append(line)
    return out


def base_by_glyph(gamedata):
    bases, cur = {}, None
    for line in open(f"{gamedata}/monster_base.txt"):
        if line.startswith("name:"):
            cur = line.split(":", 1)[1].strip()
        elif line.startswith("glyph:") and cur:
            bases.setdefault(line.split(":", 1)[1].strip(), cur)
    bases["@"] = "person"
    return bases


def split_flags(text):
    return [f.strip() for f in text.split("|") if f.strip()]


def convert(name, lines, bases):
    """One Sil-Q monster to NarSil lines, as a list of 'key:value' strings."""
    f = {"flags": [], "desc": [], "spells": []}
    out = {}
    for line in lines:
        k, v = line[0], line[2:]
        if k == "W":
            out["depth"], out["rarity"] = v.split(":")
        elif k == "G":
            out["glyph"], c = v.split(":")
            out["color"] = COLOR_MAP.get(c, c)
        elif k == "I":
            out["speed"], out["health"], out["light"] = v.split(":")
        elif k == "A":
            out["sleep"], out["percept"], out["stealth"], out["will"] = v.split(":")
        elif k == "P":
            m = re.fullmatch(r"\[([+-]?\d+)(?:,(.*))?\]", v)
            out["prot"] = f"{int(m.group(1))}" + (f":{m.group(2)}" if m.group(2) else "")
        elif k == "B":
            m = re.fullmatch(r"(\w+):(\w+):\(([+-]?\d*)(?:,(.*))?\)", v)
            out.setdefault("blows", []).append(f"{m.group(1)}:{m.group(2)}:{int(m.group(3) or 0)}" + (f":{m.group(4)}" if m.group(4) else ""))
        elif k == "F":
            f["flags"] += [FLAG_RENAME.get(x, x) for x in split_flags(v)]
        elif k == "S":
            f["spells"] += split_flags(v)
        elif k == "D":
            f["desc"].append(v)
    res = []
    glyph = out.get("glyph", "")
    base = bases.get(glyph)
    res.append(("name", name))
    if base:
        res.append(("base", base))
    res += [(k, out[k]) for k in ("depth", "rarity") if k in out]
    if glyph and glyph != next((g for g, b in bases.items() if b == base), glyph):
        res.append(("glyph", glyph))
    res += [("color", out["color"])] if "color" in out else []
    res += [(k, out[k]) for k in ("speed", "health") if k in out]
    if out.get("light", "0") != "0":
        res.append(("light", out["light"]))
    res += [(k, out[k]) for k in ("sleep", "percept", "stealth", "will", "prot") if k in out]
    res += [("blow", b) for b in out.get("blows", [])]
    flags = list(OrderedDict.fromkeys(f["flags"] + (["NAME_COMMA"] if "," in name else [])))
    if flags:
        res.append(("flags", " | ".join(flags)))
    freq = power = None
    spells = []
    for s in f["spells"]:
        m = re.fullmatch(r"SPELL_PCT_(\d+)", s)
        p = re.fullmatch(r"POW_(\d+)", s)
        if m:
            freq = m.group(1)
        elif p:
            power = p.group(1)
        else:
            spells.append(SPELL_RENAME.get(s, s))
    for dropped in [x for x in spells if x in UNSUPPORTED_SPELLS]:
        print(f"{name}: spell {dropped} not implemented yet, dropped", file=sys.stderr)
    spells = [x for x in spells if x not in UNSUPPORTED_SPELLS]
    if spells:
        res += [("ranged-freq", freq or "100")] + ([("spell-power", power)] if power else [])
        res.append(("spells", " | ".join(spells)))
    res += [("desc", d) for d in f["desc"]]
    return [f"{k}:{v}" for k, v in res]


def narsil_fields(lines):
    d = OrderedDict()
    for line in lines:
        k, _, v = line.partition(":")
        d.setdefault(k, []).append(v)
    return d


# Tiles for monsters NarSil's tileset lacks: they borrow a look-alike's tile until they get their own.
TILE_BORROW = {"Orc skirmisher": "Orc scout", "Orc thrallmaster": "Orc captain", "Attercop": "Sword spider",
               "Brood spider": "Distended spider", "Alert human thrall": "Easterling spy",
               "Dejected human thrall": "Easterling spy", "Alert elven thrall": "Easterling spy",
               "Dejected elven thrall": "Easterling spy", "Phantom": "Shadow", "Spectre": "Grave wight",
               "Wraith": "Barrow wight", "Lug, the Grotesque": "Giant", "Balcmeg, the Relentless": "Giant",
               "Aule, the Smith": "Manwe, Lord of the Breath of Arda",
               "Morgoth, Lord of Darkness (without crown)": "Morgoth, Lord of Darkness"}


def tiles(prf, names):
    """A NarSil graphics pref with monster lines kept only for monsters that exist, plus borrowed tiles."""
    names = names | {n.split(",")[0] for n in names}  # the game looks up "Gothmog" for "Gothmog, High Captain..."
    lines = open(prf).read().split("\n")
    kept = [l for l in lines if not l.startswith("monster:") or l.split(":")[1] in names]
    have = {l.split(":")[1]: l for l in lines if l.startswith("monster:")}
    extra = [f"monster:{n}:" + have[b].split(":", 2)[2] for n, b in TILE_BORROW.items() if n in names and b in have]
    at = max(i for i, l in enumerate(kept) if l.startswith("monster:")) + 1
    return "\n".join(kept[:at] + extra + kept[at:])


def main():
    if len(sys.argv) == 4 and sys.argv[1] == "tiles":
        print(tiles(sys.argv[2], {l.split(":", 1)[1].strip() for l in open(sys.argv[3]) if l.startswith("name:")}), end="")
        return
    if len(sys.argv) < 4 or sys.argv[1] != "monster":
        sys.exit(__doc__)
    silq, gamedata, diff = sys.argv[2], sys.argv[3], "--diff" in sys.argv
    bases = base_by_glyph(gamedata)
    sq = read_entries(f"{silq}/monster.txt", r"^N:\d+:(.*)")
    ns = read_entries(f"{gamedata}/monster.txt", r"^name:(.*)")
    converted = OrderedDict((n, convert(n, l, bases)) for n, l in sq.items() if n != "<player>" and any(x.startswith("W:") for x in l))
    if diff:
        shared = [n for n in converted if n in ns]
        changed = 0
        for n in shared:
            a, b = narsil_fields(["name:" + n] + ns[n]), narsil_fields(converted[n])
            norm = lambda d, k: sorted(split_flags(" | ".join(d.get(k, [])))) if k == "flags" else d.get(k)
            keys = [k for k in b if k not in ("desc", "base", "name") and norm(a, k) != norm(b, k)]
            if keys:
                changed += 1
                print(n, "|", "; ".join(f"{k}: {norm(a, k)} -> {norm(b, k)}" for k in keys))
        print(f"\n{len(shared)} shared, {changed} differ, {len(converted) - len(shared)} new,"
              f" {len([n for n in ns if n not in converted])} only in NarSil: {[n for n in ns if n not in converted]}")
        return
    head = open(f"{gamedata}/monster.txt").read().split("\n")
    first = next(i for i, l in enumerate(head) if l.startswith("name:") and not l.startswith("name:<player>"))
    start = next(i for i in range(first, -1, -1) if head[i].startswith("##### ") or i == 0)
    print("\n".join(head[:first]).rstrip() + "\n")
    for n, lines in converted.items():
        kept = [l for l in ns.get(n, []) if l.startswith(KEEP_FROM_NARSIL)]
        desc = [l for l in lines if l.startswith("desc:")]
        print("\n".join([l for l in lines if not l.startswith("desc:")] + kept + desc) + "\n")


main()
