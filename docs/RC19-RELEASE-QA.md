# Pocket AI 2.1.0-RC19 / versionCode 44

## Candidate scope

Integration source base: PR #56, including PR #54 conceptual MCP guide,
PR #55 LiteRT-LM 0.18.0 pinned Google Tensor G5 dispatcher, RC18 memory,
download and MCP compatibility fixes, with subsequent device-diagnostics
regressions.

New RC19 target fixes:
- Conservative non-overridable Tensor NPU memory floor after repeated Android
  LOW_MEMORY process exits with Spark-X; preserve real GPU/CPU fallback only
  for genuinely compatible LiteRT graphs.
- Before an engine unload, Android lowMemory must immediately refuse a model
  switch and preserve the existing session. Re-check after engine release.
- Partial MCP discovery: trace each of the three source ordinal slots,
  classify HTTP failures, retain live sources under their original identities,
  report offline sources without leaking endpoints/tokens. Do not auto retry
  a tools/call with uncertain outcome.
- GGUF initialization failures: privacy-safe phase errors for metadata,
  previous-engine release, native load, thinking, prompt/template and
  sampling; reclaim native engine if post-load initialization fails.
- Capped LiteRT MCP output, repaired download journal UI, checksum provenance
  distinctions, saved source metadata and conservative recovery.

## Source and device acceptance

Before any public prerelease publication:
- PR #56 merged with green fast/unit, full Android CI and security checks.
- PR #54 and #55 closed only once their exact functional patches are present
  in Main via PR #56; do not merge duplicate binary changes separately.
- RC19 branch and tag must derive from the merged Main, not an old RC17 tag.
- Local/unit/lint/assemble passes; signed RC19 APK versionCode 44 and package
  me.ailinux.pocket; certificate SHA-256 must equal public RC17 certificate
  125766c26dd400e39804c7b97c36682d8a2c16caa134c58745a02aca0bd51187.
- Re-download public asset, compare SHA-256, verify Android APK signing,
  manifest metadata, arm64 binary and real dispatch ELF hash.
- Real Pixel: verify in-place update retains chats, models, and auth; no
  unaccepted tools/call; native generation; model switch and LMK evidence.

## Deliberate limitations / open gates

As of this candidate, GitHub issues #19, #20, #21, #22, #25, and #29 have not
all met their full acceptance criteria. In particular, long-running background
inference, complete multilingual coverage, verified catalogue pagination and
end-to-end native 0.8B tool calling remain device/integration gates. Do not
automatically close them or advertise them as fully solved.

A published RC19 (if accepted) is a QA prerelease, not a verified stable build.
CI's ephemeral debug signature is NOT accepted as an in-place upgrade. Use
only the persistent project signer matching RC17/RC18 for user-facing assets.
