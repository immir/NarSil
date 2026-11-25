#!/usr/bin/env bash
# Builds the bridge for the host (ASan+UBSan; TSan with TSAN=1; MSan with
# MSAN=1, which needs clang) and runs
# scripted end-to-end scenarios against the real game data in ../lib.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
root=$(dirname "$here")
if [ -z "${WORK:-}" ]; then work=$(mktemp -d); trap 'rm -rf "$work"' EXIT; else work=$WORK; fi
san=${TSAN:+thread}
san=${san:-${MSAN:+memory}}
san=${san:-address,undefined}
build="$work/build-$san"
cc=${MSAN:+clang}

cmake -S "$here/../bridge" -B "$build" -DCMAKE_BUILD_TYPE=Debug ${cc:+-DCMAKE_C_COMPILER=$cc} \
    -DCMAKE_C_FLAGS="-fsanitize=$san -fno-omit-frame-pointer -g" >/dev/null
cmake --build "$build" -j"$(nproc)" >/dev/null

# GAME=silq runs everything on NarSil-Q's data: lib with lib-silq over it.
game=${GAME:-narsil}
case "$game" in
    narsil) lib="$root/lib" ;;
    silq) lib="$work/lib-silq"; "$root/tools/merge-lib.sh" "$root/lib" "$root/lib-silq" "$lib" ;;
    *) echo "GAME is narsil or silq" >&2; exit 2 ;;
esac
run() { NARSIL_SEED=${NARSIL_SEED:-7} timeout 120 "$build/narsil_host" "$lib" "$work/data" "$@"; }
# Fails the test if the command succeeds ("! cmd" would not: set -e ignores it).
refute() { if "$@"; then echo "unexpectedly true: $*" >&2; exit 1; fi; }
# Whether the nth "context=" flags in $out include the mask.
ctx_has() { (( $(echo "$out" | grep -o 'context=0x[0-9a-f]*' | sed -n "$1p" | cut -d= -f2) & $2 )); }
# The native birth screens: race, house, sex (first of each), accept stats and
# skills, keep the name, accept history, begin; dismiss the opening text.
birth=(@answer=0 @answer=0 @answer=0 @answer=0 @answer=2 @answer=2 @text= @answer=0 @answer=0 @esc)
# Quit from the command prompt; the Escs first dismiss any -more- left on screen.
quit=(@esc @esc ^x @enter @esc)

rm -rf "$work/data"
echo "== tiles on before the game starts (as the apps set them): startup and birth publish without touching unloaded data"
rm -rf "$work/data"
NARSIL_TILES=1 run "${birth[@]}" "${quit[@]}" | tail -1 | grep -q '^state=2 '
echo "== empty message log at startup is published cleanly (MSan catches the old bug)"
rm -rf "$work/data"
run @messages | grep -q '^messages:'

echo "== a native crash leaves crash.txt, which the next start reports once"
rm -rf "$work/data"
NARSIL_CRASH_TEST=1 run @crash >/dev/null 2>&1 && { echo "crash did not kill the host"; exit 1; }
grep -q '^NarSil native crash: signal 11' "$work/data/crash.txt"
grep -q '^  #0 ' "$work/data/crash.txt"
out=$(run @crashreport)
echo "$out" | grep -q '^crashreport: yes'
echo "$out" | grep -q 'signal 11 (SEGV)'
run @crashreport | grep -q '^crashreport: none'
rm -rf "$work/data"

echo "== fuzz: random keys, taps, actions and answers (two seeds) never hang or trip a sanitizer"
for seed in 1 2; do
    rm -rf "$work/data"
    run "${birth[@]}" "@fuzz=$seed,${FUZZ_STEPS:-1500}" >/dev/null 2>"$work/fuzz.err" || { tail -30 "$work/fuzz.err"; exit 1; }
    refute grep -qE 'runtime error|AddressSanitizer|hung at' "$work/fuzz.err"
done

echo "== data: every monster has a base template, every artefact drop and engine-named monster exists"
python3 - "$lib" <<'PY'
import re, sys
lib = sys.argv[1]
mon = open(f"{lib}/gamedata/monster.txt").read()
entries = re.split(r"\n(?=name:)", mon)[1:]
names = {e.split("\n")[0][5:].strip() for e in entries}
bare = [e.split("\n")[0] for e in entries if "\nbase:" not in e and not e.startswith("name:<player>")]
assert not bare, f"monsters without a base: {bare}"
need = ["Morgoth, Lord of Darkness", "Carcharoth", "Oathwraith", "Silent watcher", "Easterling spy", "Orc champion", "Orc captain",
        "Cat warrior", "Cat assassin", "Troll guard", "Barrow wight", "Young cold-drake", "Young fire-drake", "Aldor", "Glaurung",
        "Gothmog", "Ungoliant", "Gorthaur", "Spider hatchling"]
missing = [n for n in need if not any(x == n or x.startswith(n + ",") for x in names)]
assert not missing, f"monsters the engine looks up are missing: {missing}"
art = set(re.findall(r"^name:(.*)$", open(f"{lib}/gamedata/artefact.txt").read(), re.M))
drops = set(re.findall(r"^drop-artifact:(.*)$", mon, re.M))
assert drops <= art, f"drop-artifact names missing: {drops - art}"
PY

echo "== fuzz at depth: the deeper monsters (spells, breaths, rallies, webs) never trip a sanitizer"
for depth in 6 14; do
    rm -rf "$work/data"
    run "${birth[@]}" "@depth=$depth" @act=1 @esc "@fuzz=$depth,${FUZZ_STEPS:-1500}" >/dev/null 2>"$work/fuzz.err" || { tail -30 "$work/fuzz.err"; exit 1; }
    refute grep -qE 'runtime error|AddressSanitizer|hung at' "$work/fuzz.err"
done

if [ "$game" = narsil ]; then
echo "== NarSil unchanged: a seeded game matches the recorded baseline (UPDATE_BASELINE=1 re-records it)"
rm -rf "$work/data"
baseline_run=("${birth[@]}" @map @walk=6 @walk=2 @walk=4 @run=8 @act=20 @info @look @messages @map @dump)
NARSIL_SEED=7 run "${baseline_run[@]}" 2>/dev/null > "$work/baseline.txt"
if [ -n "${UPDATE_BASELINE:-}" ]; then cp "$work/baseline.txt" "$here/baselines/narsil-seed7.txt"
else diff -u "$here/baselines/narsil-seed7.txt" "$work/baseline.txt" | head -40; cmp -s "$here/baselines/narsil-seed7.txt" "$work/baseline.txt"; fi
rm -rf "$work/data"
fi

