#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."

# Convert the supplied artwork to Apple's multi-resolution icon format.
runtimebay_icon_source="$PWD/frontend/src/assets/runtime-bay.png"
runtimebay_iconset="$PWD/.build/RuntimeBay.iconset"
mkdir -p "$runtimebay_iconset"
for runtimebay_icon_size in 16 32 128 256 512; do
  sips -z "$runtimebay_icon_size" "$runtimebay_icon_size" "$runtimebay_icon_source" --out "$runtimebay_iconset/icon_${runtimebay_icon_size}x${runtimebay_icon_size}.png" >/dev/null
  runtimebay_retina_size=$((runtimebay_icon_size * 2))
  sips -z "$runtimebay_retina_size" "$runtimebay_retina_size" "$runtimebay_icon_source" --out "$runtimebay_iconset/icon_${runtimebay_icon_size}x${runtimebay_icon_size}@2x.png" >/dev/null
done
iconutil -c icns "$runtimebay_iconset" -o "$PWD/.build/RuntimeBay.icns"
