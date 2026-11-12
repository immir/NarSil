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
birth=(@enter @enter @enter @enter @enter @enter @enter y y @esc)

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

echo "== touch: first tap highlights a menu item, second selects it"
rm -rf "$work/data"
run @enter @tap=4,12 @expect=Edain @tap=4,12 @expect='House affects' | tail -1 | grep -q 'state=1'

echo "== native map, path preview and travel"
rm -rf "$work/data"
out=$(run "${birth[@]}" @map @preview@1,0 @travel@1,0 @map ^x @enter @esc)
echo "$out" | grep -q 'mode=1 prompt=1'
echo "$out" | grep -qE '^travel -> '
echo "$out" | tail -1 | grep -qx 'state=2 exit="" saves=0'

echo "== native item prompt: browse + cancel, then eat via the prompt"
rm -rf "$work/data"
out=$(run "${birth[@]}" i @prompt @pick=-1 E @prompt @pick=0 @messages ^x @enter @esc)
echo "$out" | grep -q 'Lembas'
echo "$out" | grep -q 'renewed energy'
echo "$out" | grep -q 'Eat which food'
echo "$out" | tail -1 | grep -qx 'state=2 exit="" saves=0'

echo "== browse actions (eat from 'i'), yes/no (retire? no), throw (first item) + aim east"
rm -rf "$work/data"
out=$(run "${birth[@]}" i @pick=0 @prompt @answer=0 @messages \
      Q @prompt @answer=0 t @prompt @pick=0 @prompt @answer=6 @messages ^x @enter @esc)
echo "$out" | grep -q 'prompt kind=2'
echo "$out" | grep -q 'renewed energy'
echo "$out" | grep -q 'Do you really want to retire'
echo "$out" | grep -q 'prompt kind=4'
echo "$out" | tail -1 | grep -qx 'state=2 exit="" saves=0'

# Where the missile lands depends on the random level (a wall beside the
# player legitimately stops it), so only the flow is asserted here.
echo "== aiming beyond range completes (aims that way rather than at the player)"
rm -rf "$work/data"
out=$(run "${birth[@]}" t @pick=0 @aim@-9,0 @messages @esc @esc ^x @enter @esc)
echo "$out" | tail -1 | grep -qx 'state=2 exit="" saves=0'

echo "== autosave on first prompt; stealth action toggles; close opens the direction prompt"
rm -rf "$work/data"
out=$(run "${birth[@]}" @ctx @act=5 @ctx @act=10 @prompt @answer=-1 @act=0 @act=1 2>&1)
test -s "$work/data/save/Harness"
echo "$out" | grep -q 'autosave: done'
[ "$(echo "$out" | grep -o 'context=0x[0-9a-f]*' | sed -n 2p)" != "$(echo "$out" | grep -o 'context=0x[0-9a-f]*' | sed -n 1p)" ]

echo "== look list and inspect are answered, read-only"
rm -rf "$work/data"
out=$(run "${birth[@]}" @look @inspect@0,0 ^x @enter @esc)
echo "$out" | grep -q '^Look'
echo "$out" | grep -qP '^Inspect\t'
echo "$out" | tail -1 | grep -qx 'state=2 exit="" saves=0'

echo "== spend experience: buy, buy, sell, accept persists; cancel undoes"
rm -rf "$work/data"
out=$(run "${birth[@]}" @act=23 @answer=100 @answer=100 @answer=200 @answer=2 @act=23 @prompt @answer=-1 ^x @enter @esc)
echo "$out" | grep -qP '^Skills\t4900$'
echo "$out" | grep -qP '^Melee\t1\t'
echo "$out" | tail -1 | grep -qx 'state=2 exit="" saves=0'

echo "== abilities: learn one (confirmed by the native check), then switch it off"
rm -rf "$work/data"
out=$(run "${birth[@]}" @act=23 @answer=101 @answer=2 @act=21 @answer=14 @answer=1 @prompt @answer=14 @prompt @answer=-1 ^x @enter @esc)
echo "$out" | grep -qP '^1\t2\t1\t\d+\t\tCareful Shot\t'
echo "$out" | grep -qP '^1\t3\t1\t\d+\t\tCareful Shot\t'
echo "$out" | tail -1 | grep -qx 'state=2 exit="" saves=0'

echo "== native walk and run: some direction is open; runs end at the prompt"
rm -rf "$work/data"
out=$(run "${birth[@]}" @walk=1 @walk=2 @walk=3 @walk=4 @walk=6 @walk=7 @walk=8 @walk=9 @run=2 @run=4 @run=6 @run=8 @walk=5 ^x @enter @esc)
echo "$out" | grep -q '^walk [0-9]: moved 1$'
[ "$(echo "$out" | grep -c '^run [0-9]: moved')" = 4 ]
echo "$out" | tail -1 | grep -qx 'state=2 exit="" saves=0'

echo "PASS ($san) work=$work"
