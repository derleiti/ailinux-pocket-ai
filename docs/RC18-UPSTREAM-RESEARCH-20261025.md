# Pocket AI RC18: official upstream findings and implementation gates

Research date: 2026-10-25 (Europe/Berlin). This is verified upstream guidance for the open RC18 issues, not a declaration of resolved device failures. Link to this note from the combined PR and re-check release/API versions before implementation. Scope: Pixel 10 Pro / Android 17 / LiteRT-LM 0.18.0, Google Tensor G5, MCP, model downloads, lifecycle and UI.

## P0: Tensor G5 / LiteRT-LM / ABI

Primary official documentation:
- https://developers.google.com/edge/tensor-sdk/release-notes
- https://developers.google.com/edge/tensor-sdk/tensor-faqs
- https://developers.google.com/edge/litert/next/npu
- https://developers.google.com/edge/litert-lm/android
- https://github.com/google-ai-edge/LiteRT/issues/7787
- https://github.com/google-ai-edge/LiteRT-LM/releases

Findings:
- Google Tensor SDK v2.1 (released 2026-10-14) lists Tensor G5 with stable LiteRT 2.3.0 and Android build 26Q3. Pocket AI already pins LiteRT 2.3.0 and LiteRT-LM 0.18.0. Do NOT change those pins merely because 0.18.1 exists; its published change targets an EmbeddingGemma 2 source-build ABI problem.
- Google Tensor NPU execution is AOT-based; Google Tensor SDK currently does NOT support on-device JIT compilation. Loading an arbitrary model or bundling a dispatcher cannot prove NPU compatibility.
- The official Android Kotlin guide calls for Backend.NPU with context.applicationInfo.nativeLibraryDir when packaged, and optional libOpenCL.so and libvndksupport.so manifest declarations for GPU. These are already present in the RC18 integration.
- The Google Tensor compiler FAQ recommends stable ai-edge-litert 2.3.0 with GOOGLE_TENSOR_BACKEND_ENABLED=1 when actually AOT-compiling Google-Tensor-targeted models. This is a BUILD environment setting, NOT an Android runtime configuration.
- Upstream Tensor G5 issue 7787 showed dispatcher/runtime/toolchain mismatches, required native library extraction and silent CPU fallbacks. It was resolved after correcting AOT compiler/runtime and packaging pairing; its classical-ML example is NOT proof of a Gemma 4 E2B LLM fix. Pocket AI already sets packaging.jniLibs.useLegacyPackaging = true. Verify the Google dispatcher physically in applicationInfo.nativeLibraryDir on the Pixel; finding the .so in the APK ZIP is not enough.

RC18 action:
- Preserve source-pinned libLiteRtDispatch_GoogleTensor.so from PR #55, its exact source commit, ELF ABI, export and SHA; do not mix with other runtime binary pins.
- Inspect Gemma E2B model provenance, embedded backend/config, AOT compiler+SoC target, original file hash, and actual runtime diagnostics before changing defaults.
- Pixel cold-start NPU load + at least one generated answer; compare against model-supported GPU/CPU only if compatible. Record real backend, prefill/decode latency, free RAM, RSS, native exit reason, and repeated-load behavior. An engine that only initializes does NOT pass.
- Preserve old release artifact and data; after backend failure never auto-retry unknown-outcome tool calls.

## P0: MCP / 0.8B native tool calling

Sources:
- https://github.com/modelcontextprotocol/modelcontextprotocol/blob/main/docs/specification/2026-07-28/server/tools.mdx
- https://github.com/modelcontextprotocol/modelcontextprotocol/blob/main/docs/specification/2025-06-18/schema.mdx
- https://developers.google.com/edge/litert-lm/android

Findings:
- Tool metadata includes name, inputSchema and optional outputSchema/annotations. readOnlyHint, destructiveHint and idempotentHint are untrusted hints, not authority.
- Human approval must remain possible. A call needs real server result after DISPATCH; search, details and prose are not server receipts.
- LiteRT-LM Kotlin tool use is conditional on a model that actually supports tool calling, not every 0.8B or vision model. Compare against an explicitly tool-capable test model for native planner acceptance.

RC18 action:
- Keep only three local progressive meta-tools, bounded results, strict JSON/schema validation and explicit per-call consent. Distinguish native-call/model continuation from host-assisted proposals in diagnostics/UI.
- Verify new #54 explanatory routing does NOT suppress mixed prompts with a real external action. Never allow a read-only claim from server annotations to bypass approval on its own.
- Pixel gate: repeat exact natural-language status request and verify SEARCH, DETAILS, PROPOSAL, APPROVAL, AUTH_CHECK, SCHEMA_CHECK, DISPATCH, SERVER_RESULT and native MODEL_FOLLOWUP (or explicitly marked host fallback). On DENY prove zero DISPATCH.
- Cap generation/thinking time and preserve cancellation traces; a 6-minute planning stall with no DISPATCH is a failed usability path, not a tool success.

## P0: large model download recovery

Sources:
- https://developer.android.com/develop/background-work/background-tasks/uidt
- https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running
- https://developer.mozilla.org/en-US/docs/Web/HTTP/Guides/Range_requests
- https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/If-Range