if [ "$game" = silq ]; then
echo "== NarSil-Q: saves are tagged USLQ, and NarSil's data refuses them"
rm -rf "$work/data"
run "${birth[@]}" @save "${quit[@]}" >/dev/null 2>&1
head -c 8 "$work/data/save/Harness" | grep -q '^SaveUSLQ$'
timeout 120 "$build/narsil_host" "$root/lib" "$work/data" @answer=0 @esc @esc 2>/dev/null | tail -1 | grep -q 'exit="Broken savefile"'
rm -rf "$work/data"

echo "== NarSil-Q: a guaranteed forge on the first level at or below 100', 300' and 500', three in all"
out=$(run "${birth[@]}" @depth=2 @act=1 @esc @forges @depth=3 @act=1 @esc @forges @depth=6 @act=1 @esc @forges \
      @depth=10 @act=1 @esc @forges @depth=12 @act=1 @esc @forges "${quit[@]}" 2>/dev/null)
echo "$out" | grep '^forges' | awk '{print $4}' | tr '\n' ' ' | grep -qx '1 1 2 3 3 '
echo "$out" | grep -E '^forges [0-9]+ fixed [123] depth (2|6|10)$' | awk '$2 < 1 {bad=1} END {exit bad}'
rm -rf "$work/data"
fi

echo "== bridge constants: narsil_bridge.h and NarSilNative.kt agree"
python3 "$here/check-bridge-constants.py"

echo "== app version: narsil_bridge.h, Android versionName and both iOS plists agree"
appver=$(sed -n 's/^#define NB_APP_VERSION "\(.*\)"$/\1/p' "$root/bridge/narsil_bridge.h")
test -n "$appver"
grep -qx "        versionName = \"$appver\"" "$here/app/build.gradle.kts"
grep -q "<key>CFBundleShortVersionString</key><string>$appver</string>" "$root/ios/xtool-Info.plist"
grep -qx "        CFBundleShortVersionString: \"$appver\"" "$root/ios/project.yml"

# The birth option "Choose the dungeon's seed" is toggled from the first birth screen's options (its answer is 1000 + the option's index).
seed_opt=$((1000 + $(awk 'index($0, "OP(birth_choose_seed,") == 1 {print n; exit} /^OP\(/ {n++}' "$root/src/list-options.h")))
choose_seed=(@answer=0 @answer=1001 @answer=$seed_opt @answer=-1)

echo "== seeded levels: the seed typed at birth, not the harness's own randomness, fixes the first level"
seeded_map() {
    rm -rf "$work/data"
    NARSIL_SEED=$1 run "${choose_seed[@]}" @answer=0 @answer=0 @answer=0 @answer=2 @answer=2 @text= "@text=$2" @answer=0 @answer=0 @esc @map 2>/dev/null
}
map_a=$(seeded_map 7 424242); map_b=$(seeded_map 99 424242); map_c=$(seeded_map 7 424243)
echo "$map_a" | grep -q '^map '
[ "$map_a" = "$map_b" ]
[ "$map_a" != "$map_c" ]
rm -rf "$work/data"

echo "== fixed experience (Sil-Q's birth option): 50,000 to spend at birth, and descending adds none"
fixed_opt=$((1000 + $(awk 'index($0, "OP(birth_fixed_exp,") == 1 {print n; exit} /^OP\(/ {n++}' "$root/src/list-options.h")))
spend=(@act=23 @prompt @answer=-1)
out=$(run @answer=0 @answer=1001 @answer=$fixed_opt @answer=-1 "${birth[@]:1}" "${spend[@]}" @depth=4 @act=1 @esc "${spend[@]}" "${quit[@]}" 2>/dev/null)
[ "$(echo "$out" | grep -cP '^Skills\t50000$')" = 2 ]
rm -rf "$work/data"
out=$(run "${birth[@]}" "${spend[@]}" @depth=4 @act=1 @esc "${spend[@]}" "${quit[@]}" 2>/dev/null)
[ "$(echo "$out" | grep -P '^Skills\t' | sed -n 1p)" = "$(printf 'Skills\t5000')" ]
[ "$(echo "$out" | grep -P '^Skills\t' | sed -n 2p | cut -f2)" -gt 5000 ]
rm -rf "$work/data"

eat=(); for _ in 1 2 3 4 5; do eat+=(E @prompt @pick=0); done
out=$(run "${birth[@]}" "${eat[@]}" @messages "${quit[@]}" 2>/dev/null)
if [ "$game" = silq ]; then
    echo "== NarSil-Q: an unsearched skeleton offers Search (0x20000); searching it finds something, once; full, you can still eat"
    echo "$out" | grep -q 'You are as full as you can be.'
    refute grep -q 'too full' <<<"$out"
    rm -rf "$work/data"
    out=$(run "${birth[@]}" @skeleton @ctx @act=36 @ctx @act=36 @prompt @messages "${quit[@]}" 2>/dev/null)
    ctx_has 1 0x20000
    refute ctx_has 2 0x20000
    [ "$(echo "$out" | grep -c 'You find something among the bones!')" = 1 ]
    echo "$out" | grep -qP '^1\t1\tYou have (a|an|\d+) .*\([a-z]\)\.$'
    echo "$out" | grep -qx 'prompt kind=0'
    echo "== NarSil-Q: Fletch is an action on plain arrows once you have Fletchery (not a bar button)"
    rm -rf "$work/data"
    archer=(@answer=0 @answer=1001 @answer=$fixed_opt @answer=-1 "${birth[@]:1:4}" @answer=101 @answer=101 @answer=101 "${birth[@]:5}")
    out=$(run "${archer[@]}" @arrows @act=15 @answer=1002 @prompt @answer=-1 @act=21 @prompt @answer=-1 "${quit[@]}" 2>/dev/null)
    refute grep -qP '^#A\t1\tFletch$' <<<"$out"
    fletchery=$(echo "$out" | awk -F'\t' '/^[0-9]\t[0-9]\t/ { if ($6 == "Fletchery") { print n; exit } n++ }')
    rm -rf "$work/data"
    fletch_all=(@arrows @act=21 @answer=$fletchery @answer=1 @answer=-1 @act=15 @answer=1002 @prompt @answer=100204 @prompt @answer=-1 @messages)
    out=$(NARSIL_SEED=2 run "${archer[@]}" "${fletch_all[@]}" "${quit[@]}" 2>/dev/null)  # a seed where nothing turns up meanwhile
    echo "$out" | grep -qP '^#A\t1\tFletch$'
    echo "$out" | grep -qP '^1\t20\tYou straighten and adjust the feathering of an arrow\.$'  # an arrow a turn, all twenty (the count is the repeats)
    echo "== NarSil-Q: fletching stops when something disturbs (a Mewlip comes down the stairs): the rest of the stack is left plain"
    rm -rf "$work/data"
    out=$(run "${archer[@]}" "${fletch_all[@]}" "${quit[@]}" 2>/dev/null)
    echo "$out" | grep -q 'The Mewlip comes down the stair!'
    echo "$out" | grep -qP '^1\t([1-9]|1[0-9])\tYou straighten and adjust the feathering of an arrow\.$'
