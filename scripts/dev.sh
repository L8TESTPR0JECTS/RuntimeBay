#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
bash scripts/build.sh
export RUNTIMEBAY_JAR="$PWD/backend/target/runtimebay-backend.jar"
export RUNTIMEBAY_LAUNCHER="$PWD/.build/runtimebay-launcher"
export RUNTIMEBAY_WEB_ROOT="$PWD/dist/web/browser"
exec .build/RuntimeBay
