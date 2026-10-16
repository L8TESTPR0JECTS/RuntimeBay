#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
bash scripts/build.sh
runtimebay_app="${RUNTIMEBAY_PACKAGE_PATH:-$PWD/dist/Runtime Bay.app}"
mkdir -p "$runtimebay_app/Contents/MacOS" "$runtimebay_app/Contents/Resources"
cp .build/RuntimeBay "$runtimebay_app/Contents/MacOS/RuntimeBay"
cp .build/runtimebay-launcher .build/runtimebay-terminal backend/target/runtimebay-backend.jar "$runtimebay_app/Contents/Resources/"
cp .build/loading-startup.js .build/loading.css "$runtimebay_app/Contents/Resources/"
cp .build/RuntimeBay.icns "$runtimebay_app/Contents/Resources/"
cp frontend/src/assets/runtime-bay.png "$runtimebay_app/Contents/Resources/"
cp LICENSE THIRD_PARTY_NOTICES.md "$runtimebay_app/Contents/Resources/"
mkdir -p "$runtimebay_app/Contents/Resources/licenses"
cp native/licenses/Apache-2.0.txt "$runtimebay_app/Contents/Resources/licenses/"
rm -rf "$runtimebay_app/Contents/Resources/web"
mkdir -p "$runtimebay_app/Contents/Resources/web"
cp -R dist/web/browser/. "$runtimebay_app/Contents/Resources/web/"
if [ -f dist/web/3rdpartylicenses.txt ]; then cp dist/web/3rdpartylicenses.txt "$runtimebay_app/Contents/Resources/"; fi
# jlink needs an empty output path. Remove only this app's generated runtime.
rm -rf "$runtimebay_app/Contents/Resources/jre"
runtimebay_jdk="$(/usr/libexec/java_home -v 25)"
"$runtimebay_jdk/bin/jlink" --add-modules java.base,java.net.http,java.logging,java.management,java.sql,jdk.httpserver,jdk.unsupported --strip-debug --no-header-files --no-man-pages --output "$runtimebay_app/Contents/Resources/jre"
cat > "$runtimebay_app/Contents/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>CFBundleName</key><string>Runtime Bay</string>
<key>CFBundleDisplayName</key><string>Runtime Bay</string>
<key>CFBundleIdentifier</key><string>dev.runtimebay.app</string>
<key>CFBundleExecutable</key><string>RuntimeBay</string>
<key>CFBundleIconFile</key><string>RuntimeBay.icns</string>
<key>CFBundlePackageType</key><string>APPL</string>
<key>CFBundleShortVersionString</key><string>0.5.0</string>
<key>CFBundleVersion</key><string>10</string>
<key>LSMinimumSystemVersion</key><string>14.0</string>
<key>NSHighResolutionCapable</key><true/>
<key>NSHumanReadableCopyright</key><string>Runtime Bay contributors</string>
</dict></plist>
PLIST
codesign --force --deep --sign - "$runtimebay_app"
ditto -c -k --sequesterRsrc --keepParent "$runtimebay_app" "$PWD/dist/Runtime-Bay-macos-$(uname -m).zip"
printf 'Built %s\n' "$runtimebay_app"
