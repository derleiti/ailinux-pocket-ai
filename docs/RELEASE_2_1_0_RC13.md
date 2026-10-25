# AILinux Pocket AI 2.1.0-RC13 - download completion and AUTO-MCP QA

**Android arm64-v8a, 2.1.0-RC13, versionCode 38, prerelease.**

Update RC12 in place; do not uninstall or clear application data:
the private model .part files, chat database and MCP OAuth configuration
must survive. This QA APK must be signed with the original RC9-RC12 signing
identity and must not be replaced by a GitHub Actions debug certificate.

## Changes since RC12

- Fix Qwen3_1.7B.litertlm full-byte/no-installation failure. When a
  resumed HTTP Range at EOF returns 416 without Content-Range or ETag,
  fetch bounded HTTPS HEAD metadata from original Hugging Face URL
  (X-Linked-Size and content SHA-256 X-Linked-ETag).
  Check actual local .part byte count and full SHA-256, validate LiteRT-LM
  header, then atomically install. Preserve bytes on inconclusive errors.
  Rounded 100% progress is not an integrity proof.
- Enforce the requested TriForce Workspace versus DEV MCP server through
  progressive search, detail lookup and calls. Never confuse s1/s2/s3
  local IDs across servers; fail closed if ambiguous.
- Normalize a small model's genuine native arguments_json object representation
  only for internal mcp_call_tool host meta-call, preserving complete
  schema validation, one-time user approval and server-origin receipts.
- Keep host-proposed fallback for explicitly requested, unambiguous,
  audited read-only tools when LiteRT omits a native function call.
- Preserve RC12 Android 17-compatible UIDT and network diagnostics.

## Tests

- Integration baseline: 364/364 local JVM tests, Android Lint green
  for source integration of PR #36, #37 and #38.
- Release branch: 364/364 JUnit, :app:lintDebug, :app:assembleDebug
  and scripts/verify-apk.sh passed locally on release commit 6ad3a20
  (source tree unchanged from its parent except Gradle version and this doc).
- Build/signature identity: package me.ailinux.pocket, versionCode 38,
  versionName 2.1.0-RC13, arm64-v8a; signer certificate SHA-256
  125766c26dd400e39804c7b97c36682d8a2c16caa134c58745a02aca0bd51187
  is the same as the publicly downloaded RC12 certificate.
- The expected SHA-256 for the specifically locally QA-signed release APK
  AILinux-Pocket-AI-2.1.0-RC13-arm64-debug.apk is
  e4b398de4ab72025c3d5aab4dc505f879a6585a6674d0fccb31ae029f54c9578.
  This hash must match the SHA-256 of the asset downloaded through the
  public GitHub release URL, not merely a locally generated checksum file.
- After merging, tag exactly the verified main SHA. The GitHub tag-triggered
  Android CI (not a successful run of a different commit) must have
  conclusion=success and headSha == peeled v2.1.0-RC13 tag commit.
  If the tag SHA, main SHA, CI headSha, or public APK hash differs,
  keep the release as a draft and investigate rather than publishing it.

## Required physical Pixel QA (not implied by automated tests)

1. Install RC13 over RC12. Confirm chats and model .part file survive,
   without re-downloading 2 GB.
2. Resume Qwen job. Capture queued -> verifying -> complete, actual
   file size and digest, verify model is listed.
3. Load Qwen only if LiteRT-LM runtime declares it compatible: file integrity
   does not imply Tensor G5 backend compatibility.
4. With Gemma 4 E2B Tensor in AUTO mode, request TriForce Workspace
   status twice. Verify DISCOVERY, correct server/tool, one-time approval,
   DISPATCH and genuine SERVER_RESULT.
5. Denied proposals must not dispatch, malformed JSON cannot bypass schema,
   ambiguous provider choice must fail closed.
6. Updates must not be misreported as crashes. Distinguish memory kills and
   native backend errors.

Outstanding: full background inference, exhaustive GPU/NPU compatibility,
complete localization/RTL QA and model catalogue scaling.
Do not label stable until real device acceptance passes.

Source PRs: #36 (Qwen HTTP 416), #37 (MCP source affinity),
#38 (native meta JSON arguments). Tracking: #33 and #29.
