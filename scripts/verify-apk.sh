#!/usr/bin/env bash
set -euo pipefail
APK=app/build/outputs/apk/debug/app-debug.apk
if [ "$#" -gt 0 ]; then APK="$1"; fi
SDK="$HOME/Android/Sdk"
if [ -n "${ANDROID_HOME:-}" ]; then SDK="$ANDROID_HOME"; fi
if [ ! -x "$SDK/build-tools/35.0.0/aapt" ]; then
    echo "Cannot find Android build tools. Set ANDROID_HOME." >&2
    exit 1
fi
AAPT="$SDK/build-tools/35.0.0/aapt"
SIGNER="$SDK/build-tools/35.0.0/apksigner"
test -s "$APK"
"$SIGNER" verify "$APK"
"$AAPT" dump badging "$APK" | grep -E "^package:|^sdkVersion:|^targetSdkVersion:"
ZIPLIST=$(mktemp)
trap 'rm -f "$ZIPLIST"' EXIT
unzip -Z1 "$APK" > "$ZIPLIST"
grep -qx 'lib/arm64-v8a/libai-chat.so' "$ZIPLIST"
grep -qx 'lib/arm64-v8a/libllama.so' "$ZIPLIST"
if grep -q '^lib/x86_64/' "$ZIPLIST"; then
    echo "Unexpected x86_64 libraries in arm64-only APK" >&2
    exit 1
fi
echo "APK metadata, signing and JNI ABI verified."
