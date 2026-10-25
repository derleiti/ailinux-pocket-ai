# RC18 GGUF IOException / UNKNOWN — phase diagnostics

User-provided Pixel incident: 2026-10-25; Pocket AI 2.1.0-RC18; error IOException / UNKNOWN with ID 5d38d911, title "Modell/Chat-Template konnte nicht gestartet werden".

## Evidence and limits

- No raw exception message / app-private logcat provided. The RC18 error card is deliberately privacy-safe. IOException / UNKNOWN alone cannot establish invalid GGUF metadata, failed llama.cpp native model load, context OOM, corrupted template, model-file access, or failed sampling.
- The generic catch in MainActivity.loadModel grouped metadata inspection, prior-engine shutdown, native load, GGUF Jinja/system-prefix processing and sampling behind the same code path. Native IOException was wrapped with filename, architecture and llama.cpp details, then the exported diagnostic classifier reduced the wrapper to UNKNOWN. The existing UI intentionally did not disclose that raw internal exception.
- The paired Android Helper reported the Pocket AI foreground, Qwen3_1.7B LiteRT/GPU currently ready at observation. This does not confirm success or cause of the earlier GGUF attempt. The user's current phone interaction is not interrupted.

## Bounded changes

- Assign an explicit enum stage before each GGUF startup phase. Report typed, non-sensitive codes GGUF_METADATA, GGUF_PREVIOUS_ENGINE, GGUF_NATIVE_LOAD, GGUF_THINKING, GGUF_TEMPLATE, GGUF_SAMPLING; attach monotonic elapsed milliseconds to MODEL_LOAD failure. Preserve cause only in runtime memory, not diagnostic JSON or user-visible message.
- Clean up native GGUF weights when engine.loadModel succeeded but later thinking mode, template/prompt or sampling failed. Do not double-unload after InferenceEngineImpl.loadModel() already cleaned up its own failed native load. Preserve the old active model on preflight rejection; do not touch saved model files, chat data, OAuth or .part downloads.
- No change to the native C++ model parser, Jinja templates, GGUF bytes, signing or Pixel app installation. The code improves diagnosis and recovery, not proof that the original file can now load.

## Acceptance

- Kotlin/JVM tests covering each stage and zero leakage of sensitive exception text, plus unit tests/lint/assemble and signer verification.
- A new opt-in APK on the Pixel must reproduce or clear the original GGUF failure. Inspect trace/stage and measured elapsed time. Then determine the actual cause from the model's original metadata and permitted on-device native diagnostics. Only after that change the offending loader/template/compatibility code.
