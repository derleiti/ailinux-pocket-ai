# AILinux Pocket AI

Native Android GGUF model manager and local AI chat. Designed for ARM64 phones.

**Status: 0.1.0-alpha.9.** Android ARM64 pre-release. The developer confirmed that Spark-X2.6 chat works on the Pixel after Alpha 8 Jinja fixes; throughput and other GGUF architectures still need testing.

## Features

- Fully offline chat via the official llama.cpp Android JNI binding.
- GGUF import via Android document picker, no storage permissions.
- HTTPS download from GitHub Releases, Hugging Face, or a direct GGUF URL.
- Interrupted download resume (HTTP Range) and SHA-256 verification when provided by the source.
- Model library: load, switch, delete and inspect storage usage.
- Token streaming with stop action, editable system prompt and generation-length setting.
- No account, analytics, telemetry, cloud inference or API key.

## Privacy and permissions

Only Android INTERNET permission is requested for remote repository metadata and model downloads. Inference is local. This app does not upload conversations to an AI provider. Chats remain in activity memory (not persisted); local models are in private app storage.

## Requirements

- Android 13 or later, ARM64.
- Free storage and RAM to fit your selected model. Start with a Q4 0.5B–3B model.
- No root needed.

## Build

JDK 17, Android SDK 36, Android Build Tools 35, NDK 29.0.13113456 and CMake 3.31.6 required.

    git clone --recursive https://github.com/derleiti/ailinux-pocket-ai.git
    cd ailinux-pocket-ai
    export ANDROID_HOME="$HOME/Android/Sdk"
    ./gradlew :app:testDebugUnitTest :app:assembleDebug
    ./scripts/verify-apk.sh

Debug APK output: app/build/outputs/apk/debug/app-debug.apk

This APK uses the Android debug signing key and is intended for testing. Public updates require a long-lived, private release signing key. Never commit signing material.

## Using a model

1. Open MODELLE.
2. Choose a local GGUF, a GitHub/Hugging Face repository or a direct HTTPS GGUF link.
3. Download/import and select Laden.
4. Return to CHAT, type and send. Everything runs on-device.

Use **Kleine Modelle entdecken** for four verified starter GGUF choices: SmolLM2 360M, Qwen 2.5 0.5B, Qwen 3.5 0.8B and Gemma 3 1B (Q4_K_M). The app fetches the current public files rather than hardcoding download URLs.

Repository browser supports public GitHub release GGUF assets and public Hugging Face model files. Private/gated models are not supported yet. Resuming transfers requires the server to support Range requests. CPU inference is the initial target; future GPU support is not advertised as a completed feature.

## Structure

- app: Kotlin Material UI and model manager.
- third_party/llama.cpp: pinned upstream C/C++ model engine submodule.
- inference: vendored MIT-licensed Android JNI bridge with native diagnostics forwarding; upstream submodule is untouched.
- scripts/verify-apk.sh: archive and signature checks.
- .github/workflows/android.yml: CI tests and debug artifact.

Upstream llama.cpp and its Arm AI Chat Android binding are licensed under MIT. See third_party/llama.cpp/LICENSE and THIRD_PARTY_LICENSE_LLAMA_CPP.txt. The AILinux app code uses MIT.

## Architecture diagnostics

Before JNI model loading, Pocket AI reads GGUF metadata and checks for known
image-generation/diffusion architectures. These models remain in your library, but
cannot be run as chat models by llama.cpp. Qwen-Image 2.1 GGUF requires an
image-specific backend such as ComfyUI-GGUF plus the matching text encoder and VAE.
An unknown architecture is not automatically rejected; if llama.cpp rejects the
model, the error now names the detected architecture and possible reasons.

Try a text model such as
https://huggingface.co/bartowski/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/Qwen2.5-0.5B-Instruct-Q4_K_M.gguf?download=true

Note: CI debug builds use their own temporary Android signing certificates and
are **not** update-compatible with releases signed on the AILinux build server.
Release APKs are debug-signed for alpha tests only.

## Transfer UI (Alpha 3)

Active `.gguf.part` files are labelled **Läuft gerade**, not **Unterbrochen**.
The active model name and live downloaded bytes/percentage remain visible.
Repository searches cannot hide the download cancel button or overwrite active
transfer progress. Interrupted downloads are still resumable through HTTP Range.

**Android background downloads:** The current alpha executes transfers inside
the Activity lifecycle, so Android can interrupt a download when the process
is killed. Reopen the app and use Fortsetzen; a foreground download service
is still planned. For large GGUFs, keep Pocket AI active while transferring.


## Native load diagnostics (Alpha 4)

The original upstream Android JNI bridge converted every failed native model load
(return code 1) into UnsupportedArchitectureException, regardless of why the load failed.
The vendored MIT-licensed JNI wrapper in inference/ now captures bounded native llama.cpp
warnings/errors and displays them in the error dialog. Tap Diagnose kopieren to copy
the detail for a reproducible bug report. The settings page shows the actual installed
version. This improves observability, not universal GGUF compatibility.


## Android GGML CPU backend initialization (Alpha 5)

