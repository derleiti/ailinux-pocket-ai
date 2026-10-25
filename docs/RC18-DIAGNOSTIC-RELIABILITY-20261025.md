# RC18 device diagnosis to bounded fixes

Test device: Pixel 10 Pro, Android 17; original public RC17 remains untouched. Time: 2026-10-25 Europe/Berlin DST transition, correlate traces with UTC epoch and offset, not just local clocks.

## Evidenced failures

- **17:39:24** LiteRT MODEL_ERROR/TIMEOUT: model/tool planner ran from approximately 17:27:08; NO server DISPATCH in this trace.
- **17:39:46** Android REASON_LOW_MEMORY: RSS **5,727,100 kB** when the last saved state was Spark-X2.5-1.7B_int8.litertlm / backend_google_tensor. Android's exit reason is stronger evidence than a guessed Java exception, but the phase marker alone cannot prove the exact allocation that triggered LMK.
- **17:40:20** TENSOR_BACKEND, trace 7bcd13b4: model start at 17:40:20.514 and error at 17:40:20.973, about **459 ms**. Repeated rapid native backend setup failure is distinct from memory pressure or an inference timeout.
- **17:40:42** Android REMOVE TASK user action, status 0. Do NOT report this as a crash.
- Stale complete download journal qwen3_4b_mixed_int4.litertlm with missing or size-mismatched final model file. No independent SHA-256 recheck of the other three models was reported; never infer it.

## Limited source fixes (not full device acceptance)

1. Model-load memory protection has **two sequential checks**. First, **before any runtime shutdown**, Android \`lowMemory=true\` aborts the switch and preserves the loaded engine even if the user previously consented to a risky load. A prompt may then request explicit acknowledgement for a large model or the *soft* Tensor/NPU warning floor: \`max(3 GiB, 2 x model bytes + 2 GiB, OS LMK threshold + 2 GiB, existing non-Tensor floor)\`. This is a heuristic only, not a measured allocation prediction. Second, **after** the old engine shutdown, the code recomputes Android's available memory and flag. Android \`lowMemory=true\` and the **hard non-Tensor floor** \`max(2 GiB, model bytes / 2 + 1 GiB)\` may **never** be bypassed by acknowledgement. Only the soft Tensor warning may be overridden by an explicit user decision; when not accepted, the NPU candidate is excluded but genuinely compatible GPU/CPU candidates may remain. Tensor-only AOT files have no fictitious GPU fallback and fail closed. A change of memory state after teardown may cause a safe abort; no model or partial bytes are deleted. This does not guarantee the kernel cannot later kill the process.
2. LiteRT-LM **0.18.0** exposes ConversationConfig.maxOutputToken. Set 256 output tokens for each local MCP planner turn, 768 for ordinary LiteRT chat. Apply to initial engine conversation, every MCP rebind and reset. This bounds the output length, **not execution wall time**, so a blocked JNI call still needs independent investigation. Never call cancelProcess from an arbitrary thread or release the engine while a native call remains in progress.
3. Present a journal marked complete but missing/wrong-sized as *inconsistent* in the model library. Disable misleading Laden for such entries and offer **explicit source re-selection**. Preserve .part bytes and validators; never auto-requeue, overwrite, append after an unknown source, or silently delete a multi-GB file. Clear saved source metadata only when the currently expected final file and byte count actually match. Fresh SHA-256 verification remains a separate gate.
4. Record elapsed monotonic milliseconds on LiteRT load failure, model timeout, cancellation and completion, using the existing privacy-safe diagnostic event structure. No prompts, URLs, tokens, exception messages or stack traces.

## Actual regression and release gates

- Kotlin/JVM unit tests for observed low-memory conditions, overflow, boundary conditions, **genuine GPU/CPU fallback without ever reintroducing the excluded NPU**, download mismatch and non-destructive UI recovery; verify output-budget use at creation/rebind/reset and model error duration. Android Lint, ARM64 APK build, ABI/manifest, persistent RC17 signer and clean code/secret diff.
- On Pixel, verify no new low-memory kill during Spark/NPU start, prompt rejection when RAM below floor, model switching, Qwen3 text generation, cold Gemma E2B Tensor G5 **actual generated tokens** using pinned dispatch candidate, canceled/slow model turn and no duplicate tools/call, and orphaned download re-selection without losing partial bytes.
- Merge only after PR CI/review. RC18 versionCode=43 is a **candidate**; no new tag/release until installed-device checks and independent public asset SHA/signing verification pass.
- Keep issues #19/#20/#21/#22/#25/#29 open until their actual acceptance criteria are met.

## 12 GiB Pixel memory-floor review

The paired Android device reports 12,124,946,432 bytes total RAM (~11.29 GiB). A flat 6 GiB free-memory requirement can reject valid small Tensor models even after native resources were released. RC18 therefore uses a **size-scaled soft NPU warning** (max(3 GiB, 2x file bytes + 2 GiB, Android LMK threshold + 2 GiB)) and requires explicit user acknowledgement before attempting a low-margin NPU load. The user opt-in never bypasses Android lowMemory=true or the pre-existing hard non-Tensor safety floor. If an NPU model supports GPU/CPU, the app may skip NPU rather than attempting an unsafe automatic NPU load; NPU-only models have no imaginary GPU fallback. Trace BACKEND_RISK_ACCEPTED and BACKEND_SKIPPED separately, without logging model prompts or secrets. This is a conservative safety heuristic; actual memory consumption remains subject to Pixel QA.
