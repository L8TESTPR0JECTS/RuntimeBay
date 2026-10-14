#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p .build .cache/swift
export CLANG_MODULE_CACHE_PATH="$PWD/.cache/swift"
clang -O2 -Wall -Wextra -Werror native/ProcessLauncher.c -o .build/runtimebay-launcher
swiftc -module-cache-path "$PWD/.cache/swift" -O native/RuntimeBay.swift -framework AppKit -framework WebKit -framework Security -o .build/RuntimeBay