else
    echo "== NarSil: gorged, you cannot eat more; a skeleton is junk"
    echo "$out" | grep -q 'You are too full to eat it.'
    rm -rf "$work/data"
    out=$(run "${birth[@]}" @skeleton @ctx "${quit[@]}" 2>/dev/null)
    refute ctx_has 1 0x20000
fi
rm -rf "$work/data"

echo "== new game; save requested during birth is deferred to first command prompt"
run @answer=0 @save-async "${birth[@]:1}" @expect=Harness 6 4 @save ^x @enter @esc | tail -1 \
    | grep -qx 'state=2 exit="" saves=2'
test -s "$work/data/save/Harness"

echo "== reload the savefile"
run @answer=0 @expect=Harness @expect=50\' ^x @enter @esc | tail -1 | grep -qx 'state=2 exit="" saves=0'

echo "== touch: tapping a line of a text menu (the command menu) selects it"
rm -rf "$work/data"
run "${birth[@]}" @enter @tap=22,8 @expect='Full dungeon map' | tail -1 | grep -q 'state=1'

echo "== native map, path preview and travel"
rm -rf "$work/data"
out=$(run "${birth[@]}" @map @preview@1,0 @travel@1,0 @map "${quit[@]}")
echo "$out" | grep -q 'mode=1 prompt=1'
echo "$out" | grep -qE '^travel -> '
echo "$out" | tail -1 | grep -qx 'state=2 exit="" saves=0'

echo "== native item prompt: browse + cancel, then eat via the prompt"
rm -rf "$work/data"
out=$(run "${birth[@]}" i @prompt @pick=-1 E @prompt @pick=0 @messages "${quit[@]}")
echo "$out" | grep -q 'Lembas'
echo "$out" | grep -q 'renewed energy'
echo "$out" | grep -q 'Eat which food'
echo "$out" | tail -1 | grep -qx 'state=2 exit="" saves=0'

echo "== 'i' opens the panel's Pack tab (eat from it), yes/no (retire? no), throw (first item) + aim east"
rm -rf "$work/data"
out=$(run "${birth[@]}" i @prompt @answer=1000 @answer=100000 @prompt @answer=-1 @messages \
      Q @prompt @answer=0 t @prompt @pick=0 @prompt @answer=6 @messages "${quit[@]}")
echo "$out" | grep -q 'prompt kind=13'
echo "$out" | grep -q 'renewed energy'
echo "$out" | grep -q 'Do you really want to retire'
echo "$out" | grep -q 'prompt kind=4'
echo "$out" | tail -1 | grep -qx 'state=2 exit="" saves=0'

# Where the missile lands depends on the random level (a wall beside the
# player legitimately stops it), so only the flow is asserted here.
echo "== aiming beyond range completes (aims that way rather than at the player)"
rm -rf "$work/data"
out=$(run "${birth[@]}" t @pick=0 @aim@-9,0 @messages "${quit[@]}")
echo "$out" | tail -1 | grep -qx 'state=2 exit="" saves=0'

echo "== autosave on first prompt; stealth action toggles; close opens the direction prompt"
rm -rf "$work/data"
out=$(run "${birth[@]}" @ctx @act=5 @ctx @act=10 @prompt @answer=-1 @act=0 @act=1 2>&1)
test -s "$work/data/save/Harness"
echo "$out" | grep -q 'autosave: done'
[ "$(echo "$out" | grep -o 'context=0x[0-9a-f]*' | sed -n 2p)" != "$(echo "$out" | grep -o 'context=0x[0-9a-f]*' | sed -n 1p)" ]

echo "== a chest underfoot offers Open (flag 0x4000); a known-trapped one also offers Disarm (0x400)"
rm -rf "$work/data"
out=$(run "${birth[@]}" @ctx @chest @ctx "${quit[@]}")
ctx=($(echo "$out" | grep -o 'context=0x[0-9a-f]*' | sed 's/context=//'))
[ $(( ctx[0] & 0x4000 )) -eq 0 ] && [ $(( ctx[1] & 0x4000 )) -ne 0 ] && [ $(( ctx[1] & 0x400 )) -eq 0 ]
rm -rf "$work/data"
out=$(run "${birth[@]}" @trapchest @ctx "${quit[@]}")
ctx=($(echo "$out" | grep -o 'context=0x[0-9a-f]*' | sed 's/context=//'))
[ $(( ctx[0] & 0x4000 )) -ne 0 ] && [ $(( ctx[0] & 0x400 )) -ne 0 ]

echo "== Open beside two chests asks which way, naming just the chests (the d-pad lights those keys)"
rm -rf "$work/data"
# west then back south-east (NarSil-Q's first room: west then north-east), each start a chest behind
if [ "$game" = silq ]; then back=9 want="1 chest 2 chest "; else back=3 want="7 chest 8 chest "; fi
out=$(run "${birth[@]}" @chest @walk=4 @chest @walk=$back @act=9 @prompt @answer=-1 "${quit[@]}" 2>/dev/null)
echo "$out" | grep -qx 'walk 4: moved 1' && echo "$out" | grep -qx "walk $back: moved 1"
[ "$(echo "$out" | grep -P '^[1-9]\t[0-9]+\t[0-9]+\t' | cut -f1,4 | tr '\t\n' ' ')" = "$want" ]

