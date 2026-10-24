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
# A Tensor G5 backend requires the real vendor dispatch native library.
grep -qx 'lib/arm64-v8a/libLiteRtDispatch_GoogleTensor.so' "$ZIPLIST"
DISPATCH_SHA="$(unzip -p "$APK" lib/arm64-v8a/libLiteRtDispatch_GoogleTensor.so | sha256sum | cut -d' ' -f1)"
EXPECTED_DISPATCH_SHA="75d637c4de3cf60b464daf7ba85176f856779f3d2d6cc091c0392bb3776064ed"
if [ "$DISPATCH_SHA" != "$EXPECTED_DISPATCH_SHA" ]; then
    echo "Google Tensor dispatcher digest mismatch; refusing APK" >&2
    exit 1
fi
# GGML_BACKEND_DL needs extracted .so files; simply shipping libraries in
# the APK with extractNativeLibs=false reproduces the original failure.
grep -Eq '^lib/arm64-v8a/libggml-cpu-android_armv8.*\.so$' "$ZIPLIST"
MANIFEST=$("$AAPT" dump xmltree "$APK" AndroidManifest.xml)
if ! printf '%s\n' "$MANIFEST" | grep -Eq 'android:extractNativeLibs.*0xffffffff'; then
    echo "Native library extraction is disabled; GGML CPU backend discovery will fail" >&2
    exit 1
fi
if grep -q '^lib/x86_64/' "$ZIPLIST"; then
    echo "Unexpected x86_64 libraries in arm64-only APK" >&2
    exit 1
fi
echo "APK metadata, signing and JNI ABI verified."
