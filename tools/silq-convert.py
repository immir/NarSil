#!/usr/bin/env python3
"""Convert Sil-Q 1.5.1 data files to NarSil's format.

  silq-convert.py monster SILQ_EDIT_DIR NARSIL_GAMEDATA_DIR            > monster.txt
  silq-convert.py monster SILQ_EDIT_DIR NARSIL_GAMEDATA_DIR --diff     (field differences on shared names)

  silq-convert.py object SILQ_EDIT_DIR NARSIL_GAMEDATA_DIR tools/silq-new-objects.txt > object.txt
  silq-convert.py special|artefact SILQ_EDIT_DIR NARSIL_GAMEDATA_DIR tools/silq-new-objects.txt > special.txt
  silq-convert.py vault SILQ_EDIT_DIR NARSIL_GAMEDATA_DIR > vault.txt
  silq-convert.py tiles NARSIL_GRAF_PREF LIB_SILQ_GAMEDATA_DIR SILQ_LIB_DIR > graf-mcm.prf
      (NarSil's tile lines for what NarSil-Q has, at Sil-Q's positions where Sil-Q has them, plus Sil-Q's depleted
      lights, rage tiles and crownless Morgoth)
  silq-convert.py flavours NARSIL_FLVR_PREF SILQ_LIB_DIR > flvr-mcm.prf
  silq-convert.py sheet SILQ_LIB_DIR NARSIL_TILE_PNG OUT_PNG
      (Sil-Q's tile sheet, with NarSil's own bottom row moved one row down: NarSil-Q's 16x16.png)

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


def read_entries_all(path, start):
    """Every entry, duplicates included: [((index, name), lines)] for a start pattern with groups index, name."""
    out, cur = [], None
    for line in open(path, errors="replace"):
        line = line.rstrip("\n")
        m = re.match(start, line)
        if m:
            cur = []
            out.append(((int(m.group(1)), m.group(2).strip()), cur))
        elif cur is not None and line and not line.startswith("#"):
            cur.append(line)
    return out


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


def convert(name, lines, bases, narsil_lines=None):
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
    base = next((l[5:] for l in (narsil_lines or []) if l.startswith("base:")), None)
    if not base:    # a monster NarSil lacks: by its race flag, else by its glyph
        base = next((b for fl, b in RACE_BASE if fl in f["flags"]), None) or bases.get(glyph) or "person"
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


RACE_BASE = [("SPIDER", "spider"), ("WOLF", "wolf"), ("ORC", "orc"), ("TROLL", "troll"), ("RAUKO", "rauko"),
             ("SERPENT", "serpent"), ("DRAGON", "dragon"), ("UNDEAD", "wraith")]

# Tiles for monsters with none of their own in Sil-Q's prefs either: they borrow a look-alike's.
TILE_BORROW = {"Orc skirmisher": "Orc scout", "Orc thrallmaster": "Orc captain", "Attercop": "Sword spider",
               "Brood spider": "Distended spider", "Alert human thrall": "Easterling spy",
               "Dejected human thrall": "Easterling spy", "Alert elven thrall": "Easterling spy",
               "Dejected elven thrall": "Easterling spy", "Phantom": "Shadow", "Spectre": "Grave wight",
               "Wraith": "Barrow wight", "Lug, the Grotesque": "Giant", "Balcmeg, the Relentless": "Giant",
               "Aule, the Smith": "Manwe, Lord of the Breath of Arda",
               "Morgoth, Lord of Darkness (without crown)": "Morgoth, Lord of Darkness"}


# NarSil's tile sheet is an older Sil-Q sheet: the same layout, with NarSil's own tiles in row 0x90, where Sil-Q
# now keeps its rage tiles. NarSil-Q uses Sil-Q's sheet with that NarSil row moved to TILE_NARSIL_ROW.
TILE_SHEET_ROW = 0x90
TILE_NARSIL_ROW = 0x91

# Sil-Q's special tiles for spent lights (its "S:" lines) and the objects they belong to.
TILE_DEPLETED = {0x0D: "Brass Lantern", 0x0E: "Wooden Torch", 0x0F: "Mallorn Torch"}


def plain_name(name):
    """An object name as pref files write it: "& Pair~ of Shabby Boots" -> "Pair of Shabby Boots"."""
    return re.sub(r"^& ", "", name).replace("~", "")


def silq_tiles(silq_lib):
    """Sil-Q's tile lines: {("K" | "R" | "S", index): (attr, char)}, and the rage tiles {monster index: (attr, char)}."""
    tiles, rage = {}, {}
    for line in open(f"{silq_lib}/pref/graf-tiles.prf"):
        m = re.match(r"^([KRSF]):(0x[0-9A-Fa-f]+|\d+):(?:(rage):)?(0x[0-9A-Fa-f]+)/(0x[0-9A-Fa-f]+)", line)
        if m:
            at = (int(m.group(4), 16), int(m.group(5), 16))
            if m.group(3):
                rage[int(m.group(2), 0)] = at
            else:
                tiles[(m.group(1), int(m.group(2), 0))] = at
    return tiles, rage