echo "== a pile underfoot is one line in the message log, not a screen waiting for a key"
rm -rf "$work/data"
out=$(run "${birth[@]}" @pile @act=1 @messages "${quit[@]}")
echo "$out" | grep -qP 'You see .*, .*, .* and 1 more\.'

echo "== special items underfoot are marked in the message (ego name, and {??} for properties still to find) and flagged for the action bar"
rm -rf "$work/data"
out=$(run "${birth[@]}" @specials @act=1 @messages @ctx "${quit[@]}")
echo "$out" | grep -qP "You see a Shortsword \x013\{\?\?\}\x02 and a Shortsword \x011of Hador's House\x02"
ctx=($(echo "$out" | grep -o 'context=0x[0-9a-f]*' | sed 's/context=//'))
[ $(( ctx[0] & 0x10000 )) -ne 0 ] && [ $(( ctx[0] & 0x4 )) -ne 0 ]

echo "== stepping onto one item with a property still to find says so in the message ({??}), before the game's update has touched it"
rm -rf "$work/data"
out=$(run "${birth[@]}" @sword @walk=4 @messages @ctx "${quit[@]}" 2>/dev/null)
echo "$out" | grep -qP "You see a Shortsword .*\x013\{\?\?\}\x02"
ctx=($(echo "$out" | grep -o 'context=0x[0-9a-f]*' | sed 's/context=//'))
[ $(( ctx[0] & 0x10000 )) -ne 0 ]

echo "== long-press on Pick up opens the pack at the floor section (focus 2)"
rm -rf "$work/data"
out=$(run "${birth[@]}" @specials @act=1 @esc @esc @act=33 @prompt @answer=-1 "${quit[@]}")
echo "$out" | grep -qP '^Pack\t-1\t2\t'

echo "== explore goes to the nearest seen item and stops on it; later it stops for a monster in view (not for one already seen); one step stops after one leg"
# which level has what is up to the game's generation: NarSil-Q's differs, so its seed 7 has no item in view at 100 ft
explore_seed=$([ "$game" = silq ] && echo 9 || echo 7)
rm -rf "$work/data"
out=$(NARSIL_SEED=$explore_seed run "${birth[@]}" @depth=2 @act=1 @esc @act=34 @esc @messages "${quit[@]}" 2>/dev/null)
echo "$out" | grep -q 'You stop: there is something here\.'
rm -rf "$work/data"
explore_runs=(); for _ in $(seq 1 14); do explore_runs+=(@act=34 @esc); done
out=$(NARSIL_SEED=$explore_seed run "${birth[@]}" @depth=1 @act=1 @esc "${explore_runs[@]}" @messages "${quit[@]}" 2>/dev/null)
echo "$out" | grep -qE 'You stop: you see an? '
! echo "$out" | grep -q 'something disturbed' || true
rm -rf "$work/data"
out=$(NARSIL_SEED=$explore_seed run "${birth[@]}" @depth=2 @act=1 @esc @act=35 @esc @messages "${quit[@]}" 2>/dev/null)
echo "$out" | grep -q 'You stop: there is something here\.'

echo "== bolts, missiles, blasts and hits are laid over the published map step by step (the engine's terminal drawing never reaches the apps)"
rm -rf "$work/data"
out=$(run "${birth[@]}" @depth=1 @act=1 @esc @anim "${quit[@]}" 2>/dev/null)
frames=$(echo "$out" | sed -n 's/^animframes=//p')
[ "$frames" -ge 8 ]

echo "== the status area's data: a lit torch has turns left; the monsters-only look list has its own header"
rm -rf "$work/data"
out=$(run "${birth[@]}" @ctx @lookm "${quit[@]}")
echo "$out" | grep -qE '^threats=0 light=([0-9]+|-1) hp=[0-9]+/[0-9]+$'
echo "$out" | grep -q '^Monsters$'

echo "== look list and inspect are answered, read-only"
rm -rf "$work/data"
out=$(run "${birth[@]}" @look @inspect@0,0 "${quit[@]}")
echo "$out" | grep -q '^Look'
echo "$out" | grep -qP '^Inspect\t'
echo "$out" | tail -1 | grep -qx 'state=2 exit="" saves=0'

echo "== spend experience: buy, buy, sell, accept persists; cancel undoes"
rm -rf "$work/data"
out=$(run "${birth[@]}" @act=23 @answer=100 @answer=100 @answer=200 @answer=2 @answer=-1 @act=23 @prompt @answer=-1 "${quit[@]}")
echo "$out" | grep -qP '^Skills\t4900$'
echo "$out" | grep -qP '^Melee\t1\t'
echo "$out" | tail -1 | grep -qx 'state=2 exit="" saves=0'

if [ "$game" = narsil ]; then
    echo "== abilities: learn one (confirmed by the native check), then switch it off"
    rm -rf "$work/data"
    # the first Archery ability is the row after the last Melee one, whichever game's tree this is
    first_archery=$(run "${birth[@]}" @act=23 @answer=101 @answer=2 @answer=-1 @act=21 @prompt @answer=-1 "${quit[@]}" 2>/dev/null |
        awk -F'\t' '$1 ~ /^[0-9]+$/ && $2 == "0" && NF > 5 { if ($1 == 1 && !done) { print n; done = 1 } n++ }')
    rm -rf "$work/data"
    out=$(run "${birth[@]}" @act=23 @answer=101 @answer=2 @answer=-1 @act=21 @answer="$first_archery" @answer=1 @prompt @answer="$first_archery" @prompt @answer=-1 "${quit[@]}")
    echo "$out" | grep -qP '^1\t2\t1\t\d+\t\t[A-Z][^\t]*\t'
    echo "$out" | grep -qP '^1\t3\t1\t\d+\t\t[A-Z][^\t]*\t'
    echo "$out" | tail -1 | grep -qx 'state=2 exit="" saves=0'
else
    echo "== abilities: NarSil-Q lists Sil-Q's tree"
    rm -rf "$work/data"
    out=$(run "${birth[@]}" @act=23 @answer=101 @answer=2 @answer=-1 @act=21 @prompt @answer=-1 "${quit[@]}")
    for ability in "Quick Study" "Rout" "Indomitable" "Song of Thresholds" "Oath" "Smite"; do
        echo "$out" | grep -qP "\t$ability\t"
    done
