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

# the native true-peak detector as a Mac library, for the JVM tests (NativePeaksTest)
JH="${JAVA_HOME:-$(/usr/libexec/java_home 2>/dev/null || true)}"
if [ -n "$JH" ] && [ -f "$JH/include/jni.h" ]; then
    clang++ -std=c++17 -O3 -shared -fPIC -I"$JH/include" -I"$JH/include/darwin" \
        app/src/main/cpp/truepeak_core.cpp app/src/main/cpp/dsp_jni.cpp -o build-probe/libschwungcam_dsp.dylib
    echo "build-probe/libschwungcam_dsp.dylib"
else
    echo "no JDK headers (set JAVA_HOME): skipped libschwungcam_dsp.dylib" >&2
fi
