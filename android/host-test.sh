#!/usr/bin/env bash
# Builds the bridge for the host (ASan+UBSan, or TSan with TSAN=1) and runs
# scripted end-to-end scenarios against the real game data in ../lib.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
root=$(dirname "$here")
work=${WORK:-$(mktemp -d)}
san=${TSAN:+thread}
san=${san:-address,undefined}
build="$work/build-$san"

cmake -S "$here/app/src/main/cpp" -B "$build" -DCMAKE_BUILD_TYPE=Debug \
    -DCMAKE_C_FLAGS="-fsanitize=$san -fno-omit-frame-pointer -g" >/dev/null
cmake --build "$build" -j"$(nproc)" >/dev/null

run() { timeout 120 "$build/narsil_host" "$root/lib" "$work/data" "$@"; }
birth=(@enter @enter @enter @enter @enter @enter @enter y y @esc)

rm -rf "$work/data"
echo "== new game; save requested during birth is deferred to first command prompt"
run @enter @save-async "${birth[@]:1}" @expect=Harness 6 4 @save ^x @enter @esc | tail -1 \
    | grep -qx 'state=2 exit="" saves=2'
test -s "$work/data/save/Harness"

echo "== reload the savefile"
run @enter @expect=Harness @expect=50\' ^x @enter @esc | tail -1 | grep -qx 'state=2 exit="" saves=0'

echo "PASS ($san) work=$work"
