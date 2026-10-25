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
import shutil
import tempfile
import urllib.request
import urllib.error
from urllib.parse import urlsplit

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


def matching_assets(release: dict, tag: str, *, prepublish: bool = False) -> tuple[dict, dict]:
    version = version_of(tag)
    if release.get("tag_name") != tag:
        raise AuditError("Release tag does not match requested immutable tag")
    if prepublish:
        if release.get("draft") is not True or release.get("prerelease") is not True:
            raise AuditError("Prepublish audit requires an unpublished RC draft")
    else:
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
        expected_url = ("https://github.com/derleiti/ailinux-pocket-ai/"
                        "releases/download/" + tag + "/" + obj["name"])
        if prepublish:
            # GitHub assigns untagged-<digest> browser URLs to unpublished
            # assets. They are NOT used as a download source here; the gh CLI
            # fetches a user-authenticated draft by exact tag instead.
            url = obj.get("browser_download_url")
            parts = urlsplit(url) if isinstance(url, str) else None
            if (parts is None or parts.scheme != "https" or
                parts.netloc != "github.com" or parts.query or parts.fragment or
                not re.fullmatch(
                    r"/derleiti/ailinux-pocket-ai/releases/download/"
                    r"(?:" + re.escape(tag) + r"|untagged-[0-9a-f]{8,})/" +
                    re.escape(obj["name"]), parts.path)):
                raise AuditError("Untrusted draft browser URL in release metadata")
        elif obj.get("browser_download_url") != expected_url:
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


def local_asset_sha256(path: Path, size: int, cap: int) -> str:
    # Draft release assets are fetched by authenticated GH CLI first:
    # private draft browser_download_url generally cannot be read anonymously.
    # No symlinks, paths from API metadata, or unbounded reads are trusted.
    if path.is_symlink() or not path.is_file():
        raise AuditError("Expected a regular, non-symlink local asset")
    if not (0 < size <= cap) or path.stat().st_size != size:
        raise AuditError("Local asset size differs from GitHub release metadata")
    digest = hashlib.sha256()
    total = 0
    with path.open("rb") as stream:
        while chunk := stream.read(256 * 1024):
            total += len(chunk)
            if total > size or total > cap:
                raise AuditError("Local asset exceeds published size")
            digest.update(chunk)
    if total != size:
        raise AuditError("Incomplete local release asset")
    return digest.hexdigest()


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


def github_metadata(tag: str, *, prepublish: bool = False) -> dict:
    token = os.getenv("GH_TOKEN", "")
    base = "https://api.github.com/repos/derleiti/ailinux-pocket-ai/releases"
    headers = {
        "Accept": "application/vnd.github+json",
        "User-Agent": "AILinux-Release-Audit/1",
        **({"Authorization": "Bearer " + token} if token else {}),
    }

    def fetch(url: str):
        with urllib.request.urlopen(urllib.request.Request(url, headers=headers),
                                    timeout=30) as response:
            return json.load(response)

    try:
        result = fetch(base + "/tags/" + tag)
        if not isinstance(result, dict):
            raise AuditError("Malformed release-by-tag metadata")
        return result
    except urllib.error.HTTPError as error:
        # GitHub's release-by-tag endpoint can return 404 for an authenticated
        # draft that DOES appear in the releases listing. No fallback for
        # published audits: their public immutable URL must work.
        if not prepublish or error.code != 404:
            raise AuditError("Cannot read release-by-tag metadata (HTTP " +
                             str(error.code) + ")") from error

    # Bounded scan for the exact draft tag. In particular, never select
    # "latest", a similarly named tag or a release by its display title.
    for page in range(1, 6):
        try:
            batch = fetch(base + "?per_page=100&page=" + str(page))
        except urllib.error.HTTPError as error:
            raise AuditError("Cannot read authenticated draft release list (HTTP " +
                             str(error.code) + ")") from error
        if not isinstance(batch, list):
            raise AuditError("Malformed draft release list")
        found = [item for item in batch if isinstance(item, dict) and
                 item.get("tag_name") == tag and item.get("draft") is True]
        if len(found) > 1:
            raise AuditError("Duplicate matching draft releases")
        if found:
            return found[0]
        if len(batch) < 100:
            break
    raise AuditError("Draft tag not accessible or not present in authenticated release list")