The GGUF model loader previously failed with "no backends are loaded". The
ARM64 CPU variant libraries were included in the APK, but the manifest had
extractNativeLibs=false. Dynamic GGML backend enumeration scans the physical
nativeLibraryDir, so it could not see or load any of these .so files.

Alpha 5 sets jniLibs.useLegacyPackaging=true to extract the native libraries,
checks that a CPU backend is actually registered during JNI initialization,
tries the ARMv8.0 baseline backend if the automatic variant score fails, and
reports actionable errors if backend initialization still fails. APK verification
now asserts physical native extraction is enabled and an ARM CPU backend exists
in the archive. The app upgrade does not delete private model files.


## Low-memory safe mode and crash reports (Alpha 6)

The Android JNI wrapper previously reserved a fixed 8192-token context and
512-token microbatch, and did not release partially loaded resources on errors.
The safe profile uses a 2048-token default context and 128-token microbatch,
with explicit memory-mapping and without extra CPU repack buffers. It initializes
a conservative ARMv8 CPU backend before trying optimized variants. It now fully
releases native model/context/sampler resources even after failed loads or
context initialization errors.

Before attempting a multi-GB model, the app shows the available-RAM estimate
and a warning that Android may kill the process during allocation. Users can
choose to attempt anyway. The native load progress indicator is indeterminate:
loading depends on I/O, memory pressure, and architecture and cannot be
meaningfully represented as a percentage without detailed callbacks.

Android's ActivityManager historical process exit reasons can be inspected
under SETUP -> Letzte App-Abstürze prüfen and copied from the dialog. This
shows native crashes, ANRs or low-memory kills when Android has recorded
them. If the app process dies while loading a model, the next startup
shows a recovery notice using a persisted load-attempt marker. Stored GGUF
files are preserved on upgrades. There is no automatic crash telemetry or
upload of logs.


## Native crash tombstone diagnostics (Alpha 7)

Pocket AI Alpha 6 can identify SIGABRT (status 6) but cannot determine the
native assertion from ApplicationExitInfo alone. Android API 31+ can expose
a protobuf native tombstone via ApplicationExitInfo.getTraceInputStream().
Alpha 7 adds a bounded local reader for the public Android debuggerd
tombstone.proto schema. SETUP > Letzte App-Abstuerze pruefen now displays
the latest native crash's abort message, signal, faulting thread and up to
18 backtrace frames, with a copy button for manual bug reporting. It does
not upload logs automatically or require additional Android permissions.

The diagnosis also includes the custom native inference phase recorded via
ActivityManager.setProcessStateSummary(): backend_init, model_load,
context_prepare, model_ready, system_prompt etc. This limited state
is stored in the Android process exit record for *future* crashes; old records
may not contain it. The Android tombstone history is a bounded circular
buffer, so old crash traces might have been overwritten.

**This is instrumentation, not a confirmed fix for a SIGABRT native crash.**
Do not delete or re-download GGUF models to collect the crash trace.
Prefer tapping SETUP > Letzte App-Abstuerze pruefen immediately after
installing Alpha 7, before attempting another model load.


## Chat-template SIGABRT fix (Alpha 8)

An Android 17 native tombstone provided a reproducible root cause on
Spark-X2.6-4B-Q8_0.gguf:

    phase=system_prompt
    std::runtime_error: this custom template is not supported, try using --jinja
    common_chat_templates_apply -> common_chat_format_single
      -> InferenceEngineImpl_processSystemPrompt -> SIGABRT (signal 6)

The old JNI bridge explicitly set use_jinja=false, which only supports
specific legacy hard-coded templates. The model's actual GGUF metadata
contains a custom Jinja template with system/user/assistant roles and a
thinking prefix. Alpha 8 enables Jinja rendering on all chat message
formats. It catches standard C++ exceptions at the native load, context
preparation, system prompt, user prompt, and generation JNI boundaries,
converting them to actionable errors rather than allowing exceptions to
terminate the Android process.

Prompt strings are copied from JNI and released before rendering, even
when formatting throws. Native model and prompt error details propagate
to the Kotlin UI. Heavy JNI operations no longer use @FastNative,
which is intended for short-running methods.

The pinned upstream llama.cpp test-chat-template binary has been built
on the AILinux server and successfully rendered the exact public
Spark-X2.6-4B Q8_0 GGUF chat template, retrieved via an HTTP Range
request (only the GGUF header/metadata, not full model weights). Test
messages included a system instruction and multiple user/assistant
turns. This verifies template rendering; it is NOT a substitute for
on-device model load, decoding and throughput testing.

Do not delete or re-download models when upgrading Alpha 7 to Alpha 8;
the APK is signed with the same development certificate.


## Chat-first layout (Alpha 9)

The chat page no longer contains a large 'DEIN MODELL' hero card,
GGUF selection button, download controls or model switching. It has a
compact, non-interactive active-model/status row above the conversation,
with the message list taking the remaining vertical space. Model import,
downloads, installed model cards and model switching belong entirely to
the MODELLE tab. The message composer is disabled until a model is
loaded; it remains possible to stop a running generation.

The model selection and chat layout remain separate when navigating
between tabs. Four JVM UI-content/presentation regression tests validate
this separation. This is a UI-only improvement; no changes are made to
native inference or existing installed GGUF files.