def silq_names(path):
    """Sil-Q's index -> name for an edit file."""
    return {int(m.group(1)): m.group(2).strip() for m in re.finditer(r"^N:(\d+):(.*)$", open(path, errors="replace").read(), re.M)}


def tiles(prf, gamedata, silq_lib):
    """NarSil-Q's graphics pref: NarSil's lines for what NarSil-Q has, at Sil-Q's positions where Sil-Q has them,
    plus Sil-Q's tiles for what NarSil lacks, its depleted lights, rage tiles and crownless Morgoth."""
    hexes = lambda at: f"0x{at[0]:02X}:0x{at[1]:02X}"
    monsters = [m.group(1) for m in re.finditer(r"^name:(.*)$", open(f"{gamedata}/monster.txt").read(), re.M)]
    objects = OrderedDict()
    for entry in open(f"{gamedata}/object.txt").read().split("\nname:")[1:]:
        tval = re.search(r"^type:(.*)$", entry, re.M)
        if tval:
            objects[plain_name(entry.split("\n", 1)[0].strip())] = tval.group(1).strip()
    sq, rage = silq_tiles(silq_lib)
    sq_monsters, sq_objects = silq_names(f"{silq_lib}/edit/monster.txt"), silq_names(f"{silq_lib}/edit/object.txt")
    monster_at = {sq_monsters[i]: at for (k, i), at in sq.items() if k == "R" and i in sq_monsters}
    object_at = {plain_name(sq_objects[i]): at for (k, i), at in sq.items() if k == "K" and i in sq_objects}
    full = {n.split(",")[0]: n for n in monsters}  # the game looks up "Gothmog" for "Gothmog, High Captain..."

    out, have_monster, have_object = [], set(), set()
    for line in open(prf).read().split("\n"):
        m = re.match(r"^(object:[^:]*|monster):(.*):(0x[0-9A-Fa-f]{2}):(0x[0-9A-Fa-f]{2})$", line)
        if not m:
            out.append(line)
            continue
        kind, name, at = m.group(1), m.group(2), (int(m.group(3), 16), int(m.group(4), 16))
        if kind == "monster":
            name_full = name if name in monsters else full.get(name)
            if name_full is None:
                continue
            have_monster.add(name_full)
            if name_full != "<player>":  # the player's tile is by race, in xtra-mcm.prf
                at = monster_at.get(name_full, at)
        elif name in objects:
            have_object.add(name)
            at = object_at.get(name, at)
        if at[0] == TILE_SHEET_ROW and at not in set(monster_at.values()) | set(object_at.values()):
            at = (TILE_NARSIL_ROW, at[1])
        out.append(f"{kind}:{name}:{hexes(at)}")

    def insert_after(prefix, new):
        at = max(i for i, l in enumerate(out) if l.startswith(prefix)) + 1
        out[at:at] = new

    insert_after("monster:", [f"monster:{n}:{hexes(monster_at[n])}" for n in monsters
                              if n not in have_monster and n in monster_at]
                 + [f"monster:{n}:{hexes(monster_at.get(b))}" for n, b in TILE_BORROW.items()
                    if n in monsters and n not in have_monster and n not in monster_at and b in monster_at])
    insert_after("object:", [f"object:{t}:{n}:{hexes(object_at[n])}" for n, t in objects.items()
                             if n not in have_object and n in object_at])
    out += ["", "##### Sil-Q: sunlight, a spent light, a monster without its crown, and monsters as a raging player sees them #####", ""]
    if ("F", 9) in sq:  # Sil-Q's patch of sunlight (terrain 0x09)
        out += [f"feat:SUNLIGHT:*:{hexes(sq[('F', 9)])}"]
    out += [f"object-depleted:{objects[n]}:{n}:{hexes(sq[('S', i)])}" for i, n in TILE_DEPLETED.items()
            if n in objects and ("S", i) in sq]
    out += [f"monster-uncrowned:{sq_monsters[i][:-len(' (without crown)')]}:{hexes(at)}" for (k, i), at in sq.items()
            if k == "R" and sq_monsters.get(i, "").endswith(" (without crown)")
            and sq_monsters[i][:-len(" (without crown)")] in monsters]
    out += [f"monster-rage:{sq_monsters[i]}:{hexes(at)}" for i, at in sorted(rage.items())
            if sq_monsters.get(i) in monsters]
    return "\n".join(out).rstrip("\n") + "\n"


