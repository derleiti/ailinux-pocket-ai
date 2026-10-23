# AILinux Pocket AI

Native Android GGUF model manager and local AI chat. Designed for ARM64 phones.

**Status: 0.1.0-alpha.2** Debug APK builds; testing inference on the physical Pixel is still pending.

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
- third_party/llama.cpp: pinned upstream submodule; official JNI module under examples/llama.android/lib.
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
