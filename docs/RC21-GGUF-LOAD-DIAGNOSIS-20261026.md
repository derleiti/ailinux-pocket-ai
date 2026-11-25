# RC21 GGUF native load: device evidence and diagnostic correction

Reviewed 2026-10-26 Europe/Berlin (the prior day includes the winter-time transition). Keep UTC offset plus trace IDs when cross-correlating historical events.

On a paired Android 17 Pixel running Pocket AI 2.1.0-RC21, the latest accessible diagnostic reports a GGUF/MODEL_LOAD failure and GGUF/DIAGNOSTIC failure at 2026-10-25 23:57:50 local time under trace 92997af0, category GGUF_NATIVE_LOAD. This is evidence that native GGUF loading failed, not proof of an out-of-memory crash, corrupt file, unsupported architecture or missing backend. No native error text or model-file hash is available from the privacy-preserving log.

## Minimal fix on current Main (not a release)

- Preserve the existing startup phase boundary and exception handling. Classify a short, audited set of native llama.cpp loader error patterns into fixed, privacy-safe diagnostic categories: GGUF_NATIVE_MEMORY, GGUF_NATIVE_BACKEND, GGUF_NATIVE_ARCH and GGUF_NATIVE_FORMAT. All other native exceptions stay GGUF_NATIVE_LOAD.
- Inspect at most six exception causes in memory; store only the category. Never emit file paths, arguments, auth tokens, server endpoints, raw native messages or stack traces. Categorization is not conclusive evidence of the underlying root cause.
- If the Android memory manager already reports lowMemory=true, refuse a new GGUF load before unloading the previous engine or starting a JNI allocation. Preserve all model files and .part fragments; do not retry automatically.
- Add regression tests covering recognized and unknown categories, nested causes, privacy of public logs and the exact ordering of the pre-load guard.

## Still open for Pixel quality acceptance

Check memory, model GGUF architecture and exact digest, backend native library availability in applicationInfo.nativeLibraryDir, and meaningful small GGUF load/generation with the new build. Do not assume LiteRT Tensor G5 failure and GGUF native load failure share a cause. Main and GitHub CI must be green. Since RC21/versionCode 45 is already installed on the device, any further APK installation will require an incremented versionCode and original signing certificate. Do not overwrite a draft release or immutable tag and do not deinstall or clear user data.

Issue #22 remains open until on-device verification.