def flavours(prf, silq_lib):
    """NarSil's flavour tiles, at Sil-Q's positions where Sil-Q has the same flavour."""
    sq = {int(m.group(1)): (m.group(2), m.group(3))
          for m in re.finditer(r"^L:(\d+):(0x[0-9A-Fa-f]+)/(0x[0-9A-Fa-f]+)", open(f"{silq_lib}/pref/flvr-tiles.prf").read(), re.M)}
    out = []
    for line in open(prf).read().split("\n"):
        m = re.match(r"^flavor:(\d+):", line)
        if m and int(m.group(1)) in sq:
            a, c = sq[int(m.group(1))]
            line = f"flavor:{m.group(1)}:0x{int(a, 16):02X}:0x{int(c, 16):02X}"
        out.append(line)
    return "\n".join(out)


def sheet(silq_lib, narsil_png, out_png):
    """Sil-Q's tile sheet (its BMP, with the colour of pixel (0, 0) transparent) and NarSil's own tile row below it."""
    from PIL import Image
    sq = Image.open(f"{silq_lib}/xtra/graf/16x16.bmp").convert("RGBA")
    key = sq.getpixel((0, 0))
    px = sq.load()
    for y in range(sq.height):
        for x in range(sq.width):
            if px[x, y] == key:
                px[x, y] = (0, 0, 0, 0)
    ns = Image.open(narsil_png).convert("RGBA")
    out = Image.new("RGBA", (sq.width, 16 * (TILE_NARSIL_ROW - 0x80 + 1)), (0, 0, 0, 0))
    out.paste(sq, (0, 0))
    y = 16 * (TILE_SHEET_ROW - 0x80)
    out.paste(ns.crop((0, y, ns.width, y + 16)), (0, 16 * (TILE_NARSIL_ROW - 0x80)))
    out.save(out_png, optimize=True)


def silq_object_numbers(lines):
    """Sil-Q object lines -> the NarSil lines for depth, weight, cost, attack, defence and alloc."""
    w = next((l[2:].split(":") for l in lines if l.startswith("W:")), None)
    p = next((l[2:].split(":") for l in lines if l.startswith("P:")), None)
    a = [x for l in lines if l.startswith("A:") for x in l[2:].split(":")]
    out = []
    if w and len(w) >= 4:
        out += [("depth", w[0]), ("weight", w[2]), ("cost", w[3])]
    if p and len(p) >= 4:
        out += [("attack", f"{int(p[0])}:{p[1]}"), ("defence", f"{int(p[2])}:{p[3]}")]
    out += [("alloc", x.strip().replace("/", ":")) for x in a]
    return out


