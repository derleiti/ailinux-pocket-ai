# Pocket AI 0.2.0-rc.1 — ARM64 test candidate

Status: Android debug-signed test candidate, not a production release.
VersionCode 25; previous RC14 was versionCode 24.

## Changes

- Brumo persona is the default for new profiles and the legacy stock prompt;
  genuinely customized system prompts are preserved.
- SETUP > Konversation zurücksetzen keeps loaded GGUF weights and the
  previously decoded SYSTEM/MCP KV prefix; a 710-token re-prefill is avoided
  when KV rollback succeeds. No native or remote tool is replayed.
- LiteRT-LM chat reset creates a fresh conversation while retaining an
  initialized LiteRT engine and model weights.
- Spark incomplete reasoning recovery discards a truncated partial thinking
  turn, retaining the exact bound SYSTEM/MCP prefix. On unsupported cache
  rollback, an explicit model reload is required; never auto-retry a tool call.
- Hugging Face search offers GGUF and LiteRT-LM formats using a read-only
  TriForce search endpoint with an official Hugging Face API fallback;
  recommended small GGUF Q4_K_M files are checked live.
- Import/download/export supports GGUF and LiteRT-LM binary headers and
  direct HTTPS model links. Public metadata queries do not upload prompts.
- LiteRT-LM 0.12.0 and LiteRT 2.3.0 provide a separate NPU -> GPU -> CPU
  backend-attempt sequence for eligible Google Pixel 10/11 devices;
  non-Pixel devices start with GPU -> CPU. Actual engine initialization must
  succeed before advertising the selected backend. It does not accelerate
  GGUF; Spark continues to use llama.cpp CPU.
- LiteRT chat does not perform model-directed MCP calls until a dedicated
  user-consent/host receipt boundary is implemented. Existing GGUF MCP
  approval and direct host quick actions are separate.
- Android excessive-resource termination (reason code 9) now has a named
  process-diagnostics label. Process phase is only last known activity.

## Proven by host checks

- RC14 public asset downloaded, verified SHA256 checksum and verified signed
  Android package and matching RC13 test signature; RC14 CI successful.
- Kotlin/Android unit tests, Android debug compile, APK verification and
  ARM64 JNI export checks are local host checks, NOT physical Pixel tests.
- TriForce Hugging Face catalogue queries can return public filtered model
  metadata; no user data or private model files are proxied.

## Open and blocked by device QA

On October 24, 2026, an actual Android process record with reason code 9
reported EXCESSIVE CPU USAGE over a 300117 ms window (CPU time 103880ms);
last saved phase system_prompt; 3.16 GB RSS. This is an Android system
termination, not an ordinary package update. The new fast chat reset prevents
one unnecessary repeated prefill but does not prove first-time prefill is
fast enough or that Android will no longer terminate the process.

Physical Pixel 10 tests remain required for TPU initialization, compatible
Tensor-G5-compiled LiteRT-LM file loading, actual generated tokens, temperature,
native Spark -> MCP tool planning, explicit user consent, genuine tools/call
receipts, OAuth and app update retention. In-place installation requires
identical signing certificate; do not uninstall or clear app data.

A public test-candidate APK must be verified before updating issues #1 and #2.
