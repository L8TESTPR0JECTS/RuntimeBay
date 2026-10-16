#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."

# Finder and Dock use the same Sage artwork as the README; UI masks remain themeable.
mkdir -p .build .cache/swift
swift -module-cache-path "$PWD/.cache/swift" scripts/render-icon.swift "$PWD/docs/assets/runtime-bay-sage.svg" "$PWD/.build/runtime-bay-sage.png"
runtimebay_icon_source="$PWD/.build/runtime-bay-sage.png"
runtimebay_iconset="$PWD/.build/RuntimeBay.iconset"
mkdir -p "$runtimebay_iconset"
for runtimebay_icon_size in 16 32 128 256 512; do
  sips -z "$runtimebay_icon_size" "$runtimebay_icon_size" "$runtimebay_icon_source" --out "$runtimebay_iconset/icon_${runtimebay_icon_size}x${runtimebay_icon_size}.png" >/dev/null
  runtimebay_retina_size=$((runtimebay_icon_size * 2))
  sips -z "$runtimebay_retina_size" "$runtimebay_retina_size" "$runtimebay_icon_source" --out "$runtimebay_iconset/icon_${runtimebay_icon_size}x${runtimebay_icon_size}@2x.png" >/dev/null
done
iconutil -c icns "$runtimebay_iconset" -o "$PWD/.build/RuntimeBay.icns"