def patch_objects(silq_path, narsil_path, extra_path):
    """NarSil's object.txt with Sil-Q's numbers (depth, weight, cost, attack, defence, allocation) on shared
    objects; NarSil-only objects stop being generated; tools/silq-new-objects.txt is appended.
    Effects, flags and values stay NarSil's: Sil-Q hard-codes them in C, NarSil keeps them in data."""
    sq = read_entries(silq_path, r"^N:\d+:(.*)")
    out, entry, name = [], [], None

    def flush():
        body = list(entry)
        tail = []
        while body and (not body[-1].strip() or body[-1].startswith("#")):
            tail.insert(0, body.pop())
        if name in sq:
            nums = [(k, v) for k, v in silq_object_numbers(sq[name])
                    if k == "alloc" or any(l.startswith(k + ":") for l in body)]
            have = {k for k, _ in nums} | {"alloc"}  # no A: line in Sil-Q: never generated (its crowns)
            jewellery = any(l in ("type:ring", "type:amulet") for l in body)
            if jewellery:  # NarSil's 99s mark bonuses rolled at creation (Sil-Q rolls them in code too)
                have -= {"attack", "defence"}
                nums = [(k, v) for k, v in nums if k not in ("attack", "defence")]
            sq_flags = {x.strip() for l in sq[name] if l.startswith("F:") for x in l[2:].split("|") if x.strip()}
            extra = [OBJECT_FLAGS_FROM_SILQ[x] for x in sorted(sq_flags) if x in OBJECT_FLAGS_FROM_SILQ]
            if not any(l.startswith("type:") for l in body):  # an entry NarSil keeps commented out
                extra = []
            body = [l for l in body if l.partition(":")[0] not in have]
            if extra:
                at_flags = next((i for i, l in enumerate(body) if l.startswith("flags:")), None)
                if at_flags is None:
                    body.append("flags:" + " | ".join(extra))
                else:
                    have_flags = [x.strip() for x in body[at_flags][6:].split("|")]
                    body[at_flags] = "flags:" + " | ".join(have_flags + [x for x in extra if x not in have_flags])
            at = next((i for i, l in enumerate(body) if l.partition(":")[0] in ("effect", "charges", "flags", "values", "brand", "slay", "special", "msg", "desc", "pval", "time", "dice", "expr", "thrown-effect")), len(body))
            body[at:at] = [f"{k}:{v}" for k, v in nums]
        elif name is not None:
            body = [l for l in body if not l.startswith("alloc:")]
        if "type:skeleton" in body:  # Sil-Q's skeletons can be searched once (pval 1 until then)
            body.append("pval:1")
        if "type:horn" in body:     # Sil-Q's horns sound in a 90 degree cone reaching three grids, not a line
            body = [re.sub(r"^effect:BEAM:(\w+)$", r"effect:ARC:\1:3:90", l) for l in body]
            text = "\n".join(body)
            text = text.replace("dice:4d4m$M", "dice:10d4m$M")      # Thunder: 10d4 in Sil-Q
            text = text.replace("effect:TERRAIN_BEAM:KILL_WALL:0:1\ndice:m$M", "effect:TERRAIN_BEAM:KILL_WALL:3:1\ndice:8d4m$M")
            text = text.replace("effect:ARC:FORCE:3:90\ndice:m$M\nexpr:M:PLAYER_WILL: + 10",
                                "effect:HORN_FORCE\ndice:$B\nexpr:B:PLAYER_WILL: + 0\neffect:ARC:NOTHING:3:90\ndice:m$M\nexpr:M:PLAYER_WILL: + 0")
            body = text.split("\n")
        out.extend(body + tail)

    for line in open(narsil_path).read().split("\n"):
        if line.startswith("name:"):
            flush()
            entry, name = [], line[5:].strip()
        entry.append(line)
    flush()
    # Sil-Q's Potion of Healing restores 50 points of health (NarSil: half your maximum)
    healing = ("effect:HEAL_HP\ndice:$B\nexpr:B:PLAYER_MAX_HP: / 2\neffect:NOURISH:INC_BY\ndice:100\n"
               "flags:GOOD\ndesc:It heals all cuts and restores half your health.")
    joined = "\n".join(out)
    assert joined.count(healing) == 1, "Potion of Healing text changed"
    out = joined.replace(healing, healing.replace("dice:$B\nexpr:B:PLAYER_MAX_HP: / 2", "dice:50")
                         .replace("restores half your health", "restores 50 points of health")).split("\n")
    extra = open(extra_path).read()
    have = {l[5:].strip() for l in out + extra.split("\n") if l.startswith("name:")}
    for n in sorted(set(sq) - have - set(OBJECTS_NOT_PORTED)):
        print(f"silq-convert: Sil-Q object '{n}' is not in NarSil-Q (add it to {extra_path})", file=sys.stderr)
    return "\n".join(out).rstrip() + "\n\n" + extra


# Sil-Q object flags NarSil-Q's objects take (the rest stay NarSil's, which Sil-Q hard-codes in C):
# MORE_SPECIAL (an extra 50% chance of a special), ENCHANTABLE, the light curse, and INSTA_ART on a base made
# only as an artefact (Sil-Q's crowns).
OBJECT_FLAGS_FROM_SILQ = {"MORE_SPECIAL": "MORE_SPECIAL", "ENCHANTABLE": "ENCHANTABLE", "LIGHT_CURSE": "CURSED",
                          "INSTA_ART": "INSTA_ART"}

# Sil-Q objects that have no entry of their own in NarSil-Q's object.txt, and why.
OBJECTS_NOT_PORTED = {
    "& Pearl~": "special artefact base, NarSil's 'Pearl'",
    "& Jewel~": "special artefact base, NarSil's 'Jewel'",
    "& Necklace~": "special artefact base, NarSil's 'Necklace'",
    "& Mighty Hammer~": "Grond's base, in artefact.txt",
    "& Massive Iron Crown~": "the Iron Crown's base, in artefact.txt",
    "& Note~": "Sil-Q's tutorial notes; NarSil has its Tutorial Note",
    "Self Knowledge": "not ported: the abilities screen lists everything it would",
}