fi

echo "== character sheet: the panel's Character tab, as sections"
rm -rf "$work/data"
out=$(run "${birth[@]}" @act=20 @prompt @answer=-1 "${quit[@]}")
echo "$out" | grep -qx 'Character'
echo "$out" | grep -qP '^#S\tSkills$'
echo "$out" | grep -qP '^Strength\t-?\d+\t\d+\t'
echo "$out" | tail -1 | grep -qx 'state=2 exit="" saves=0'

echo "== smithing action opens the native smithing screen (exploration mode away from a forge)"
rm -rf "$work/data"
out=$(run "${birth[@]}" @act=25 @prompt @answer=-1 "${quit[@]}")
echo "$out" | grep -qP '^#N\tExploration mode'
echo "$out" | tail -1 | grep -qx 'state=2 exit="" saves=0'

echo "== native smithing: base item, numbers, artifice, naming; back out"
rm -rf "$work/data"
out=$(run "${birth[@]}" @act=25 @answer=0 @answer=0 @answer=0 @prompt @answer=2 @answer=16 @prompt @text=Brightfletch @prompt @answer=-1 @answer=-1 "${quit[@]}")
echo "$out" | grep -qP '^Smith\tmain\t'
echo "$out" | grep -qP '^#I\t24 Arrows'
echo "$out" | grep -qx 'prompt kind=10'
echo "$out" | grep -qP '^#O\t1\tName artefact\tBrightfletch$'
echo "$out" | tail -1 | grep -qx 'state=2 exit="" saves=0'

echo "== native birth: random house, buy a stat, back from skills to stats, then on"
rm -rf "$work/data"
out=$(run @answer=0 @answer=0 @answer=1000 @prompt @answer=0 @answer=100 @prompt @answer=2 @answer=-1 @prompt \
      @answer=2 @answer=2 @text= @answer=0 @answer=0 @esc "${quit[@]}")
echo "$out" | grep -qP '^Screen\tsex\tSex$'
echo "$out" | grep -qP '^#S\tStrength\t1\t1\t'
echo "$out" | grep -qP '^#P\t12\t13$'
echo "$out" | grep -qP '^#K\tMelee\t-?[0-9]+$'
echo "$out" | grep -qP '^#C\tDefence\t[+-][0-9]+$'
echo "$out" | tail -1 | grep -qx 'state=2 exit="" saves=0'

echo "== death screen (by retiring): character, then a new character goes straight to birth"
rm -rf "$work/data"
out=$(run "${birth[@]}" Q @answer=1 @answer=1 @enter @prompt @answer=0 @answer=-1 @answer=5 @answer=1 @prompt)
echo "$out" | grep -qP '^Screen\tdeath\tYou have retired$'
echo "$out" | grep -qP '^Screen\trace\tRace$'

echo "== death screen (slain): -more-, then the death screen, then a new character"
rm -rf "$work/data"
out=$(run "${birth[@]}" @die @enter @messages @prompt @answer=5 @answer=1 @prompt @messages)
echo "$out" | grep -qP '^Screen\tdeath\tYou have been slain$'
echo "$out" | grep -qP '^Screen\trace\tRace$'
echo "== ...and the new character starts with an empty message log"
echo "$out" | awk '/^messages:/{n++} n==2' | sed -n 2p | grep -q '^state='  # nothing between the header and the final state line

echo "== saved builds: Save build only at turn 0 (0x40000); the build is kept and bought again for a new character"
rm -rf "$work/data"
out=$(run "${birth[@]}" @ctx @run=6 @run=4 @ctx 2>/dev/null)
ctx_has 1 0x40000
refute ctx_has 2 0x40000
rm -rf "$work/data"
out=$(run "${birth[@]}" @act=23 @answer=100 @answer=100 @answer=2 @answer=-1 @act=21 @answer=0 @answer=1 @answer=-1 @act=20 @answer=4 @text=Fighter @answer=-1 @messages @die @enter @prompt @answer=5 @prompt @answer=1 @prompt @answer=1 @answer=1 @answer=0 @answer=0 @esc @ctx @act=21 @prompt @answer=-1 2>&1)
grep -qx 'build=Fighter' "$work/data/user/builds.txt"
grep -qx 'skills=2 0 0 0 0 0 0 0' "$work/data/user/builds.txt"
grep -qP '^ability=\d+:\S' "$work/data/user/builds.txt"
echo "$out" | grep -qP '^#O\t1\tFighter\t.* · 1 ability · \d+ experience\t'
echo "== ...the same character comes back: the skills and the ability are bought"
echo "$out" | grep -qP '^0\t2\t1\t\d+\t\t[A-Z]'
echo "== ...a build with more purchases than the command queue holds (20) comes back whole"
rm -rf "$work/data"; mkdir -p "$work/data/user"
race=$(echo "$out" | grep -oP '^#O\t1\tFighter\t\K[^·]*?(?= of )'); house=$(echo "$out" | grep -oP '^#O\t1\tFighter\t[^·]*? of \K[^·]*(?= · )')
printf 'build=Big\nrace=%s\nhouse=%s\nsex=Male\nstats=0 0 0 0\nskills=3 3 3 3 2 2 2 2\noath=0\nfixed=0\n' "$race" "$house" > "$work/data/user/builds.txt"
out=$(run @answer=0 @answer=1 @text= @answer=0 @answer=0 @esc @act=21 @prompt @answer=-1 2>&1)
refute grep -q 'builds-note' <<<"$out"
echo "$out" | grep -qP '^Melee/3/\d+\tArchery/3/\d+\tEvasion/3/\d+\tStealth/3/\d+\tPerception/2/\d+\tWill/2/\d+\tSmithing/2/\d+\tSong/2/\d+$'
echo "== ...and a build can be deleted"
rm -rf "$work/data"
out=$(run "${birth[@]}" @act=20 @answer=4 @text=Fighter @answer=-1 @messages @die @enter @prompt @answer=5 @prompt @answer=1 @prompt @answer=2 @prompt @answer=0 @prompt 2>&1)
echo "$out" | grep -q '^Screen	builds-delete	Delete a saved build'
refute grep -q '^build=' "$work/data/user/builds.txt"
rm -rf "$work/data"

