#!/usr/bin/env bash
# Renders the app icon (AppIcon.svg, the Android icon's design) for both iOS builds:
#  - icons/: loose PNGs at iOS's sizes, listed in xtool.yml and xtool-Info.plist (CFBundleIcons), since xtool on Linux
#    cannot compile an asset catalog;
#  - Assets.xcassets/AppIcon.appiconset: the single 1024px icon Xcode compiles (project.yml).
# Needs rsvg-convert and ImageMagick.  Icons must be opaque, so alpha is dropped.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
cd "$here"
render() { rsvg-convert -w "$1" -h "$1" AppIcon.svg | magick - -alpha off "$2"; }
mkdir -p icons Assets.xcassets/AppIcon.appiconset
for s in "60x60@2x:120" "60x60@3x:180" "76x76@2x~ipad:152" "83.5x83.5@2x~ipad:167"; do
    render "${s##*:}" "icons/AppIcon${s%%:*}.png"
done
render 1024 AppIcon.png
cp AppIcon.png Assets.xcassets/AppIcon.appiconset/AppIcon.png
