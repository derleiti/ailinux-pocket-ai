#!/usr/bin/env python3
"""Offline guard BEFORE tagging/uploading an AILinux Pocket AI RC.

This validates local Gradle source, not APK signing, CI, device QA or release
assets. Never creates/pushes/replaces tags or makes a public release.
"""
from __future__ import annotations
import argparse
from pathlib import Path
from audit_rc_release import AuditError, gradle_version, version_of


def validate_source(tag: str, gradle_text: str, min_version_code: int) -> tuple[str, int]:
    version = version_of(tag)
    actual, code = gradle_version(gradle_text)
    if version != actual:
        raise AuditError(f"Refusing tag {tag}: Gradle versionName does not match")
    if code <= min_version_code:
        raise AuditError("Refusing candidate: versionCode must increase for in-place upgrades")
    return actual, code


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--tag", required=True)
    parser.add_argument("--gradle", type=Path,
                        default=Path("app/build.gradle.kts"))
    parser.add_argument("--min-version-code", type=int, default=44)
    args = parser.parse_args()
    try:
        version, code = validate_source(args.tag, args.gradle.read_text(),
                                        args.min_version_code)
    except (AuditError, OSError) as err:
        parser.exit(2, f"Release source guard: {err}\n")
    print(f"Metadata source PASS: tag={args.tag} versionName={version} versionCode={code}")
    print("NOT an APK/signer, Pixel or release-publication verification.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
