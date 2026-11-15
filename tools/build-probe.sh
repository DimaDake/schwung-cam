#!/usr/bin/env bash
# Builds tools/stems_probe for this Mac from the app's own stems_core.
set -euo pipefail
cd "$(dirname "$0")/.."
LINK=third_party/link
[ -f "$LINK/modules/asio-standalone/asio/include/asio.hpp" ] || git submodule update --init --recursive "$LINK"
mkdir -p build-probe
clang++ -std=c++17 -O2 -DLINK_PLATFORM_MACOSX=1 \
    -I"$LINK/include" -I"$LINK/modules/asio-standalone/asio/include" \
    tools/stems_probe.cpp app/src/main/cpp/stems_core.cpp -o build-probe/stems_probe
echo "build-probe/stems_probe"
