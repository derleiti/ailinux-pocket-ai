#!/usr/bin/env python3
"""Remove native debug symbols, align 16K libraries and sign with Android DEBUG key.

Produces an alpha/test APK only; NOT a production release signing workflow.
"""
import os
import pathlib
import subprocess
import sys
import tempfile
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
SDK = pathlib.Path(os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or "")
if not SDK.is_dir():
    raise SystemExit("ANDROID_HOME/ANDROID_SDK_ROOT must point to the Android SDK")
TOOLS = SDK / "build-tools" / "35.0.0"
STRIP = SDK / "ndk" / "29.0.13113456" / "toolchains" / "llvm" / "prebuilt" / "linux-x86_64" / "bin" / "llvm-strip"
DEBUG_KEY = pathlib.Path.home() / ".android" / "debug.keystore"
SOURCE = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
OUTPUT = ROOT / "dist/AILinux-Pocket-AI-1.0.0-rc.9-arm64-debug.apk"

def run(*args):
    subprocess.run([str(v) for v in args], check=True, stdout=subprocess.DEVNULL)

def build():
    for req in (STRIP, TOOLS / "zipalign", TOOLS / "apksigner", SOURCE, DEBUG_KEY):
        if not req.exists():
            raise RuntimeError("Required build/signing file missing: " + str(req))
    OUTPUT.parent.mkdir(exist_ok=True, parents=True)
    with tempfile.TemporaryDirectory(prefix="pocket-compact-") as tmp:
        temp = pathlib.Path(tmp)
        archive = temp / "unsigned.zip"
        aligned = temp / "aligned.apk"
        with zipfile.ZipFile(SOURCE, "r") as original, zipfile.ZipFile(archive, "w", allowZip64=True) as packed:
            for info in original.infolist():
                if info.filename.startswith("META-INF/") and info.filename.lower().endswith((".rsa", ".sf", ".dsa", ".ec", "manifest.mf")):
                    continue
                data = original.read(info)
                compression = info.compress_type
                if info.filename.startswith("lib/") and info.filename.endswith(".so"):
                    native = temp / "native.so"
                    stripped = temp / "stripped.so"
                    native.write_bytes(data)
                    run(STRIP, "--strip-unneeded", "-o", stripped, native)
                    data = stripped.read_bytes()
                    compression = zipfile.ZIP_STORED
                packed.writestr(info, data, compress_type=compression)
        run(TOOLS / "zipalign", "-P", "16", "-f", "4", archive, aligned)
        run(TOOLS / "apksigner", "sign",
            "--ks", DEBUG_KEY,
            "--ks-key-alias", "androiddebugkey",
            "--ks-pass", "pass:android",
            "--key-pass", "pass:android",
            "--v1-signing-enabled", "false",
            "--v2-signing-enabled", "true",
            "--v3-signing-enabled", "true",
            "--out", OUTPUT, aligned)
    run(TOOLS / "zipalign", "-c", "-P", "16", "4", OUTPUT)
    run(TOOLS / "apksigner", "verify", OUTPUT)
    print("Compact, aligned and debug-signed:", OUTPUT)
    print("Size:", round(OUTPUT.stat().st_size / (1024 * 1024), 1), "MiB")

if __name__ == "__main__":
    build()
