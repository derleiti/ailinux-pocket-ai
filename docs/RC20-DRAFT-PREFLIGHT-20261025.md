# Pocket AI release draft safety, 2026-10-25

This is source/asset quality assurance, not Android device acceptance or a
request to publish any existing draft. Revalidate live GitHub state before a
future release decision.

## Live evidence

- Latest publicly published RC: **v2.1.0-RC17**. A paired Android 17 Pixel
  reports **Pocket AI 2.1.0-RC18** in its Settings accessibility tree.
  This does **not** prove that installed APK bytes match the GitHub draft.
- Immutable tag **v2.1.0-RC20** points to commit
  7e0194ecf8a5ec62e273af554add9fa79688aaf2, but tagged
  app/build.gradle.kts has **2.1.0-RC19, versionCode 44**.
  Android tag CI run 40227482726 failed; RC20 draft has zero assets.
  **Do not publish, reuse or move this tag.** The preflight rejects it
  before downloading anything due to version mismatch.
- RC19 draft is marked withdrawn, with no APK. GitHub main at
  e80a4537ebe1ee3c0728f3af504be948be4a3ebb also identifies RC19/code44.
  Version-name and version-code changes require their own reviewed release PR.
- The **RC18 draft** had two assets, but no refs/tags/v2.1.0-RC18 on the
  remote at audit time. Draft asset browser URLs used GitHub's temporary
  untagged-... paths; the REST release-by-tag endpoint returned 404, while
  the authorized releases list contained RC18. Each explains why the
  published-only audit does not work on GitHub drafts.
- Authenticated RC18 draft asset QA passed: APK 79,396,359 bytes,
  SHA-256 80953bb51d7d1ff3beb64c6ca31251a1508faef2541fe94e53272fa423350609,
  signer certificate SHA-256
  125766c26dd400e39804c7b97c36682d8a2c16caa134c58745a02aca0bd51187,
  package me.ailinux.pocket, versionCode 43, versionName 2.1.0-RC18 and
  16-KiB alignment. This is **asset validation only**; the missing Git tag
  prevents complete release-chain preflight success.
- Open Issues #19, #20, #21, #22, #25, #29 still require Android and
  application acceptance, not just build and hash checks.

## Manual prepublication gate

The new .github/workflows/rc-release-preflight.yml runs manually
(workflow_dispatch). Supply an already existing, immutable RC tag.
It checks tagged Gradle version, two draft assets, authentication, SHA-256
against GitHub digests and the exact checksum sidecar, package/versionCode,
original RC17 upgrade signer, 16-KiB APK alignment, and that the signed APK
can be verified. Draft assets are retrieved with authenticated
gh release download rather than a temporary untagged browser URL.
The read-only workflow token must be able to see the draft; otherwise
the preflight fails closed.

The existing published audit workflow remains separate and unchanged.
This new workflow has contents:read and never creates, modifies, uploads,
publishes or deletes releases/tags, models, or phone data. Passing the
prepublication gate does NOT prove Tensor G5, MCP, app-download or
background-inference acceptance on a physical Pixel.

For a future RC, start from a reviewed main commit, bump versionCode beyond
the previous installed/published build, create a new immutable tag, stage
the signed APK and SHA sidecar, run preflight, test in-place on the Pixel
without deleting app data, then decide on publication. Never reuse the
mismatched RC20 tag or install a CI-ephemeral debug certificate.
