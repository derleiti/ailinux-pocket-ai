# 2.1.0-RC21 (versionCode 45): QA candidate, NOT publicly released

Reference: GitHub issue #66 (invalid public RC20 and v2.2.0 releases), device issue #22. This branch starts at verified main merge commit c4c056135d30cd176f8b89a36422f734f6e0e282, which includes PR #65 Tensor-G5 backend affinity, the combined RC18 issue/test integration and further RC19/RC20 diagnostics fixes. Prior invalid RC19, RC20 and v2.2.0 tags MUST NOT be reused or overwritten.

## Source and APK constraints

- Candidate tag only if later explicitly approved: `v2.1.0-RC21`; Gradle `versionName=2.1.0-RC21`, `versionCode=45` (higher than the existing RC18 installation with code 43 and previous source RC19 code 44).
- Before any immutable tag or draft, use `python3 scripts/verify_rc_source.py --tag v2.1.0-RC21 --min-version-code 44`. This checks **metadata only**. A mismatch is a hard failure.
- Android CI, Fast Checks, unit tests, Lint, ARM64 APK and JNI dispatch digest must pass on the exact merged main SHA. A CI-debug APK with ephemeral signer is **not** an in-place upgrade.
- Local upgrade signing fingerprint must equal RC17 original SHA-256 `125766c26dd400e39804c7b97c36682d8a2c16caa134c58745a02aca0bd51187`. Do not commit the private keystore or release credentials. Verify APK badging and SHA-256 independently.
- Candidate assembly is not a public release. Preserve app data (chat, OAuth/MCP profiles, downloaded models, partial downloads) and compare installed Pixel package version **before and after** any install.

## Pixel 10 Pro hardware acceptance still needed

- RC18 remains visible in the paired Pixel UI as of 2026-10-25. Do not claim PR #65's NPU filtering has been tested on it yet. Pixel helper has reported low-ish battery/temperature; avoid unnecessary repeated multi-GB model initialization under memory pressure.
- Verify Gemma 4 E2B Google Tensor G5 official file hash, native dispatcher extraction and *actual generated text* from target NPU; successful `Engine.initialize()` alone is insufficient. Verify Qwen3 known GPU and unverified Qwen/Spark skip NPU attempts. Monitor Android LMK, byte-level download integrity and the expensive NPU/CPU fallback sequence.
- MCP end-to-end: verify native FunctionCall vs host proposal, explicit user consent, actual DISPATCH/SERVER_RESULT and MODEL_FOLLOWUP. Reject DENY paths without dispatch or replay. Issues #19, #20, #21, #22, #25 and #29 remain open until independently demonstrated.

## Draft-first publication contract

1. Merge this PR only after its own GitHub gates pass; record merged main commit.
2. Build sign-matching QA APK from that exact main commit. Verify file locally, SHA sidecar and original signer without exposing secrets.
3. Device install without wipe followed by real tests. Only then create a **new** immutable RC21 tag, an unpublished GitHub draft with exactly APK + sidecar, and run `rc-release-preflight.yml` for that tag.
4. Publish only when all gates pass. `rc-release-audit.yml` is a *post*-publication check and does not prevent manual bypass; never interpret a published GitHub page as a verified APK. Do not create a semver `v2.2.0` from RC19 Gradle metadata.
