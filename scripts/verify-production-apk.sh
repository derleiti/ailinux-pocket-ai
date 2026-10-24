#!/usr/bin/env bash
set -euo pipefail

# Never label an RC/debug APK or unknown signing certificate as production.
APK="app/build/outputs/apk/release/app-release.apk"
if [ "$#" -gt 0 ]; then APK="$1"; fi
EXPECTED_CERT="$(printenv AILINUX_RELEASE_CERT_SHA256 || true)"
EXPECTED_VERSION="$(printenv AILINUX_EXPECTED_VERSION_NAME || true)"

if ! [[ "$EXPECTED_CERT" =~ ^[a-fA-F0-9]{64}$ ]]; then
    echo "Missing/invalid AILINUX_RELEASE_CERT_SHA256; production signing NOT VERIFIED." >&2
    exit 2
fi
if [[ -z "$EXPECTED_VERSION" || ! "$EXPECTED_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo "Set AILINUX_EXPECTED_VERSION_NAME to a stable semver without -rc." >&2
    exit 2
fi

SDK="$(printenv ANDROID_HOME || true)"
if [ -z "$SDK" ]; then SDK="$(printenv ANDROID_SDK_ROOT || true)"; fi
if [ -z "$SDK" ]; then SDK="$HOME/Android/Sdk"; fi
SIGNER="$SDK/build-tools/35.0.0/apksigner"
AAPT="$SDK/build-tools/35.0.0/aapt"
test -s "$APK"
test -x "$SIGNER"
test -x "$AAPT"

BADGING="$("$AAPT" dump badging "$APK")"
PACKAGE_NAME="$(printf '%s\n' "$BADGING" | sed -n "s/^package: name='\([^']*\)'.*/\1/p" | head -1)"
VERSION="$(printf '%s\n' "$BADGING" | sed -n "s/^package: .*versionName='\([^']*\)'.*/\1/p" | head -1)"
if [[ "$PACKAGE_NAME" != "me.ailinux.pocket" || "$VERSION" != "$EXPECTED_VERSION" ]]; then
    echo "Refusing release: package/version mismatch (name=$PACKAGE_NAME, version=$VERSION)" >&2
    exit 1
fi

./scripts/verify-apk.sh "$APK" > /dev/null
CERT_INFO="$("$SIGNER" verify --print-certs "$APK")"
if printf '%s\n' "$CERT_INFO" | grep -Eiq 'Android Debug|CN=Android Debug'; then
    echo "Refusing release: APK is signed with Android Debug test certificate." >&2
    exit 1
fi
ACTUAL="$(printf '%s\n' "$CERT_INFO" |
    sed -n 's/^Signer #1 certificate SHA-256 digest: //p' | head -1 | tr '[:upper:]' '[:lower:]')"
EXPECTED_CERT="$(printf '%s' "$EXPECTED_CERT" | tr '[:upper:]' '[:lower:]')"
if [[ "$ACTUAL" != "$EXPECTED_CERT" ]]; then
    echo "Refusing release: certificate does not match pinned production fingerprint." >&2
    exit 1
fi

echo "Production APK verified: $PACKAGE_NAME $VERSION (private release signer matched)."
sha256sum "$APK"
