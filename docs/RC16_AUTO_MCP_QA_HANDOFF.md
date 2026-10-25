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


## Live Android 17 AUTO-MCP verification, 25 October 2026 (RC15)

- Paired Workspace Helper reported online on Android 17 Pixel, computer control enabled.
- Pocket AI SETUP confirmed installed version 2.1.0-RC15; 4 installed LiteRT models / ~10.06 GB (Qwen3 1.7B, Qwen3 4B Mixed, Gemma 4 E4B GPU, Gemma 4 E2B Tensor G5).
- Model gemma-4-E2B-it_Google_Tensor_G5.litertlm successfully loaded; UI confirmed LITERT BEREIT / GOOGLE_TENSOR and MCP AUTO LOW.
- Natural-language request to retrieve TriForce Workspace system status: server catalog discovery reported 172 compatible definitions on 3 servers; s1/status was top search result (six shown); the original input schema was loaded.
- An explicit one-shot approval dialog showed actual Workspace endpoint, status and empty arguments. After approval, the chat showed ECHTE MCP-SERVERANTWORT (App-gestützt), including server timestamp 2026-10-25T06:31:17.873451+00:00 and reported active TriForce/Ollama/Redis status. This verifies one host-assisted tools/call, NOT fully native model-driven multi-turn function calling.
- A second explicit request for TriForce Workspace status returned HOST_FALLBACK / AMBIGUOUS_TOOL and did not dispatch. The original wording included status-Werkzeug und frage...; lexical extraction could misidentify a conjunction as a function name. A new exact-prompt unit regression reproduced failure without the fix and passed with the grammar-word filter. Strict source whitelist and one-time approval remain enforced.
- Some server status metadata lack readOnlyHint=true, causing the approval dialog to classify an otherwise observational status call conservatively as potentially writing. No trust elevation was applied.
- Qwen3_1.7B.litertlm appeared installed (1.92 GB). Loading, generating, backend choice and checksum verification were not physically tested in this pass. Old queued-download report is not proof of the current job state.

## RC16 candidate verification after Android-prompt regression

- Local source: fix/pocket-rc16-auto-mcp-native-args-20261025; versionName 2.1.0-RC16, versionCode 41.
- 394/394 JVM unit tests passed, plus Android Lint, full ARM64 assembleDebug, compact QA packaging and scripts/verify-apk.sh.
- Local generated APK SHA-256: 460bc0f130fe899bafae1905350db4f5af65107e3af672744482a54e79adc8e8.
- Local RC16 signer SHA-256 matches independently downloaded, SHA-checked public RC15 certificate: 125766c26dd400e39804c7b97c36682d8a2c16caa134c58745a02aca0bd51187.
- Local KSP Room build needs a native SQLite JDBC library path on this workstation; this is a build-host environment issue, not an Android runtime error.
- Physical RC16 on-device QA and public release remain not done. Avoid misreporting the RC15 status receipt as an RC16 pass.
