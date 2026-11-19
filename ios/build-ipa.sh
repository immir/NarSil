#!/usr/bin/env bash
# Builds the release .ipa for sideloading (unsigned; Sideloadly signs it): stages fresh game data first, then checks
# the bundle holds exactly that data and the binary does not link SwiftUICore (absent before iOS 18).
#   ./build-ipa.sh            → xtool/NarSil.ipa
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
export PATH=$HOME/.local/swift/usr/bin:$HOME/.local/bin:$PATH
cd "$here"
./xtool-prepare.sh
xtool dev build -c release --ipa

check=$(mktemp -d); trap 'rm -rf "$check"' EXIT
unzip -q xtool/NarSil.ipa -d "$check"
app="$check/Payload/NarSil.app"
for d in lib lib-silq; do diff -rq "$here/$d" "$app/$d"; done
! llvm-otool -L "$app/NarSil" | grep -q SwiftUICore
plutil() { python3 -c "import plistlib,sys; print(plistlib.load(open(sys.argv[1],'rb'))[sys.argv[2]])" "$@"; }
echo "ok: NarSil $(plutil "$app/Info.plist" CFBundleShortVersionString) ($(plutil "$app/Info.plist" CFBundleVersion))" \
     "→ $here/xtool/NarSil.ipa"