Findings:
- User-initiated data transfer jobs are supported on Android 14+ for long downloads started with appropriate visible-user conditions. They can still be killed for low memory, thermal state or lost constraints; a process kill may omit onStopJob callbacks.
- Android 16 can count long-running WorkManager workers against JobScheduler quotas, even with foreground service support. Use UIDT for user-initiated model downloads where appropriate; do not assume WorkManager means unrestricted execution.
- A resumed HTTP Range request must use Content-Range validation, an appropriate strong ETag/If-Range or Last-Modified validator, and handle 206/200/416 explicitly. When a Range request returns 200 the server has sent a full representation, NOT an appendable partial fragment.

RC18 action:
- On app restart or journal reconciliation, compare final file existence and size, then SHA-256 when a trusted digest is supplied. A journal marked complete with missing/wrong-sized qwen3_4b_mixed_int4.litertlm is NOT a completed import. A file with only existence/size/header evidence must be labelled UNVERIFIED until a trusted digest or immutable upstream manifest is checked; do not infer a fresh digest from the journal. The RC18 UI distinguishes a SHA-256 checked at transfer time from an unverified completed journal without rehashing a multi-GB model on the UI thread.
- Keep existing .part bytes unless an explicit safe restart/new generation was chosen. Use atomic final rename only after full validation, durable progress and user-visible pause/resume/cancel controls.
- Pixel test: screen off, app switch, Wi-Fi/VPN changes, UIDT stop and update over installed APK. Never assume onStopJob ran after low-memory process death.

## P1: background local inference and low memory

Sources:
- https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running
- https://developer.android.com/develop/background-work/services/fgs/service-types
- https://developer.android.com/develop/background-work/services/fgs/timeout

Findings:
- WorkManager can keep long-running ML jobs active with foreground service semantics, but Android 16 quota and Android 14+ service-type restrictions still apply.
- The dataSync foreground-service type is for transfers/local file processing and is not a universal exemption for an indefinite local generation session. Select a legally supported service type/architecture for the actual work; don't mask an inference session as a download.
- Keep runtime session ownership separate from Activity; use persisted chat/token output and bounded memory. After process death, restore user-visible transcript and status, not a fictitious native KV-cache continuation.

RC18 gate: start generation, Home/screen off, return, cancel, low-memory/restart and verify no unbounded wake locks, duplicate models, lost transcript or repeated side-effecting tools.

## P1: model catalogue and metadata

Sources:
- https://huggingface.co/docs/huggingface_hub/main/en/guides/search
- https://huggingface.co/docs/huggingface_hub/v1.19.0/en/package_reference/hf_api
- https://developers.google.com/edge/litert-lm

Findings:
- Hugging Face model searches can be iterated/paginated and repository trees queried by revision. A search hit or .gguf/.litertlm suffix is NOT a hardware compatibility certificate.
- LiteRT-LM 0.18 adds ModelInfo / describe support: use metadata inspection prior to claiming compatible runtime, device backend or multimodal inputs. Reject unknown metadata honestly; don't auto-download all search results.
- Record immutable model repository revisions and upstream digests where supplied, and distinguish listed, downloaded, parsed and device-tested model states.

## P1: per-app languages

Source:
- https://developer.android.com/guide/topics/resources/app-languages

Findings:
- Android 13+ LocaleManager exposes applicationLocales. AndroidX AppCompatDelegate is appropriate when the Activity uses AppCompat and migration rules are satisfied. Language switching can recreate the Activity.
- LocaleConfig generated from resource folders can advertise incomplete languages as supported. Show only accurately covered locales; IDs, tool schemas, server receipts and model filenames are never translated.
- Test local chat and downloads survive language switch, rotation and process restart.

## CI / review integration protocol

- RC18 issues: #19, #20, #21, #22, #25, #29 remain OPEN until actual acceptance evidence.
- Combined PR #56 integrates #54 and #55; do not duplicate cherry-picks. Only merge after current combined CI, CodeRabbit, GitGuardian, code diff and signer checks pass. A successful build is not device E2E.
- Trigger the new main workflow only AFTER the controlled merge, per user instruction. Keep public RC17 unchanged until signed RC18 and Pixel QA.
- After every failure, record trace ID, UTC epoch and local offset; do not log prompts, auth tokens, raw server responses or private tool arguments.

## Exact official Gemma 4 E2B Google Tensor G5 bundle (critical QA fingerprint)

Official Google page: https://developers.google.com/edge/tensor-sdk/slm-compilation

Canonical LiteRT Community file:
https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/blob/main/gemma-4-E2B-it_Google_Tensor_G5.litertlm

- Artifact name: gemma-4-E2B-it_Google_Tensor_G5.litertlm
- Published revision containing this upload: 9262660 (2026-07-10).
- Exact byte length: 3,113,545,589 (about 2.90 GiB; some UI displays this as 2.90 GB).
- Published SHA-256: af1082986639ecde7db95d91be6fe54f8b6b458104734c5bafc204e69d6852dc
- The public file was replaced in the community repository on that revision: its older LFS pointer was 3,953,110,901 bytes. Always verify model revision and digest rather than trusting identical filenames.
- Source revision and digest are test references; do not download a duplicate 3-GB model unless the already installed file fails the **independent** hash or metadata checks. A matching length alone does not prove a matching SHA.
- The official Tensor SDK guidance explicitly recommends a pre-compiled SLM bundle on Tensor G5. Do not try to JIT-compile this file on the phone.

Upstream community repro: https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/discussions/32
This thread reported an NPU dispatch-library-not-found failure on a Pixel 10 Pro using Gemma E2B. It is directly relevant to our TENSOR_BACKEND diagnostics but not proof that every Pocket AI G5 failure has this cause. First inspect the native library installation path, actual dispatcher ABI and loaded model hash, then run a real NPU generation test.