echo "== resting (the d-pad's held centre) heals to full; the resting flag (0x80000) is clear once it ends"
rm -rf "$work/data"
out=$(NARSIL_SEED=2 run "${birth[@]}" @hurt @ctx @act=0 @ctx "${quit[@]}" 2>/dev/null)  # a seed where no monster interrupts the rest in either game
echo "$out" | grep -qP '^threats=\d+ light=-?\d+ hp=1/\d+$'
echo "$out" | grep -P '^threats=' | sed -n 2p | grep -qP ' hp=(\d+)/\1$'
refute ctx_has 2 0x80000
echo "== usage trace: a run reports its steps and why it stopped (NB_META_RUN_*)"
rm -rf "$work/data"
out=$(run "${birth[@]}" @runreport @run=2 @runreport @run=4 @runreport "${quit[@]}" 2>/dev/null)
echo "$out" | grep -qx 'runreport 0 0 0'
echo "$out" | grep -qP '^runreport [1-9]\d* [1-9]\d* [1-5]$'
echo "== combat forecast: off by default; with the option, inspecting a monster whose defence is known gives the odds"
around=(@inspect@0,1 @inspect@0,-1 @inspect@1,0 @inspect@-1,0 @inspect@1,1 @inspect@-1,1 @inspect@1,-1 @inspect@-1,-1)
rm -rf "$work/data"
forecast_opt=$(run "${birth[@]}" @act=26 @prompt @answer=-1 "${quit[@]}" 2>/dev/null | grep -P '^#O\t\d+\t[01]\t[01]\tShow combat forecasts' | cut -f2)
[ -n "$forecast_opt" ]
rm -rf "$work/data"
out=$(run "${birth[@]}" @orc "${around[@]}" "${quit[@]}" 2>/dev/null)
echo "$out" | grep -qP '^#M\tAn Orc soldier'
refute grep -q 'to hit (' <<<"$out"
rm -rf "$work/data"
out=$(run "${birth[@]}" @act=26 @answer="$forecast_opt" @answer=-1 @orc "${around[@]}" "${quit[@]}" 2>/dev/null)
echo "$out" | grep -qP '\d+%[\s\x04]*to hit \([+-]\d+ against [+-]\d+\), \S*\d+%[\s\x04]*to crit, \S*\d+\.\d[\s\x04]*damage an attack'
rm -rf "$work/data"

echo "== native options: toggle, number, locked birth page; birth options editable at birth"
rm -rf "$work/data"
out=$(run "${birth[@]}" @act=26 @prompt @answer=1019 @prompt @answer=10005 @prompt @answer=100 @prompt @answer=-1 @prompt @answer=-1 "${quit[@]}")
echo "$out" | grep -qP '^#O\t1019\t1\t0\tAlways pickup items'
echo "$out" | grep -qP '^#N\t10000\tHitpoint warning\t5\t'
echo "$out" | grep -qP '^#O\t1046\t0\t1\tDisconnected stairs'
rm -rf "$work/data"
out=$(run @answer=0 @answer=1001 @prompt @answer=1046 @prompt @answer=-1 @answer=0 @answer=0 @answer=0 @answer=2 @answer=2 @text= @answer=0 @answer=0 @esc "${quit[@]}")
echo "$out" | grep -qP '^#O\t1046\t1\t0\tDisconnected stairs'

echo "== native pack: expand an item, act on it, the pack reopens with the result"
rm -rf "$work/data"
out=$(run "${birth[@]}" @act=15 @prompt @answer=1000 @prompt @answer=100000 @prompt @answer=-1 "${quit[@]}")
echo "$out" | grep -qP '^#A\t1\tEat$'
echo "$out" | grep -qP '^#I\t0\ta\t[0-9]+\t[0-9]+\t[0-9]+\t0\t2 Fragments of Lembas$'

echo "== quick slots: pin the pack's expanded item, see its count, use one from the bar"
rm -rf "$work/data"
out=$(run "${birth[@]}" @act=15 @answer=1000 @pinexpanded @answer=-1 @quick @usequick @quick @messages "${quit[@]}")
echo "$out" | grep -qP '^\d+\t3\tFragment of Lembas$'
echo "$out" | grep -qP '^\d+\t2\tFragment of Lembas$'
echo "$out" | grep -q 'It fills you with renewed energy'

echo "== special items: tier on the pack line, and the name marked in the message"
rm -rf "$work/data"
out=$(run "${birth[@]}" @ego @messages @act=15 @prompt @answer=-1 "${quit[@]}")
echo "$out" | grep -qP "^#I\t\d\t.\t\d+\t\d+\t\d+\t1\ta Shortsword .*of Hador's House"
echo "$out" | grep -qP "You have .*\x011of Hador's House\x02"

echo "== a known ego says in general what it gives, only with the option on"
rm -rf "$work/data"
out=$(run "${birth[@]}" @ego @act=15 @answer=1002 @prompt @answer=-1 "${quit[@]}")
refute grep -q 'In general, items' <<<"$out"
rm -rf "$work/data"
out=$(run "${birth[@]}" @details=1 @ego @act=15 @answer=1002 @prompt @answer=-1 "${quit[@]}")
echo "$out" | grep -qP "^#D\tIn general, items of Hador's House have:"
echo "$out" | grep -qP '^#D\tA random bonus of 1 to the sides of each damage die\.'
echo "$out" | grep -qP '^#D\tFollow-Through: Allows you to continue'

echo "== native pack: equipping (two messages, so a -more-) reopens the pack by itself, and the option is put back"
rm -rf "$work/data"
out=$(run "${birth[@]}" @act=15 @answer=1001 @answer=100102 @prompt @answer=-1 @ctx "${quit[@]}")
echo "$out" | grep -qP '^prompt kind=13$'
echo "$out" | grep -q 'automore=0'
echo "== skip -more-: set from the UI side, shown by the game's option"
rm -rf "$work/data"
out=$(run "${birth[@]}" @automore=1 @ctx "${quit[@]}")
echo "$out" | grep -q 'automore=1'
echo "== game options: what a phone has no use for is not listed (keys, sound, cursor, centring, -more-), the rest is"
rm -rf "$work/data"
out=$(run "${birth[@]}" @act=26 @prompt @answer=-1 "${quit[@]}" 2>/dev/null)
for gone in hjkl_movement angband_keyset use_sound beep highlight_player highlight_target center_player run_avoid_center quick_messages mouse_movement auto_more; do
    refute grep -qP "^#O\t\d+\t[01]\t[01]\t.*\t$gone\$" <<<"$out"
