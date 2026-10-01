#!/usr/bin/env sh
# Downloads the nodejs-mobile Android core library and installs the
# armeabi-v7a shared library as libnode.so so the app can start Node in-process.
set -e

NJM_VERSION="${NJM_VERSION:-v18.20.4}"
ABI="${ABI:-armeabi-v7a}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST_DIR="$ROOT/app/src/main/jniLibs/$ABI"
WORK="$(mktemp -d)"

ZIP_URL="https://github.com/nodejs-mobile/nodejs-mobile/releases/download/${NJM_VERSION}/nodejs-mobile-${NJM_VERSION}-android.zip"

echo "Downloading nodejs-mobile ${NJM_VERSION} ..."
curl -fSL --retry 3 -o "$WORK/nodejs-mobile-android.zip" "$ZIP_URL"

echo "Extracting $ABI/libnode.so ..."
mkdir -p "$DEST_DIR"
unzip -o -j "$WORK/nodejs-mobile-android.zip" "bin/$ABI/libnode.so" -d "$DEST_DIR"

ls -la "$DEST_DIR/libnode.so"
echo "Installed $DEST_DIR/libnode.so"
