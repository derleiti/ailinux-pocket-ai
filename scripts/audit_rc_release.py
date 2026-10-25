#!/usr/bin/env python3
"""Read-only quality gate for publicly published AILinux Pocket AI RC APKs.

Never treats a GitHub Release page or CI-only debug APK as proof of an
installable, signed update. Intended for the release:published event after
assets have already been uploaded to a draft, then published.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import urllib.request

PACKAGE = "me.ailinux.pocket"
RC_TAG = re.compile(r"^v(?P<version>[0-9]+\.[0-9]+\.[0-9]+-RC[0-9]+)$")
EXPECTED_CERT = "125766c26dd400e39804c7b97c36682d8a2c16caa134c58745a02aca0bd51187"
SHA = re.compile(r"^[0-9a-f]{64}$")
MAX_APK = 1024 * 1024 * 1024
MAX_CHECKSUM = 4096


class AuditError(RuntimeError):
    pass


def version_of(tag: str) -> str:
    match = RC_TAG.fullmatch(tag)
    if match is None:
        raise AuditError("Expected an immutable release-candidate tag vX.Y.Z-RCN")
    return match.group("version")


def gradle_version(text: str) -> tuple[str, int]:
    names = re.findall(r'^\s*versionName\s*=\s*"([^"]+)"', text, re.M)
    codes = re.findall(r"^\s*versionCode\s*=\s*(\d+)\s*$", text, re.M)
    if len(names) != 1 or len(codes) != 1 or int(codes[0]) <= 0:
        raise AuditError("Gradle must have exactly one valid versionName/versionCode")
    return names[0], int(codes[0])


def matching_assets(release: dict, tag: str) -> tuple[dict, dict]:
    version = version_of(tag)
    if release.get("tag_name") != tag:
        raise AuditError("Release tag does not match requested immutable tag")
    if release.get("draft") is not False or release.get("prerelease") is not True:
        raise AuditError("Release must be a published prerelease, not a draft")
    if not release.get("published_at"):
        raise AuditError("Release has no publication timestamp")
    name = f"AILinux-Pocket-AI-{version}-arm64-debug.apk"
    expected = {name, name + ".sha256"}
    assets = release.get("assets")
    if not isinstance(assets, list):
        raise AuditError("Release assets metadata missing")
    by_name: dict[str, dict] = {}
    for asset in assets:
        if not isinstance(asset, dict):
            raise AuditError("Invalid asset metadata")
        filename = asset.get("name")
        if filename in by_name:
            raise AuditError("Duplicate release asset filename")
        by_name[filename] = asset
    if set(by_name) != expected:
        raise AuditError("Published RC requires exactly an APK and its SHA-256 sidecar")
    apk, sidecar = by_name[name], by_name[name + ".sha256"]
    for obj, max_bytes in ((apk, MAX_APK), (sidecar, MAX_CHECKSUM)):
        if not isinstance(obj.get("size"), int) or not (0 < obj["size"] <= max_bytes):
            raise AuditError("Release asset has an invalid size")
        digest = obj.get("digest")
        if not isinstance(digest, str) or not digest.startswith("sha256:") or not SHA.fullmatch(digest[7:]):
            raise AuditError("Release asset needs a GitHub sha256 digest")
        if obj.get("state") not in (None, "uploaded"):
            raise AuditError("Release asset is not uploaded")
        expected_url = "https://github.com/derleiti/ailinux-pocket-ai/releases/download/" + tag + "/" + obj["name"]
        if obj.get("browser_download_url") != expected_url:
            raise AuditError("Unexpected release download URL; refusing remote download")
    if apk["size"] < 10 * 1024 * 1024:
        raise AuditError("APK too small to be a Pocket AI Android build")
    return apk, sidecar


def sidecar_sha256(content: bytes, filename: str) -> str:
    try:
        text = content.decode("utf-8").strip()
    except UnicodeDecodeError as err:
        raise AuditError("Checksum sidecar is not UTF-8") from err
    match = re.fullmatch(r"([0-9a-fA-F]{64})[ \t]+\*?" + re.escape(filename), text)
    if not match:
        raise AuditError("Checksum sidecar must name the exact APK")
    return match.group(1).lower()


def downloaded_sha256(url: str, destination: Path, size: int, cap: int) -> str:
    # HTTPS URL already validated against the fixed GitHub release path.
    digest = hashlib.sha256()
    total = 0
    request = urllib.request.Request(url, headers={"User-Agent": "AILinux-Release-Audit/1"})
    with urllib.request.urlopen(request, timeout=90) as response:
        with destination.open("wb") as output:
            while chunk := response.read(256 * 1024):
                total += len(chunk)
                if total > cap or total > size:
                    raise AuditError("Downloaded asset exceeds published size")
                digest.update(chunk)
                output.write(chunk)
    if total != size:
        raise AuditError("Downloaded asset length differs from GitHub metadata")
    return digest.hexdigest()


def verify_badging(badging: str, version: str, version_code: int) -> None:
    line = badging.splitlines()[0] if badging else ""
    properties = dict(re.findall(r"\b(name|versionCode|versionName)='([^']+)'", line))
    if properties != {"name": PACKAGE, "versionName": version,
                      "versionCode": str(version_code)}:
        raise AuditError("APK package/version differs from tagged Gradle source")


def verify_signer(output: str, expected_cert: str) -> None:
    matches = re.findall(r"Signer #\d+ certificate SHA-256 digest:\s*([0-9a-fA-F]{64})", output)
    if len(matches) != 1 or matches[0].lower() != expected_cert.lower():
        raise AuditError("APK signing certificate differs from the public RC17 upgrade signer")


def github_metadata(tag: str) -> dict:
    token = os.getenv("GH_TOKEN", "")
    req = urllib.request.Request(
        "https://api.github.com/repos/derleiti/ailinux-pocket-ai/releases/tags/" + tag,
        headers={
            "Accept": "application/vnd.github+json",
            "User-Agent": "AILinux-Release-Audit/1",
            **({"Authorization": "Bearer " + token} if token else {}),
        },
    )
    with urllib.request.urlopen(req, timeout=30) as response:
        return json.load(response)


def run(command: list[str]) -> str:
    try:
        result = subprocess.run(command, text=True, capture_output=True, check=True, timeout=50)
    except (FileNotFoundError, subprocess.TimeoutExpired, subprocess.CalledProcessError) as err:
        raise AuditError("Android build-tools verification failed for " + command[0]) from err
    return result.stdout + result.stderr


def audit(tag: str, gradle: Path, expected_cert: str) -> str:
    version = version_of(tag)
    found_version, found_code = gradle_version(gradle.read_text(encoding="utf-8"))
    if version != found_version:
        raise AuditError("Immutable RC tag does not match Gradle source version")
    if not SHA.fullmatch(expected_cert):
        raise AuditError("Expected signer certificate is malformed")
    release = github_metadata(tag)
    apk_asset, sha_asset = matching_assets(release, tag)
    sdk = Path(os.environ.get("ANDROID_HOME", str(Path.home() / "Android/Sdk")))
    tools = sdk / "build-tools" / "35.0.0"
    with tempfile.TemporaryDirectory(prefix="pocket-rc-audit-") as folder:
        target = Path(folder) / apk_asset["name"]
        checksum_target = Path(folder) / sha_asset["name"]
        for obj, dest, cap in ((apk_asset, target, MAX_APK),
                               (sha_asset, checksum_target, MAX_CHECKSUM)):
            downloaded = downloaded_sha256(obj["browser_download_url"], dest, obj["size"], cap)
            if downloaded != obj["digest"][7:]:
                raise AuditError("GitHub release asset digest differs from public download")
        expected_hash = sidecar_sha256(checksum_target.read_bytes(), target.name)
        actual_hash = apk_asset["digest"][7:]
        if actual_hash != expected_hash:
            raise AuditError("APK, GitHub digest and checksum sidecar disagree")
        verify_badging(run([str(tools / "aapt"), "dump", "badging", str(target)]),
                       found_version, found_code)
        verify_signer(run([str(tools / "apksigner"), "verify", "--print-certs", str(target)]),
                      expected_cert)
        run([str(tools / "zipalign"), "-c", "-P", "16", "4", str(target)])
    return f"Verified {tag} code={found_code} SHA256={actual_hash} cert={expected_cert}"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--gradle", type=Path, default=Path("app/build.gradle.kts"))
    parser.add_argument("--cert", default=EXPECTED_CERT,
                        help="Expected public upgrade-signer SHA-256 (not a private key)")
    args = parser.parse_args()
    try:
        print(audit(args.tag, args.gradle, args.cert))
    except (AuditError, OSError, ValueError) as err:
        print("RELEASE_AUDIT_FAIL: " + str(err), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