done
echo "$out" | grep -qP '^#O\t\d+\t[01]\t[01]\t.*\tshow_forecast$'
echo "$out" | grep -qP '^#N\t\d+\tHitpoint warning\t'
refute grep -qP '^#N\t\d+\tMovement delay\t' <<<"$out"
echo "== welcome: the splash screen is native (verse, welcome, New game)"
rm -rf "$work/data"
out=$(run @prompt "${birth[@]}" "${quit[@]}")
title=$([ "$game" = silq ] && echo NarSil-Q || echo NarSil)
echo "$out" | grep -qP "^Screen\twelcome\t$title\$"
echo "$out" | grep -qP '^#V\tThe world was young'
echo "$out" | grep -qP '^#O\t1\tNew game\t'
echo "== welcome: with a saved character the first button reads Continue"
rm -rf "$work/data"
run "${birth[@]}" @save "${quit[@]}" >/dev/null 2>&1
run @prompt 2>/dev/null | grep -qP '^#O\t1\tContinue\t'
rm -rf "$work/data"
other=$([ "$game" = silq ] && echo NarSil || echo NarSil-Q)
echo "$out" | grep -qP "^#O\t1\tPlay $other instead\t"

echo "== entering the dungeon: the entry poetry is a native screen, its indentation kept, then Continue"
rm -rf "$work/data"
out=$(run "${birth[@]:0:${#birth[@]}-1}" @prompt @answer=0 @ctx "${quit[@]}")
echo "$out" | grep -qP '^Screen\tpoem\tThe Gates of Angband$'
echo "$out" | grep -qP '^#V\t {6}down to the mountain'"'"'s roots profound,$'
echo "$out" | grep -qP '^#O\t1\tContinue\t'
echo "$out" | grep -q 'context='

echo "== the status data has the minimum depth (the depth clock): 1 at the start"
rm -rf "$work/data"
run "${birth[@]}" @mindepth "${quit[@]}" | grep -qx 'depth 1 min 1'

echo "== welcome: choosing the other game ends the game thread with switch-game, before any save is touched"
rm -rf "$work/data"
run @answer=2 | tail -1 | grep -qx 'state=2 exit="switch-game" saves=0'
test ! -e "$work/data/save/Harness"

echo "== knowledge: sections, objects with a description, unknown flavours named with their kind"
rm -rf "$work/data"
out=$(run "${birth[@]}" @act=27 @prompt @answer=1 @prompt @answer=0 @prompt @answer=-1 @prompt @answer=-1 "${quit[@]}")
echo "$out" | grep -qP '^#E\t[0-9]+\t[0-9]+\tCreatures\t'
echo "$out" | grep -qP '^Knowledge\tobjects\tObjects\t0$'
echo "$out" | grep -qP '^#E\t[0-9]+\t[0-9]+\t\S+ amulet\tunknown$'
echo "$out" | grep -qP '^#T\t\S'
out=$(run "${birth[@]}" @act=27 @prompt @answer=5 @prompt @answer=-1 @prompt @answer=6 @prompt @answer=-1 @prompt @answer=-1 "${quit[@]}")
echo "$out" | grep -qP '^Knowledge\tfeatures\tTerrain\t-1$'
echo "$out" | grep -A1 -P '^Knowledge\tfeatures' | grep -qP '^#G\tFloors$'
echo "$out" | grep -qP '^#G\tTraps$'

echo "== knowledge: inscribe a rune, then change and remove the inscription offered"
rm -rf "$work/data"
out=$(run "${birth[@]}" @act=27 @prompt @answer=4 @prompt @answer=0 @prompt @answer=100000 @prompt @text=dig @prompt @answer=-1 @prompt @answer=-1 "${quit[@]}")
echo "$out" | grep -qP '^#E\t[0-9]+\t[0-9]+\tTunneling\t\{dig\}$'
echo "$out" | grep -qP '^#A\t100001\tRemove inscription$'

echo "== the name last given is the next character's default (and Random still offers others)"
rm -rf "$work/data"
run @answer=0 @answer=0 @answer=0 @answer=0 @answer=2 @answer=2 @prompt @text=Bilbo >/dev/null
out=$(run @answer=0 @answer=0 @answer=0 @answer=0 @answer=2 @answer=2 @prompt @answer=2 @prompt)
[ "$(echo "$out" | grep -cx 'Bilbo')" -eq 1 ]
[ "$(echo "$out" | grep -A1 -x 'Your character.s name' | grep -vcxe 'Your character.s name' -e '--' -e Bilbo)" -ge 1 ]

echo "== the seed is asked for only when the birth option is on"
rm -rf "$work/data"
out=$(run @answer=0 @answer=0 @answer=0 @answer=0 @answer=2 @answer=2 @prompt @text=Bilbo @prompt)
[ "$(echo "$out" | grep -c 'Dungeon seed')" -eq 0 ]
rm -rf "$work/data"
out=$(run "${choose_seed[@]}" @answer=0 @answer=0 @answer=0 @answer=2 @answer=2 @prompt @text=Bilbo @prompt)
[ "$(echo "$out" | grep -c 'Dungeon seed')" -eq 1 ]

echo "== panel: pack, character, skills (bought points kept across tabs), abilities; smithing design kept across tabs"
rm -rf "$work/data"
out=$(run "${birth[@]}" @act=15 @prompt @answer=16777217 @prompt @answer=16777218 @prompt @answer=100 @prompt @answer=16777219 @prompt @answer=16777218 @prompt @answer=16777223 "${quit[@]}")
echo "$out" | grep -qx 'panel tab=1'
echo "$out" | grep -qP '^Skills\t4900$'
# every NarSil-Q character starts with a curved sword (-1 melee)
melee_after=$([ "$game" = silq ] && echo 2 || echo 3)
[ "$(echo "$out" | grep -cP "^Melee\t1\t$melee_after\t")" -ge 2 ]
rm -rf "$work/data"
out=$(run "${birth[@]}" @act=25 @prompt @answer=0 @prompt @answer=0 @prompt @answer=0 @prompt @answer=16777216 @prompt @answer=16777221 @prompt @answer=16777223 "${quit[@]}")
[ "$(echo "$out" | grep -cP '^#I\t24 Arrows')" -ge 2 ]