def run(command: list[str]) -> str:
    try:
        result = subprocess.run(command, text=True, capture_output=True, check=True, timeout=50)
    except (FileNotFoundError, subprocess.TimeoutExpired, subprocess.CalledProcessError) as err:
        raise AuditError("Android build-tools verification failed for " + command[0]) from err
    return result.stdout + result.stderr


def audit(tag: str, gradle: Path, expected_cert: str, *,
          prepublish: bool = False, asset_dir: Path | None = None,
          metadata_only: bool = False) -> str:
    version = version_of(tag)
    found_version, found_code = gradle_version(gradle.read_text(encoding="utf-8"))
    if version != found_version:
        raise AuditError("Immutable RC tag does not match Gradle source version")
    if not SHA.fullmatch(expected_cert):
        raise AuditError("Expected signer certificate is malformed")
    if prepublish and asset_dir is None and not metadata_only:
        raise AuditError("Draft asset verification needs --asset-dir (authenticated gh release download)")
    if not prepublish and (asset_dir is not None or metadata_only):
        raise AuditError("Published audits must independently download public release assets")
    release = github_metadata(tag, prepublish=prepublish)
    apk_asset, sha_asset = matching_assets(release, tag, prepublish=prepublish)
    if metadata_only:
        return f"Draft metadata valid: {tag} code={found_code}; two bounded assets announced"
    sdk = Path(os.environ.get("ANDROID_HOME", str(Path.home() / "Android/Sdk")))
    tools = sdk / "build-tools" / "35.0.0"
    with tempfile.TemporaryDirectory(prefix="pocket-rc-audit-") as folder:
        target = Path(folder) / apk_asset["name"]
        checksum_target = Path(folder) / sha_asset["name"]
        assets = ((apk_asset, target, MAX_APK), (sha_asset, checksum_target, MAX_CHECKSUM))
        if asset_dir is not None:
            if not asset_dir.is_dir() or asset_dir.is_symlink():
                raise AuditError("Draft asset directory missing or unsafe")
            expected_names = {apk_asset["name"], sha_asset["name"]}
            if {entry.name for entry in asset_dir.iterdir()} != expected_names:
                raise AuditError("Draft asset directory must contain only the two verified names")
        for obj, dest, cap in assets:
            if asset_dir is not None:
                source = asset_dir / obj["name"]
                downloaded = local_asset_sha256(source, obj["size"], cap)
                if downloaded == obj["digest"][7:]:
                    # Copy only already verified, bounded bytes from the GH CLI
                    # staging dir into the private temporary audit directory.
                    shutil.copyfile(source, dest, follow_symlinks=False)
                    downloaded = local_asset_sha256(dest, obj["size"], cap)
            else:
                downloaded = downloaded_sha256(obj["browser_download_url"], dest, obj["size"], cap)
            if downloaded != obj["digest"][7:]:
                raise AuditError("GitHub release asset digest differs from verified bytes")
        expected_hash = sidecar_sha256(checksum_target.read_bytes(), target.name)
        actual_hash = apk_asset["digest"][7:]
        if actual_hash != expected_hash:
            raise AuditError("APK, GitHub digest and checksum sidecar disagree")
        verify_badging(run([str(tools / "aapt"), "dump", "badging", str(target)]),
                       found_version, found_code)
        verify_signer(run([str(tools / "apksigner"), "verify", "--print-certs", str(target)]),
                      expected_cert)
        run([str(tools / "zipalign"), "-c", "-P", "16", "4", str(target)])
    state = "unpublished draft candidate" if prepublish else "public RC"
    return f"Verified {state} {tag} code={found_code} SHA256={actual_hash} cert={expected_cert}"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--gradle", type=Path, default=Path("app/build.gradle.kts"))
    parser.add_argument("--prepublish", action="store_true",
                        help="Audit a draft before publish; requires --asset-dir unless --metadata-only")
    parser.add_argument("--asset-dir", type=Path,
                        help="Authenticated gh release download directory for draft assets only")
    parser.add_argument("--metadata-only", action="store_true",
                        help="Validate draft tag/source/release assets metadata before downloading")
    parser.add_argument("--cert", default=EXPECTED_CERT,
                        help="Expected public upgrade-signer SHA-256 (not a private key)")
    args = parser.parse_args()
    try:
        print(audit(args.tag, args.gradle, args.cert, prepublish=args.prepublish,
                    asset_dir=args.asset_dir, metadata_only=args.metadata_only))
    except (AuditError, OSError, ValueError) as err:
        print("RELEASE_AUDIT_FAIL: " + str(err), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
