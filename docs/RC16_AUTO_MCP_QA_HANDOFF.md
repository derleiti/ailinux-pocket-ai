# Pocket AI RC16 - AUTO-MCP QA handoff (25 October 2026)

## Starting state (verified in project workspace)
- Repository: derleiti/ailinux-pocket-ai
- Last published version at start: 2.1.0-RC15, tag v2.1.0-RC15.
- Origin main baseline: e97916aa5401ed186b5e7a414a1e9618e26cfa3a.
- Branch/worktree: fix/pocket-rc16-auto-mcp-native-args-20261025, /home/zombie/workspace/pocket-rc16-auto-mcp.
- Candidate: 2.1.0-RC16, versionCode 41. This is NOT a public release.
- RC15 Pixel Android 17 evidence: four completed LiteRT downloads; Gemma 4 E2B Tensor G5 loaded and generated; three MCP servers, 212 advertised definitions, 172 compatible callable definitions. Real host-assisted status calls had server receipts; native LiteRT tool calling is NOT yet consistently reliable.
- RC15 reproducer: INVALID_NATIVE_ARGUMENTS_SCHEMA -> reset -> UNVERIFIED_READONLY after a request to crawl https://ailinux.me on TriForce DEV. The real server crawl creates a persistent job and is not a pure read.

## This branch implements
- Explicit single-HTTPS-URL crawl host proposal through the real progressive catalog, marked hostWriteIntent with human review of server, exact arguments and state-changing risk. No silent dispatch.
- Live revalidation of OAuth, metadata, risk semantics and schema before executing, with existing one-call/no-retry limits. Manual selected-mode host fallback continues to exclude writes.
- Rejection of ambiguous, multiple, credential-bearing or non-HTTPS URLs and local hostnames. Final SSRF protections remain a server responsibility.
- Diagnostic distinction between native LiteRT response parsing errors and local MCP schema failures; filtered tool counts separate advertised from usable catalog definitions.
- Regression tests for all major cases.
- Manual GitHub Actions QA workflow at .github/workflows/pocket-rc16-mcp-qa.yml. It runs full tests, lint, build, package/signature verification and provenance output; never publishes or controls devices.

## Outstanding / next actions
1. Inspect git/PR/release state, working tree and credentials. Preserve changes, create backup and rollback.
2. Run all tests, lint and actual APK build/verification. Push PR, run both existing PR workflows and manual RC16 QA gate, inspect reviews and failures.
3. Wait for newly supplied Share/Workspace ID: pair/reconnect Android Helper, verify capabilities and foreground package; user-authorized computer control only.
4. On real Pixel: negative consent case (no DISPATCH); accepted source-specific read-only Workspace/DEV status (real SERVER_RESULT); repeat native LiteRT AUTO calls with 172+ catalog and exact source/schema correctness.
5. Explicit crawl may be run only with interactive user approval; verify server job receipt, no repeats or false success. No unapproved external crawler jobs.
6. Verify Gemma 4 E2B/E4B, Qwen3 1.7B/4B and GGUF real generation, token quality, latency, thermal/memory use and backend compatibility.
7. Test UIDT/WorkManager resume, interrupted and background downloads, locked screen, actual persistence and separate long-generation lifecycle.
8. Improve native ToolCall robustness (real parser issue, not hiding with host fallback), context budget/cache, model discovery and import, chat/history, locale resources, sampling, thinking, agent UI, logs and crash/ANR tracking.
9. For in-place update match the ORIGINAL RC15 QA signing certificate. A GitHub Actions ephemeral debug cert cannot be assumed compatible: DO NOT uninstall/clear app data.
10. Do not merge/tag/release until green CI, source review, cert/integrity and Pixel sign-off. Then document SHA, version, code, QA link, APK SHA256, release and exact download URL.

Definition of done: implemented, tested, integrated and verified on device. Any unverified functionality stays OPEN.

## P0/P1: LiteRT-LM compatibility beyond Google Gemma
- The Pixel Tensor G5 successful Gemma 4 E2B load is **not** proof of compatibility for other models. The user reports that non-Gemma LiteRT models are currently failing to load; individual runtime checks and diagnostics are required. Download journal `complete` proves neither model initialization nor inference.
- Re-test on the actual Pixel: `Qwen3_1.7B.litertlm`, `qwen3_4b_mixed_int4.litertlm`, `Spark-X2.6-4B_int4.litertlm`, Gemma E2B TPU and Gemma E4B GPU. Record exact file metadata, checksum, compiler target, model type, KV-cache inputs/outputs, tokenizer/chat template, LiteRT-LM runtime / Google Tensor delegate ABI, native libraries, backend requested, chosen and error codes.
- Known distinct errors: `GOOGLE_TENSOR: Failed to create engine: NOT_FOUND` (e.g. incompatible precompiled target/delegate); `GPU/CPU: No KV cache inputs found` (format/signature incompatibility); previously `SIGABRT` from template/system prompt and low-memory/kill on Spark GPU. DO NOT label all as low-RAM failures.
- Implement capability negotiation and truthful per-model backend compatibility (verified on device/probable/untested/incompatible), safe CPU or GPU fallback only if the **same model format** actually supports it; preserve detailed crash-free errors and do not erase existing models to debug.
- Consult upstream Google LiteRT-LM Android model-format and AOT compilation docs and actual packaged artifacts; compare GGUF llama.cpp templating/Jinja separately. Write instrumentation/regression tests plus physical multi-model test matrix, measured tokenization, TTFT, generation, context and memory. Target **maximum technically feasible compatibility** across model publishers, not Gemma-only or fake backend fallback.