SKILLS = ["Melee", "Archery", "Evasion", "Stealth", "Perception", "Will", "Smithing", "Song"]
PVAL_FLAGS = {"TUNNEL", "DAMAGE_SIDES", "STR", "DEX", "CON", "GRA", "STEALTH", "PERCEPTION", "WILL", "MELEE", "ARCHERY", "SMITHING", "SONG", "EVASION"}
NEG_FLAGS = {"NEG_STR": "STR", "NEG_DEX": "DEX", "NEG_CON": "CON", "NEG_GRA": "GRA"}
FLAG_TO_VALUES = {"VUL_FIRE": "RES_FIRE[-1]", "VUL_COLD": "RES_COLD[-1]", "VUL_POIS": "RES_POIS[-1]", "RES_FIRE": "RES_FIRE[1]", "RES_COLD": "RES_COLD[1]", "RES_POIS": "RES_POIS[1]", "RES_DARK": "RES_DARK[1]"}
FLAG_TO_FLAGS = {"RES_STUN": "PROT_STUN", "RES_BLIND": "PROT_BLIND", "RES_CONFU": "PROT_CONF", "RES_FEAR": "PROT_FEAR",
                 "RES_HALLU": "PROT_HALLU", "FEAR": "COWARDICE",
                 "IGNORE_ALL": "IGNORE_ACID | IGNORE_COLD | IGNORE_FIRE",
                 "LIGHT_CURSE": "CURSED"}  # Sil-Q's light curse: cursed (can't be taken off), kept out of good rolls
FLAG_TO_SLAY = {"SLAY_ORC": "ORC_1", "SLAY_TROLL": "TROLL_1", "SLAY_WOLF": "WOLF_1", "SLAY_SPIDER": "SPIDER_1",
                "SLAY_UNDEAD": "UNDEAD_1", "SLAY_RAUKO": "RAUKO_1", "SLAY_DRAGON": "DRAGON_1", "SLAY_MAN_OR_ELF": "MAN_1 ELF_1"}
FLAG_TO_BRAND = {"BRAND_FIRE": "FIRE_1", "BRAND_COLD": "COLD_1", "BRAND_ELEC": "ELEC_1", "BRAND_POIS": "POIS_1"}


class Tables:
    """What both games know: object names by (tval, sval), tval -> NarSil type, ability names, flags NarSil has."""

    def __init__(self, silq, gamedata, new_objects):
        self.sq_obj = read_entries(f"{silq}/object.txt", r"^N:\d+:(.*)")
        self.by_tval_sval = {}
        for name, lines in self.sq_obj.items():
            i = next((l[2:].split(":") for l in lines if l.startswith("I:")), None)
            if i and len(i) >= 2:
                self.by_tval_sval[(int(i[0]), int(i[1]))] = name
        ns = read_entries(f"{gamedata}/object.txt", r"^name:(.*)")
        extra = read_entries(new_objects, r"^name:(.*)")
        self.narsil_objects = set(ns) | set(extra)
        self.type_of_tval = {}
        for name, lines in self.sq_obj.items():
            t = next((l[2:].split(":")[0] for l in lines if l.startswith("I:")), None)
            for src in (ns, extra):
                ty = next((l[5:] for l in src.get(name, []) if l.startswith("type:")), None)
                if t and ty:
                    self.type_of_tval[int(t)] = ty
        self.abilities = {}
        for name, lines in read_entries(f"{silq}/ability.txt", r"^N:\d+:(.*)").items():
            i = next((l[2:].split(":") for l in lines if l.startswith("I:")), None)
            if i:
                self.abilities[(int(i[0]), int(i[1]))] = name
        self.narsil_abilities = {n for n in read_entries(f"{silq}/ability.txt", r"^N:\d+:(.*)")}   # NarSil-Q has Sil-Q's whole tree
        self.of_flags = {m.group(1) for l in open("src/list-object-flags.h") for m in [re.match(r"OF\((\w+)", l)] if m}
        self.of_flags |= {"ENCHANTABLE", "THROWING", "AXE", "POLEARM", "TWO_HANDED", "MITHRIL", "DANGER", "SHARPNESS", "SHARPNESS2",
                          "AGGRAVATE", "FREE_ACT", "SEE_INVIS", "REGEN", "SLOW_DIGEST", "SPEED", "RADIANCE", "VAMPIRIC", "HAUNTED",
                          "DARKNESS", "HUNGER", "LIGHT", "SUST_STR", "SUST_DEX", "SUST_CON", "SUST_GRA", "NO_RANDOM", "CURSED"}
        self.dropped = {}

    def drop(self, owner, flag):
        self.dropped.setdefault(flag, []).append(owner)

    def sval_names(self, tval, lo, hi):
        """Object names of a tval with sval in lo..hi that NarSil-Q has; None means every one of that tval."""
        all_sv = sorted(sv for (tv, sv) in self.by_tval_sval if tv == tval)
        picked = [sv for sv in all_sv if lo <= sv <= hi]
        if picked == all_sv:
            return None
        return [self.by_tval_sval[(tval, sv)] for sv in picked if self.by_tval_sval[(tval, sv)] in self.narsil_objects]

    def flags(self, owner, flags, pval=None):
        """Sil-Q flags to NarSil's flags, values, slay and brand lines. pval is None for specials (magnitude 1)."""
        out = {"flags": [], "values": [], "slay": [], "brand": []}
        mag = 1 if pval is None else pval
        for f in flags:
            if f in PVAL_FLAGS:
                out["values"].append(f"{f}[{mag}]")
            elif f in NEG_FLAGS:
                out["values"].append(f"{NEG_FLAGS[f]}[{-mag}]")
            elif f in FLAG_TO_VALUES:
                out["values"].append(FLAG_TO_VALUES[f])
            elif f in FLAG_TO_FLAGS:
                out["flags"].append(FLAG_TO_FLAGS[f])
            elif f in FLAG_TO_SLAY:
                out["slay"].extend(FLAG_TO_SLAY[f].split())
            elif f in FLAG_TO_BRAND:
                out["brand"].append(FLAG_TO_BRAND[f])
            elif f == "EASY_KNOW":
                pass    # NarSil gets this from the object base
            elif f in self.of_flags:
                out["flags"].append(f)
            else:
                self.drop(owner, f)
        return out


