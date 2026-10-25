# Pocket AI RC release asset integrity

The public Android app is currently installed with a **persistent debug signer**.
The certificate's public SHA-256 (not a keystore/secret) is:

    125766c26dd400e39804c7b97c36682d8a2c16caa134c58745a02aca0bd51187

CI-generated ephemeral debug APKs use a different signer and are NOT drop-in
upgrades. Never ask users to uninstall Pocket AI or clear model/chat/MCP data.

## Every published RC must have real, publicly available assets

A Git tag or successful GitHub Android CI run is **not** a release. A valid RC
requires all of these independently checked facts:

- Immutable tag vX.Y.Z-RCN and matching Gradle versionName/versionCode in the
  actual tag commit (not a newer main checkout).
- A published prerelease (not a draft) with an ARM64 APK and exact .apk.sha256
  sidecar; no missing, duplicate or extra release assets.
- GitHub release digest, independently downloaded APK SHA-256 and sidecar agree.
- APK package is me.ailinux.pocket; manifest version matches the tagged source.
- APK has the **same** upgrade-signing certificate as the public RC17.
- Android 16 KiB ZIP alignment is verified by the Android SDK build tools.
- Runtime acceptance on the actual Pixel must be recorded separately: successful
  build/validation does NOT prove native Tensor G5 generation or MCP tool calls.

**Safe publishing sequence**: create a GitHub release as a DRAFT first, upload
both assets to that draft, validate checksums and metadata, then publish it.
The read-only Published RC Asset Audit workflow triggers on publication and can
also be manually run against any published RC.

The audit workflow has only contents:read. It does not create release tags,
upload APKs, promote a draft or bypass Android device tests. A failed audit
must block further promotion and require a human-controlled forward correction.

## October 25, 2026 release incident

RC19 and RC20 had public releases with **zero APK assets**, and the immutable
RC20 tag pointed to an RC19 Gradle version. Their release metadata and tag refs
were backed up locally before changing the two empty GitHub release entries
to DRAFT. The original Git tags were NOT rewritten or deleted.

RC18 remains a QA draft. RC17 is the most recent published prerelease with a
fully independently verified, in-place-installable APK. Future releases must
use **new tags** with higher versionCode, not retag RC19 or RC20.

The audit script is scripts/audit_rc_release.py. It is a read-only check of
public release assets and the original signing certificate. The GitHub Actions
workflow is .github/workflows/rc-release-audit.yml.

## Diagnostics and user-data safety

Model downloads and AI behavior remain independently tracked under issues
#19, #20, #21, #22, #25 and #29. Audit success MUST NOT close those issues.
Keep installed models, .part files, chat history and OAuth/MCP data untouched.
