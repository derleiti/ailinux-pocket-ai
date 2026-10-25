# Pocket AI 2.1.0-RC14 - AUTO-MCP native-detail recovery QA

Android 13+; arm64-v8a; me.ailinux.pocket; versionCode 39.

This RC is an in-place QA update over RC13. Install without uninstalling
or clearing app data. Keep installed models, ongoing Qwen partials, chat
history, and encrypted MCP credentials. It uses the original RC9-RC13
compatible Android QA signing certificate, not the temporary CI key.

## Proven real device behavior before this candidate

On Android 17 / Pixel 10 Pro / Gemma 4 E2B GOOGLE_TENSOR, AUTO LOW
discovered 172 MCP tools across Workspace, Discord, and DEV. A real, approved
host-assisted read-only status invocation produced DISPATCH and SERVER_RESULT
at 05:17:16 (383 ms). Other turns failed with:

- INVALID_NATIVE_ARGUMENTS_SCHEMA after native SEARCH/DETAILS (05:19).
- HOST_FALLBACK / AMBIGUOUS_TOOL after that malformed call.
- HOST_FALLBACK / UNVERIFIED_READONLY when the live server did not provide
  a verified readOnlyHint for the selected tool.

These are different faults; they must not be conflated or hidden by a
model-generated success claim.

## Changes

- Retain the exact, previously searched and detailed local tool ID in the
  ephemeral progressive MCP turn when LiteRT native function-call argument
  parsing fails.
- After successful local model reset, offer a safe proposal only using
  the already-detailed canonical ID, a uniquely requested server and a
  server-declared read-only tool. Do not guess a missing parameter.
- Support explicitly requested Workspace status, including the observed
  "worspace" misspelling, without breaking duplicate-name source isolation.
- Preserve original JSON Schema validation, fresh OAuth/auth checks,
  independent one-shot consent and actual tools/call evidence.
- Never repeat a previously dispatched call or mark an unsupported read-only
  annotation as safe. UNVERIFIED_READONLY is a correct guardrail.
- Add private DETAIL_REUSE events with no credentials/arguments/URLs/body.
- Add regression coverage for 173-tool, three-server catalog and ambiguous,
  unauthorized, required-argument, explanation-only and repeat-dispatch cases.

## Acceptance required after installation

1. Confirm version 2.1.0-RC14, versionCode 39 on the Pixel via in-app setup.
2. Load Gemma 4 E2B with actual GOOGLE_TENSOR backend; leave AUTO LOW
   connected to the existing Workspace/Discord/DEV profiles.
3. Request Workspace status twice, then DEV status, with independent approvals.
4. If LiteRT returns malformed native args after details, check for
   SCHEMA_CHECK FAIL, MODEL_RESET, DETAIL_REUSE, APPROVAL, DISPATCH,
   SERVER_RESULT. Without SERVER_RESULT do not claim execution.
5. A bare generic "TriForce MCP status" remains ambiguous across two
   status tools; no silent server selection.
6. A tool without true readOnlyHint stays blocked; no bypass.
7. Verify installed models/chats remain; inspect the Qwen3_1.7B LiteRT and
   Qwen3-4B GGUF partials. Do not delete or overwrite any existing data.
8. Validate downloads by transferred bytes, completed file signature and
   checksum, not a UI display rounded to total bytes.

Known incomplete: long-running background inference; complete locale/RTL
coverage; exhaustive model compatibility; device-confirmed repeat-call
reliability of the new patch and finalization of existing Qwen downloads.
Track follow-ups in issues #19, #20, #21, #22, #25, #27 and #29.

Releases are published only after successful CI on exact merge commit, tag
verification, original signer comparison, public APK re-download and SHA-256.