def bare(name):
    """'& Pair~ of Boots' as item: and base-object: lines write it: 'Pair of Boots'."""
    return name.replace("& ", "", 1).replace("~", "")


def all_flags(lines):
    return [x.strip() for l in lines if l.startswith("F:") for x in l[2:].split("|") if x.strip()]


def abilities_of(tb, owner, lines):
    res = []
    for l in lines:
        if l.startswith("B:"):
            for pair in l[2:].split(":"):
                sk, ab = pair.strip().split("/")
                name = tb.abilities.get((int(sk), int(ab)))
                if name and name not in tb.narsil_abilities:
                    tb.drop(owner, f"ability {name} (not in NarSil-Q yet)")
                elif name:
                    res.append(f"ability:{SKILLS[int(sk)]}:{name}")
                else:
                    tb.drop(owner, f"ability {pair}")
    return res


def convert_special(tb, name, lines):
    w = next(l[2:].split(":") for l in lines if l.startswith("W:"))
    c = next(l[2:].split(":") for l in lines if l.startswith("C:"))
    out = [f"name:{name}", f"alloc:{w[1]}:{w[0]} to {w[2]}", f"cost:{w[3]}", f"max-attack:{c[0]}", f"dam-dice:{c[1]}",
           f"dam-sides:{c[2]}", f"max-evasion:{c[3]}", f"prot-dice:{c[4]}", f"prot-sides:{c[5]}", f"max-pval:{c[6]}"]
    for l in lines:
        if l.startswith("T:"):
            tv, lo, hi = (int(x) for x in l[2:].split(":")[:3])
            ty = tb.type_of_tval.get(tv)
            if not ty:
                tb.drop(name, f"tval {tv}")
                continue
            names = tb.sval_names(tv, lo, hi)
            out += [f"type:{ty}"] if names is None else [f"item:{ty}:{bare(n)}" for n in names]
    f = tb.flags(name, all_flags(lines))
    for k in ("flags", "values", "slay", "brand"):
        if f[k]:
            out += [f"{k}:{' | '.join(f[k])}"] if k in ("flags", "values") else [f"{k}:{x}" for x in f[k]]
    return out + abilities_of(tb, name, lines)


# Sil-Q's first artefacts are its "special" ones (limits.txt M:A), made only by the special-artefact roll; NarSil-Q
# marks them by an INSTA_ART base kind. A later artefact with INSTA_ART is never made at random: NO_RANDOM.
SILQ_SPECIAL_ARTEFACTS = 20

# Bases Sil-Q's object.txt gives no name NarSil-Q has: the Ring of Mairon is on a plain ring.
ARTEFACT_BASE = {"of Mairon": ("ring", "Ring")}

# Artefacts the converter once lost (a base it couldn't name, or a name Sil-Q uses twice): they follow the others,
# so that the artefacts NarSil-Q's savefiles already count keep their places.
ARTEFACTS_APPENDED = {"of Mairon"}


