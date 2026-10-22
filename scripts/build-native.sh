#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p .build .cache/swift
bash scripts/build-icon.sh
node scripts/build-loading.mjs
export CLANG_MODULE_CACHE_PATH="$PWD/.cache/swift"
clang -O2 -Wall -Wextra -Werror native/ProcessLauncher.c -o .build/runtimebay-launcher
clang -O2 -Wall -Wextra -Werror native/TerminalLauncher.c -o .build/runtimebay-terminal
clang -O2 -Wall -Wextra -Werror native/AskPass.c -o .build/runtimebay-askpass
swiftc -module-cache-path "$PWD/.cache/swift" -O native/HostKeychain.swift -framework Security -framework LocalAuthentication -o .build/runtimebay-keychain
swiftc -module-cache-path "$PWD/.cache/swift" -O native/WorkspaceZoom.swift native/RuntimeBay.swift -framework AppKit -framework WebKit -framework Security -o .build/RuntimeBay
