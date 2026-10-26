#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
export NG_BUILD_MAX_WORKERS="${NG_BUILD_MAX_WORKERS:-2}"
bash scripts/build-native.sh
swiftc -module-cache-path "$PWD/.cache/swift" native/WorkspaceZoom.swift native/tests/WorkspaceZoomTests.swift -framework AppKit -framework WebKit -o .build/workspace-zoom-tests
.build/workspace-zoom-tests
export RUNTIMEBAY_LAUNCHER="$PWD/.build/runtimebay-launcher"
mvn -q -f backend/pom.xml -Dmaven.repo.local="$PWD/.cache/m2" test
(
  cd runtime
  export GOCACHE="$PWD/.build/go-cache" GOMODCACHE="$PWD/.build/go-mod-cache"
  go test -race ./...
  go vet ./...
)
npm run test:ui
zsh scripts/test-terminal-assist.zsh
npm run build:web