echo "== native quantity (drop part of a stack), text (inscribe), ignore choice; death history and scores"
rm -rf "$work/data"
out=$(run "${birth[@]}" @act=15 @prompt @answer=1001 @prompt @answer=100103 @prompt @answer=1 @prompt @answer=1000 @prompt @answer=100003 @prompt @text=lunch @prompt @answer=1000 @prompt @answer=100005 @prompt @answer=1 @prompt @answer=-1 "${quit[@]}")
echo "$out" | grep -qx 'prompt kind=16'
echo "$out" | grep -qP '^#I\t0\tb\t[0-9]+\t[0-9]+\t(20|40)\t0\t(a|2) Wooden Torch'
echo "$out" | grep -qx 'Inscribe with what'
echo "$out" | grep -qP 'Fragments of Lembas \{lunch\}$'
echo "$out" | grep -qP '^All Fragments of Lembas\t'
rm -rf "$work/data"
out=$(run "${birth[@]}" @die @enter @prompt @answer=3 @prompt @answer=-1 @prompt @answer=4 @prompt @answer=-1 @prompt @answer=6)
echo "$out" | grep -qP '^Knowledge\thistory\t'
echo "$out" | grep -qP '^Knowledge\tscores\t'
echo "$out" | grep -qP 'killed by the test harness at 50 ft\t'
echo "$out" | grep -qP '^#H\tSeed [0-9]+$'
grep -q 'Killed by the test harness' "$work/data/user/character-dump.txt"
grep -qE '^ Seed +[0-9]+' "$work/data/user/character-dump.txt"
grep -q '\[Last Messages\]' "$work/data/user/character-dump.txt"

echo "== item descriptions carry the game's colours (an NB_COLOUR run)"
rm -rf "$work/data"
out=$(run "${birth[@]}" @act=15 @prompt @answer=1001 @prompt @answer=-1 "${quit[@]}")
echo "$out" | grep -qP '^#D\tIntensity \x03.1'

echo "== character tab: rename (text prompt) and write the dump for sharing"
rm -rf "$work/data"
out=$(run "${birth[@]}" @act=20 @prompt @answer=2 @prompt @text=Beren @prompt @answer=3 @prompt @answer=-1 "${quit[@]}")
echo "$out" | grep -qx 'Your name'
echo "$out" | grep -qP '^#S\tBeren$'
grep -q 'Name   Beren' "$work/data/user/character-dump.txt"

echo "== combat feel: a landed blow, a miss and a kill each move the feel counter once; other messages do not"
msg_type() { awk -v n="$1" 'index($0, "MSG(" n ",") == 1 {print c; exit} /^MSG\(/ {c++}' "$root/src/list-message.h"; }
rm -rf "$work/data"
out=$(run "${birth[@]}" @feel "@msgt=$(msg_type HIT)" @feel "@msgt=$(msg_type MISS)" @feel "@msgt=$(msg_type KILL)" @feel "@msgt=$(msg_type MON_HIT)" @feel "${quit[@]}")
[ "$(echo "$out" | grep -x 'feel .*' | tr '\n' '|')" = "feel 0 0|feel 1 1|feel 2 2|feel 3 4|feel 3 4|" ]

echo "== tiles: map cells carry a MicroChasm tile once switched on; ASCII elsewhere unchanged"
rm -rf "$work/data"
out=$(run "${birth[@]}" @map @tiles @map @act=15 @prompt @answer=-1 "${quit[@]}")
echo "$out" | grep -qx 'player tile -1,-1'
echo "$out" | grep -qP '^player tile [0-9]+,[0-9]+$'
echo "$out" | grep -qP '^player terrain tile [0-9]+,[0-9]+ overlays 3083,3086$'
echo "$out" | grep -qP '^#I\t0\ta\t44\t'

echo "== tiles while raging: every tile is drawn tinted (NB_TILE_RAGE), as in Sil-Q"
rm -rf "$work/data"
out=$(run "${birth[@]}" @tiles @map @rage @map "${quit[@]}" 2>/dev/null)
[ "$(echo "$out" | grep -c '^player tile flags')" = 1 ]  # none before the rage
(( $(echo "$out" | grep '^player tile flags' | cut -d' ' -f4) & 4 ))

echo "== tutorial from the welcome screen; it never saves over the player's own save"
rm -rf "$work/data"
run "${birth[@]}" "${quit[@]}" >/dev/null
before=$(md5sum < "$work/data/save/Harness")
out=$(run @prompt @answer=1 @enter @prompt @answer=0 @save-async)
echo "$out" | grep -qP '^Screen\tnote\t'
echo "$out" | grep -q 'Welcome to Sil. The game is set within a vast dungeon'
[ "$(md5sum < "$work/data/save/Harness")" = "$before" ]

echo "== save with the panel open succeeds (screen stays up); a save asked for during a command's own prompt waits"
rm -rf "$work/data"
out=$(run "${birth[@]}" @act=15 @prompt @save @prompt @answer=-1 "${quit[@]}" 2>&1)
echo "$out" | grep -q 'save: done'
echo "$out" | grep -qx 'panel tab=0'
rm -rf "$work/data"
out=$(run "${birth[@]}" @act=15 @prompt @answer=1001 @prompt @answer=100103 @prompt @save-async @prompt @answer=1 @prompt @answer=-1 "${quit[@]}" 2>&1)
echo "$out" | grep -q 'save: done'
echo "$out" | tail -1 | grep -qx 'state=2 exit="" saves=1'

echo "== native walk and run: some direction is open; runs end at the prompt"
rm -rf "$work/data"
out=$(NARSIL_SEED=7 run "${birth[@]}" @walk=1 @walk=2 @walk=3 @walk=4 @walk=6 @walk=7 @walk=8 @walk=9 @run=2 @run=4 @run=6 @run=8 @walk=5 "${quit[@]}")
echo "$out" | grep -q '^walk [0-9]: moved 1$'
[ "$(echo "$out" | grep -c '^run [0-9]: moved')" = 4 ]
echo "$out" | tail -1 | grep -qx 'state=2 exit="" saves=0'

echo "PASS ($game, $san) work=$work"
