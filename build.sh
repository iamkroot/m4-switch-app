#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

ANDROID_SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/.local/share/Android/Sdk}}"
BUILD_TOOLS="${ANDROID_SDK}/build-tools/35.0.0"
PLATFORM_JAR="${ANDROID_SDK}/platforms/android-34/android.jar"
JAVA_HOME_DIR="/opt/android-studio/jbr"
JAVAC="${JAVA_HOME_DIR}/bin/javac"
KEYSTORE="${HOME}/.android/debug.keystore"

if [ ! -d "$BUILD_TOOLS" ]; then
    echo "Error: Build tools not found at $BUILD_TOOLS" >&2
    exit 1
fi

if [ ! -f "$PLATFORM_JAR" ]; then
    echo "Error: android.jar not found at $PLATFORM_JAR" >&2
    exit 1
fi

echo "Cleaning build directory..."
rm -rf build
mkdir -p build/gen build/obj build/bin

echo "Compiling resources..."
"$BUILD_TOOLS/aapt2" compile --dir res -o build/res.zip
"$BUILD_TOOLS/aapt2" link -I "$PLATFORM_JAR" \
    --manifest AndroidManifest.xml \
    --java build/gen \
    -o build/unaligned.apk \
    build/res.zip

echo "Compiling Java sources..."
JAVA_SOURCES=$(find src -name "*.java")
"$JAVAC" -encoding UTF-8 \
    -source 17 -target 17 \
    -cp "$PLATFORM_JAR" \
    -d build/obj \
    build/gen/dev/kroot/m4switch/R.java \
    $JAVA_SOURCES


echo "Dexing with d8..."
"$BUILD_TOOLS/d8" \
    --lib "$PLATFORM_JAR" \
    --output build/bin \
    $(find build/obj -name "*.class")

echo "Packaging APK..."
cp build/unaligned.apk build/bin/app-unaligned.apk
(cd build/bin && /usr/bin/zip -qu app-unaligned.apk classes.dex)

echo "Aligning APK..."
"$BUILD_TOOLS/zipalign" -f -p 4 build/bin/app-unaligned.apk build/bin/app-aligned.apk

echo "Signing APK..."
"$BUILD_TOOLS/apksigner" sign \
    --ks "$KEYSTORE" \
    --ks-pass pass:android \
    --out build/bin/m4switch-debug.apk \
    build/bin/app-aligned.apk

echo "Build successful: build/bin/m4switch-debug.apk"

if [ "${1:-}" = "--install" ] || [ "${1:-}" = "-i" ]; then
    echo "Installing to connected device..."
    adb install -r build/bin/m4switch-debug.apk
    adb shell pm grant dev.kroot.m4switch android.permission.BLUETOOTH_CONNECT
    echo "Installed and granted BLUETOOTH_CONNECT permission."
fi
