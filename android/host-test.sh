#!/usr/bin/env bash
# Builds the bridge for the host (ASan+UBSan; TSan with TSAN=1; MSan with
# MSAN=1, which needs clang) and runs
# scripted end-to-end scenarios against the real game data in ../lib.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
root=$(dirname "$here")
work=${WORK:-$(mktemp -d)}
san=${TSAN:+thread}
san=${san:-${MSAN:+memory}}
san=${san:-address,undefined}
build="$work/build-$san"
cc=${MSAN:+clang}

cmake -S "$here/app/src/main/cpp" -B "$build" -DCMAKE_BUILD_TYPE=Debug ${cc:+-DCMAKE_C_COMPILER=$cc} \
    -DCMAKE_C_FLAGS="-fsanitize=$san -fno-omit-frame-pointer -g" >/dev/null
cmake --build "$build" -j"$(nproc)" >/dev/null

run() { timeout 120 "$build/narsil_host" "$root/lib" "$work/data" "$@"; }
# The native birth screens: race, house, sex (first of each), accept stats and
# skills, keep the name, accept history, begin; dismiss the opening text.
birth=(@enter @answer=0 @answer=0 @answer=0 @answer=2 @answer=2 @text= @answer=0 @answer=0 @esc)
# Quit from the command prompt; the Escs first dismiss any -more- left on screen.
quit=(@esc @esc ^x @enter @esc)

rm -rf "$work/data"
echo "== empty message log at startup is published cleanly (MSan catches the old bug)"
rm -rf "$work/data"
run @messages | grep -q '^messages:'

echo "== new game; save requested during birth is deferred to first command prompt"
run @enter @save-async "${birth[@]:1}" @expect=Harness 6 4 @save ^x @enter @esc | tail -1 \
    | grep -qx 'state=2 exit="" saves=2'
test -s "$work/data/save/Harness"

echo "== reload the savefile"
run @enter @expect=Harness @expect=50\' ^x @enter @esc | tail -1 | grep -qx 'state=2 exit="" saves=0'

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

echo "== browse actions (eat from 'i'), yes/no (retire? no), throw (first item) + aim east"
rm -rf "$work/data"
out=$(run "${birth[@]}" i @pick=0 @prompt @answer=0 @messages \
      Q @prompt @answer=0 t @prompt @pick=0 @prompt @answer=6 @messages "${quit[@]}")
echo "$out" | grep -q 'prompt kind=2'
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

echo "== look list and inspect are answered, read-only"
rm -rf "$work/data"
out=$(run "${birth[@]}" @look @inspect@0,0 "${quit[@]}")
echo "$out" | grep -q '^Look'
echo "$out" | grep -qP '^Inspect\t'
echo "$out" | tail -1 | grep -qx 'state=2 exit="" saves=0'

echo "== spend experience: buy, buy, sell, accept persists; cancel undoes"
rm -rf "$work/data"
out=$(run "${birth[@]}" @act=23 @answer=100 @answer=100 @answer=200 @answer=2 @act=23 @prompt @answer=-1 "${quit[@]}")
echo "$out" | grep -qP '^Skills\t4900$'
echo "$out" | grep -qP '^Melee\t1\t'
echo "$out" | tail -1 | grep -qx 'state=2 exit="" saves=0'

echo "== abilities: learn one (confirmed by the native check), then switch it off"
rm -rf "$work/data"
out=$(run "${birth[@]}" @act=23 @answer=101 @answer=2 @act=21 @answer=14 @answer=1 @prompt @answer=14 @prompt @answer=-1 "${quit[@]}")
echo "$out" | grep -qP '^1\t2\t1\t\d+\t\tCareful Shot\t'
echo "$out" | grep -qP '^1\t3\t1\t\d+\t\tCareful Shot\t'
echo "$out" | tail -1 | grep -qx 'state=2 exit="" saves=0'

echo "== character sheet is published as sections"
rm -rf "$work/data"
out=$(run "${birth[@]}" @act=20 @info "${quit[@]}")
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
out=$(run @enter @answer=0 @answer=1000 @prompt @answer=0 @answer=100 @prompt @answer=2 @answer=-1 @prompt \
      @answer=2 @answer=2 @text= @answer=0 @answer=0 @esc "${quit[@]}")
echo "$out" | grep -qP '^Screen\tsex\tSex$'
echo "$out" | grep -qP '^#S\tStrength\t1\t1\t'
echo "$out" | grep -qP '^#P\t12\t13$'
echo "$out" | grep -qP '^#K\tMelee\t-?[0-9]+$'
echo "$out" | grep -qP '^#C\tDefence\t[+-][0-9]+$'
echo "$out" | tail -1 | grep -qx 'state=2 exit="" saves=0'

echo "== death screen (by retiring): character, then a new character goes straight to birth"
rm -rf "$work/data"
out=$(run "${birth[@]}" Q @answer=1 @ @enter @prompt @answer=0 @answer=-1 @answer=5 @answer=1 @prompt)
echo "$out" | grep -qP '^Screen\tdeath\tYou have retired$'
echo "$out" | grep -qP '^Screen\trace\tRace$'

echo "== death screen (slain): -more-, then the death screen, then a new character"
rm -rf "$work/data"
out=$(run "${birth[@]}" @die @enter @prompt @answer=5 @answer=1 @prompt)
echo "$out" | grep -qP '^Screen\tdeath\tYou have been slain$'
echo "$out" | grep -qP '^Screen\trace\tRace$'

echo "== native options: toggle, number, locked birth page; birth options editable at birth"
rm -rf "$work/data"
out=$(run "${birth[@]}" @act=26 @prompt @answer=1007 @prompt @answer=10005 @prompt @answer=100 @prompt @answer=-1 @prompt @answer=-1 "${quit[@]}")
echo "$out" | grep -qP '^#O\t1007\t1\t0\tAudible beep'
echo "$out" | grep -qP '^#N\t10000\tHitpoint warning\t5\t'
echo "$out" | grep -qP '^#O\t1046\t0\t1\tDisconnected stairs'
rm -rf "$work/data"
out=$(run @enter @answer=1001 @prompt @answer=1046 @prompt @answer=-1 @answer=0 @answer=0 @answer=0 @answer=2 @answer=2 @text= @answer=0 @answer=0 @esc "${quit[@]}")
echo "$out" | grep -qP '^#O\t1046\t1\t0\tDisconnected stairs'

echo "== native walk and run: some direction is open; runs end at the prompt"
rm -rf "$work/data"
out=$(run "${birth[@]}" @walk=1 @walk=2 @walk=3 @walk=4 @walk=6 @walk=7 @walk=8 @walk=9 @run=2 @run=4 @run=6 @run=8 @walk=5 "${quit[@]}")
echo "$out" | grep -q '^walk [0-9]: moved 1$'
[ "$(echo "$out" | grep -c '^run [0-9]: moved')" = 4 ]
echo "$out" | tail -1 | grep -qx 'state=2 exit="" saves=0'

echo "PASS ($san) work=$work"
