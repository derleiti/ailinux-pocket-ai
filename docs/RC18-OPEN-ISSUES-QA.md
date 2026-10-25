# Pocket AI 2.1.0-RC18 – imported issue/PR gate

Updated: 2026-10-25, Europe/Berlin. Source of truth: the **live GitHub** issue/PR pages and the paired Android 17 device. This is an audit/acceptance matrix, **not** a claim that issues are completed. Use trace IDs and UTC timestamps to correlate events across the 2026-10-25 daylight-saving transition.

## Integration and rollback

- Baseline: GitHub `main` commit `2848a4c68ccb906d4405a1f543d2f0e228edeb50` (includes RC18 `versionCode=43` preparation and the merged RAM-guard diagnostics from PR #53).
- In the isolated QA worktree: [PR #55](https://github.com/derleiti/ailinux-pocket-ai/pull/55) Tensor G5 source-pinned dispatcher and [PR #54](https://github.com/derleiti/ailinux-pocket-ai/pull/54) tiny-model MCP architecture routing. **Do not double-merge the same changes** after this integration is merged. Verify SHA ancestry/diffs before closing a redundant PR.
- RC17 public APK/tag remain untouched. Its update signing certificate SHA-256 is `125766c26dd400e39804c7b97c36682d8a2c16caa134c58745a02aca0bd51187`. Retain the working RC17 APK, its signing keystore, model files, chat history, MCP/OAuth profiles and `.part` downloads for rollback; do not downgrade an app with data without assessing Android's downgrade restriction.
- The new Tensor binary comes from LiteRT-LM v0.18.0's exact LiteRT source pin. A matching ELF digest and build **does not prove** the Pixel NPU path succeeds. Do not remove or overwrite the old binary's Git history.

## GitHub issue import and acceptance

| Issue | RC18 evidence / remaining work | Status at import |
|---|---|---|
| [#19](https://github.com/derleiti/ailinux-pocket-ai/issues/19) Background GGUF/LiteRT generation | Activity-bound inference and opt-in Android foreground-service lifecycle, notification, persistence, process recovery, no repeated side effects; screen-off/Doze Pixel test | **Open – implementation required** |
| [#20](https://github.com/derleiti/ailinux-pocket-ai/issues/20) Per-app locale and real translations | Resource extraction, verified English/German strings, Android per-app locale persistence, language-switch and RTL coverage tests; never translate MCP IDs/schemas | **Open – implementation required** |
| [#21](https://github.com/derleiti/ailinux-pocket-ai/issues/21) Model catalogue/download integrity | HF paging/caching/compatibility and full download validation. On Pixel, `qwen3_4b_mixed_int4.litertlm` showed `complete` in the download journal but the final file was **missing or wrong size**; preserve existing partials | **Open – runtime/storage recovery required** |
| [#22](https://github.com/derleiti/ailinux-pocket-ai/issues/22) Pixel 10 Pro acceptance | RC17 in-place update retained models/chats and Qwen3 1.7B GPU generated real text. Pixel reported low-memory exits with large models. Need cold Gemma E2B G5 text generation with **new dispatcher**, repeated-model switch, memory and download interruption, screen-off/notifications, and signed RC18 device install | **Open – hardware validation required** |
| [#25](https://github.com/derleiti/ailinux-pocket-ai/issues/25) Repeat native FunctionCalls | Some model turns return prose instead of native call; conservative host proposal exists for narrowly audited read-only requests, but must demonstrate repeat prompt / accept / deny / actual receipt without duplicate actions | **Open – repeatability QA required** |
| [#29](https://github.com/derleiti/ailinux-pocket-ai/issues/29) Native MCP schema/followup | Pixel traces include `INVALID_NATIVE_ARGUMENTS_SCHEMA` and `SCHEMA` after native details. Real approved host dispatch and `SERVER_RESULT` observed, but these **do not** prove native `MODEL_FOLLOWUP`. Require native end-to-end with correct tool/arguments, clear provenance and fail-closed errors | **Open – native end-to-end QA required** |

## Verifiable device evidence (public RC17, not yet RC18)

- MCP catalogue: **212 server-advertised** tool definitions across 3 sources; **172 compatible**, 40 excluded, plus 3 bounded app-local meta-tools.
- `TENSOR_BACKEND` early model-load errors on Tensor G5 include traces `9b54cd33` and `1dbbd542`. The cause is not proven; a source-version mismatch in the old bundled Tensor dispatcher was found and addressed by PR #55 only for subsequent **device testing**.
- Real approved host-assisted `DISPATCH -> SERVER_RESULT` exists, e.g. trace `4350abc6`, but a separate native schema error appears in the same session. Do not equate host fallback with native tool success.
- Trace `b73756fc` (17:14:26 to 17:20:31 local) ended `LITERT/CANCELLED` with **no** server receipt; its initiator is undetermined.
- Android system exit data show **Low Memory** at 16:57:33 with Qwen3.5-0.8B loaded (RSS ~2.79 GB) and another at 15:14:49 during Qwen3 1.7B backend loading (RSS ~5.45 GB). Neither alone implies an ANR or native fatal signal.

## Mandatory gates before a new public RC build/release

1. Inspect all currently open issues, PRs and reviews; import only source changes actually ready for integration. Keep all six unresolved issues open with evidence and a next step.
2. Build **combined source**, not two unrelated green CI artifacts. Require unit tests, lint, ARM64 package, pinned Tensor library digest, APK manifest, 16-KiB alignment, preserved RC17 signer, clean diff and no leaked secrets.
3. GitHub CI, CodeRabbit and GitGuardian must have no unresolved blocking checks. Recheck `main` at merge time; do not overwrite tags.
4. Install the combined debug APK in place over the existing device build. Check chat/data retention, cold Gemma E2B G5 load **plus tokens**, Qwen regression and RAM behavior. Test MCP denial (no dispatch), approval (actual receipt), native `MODEL_FOLLOWUP` versus host fallback, and exact download state. Never retry unknown-outcome tool calls automatically.
5. Only after the device QA and checks pass, produce an RC18 candidate/release with `versionCode=43` and the original signer. Verify public download with an independent SHA-256, APK manifest and signing certificate. If a gate fails, publish no release and keep the QA branch for repair.

A GitHub workflow's successful exit is **only one gate**: it cannot replace Pixel hardware verification or make unresolved Issues #19–#22/#25/#29 disappear.
