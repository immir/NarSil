#!/usr/bin/env bash
# Writes the seed lists in docs/seed-lists/: what seeds FIRST-LAST hold on levels 1-DEPTH (VISITS visits each),
# for NarSil and for NarSil-Q.  Needs a built desktop game (./configure --with-no-install && make).
#   tools/gen-seed-lists.sh                       # seeds 1-100, levels 1-12, 3 visits each
#   FIRST=1 LAST=20 DEPTH=6 tools/gen-seed-lists.sh
# A list's first line names the game and version it was made with: a seed gives the same levels only there.
# Build from a clean checkout (no uncommitted changes), or the version reads "-dirty".
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
exe=${NARSIL:-$root/src/narsil}
first=${FIRST:-1} last=${LAST:-100} depth=${DEPTH:-12} visits=${VISITS:-3}
out=$root/docs/seed-lists
[ -x "$exe" ] || { echo "gen-seed-lists: no executable at $exe (build NarSil first, or set NARSIL)" >&2; exit 1; }
mkdir -p "$out"
scratch=$(mktemp -d)
trap 'rm -rf "$scratch"' EXIT
mkdir -p "$scratch/user"

"$exe" -mspoil -d"user=$scratch/user" -d"save=$scratch/user" -d"scores=$scratch/user" -- \
    -l "$out/narsil-seeds-$first-$last.txt" -S "$first-$last" -D "$depth" -V "$visits"
NARSIL=$exe "$root/tools/narsil-q.sh" -mspoil -- \
    -l "$out/narsil-q-seeds-$first-$last.txt" -S "$first-$last" -D "$depth" -V "$visits"
ls -l "$out"