def convert_artefact(tb, name, lines, index=None):
    i = next((l[2:].split(":") for l in lines if l.startswith("I:")), None)
    w = next(l[2:].split(":") for l in lines if l.startswith("W:"))
    p = next((l[2:].split(":") for l in lines if l.startswith("P:")), ["0", "0d0", "0", "0d0"])
    g = next((l[2:].split(":") for l in lines if l.startswith("G:")), None)
    base = tb.by_tval_sval.get((int(i[0]), int(i[1]))) if i else None
    ty = tb.type_of_tval.get(int(i[0])) if i else None
    if name in ARTEFACT_BASE:
        ty, base = ARTEFACT_BASE[name]
        base = "& " + base + "~"
    if not base or not ty or base not in tb.narsil_objects:
        tb.drop(name, f"base object {base or i}")
        return None
    out = [f"name:{name}"]
    if g:
        out.append(f"color:{COLOR_MAP.get(g[1], g[1])}")
    out += [f"base-object:{ty}:{bare(base)}", f"depth:{w[0]}", f"rarity:{w[1]}", f"weight:{w[2]}", f"cost:{w[3]}",
            f"attack:{int(p[0])}:{p[1]}", f"defence:{int(p[2])}:{p[3]}"]
    flags = all_flags(lines)
    if "INSTA_ART" in flags:
        flags = [x for x in flags if x != "INSTA_ART"]
        if index is not None and index >= SILQ_SPECIAL_ARTEFACTS:
            flags.append("NO_RANDOM")
    f = tb.flags(name, flags, int(i[2]) if len(i) > 2 else 0)
    for k in ("flags", "values", "slay", "brand"):
        if f[k]:
            out += [f"{k}:{' | '.join(f[k])}"] if k in ("flags", "values") else [f"{k}:{x}" for x in f[k]]
    return out + abilities_of(tb, name, lines) + [l.replace("D:", "desc:", 1) for l in lines if l.startswith("D:")]


def convert_items(kind, silq, gamedata, new_objects):
    tb = Tables(silq, gamedata, new_objects)
    entries = read_entries(f"{silq}/{kind}.txt", r"^N:\d+:(.*)")
    conv = convert_special if kind == "special" else convert_artefact
    head = open(f"{gamedata}/{kind}.txt").read().split("\n")
    first = next(i for i, l in enumerate(head) if l.startswith("name:"))
    keep_from = next((i for i in range(first - 1, -1, -1) if not head[i].startswith("#") and head[i].strip()), -1) + 1
    print("\n".join(head[:keep_from]).rstrip() + "\n")
    narsil = read_entries(f"{gamedata}/{kind}.txt", r"^name:(.*)")
    index = {n: [] for n in entries}  # every Sil-Q index for a name (Sil-Q has two "of Maedhros")
    every = []
    for m in re.finditer(r"^N:(\d+):(.*)$", open(f"{silq}/{kind}.txt", errors="replace").read(), re.M):
        index.setdefault(m.group(2).strip(), []).append(int(m.group(1)))
    for n, lines in read_entries_all(f"{silq}/{kind}.txt", r"^N:(\d+):(.*)"):
        every.append((n, lines))
    appended = []

    def one(n, lines, idx):
        res = conv(tb, n, lines) if kind == "special" else conv(tb, n, lines, idx)
        if not res and n in narsil:     # Sil-Q's base object is one NarSil-Q lacks: keep NarSil's own entry
            res = [f"name:{n}"] + [l for l in narsil[n]]
            print(f"{kind}: {n} kept as NarSil has it", file=sys.stderr)
        return res

    for n, lines in entries.items():
        if not any(l.startswith("W:") for l in lines):
            continue
        res = one(n, lines, index[n][-1])
        if res and n in ARTEFACTS_APPENDED and kind == "artefact":
            appended.append(res)
        elif res:
            print("\n".join(res) + "\n")
    if kind == "artefact":
        # A name Sil-Q gives two artefacts: the one entries kept above is the last; the earlier ones follow here
        for (idx, n), lines in every:
            if len(index[n]) > 1 and idx != index[n][-1] and any(l.startswith("W:") for l in lines):
                res = conv(tb, n, lines, idx)  # only what converts: Morgoth's crowns are NarSil-Q's own
                if res:
                    appended.append(res)
        for res in appended:
            print("\n".join(res) + "\n")
    for flag, owners in sorted(tb.dropped.items()):
        print(f"{kind}: dropped {flag}: {', '.join(owners[:6])}{' ...' if len(owners) > 6 else ''} ({len(owners)})", file=sys.stderr)


