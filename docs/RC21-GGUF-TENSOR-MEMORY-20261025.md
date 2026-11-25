# Pocket AI RC21 post-device diagnosis: GGUF types and Tensor model switching

Evidence from Pixel Android 17 RC21 exported local diagnostics, 2026-10-25 CET (UTC+01:00). Do not confuse German local timestamps with UTC during the DST transition. No prompts, tool arguments, authentication or sensitive model paths in trace exports.

## Evidence and limitations

- 23:42:06 CET GGUF/MODEL_LOAD failed with GGUF_NATIVE_LOAD, duration 318 ms; earlier failures lasted 31 ms, 38 ms and 3033 ms. The traces omit model filename, architecture and native error text, so they cannot prove which GGUF was attempted.
- An mmproj-gemma-4-E4B-it-BF16.gguf download was reported present with SHA-256 checked during transfer. The previous loader treated every GGUF as a standalone LLM, but mmproj is a vision projector requiring a compatible language model and a multimodal loader. This is a reproducible source defect, not proof of the anonymized native error's root cause.
- Several LiteRT GPU engine/conversation initializations completed successfully. At 23:39 LiteRT memory preflight refusals were controlled; Android later reported REMOVE TASK, not a new process crash.
- Explicit RC21 user report ModelMemoryGuardException / MEMORY diagnostic 4ec98356: 4.56 GiB available and 7.80 GiB conservative Tensor floor. The 7.80 GiB floor matches the heuristic for an approximately 3.11 GB Google Tensor E2B AOT bundle: twice the file bytes plus 2 GiB. This is NOT a measured device requirement or proof of Android lowMemory=true.
- Android reference: https://developer.android.com/reference/android/app/ActivityManager.MemoryInfo ; its availMem is a snapshot, while threshold and lowMemory are distinct OS signals. Do not claim Android set lowMemory unless the flag was observed.

## Source changes

1. ModelCompatibility identifies explicit projector filenames (mmproj, vision_projector) and metadata (clip, siglip, siglip2, projector type). It does not reject normal multimodal LLM files solely because their names include vision or VL. The model library retains projector files but disables loading them as a standalone GGUF. A direct load is refused BEFORE releasing any loaded native engine. Do not delete existing downloaded weights, projectors or partials.
2. The structured GGUF parser now retains general.architecture even when it is the only architecture metadata key. Previously the parser dropped the value and could incorrectly default CLIP/SigLIP architecture to llama.
3. GGUF load adds an Android lowMemory precheck and second mandatory RAM check after previous engine shutdown and before llama_model_load_from_file. Privacy-safe GGUF_PROJECTOR, GGUF_MEMORY_PREFLIGHT and existing GGUF_NATIVE_LOAD error categories do not export native exception text.
4. Tensor-only LiteRT AOT files must not receive fictional GPU/CPU fallback. If RAM is below the NPU heuristic and no old model is loaded, refuse before native initialization. If an old native model is loaded, its allocations may be included in pre-unload available-memory readings: explicitly ask permission to end that session and remeasure memory. If the post-unload reading still misses the floor, DO NOT initialize the Tensor NPU. User consent is only permission to unload the old session and check again, never permission to bypass the hard gate. Tell the user the old session may be lost. If the selected bundle genuinely supports GPU/CPU, the existing candidate filter remains unchanged.

## Verification and release restrictions

- Run Kotlin/JVM unit tests, lint, ARM64 APK build, source-pinned llama.cpp submodule, signing check and secret scan on the isolated worktree before PR. RC21 installed versionCode 45 and original update signer must remain unchanged during source QA.
- No ADB executable was found in the connected target shell. The Workspace helper is connected and can observe Android UI but does not establish privileged ADB or automatic APK installation.
- A real Pixel load, native error-classification observation and a genuine generated answer are still required for GGUF; remeasure actual RAM after a user-approved old-model release before considering a smaller Tensor floor. No unsanctioned .part/large-model deletion.
- Do not republish an existing tag or publish a new RC without merged main, reviewed CI, correct incremented versionCode, signing and hardware acceptance.
