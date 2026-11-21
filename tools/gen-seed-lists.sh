#!/usr/bin/env bash
# Writes the seed lists in docs/seed-lists/: what seeds FIRST-LAST hold on levels 1-DEPTH (VISITS visits each, with the
# provisos for killed bosses), for NarSil and for NarSil-Q, CHUNK seeds to a file, JOBS files at a time.
# Needs a built desktop game (./configure --with-no-install && make).
#   tools/gen-seed-lists.sh                       # seeds 1-1000, levels 1-12, 3 visits each, 100 seeds a file
#   FIRST=1 LAST=20 CHUNK=20 DEPTH=6 PROVISOS=0 tools/gen-seed-lists.sh
# A list's first line names the game and version it was made with: a seed gives the same levels only there.
# Build from a clean checkout (no uncommitted changes), or the version reads "-dirty".
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
exe=${NARSIL:-$root/src/narsil}
first=${FIRST:-1} last=${LAST:-1000} chunk=${CHUNK:-100} depth=${DEPTH:-12} visits=${VISITS:-3}
provisos=${PROVISOS:-1} jobs=${JOBS:-$(nproc)}
out=$root/docs/seed-lists
[ -x "$exe" ] || { echo "gen-seed-lists: no executable at $exe (build NarSil first, or set NARSIL)" >&2; exit 1; }
mkdir -p "$out"
scratch=$(mktemp -d)
trap 'rm -rf "$scratch"' EXIT

# NarSil-Q's data (as tools/narsil-q.sh makes it) is merged once, for every job to read
"$root/tools/merge-lib.sh" "$root/lib" "$root/lib-silq" "$scratch/lib-silq"

# One job per game and range of seeds; each has a scratch user directory of its own
jobs_file=$scratch/jobs
for ((a = first; a <= last; a += chunk)); do
    b=$((a + chunk - 1)); ((b > last)) && b=$last
    echo "narsil $a $b" >> "$jobs_file"
    echo "narsil-q $a $b" >> "$jobs_file"
done

run_job() {
    local game=$1 a=$2 b=$3 dir
    dir=$(mktemp -d -p "$scratch")
    mkdir -p "$dir/user"
    local dirs=(-d"user=$dir/user" -d"save=$dir/user" -d"scores=$dir/user" -d"panic=$dir/user" -d"archive=$dir/user")
    if [ "$game" = narsil ]; then
        "$exe" -mspoil "${dirs[@]}" -- \
            -l "$out/narsil-seeds-$a-$b.txt" -S "$a-$b" -D "$depth" -V "$visits" -P "$provisos"
    else
        local lib=$scratch/lib-silq
        "$exe" -mspoil -dgamedata="$lib/gamedata" -dscreens="$lib/screens" -dhelp="$lib/help" \
            -dpref="$lib/customize" -dtiles="$lib/tiles" "${dirs[@]}" -- \
            -l "$out/narsil-q-seeds-$a-$b.txt" -S "$a-$b" -D "$depth" -V "$visits" -P "$provisos"
    fi
}
export -f run_job
export exe root out depth visits provisos scratch
xargs -a "$jobs_file" -P "$jobs" -L 1 bash -c 'run_job "$@"' _
ls -l "$out"
