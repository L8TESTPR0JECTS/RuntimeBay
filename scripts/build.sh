#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
export NG_BUILD_MAX_WORKERS="${NG_BUILD_MAX_WORKERS:-2}"
bash scripts/build-native.sh
export RUNTIMEBAY_LAUNCHER="$PWD/.build/runtimebay-launcher"
npm run test:ui
npm run build:web
mvn -q -f backend/pom.xml -Dmaven.repo.local="$PWD/.cache/m2" package