def convert_ability(silq, gamedata, new_objects):
    """Sil-Q's whole ability tree in NarSil's format: skills in order, abilities by Sil-Q's number within each."""
    tb = Tables(silq, gamedata, new_objects)
    entries = read_entries(f"{silq}/ability.txt", r"^N:\d+:(.*)")
    by_skill = {i: [] for i in range(len(SKILLS))}
    for name, lines in entries.items():
        i = next((l[2:].split(":") for l in lines if l.startswith("I:")), None)
        if i:
            by_skill[int(i[0])].append((int(i[1]), name, int(i[2]), lines))
    head = open(f"{gamedata}/ability.txt").read().split("\n")
    first = next(i for i, l in enumerate(head) if l.startswith("# Melee"))
    print("\n".join(head[:first]).rstrip() + "\n")
    for sk, items in by_skill.items():
        print(f"# {SKILLS[sk]} ---------------------------------------------\n\nskill:{SKILLS[sk]}\n")
        for _, name, level, lines in sorted(items):
            out = [f"name:{name}", f"level:{level}"]
            for l in lines:
                if l.startswith("P:"):
                    for pair in l[2:].split(":"):
                        psk, pab = pair.strip().split("/")
                        pname = tb.abilities.get((int(psk), int(pab)))
                        if pname:
                            out.append(f"prerequisite:{SKILLS[int(psk)]}:{pname}")
            out += [l.replace("D:", "desc:", 1) for l in lines if l.startswith("D:")]
            for l in lines:
                if l.startswith("T:"):
                    tv, lo, hi = (int(x) for x in l.split("#")[0][2:].split(":")[:3])
                    ty = tb.type_of_tval.get(tv)
                    names = tb.sval_names(tv, lo, hi)
                    if ty:
                        out += [f"type:{ty}"] if names is None else [f"item:{ty}:{bare(n)}" for n in names]
            print("\n".join(out) + "\n")


VAULT_TYPES = {"6": "Interesting room", "7": "Lesser vault", "8": "Greater vault", "9": "Throne room", "10": "Gates of Angband"}


def convert_vault(silq, gamedata):
    """Sil-Q's vault.txt in NarSil's layout (the vault symbols are the same, plus , z and Z)."""
    entries = read_entries(f"{silq}/vault.txt", r"^N:\d+:(.*)")
    head = open(f"{gamedata}/vault.txt").read().split("\n")
    first = next(i for i, l in enumerate(head) if l.startswith("name:"))
    keep = next((i for i in range(first - 1, -1, -1) if not head[i].startswith("#") and head[i].strip()), -1) + 1
    print("\n".join(head[:keep]).rstrip() + "\n")
    raw = open(f"{silq}/vault.txt", errors="replace").read().split("\n")
    cur, rows = None, OrderedDict()
    for line in raw:                      # D: lines can be all spaces or start with '#', so read them raw
        m = re.match(r"^N:\d+:(.*)", line)
        if m:
            cur = m.group(1).strip()
            rows[cur] = {"X": None, "F": [], "D": []}
        elif cur and line.startswith("X:"):
            rows[cur]["X"] = line[2:].split(":")
        elif cur and line.startswith("F:"):
            rows[cur]["F"] += [f.strip() for f in line[2:].split("|") if f.strip()]
        elif cur and line.startswith("D:"):
            rows[cur]["D"].append(line[2:])
    for name, v in rows.items():
        if not v["X"] or not v["D"]:
            continue
        ty, depth, rarity = v["X"][:3]
        print(f"name:{name}\ntype:{VAULT_TYPES.get(ty, ty)}\ndepth:{depth}\nrarity:{rarity}")
        if v["F"]:
            print("flags:" + " | ".join(v["F"]))
        print("\n".join("D:" + d for d in v["D"]) + "\n")


def main():
    if len(sys.argv) == 4 and sys.argv[1] == "vault":
        convert_vault(sys.argv[2], sys.argv[3])
        return
    if len(sys.argv) == 5 and sys.argv[1] == "ability":
        convert_ability(sys.argv[2], sys.argv[3], sys.argv[4])
        return
    if len(sys.argv) == 5 and sys.argv[1] in ("special", "artefact"):
        convert_items(sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4])
        return
    if len(sys.argv) == 5 and sys.argv[1] == "object":
        print(patch_objects(sys.argv[2] + "/object.txt", sys.argv[3] + "/object.txt", sys.argv[4]), end="")
        return
    if len(sys.argv) == 5 and sys.argv[1] == "tiles":
        print(tiles(sys.argv[2], sys.argv[3], sys.argv[4]), end="")
        return
    if len(sys.argv) == 4 and sys.argv[1] == "flavours":
        print(flavours(sys.argv[2], sys.argv[3]), end="")
        return
    if len(sys.argv) == 5 and sys.argv[1] == "sheet":
        sheet(sys.argv[2], sys.argv[3], sys.argv[4])
        return
    if len(sys.argv) < 4 or sys.argv[1] != "monster":
        sys.exit(__doc__)
    silq, gamedata, diff = sys.argv[2], sys.argv[3], "--diff" in sys.argv
    bases = base_by_glyph(gamedata)
    sq = read_entries(f"{silq}/monster.txt", r"^N:\d+:(.*)")
    ns = read_entries(f"{gamedata}/monster.txt", r"^name:(.*)")
    converted = OrderedDict((n, convert(n, l, bases, ns.get(n))) for n, l in sq.items() if n != "<player>" and any(x.startswith("W:") for x in l))
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
