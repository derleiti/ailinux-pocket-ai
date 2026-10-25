# AILinux Pocket AI 2.1.0-RC15 - Atomic LiteRT/GGUF model publication QA

Android arm64-v8a / package me.ailinux.pocket / versionCode 40.

This QA release builds on RC14 and addresses the observed Android 17 case
where a fully downloaded .litertlm.part remained visible, but no installed
model could be loaded. Previous publication used Files.createLink();
hardlinks can be denied on Android private storage even when the verified
fragment is complete. That could leave the model list empty at 100% bytes.

## Change

- Publish a fully validated private model by same-directory atomic rename
  using Files.move with ATOMIC_MOVE instead of requiring a hardlink.
- Keep final-file paths hidden until model header/size/checksum verification
  passes; never write an incomplete model to the installed location.
- Preserve the existing .part intact on unsupported atomic operations or
  transient I/O exceptions; never silently fall back to non-atomic copying.
- The existing model path is checked before publishing; same-process publish
  calls are synchronized and transfer ownership is serialized.
- No extra permissions, new signing identity, model downloads, or credential
  changes are introduced. The same QA signing certificate as RC14 is used.

## Carryover from RC14

RC14 introduced the more conservative LiteRT MCP native tool failure recovery,
traced source affinity and the read-only host fallback. No action can be
claimed executed without an approved DISPATCH and real SERVER_RESULT.

## Acceptance: still pending on physical device

1. Install as an update over RC14 (never uninstall, clear app data, or delete
   current full-size .part files).
2. Inspect Android JobScheduler pending reason and explicit "Fortsetzen" for
   the existing fully transferred Gemma or Qwen partial.
3. Confirm exact downloaded byte count, model header and checksum before
   marking installed; observe atomically published final path.
4. Load Gemma 4 E2B Google Tensor G5 and verify actual generation.
5. Enable AUTO MCP and verify real source-correct status discovery,
   detail schema, approval and tools/call server result on the Pixel.
6. Reproduce model-driven repeat invocation and demonstrate
   no fabricated tool success after invalid native calls.
7. Test foreground, background and locked-screen downloads separately.

Green Android CI and JVM unit tests DO NOT establish these Android runtime
tests. Long-running background inference, full localization and advanced
context/memory work remain separate issues. User-controlled Android UIDT
network estimate revision in PR #43 is intentionally not part of this release
until its CodeRabbit review blockers are resolved.

Related: PR #44, issues #27, #25, #29, #22, and the earlier RC14 release.
