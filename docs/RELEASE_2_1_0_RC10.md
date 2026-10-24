# Pocket AI 2.1.0-RC10 — MCP/Inference diagnostics and language previews

Version: 2.1.0-RC10, versionCode 35; arm64-v8a; Android 13+.
Type: debug-signature-compatible QA pre-release, NOT production Stable.

## Implemented

- Structured MCP trace spans for both GGUF and LiteRT, as well as manual
  tools and the direct read-only quick actions: request, authorized tools/list,
  local search/details, proposal, approval/denial, current auth and schema,
  actual dispatch, genuine server response, isError and follow-up.
- Each model/remote error receives an opaque diagnostic event ID. /debug
  or /mcp debug displays bounded, host-recorded event history in chat;
  SETUP and Android process diagnostics show the same private timeline.
- Durable DISPATCH marker written before attempting tools/call. Errors
  before this point are not labeled as possibly executed. Failures after
  attempted dispatch are UNCONFIRMED and NEVER automatically retried.
- Classified errors: TENSOR_BACKEND, MODEL_KV, MEMORY, TIMEOUT,
  HTTP_AUTH, NETWORK, etc. No raw error messages in diagnostic files.
  Dedicated MODEL_LOAD and native MODEL_RESET stages include measured durations.
  Monotonically ordered event timestamps prevent out-of-order evidence even
  when several asynchronous events happen in one millisecond.
- Manual MCP tool invocations revalidate live auth, full JSON schema,
  tool annotations and arguments after consent, before one dispatch.
- Android app-language selection (System default or manual); main UI
  resourceized. German is the complete base; English plus 21 additional
  language packs are explicitly marked Preview. Dynamic dialogs and
  model/MCP messages remain partly German, and RTL/device QA is pending.

## Privacy

An app-private bounded log stores ONLY enum phase/outcome, timestamp,
duration, count, random trace and event IDs, a shortened tool-name SHA-256
fingerprint and classified error type. No prompts, chat history, URLs,
OAuth tokens, API keys, parameters, raw tool results or stack traces.
No remote telemetry or automatic diagnostic export. The user controls
clipboard export and can clear the log.

## Required QA

- Run Android JVM tests, Lint and native ARM64 APK build on the release
  host; check JNI, manifest, versionName and versionCode.
- Require green GitHub Actions Android CI, Fast Checks and GitGuardian.
  Address review comments; note rate-limited review if applicable.
- Confirm RC10 tag does not exist. Re-download public RC9, compare
  signer fingerprint against RC10 local build. Never use the temporary
  CI runner signing key as the public update key.
- Merge reviewed PR; create immutable v2.1.0-RC10 on the merge commit.
  Upload signed APK and SHA-256; download public assets again and
  verify full checksum, signer and metadata.
- On Pixel install over RC9 without uninstalling. Test GGUF and LiteRT:
  tool search/details must NOT produce SERVER_RESULT. Approve a real
  TriForce status request: require durable DISPATCH and genuine receipt.
  Deny another request: APPROVAL_DENY and no DISPATCH. Simulate a
  network timeout after dispatch: UNCONFIRMED and no automatic retry.
  Recheck model errors and switch languages without losing history.

## Deliberately unfinished

Background inference (different from RC9 background downloads),
full internationalization/RTL, scalable model-catalog paging, and
Pixel 10 Pro Tensor G5 / authenticated MCP end-to-end QA.
None of those are claimed completed by this release candidate.

## PR #23 CodeRabbit review corrections

- Android minSdk is 33 (Android 13); AppCompat autoStoreLocales for
  pre-Android-13 is not applicable to any supported install.
- One per-call atomic McpDispatchState is now shared from the actual IO
  dispatch path with the GUI caller. Coroutine cancellation AFTER dispatch
  is conservatively UNCONFIRMED; preflight cancellation stays non-dispatched.
  No automatic retry for side-effecting calls.
- Reading Android process diagnostics no longer manufactures a FAIL event.
- Approval accept/deny/cancel now records a decision for native GGUF/LiteRT
  and direct read-only paths.
- Manual MCP error dialog warns about unknown post-dispatch server effects.
- Accessibility status text no longer has an overriding static description.
- Offline/privacy notice explicitly includes optional networked MCP calls.
- Added dedicated review regression tests including cancellation-before/
  -after-dispatch; updated older source-level tests for the new call signature.
