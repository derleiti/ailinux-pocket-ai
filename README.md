# AILinux Pocket AI

Native Android GGUF model manager and local AI chat. Designed for ARM64 phones.

**Status: 1.0.0-rc.2.** Android ARM64 pre-release. The developer confirmed that Spark-X2.6 chat works on the Pixel after Alpha 8 Jinja fixes; throughput and other GGUF architectures still need testing.

## Features

- Fully offline chat via the official llama.cpp Android JNI binding.
- GGUF import via Android document picker, no storage permissions.
- HTTPS download from GitHub Releases, Hugging Face, or a direct GGUF URL.
- Interrupted download resume (HTTP Range) and SHA-256 verification when provided by the source.
- Model library: load, switch, delete and inspect storage usage.
- Token streaming with stop action, editable system prompt and generation-length setting.
- Opt-in MCP agent bridge: local GGUF proposes selected tools, user confirms
  each invocation, and Pocket AI displays the server's real result separately.
- TriForce MCP quick-add preset, searchable tool picker and OAuth/Bearer/API-key support.
- Offline chat works without an account, telemetry or cloud inference. Optional
  MCP servers can require API keys, passwords or OAuth; those network services
  are used only when explicitly configured and tested by the user.

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


## Spark-X2.6 direct-answer / optional reasoning mode (Alpha 10)

The public Spark-X2.6 GGUF Jinja template defaults to enable_thinking=true
and injects thinking_effort=high into its system block. The previous
common_chat_format_single(..., use_jinja=true) did not expose
enable_thinking and always used that default, wasting the configured
response token budget on internal-style reasoning text.

The vendored JNI bridge now calls the official
common_chat_templates_apply(...) directly with an explicit
inputs.enable_thinking flag. The default is FALSE: for Spark-X2.6,
the assistant-generation prefix becomes <|Bot|></think> and the
extra high-effort reasoning instructions disappear. This is a template-
level fix, NOT a filter that hides generated tokens after they are spent.

A Denkmodus switch under SETUP enables reasoning on demand. It is
disabled on existing installations by default, and applied when a GGUF
model is loaded (before processing its system prompt). Toggle, save
settings, then reload the model or use Gespraech zuruecksetzen for the
change to take effect. Incremental chat rendering checks that a
template leaves previously encoded messages unchanged before appending
the next turn; unsupported behavior raises a recoverable error instead
of corrupting context silently.

The exact Spark-X2.6 public GGUF Jinja template was regression-tested
with an upstream-hosted common_chat_templates_apply harness in both
thinking and non-thinking modes, including a system prompt, multiple
turns, and incremental conversation prefixes. Native test source is
in tests/native/chat_template_thinking_test.cpp. This release is
locally compiled for Android ARM64, but user device confirmation of
the newly disabled thinking mode is still needed.


## MCP connections – RC1

Under SETUP > MCP VERBINDUNGEN, create and maintain multiple encrypted
MCP server profiles. Supported authentication:
- Ohne Anmeldung / No authentication
- Authorization: Bearer token
- API key in a selectable, validated HTTP header, e.g. X-API-Key
- HTTP Basic username/password (only over HTTPS except 127.0.0.1)
- OAuth authorization-code with PKCE S256, protected-resource metadata
  discovery, authorization-server metadata, dynamic client registration
  where supported, RFC 8707 resource binding, state/issuer validation,
  refresh tokens and explicit logout/disconnect.

MCP transport: Streamable HTTP (2026-07-28 server/discover and legacy 2025
initialize), request-scoped JSON/SSE, as well as original 2024 HTTP+SSE
GET endpoint / POST messages. AUTO transport recognizes conventional
/sse endpoints; otherwise Streamable HTTP is used. The Streamable HTTP
client negotiates 2026 server/discover first and can fall back to the
older initialize handshake when supported. It does not silently convert
a failed /mcp request to the separate legacy HTTP+SSE transport.
Older SSE servers may require manually selecting LEGACY SSE.
Authentication failures never trigger a silent downgrade.

Redirects: default native loopback callback
http://127.0.0.1:18467/oauth/callback; optional custom-app callback
me.ailinux.pocket://oauth/callback for providers that require an
app-registered callback. Register exactly the selected URI with your
OAuth provider. The app opens the external system browser, not a WebView.
An app-specific and session-specific PKCE verifier and state are encrypted
before launching the browser. The callback validates matching state and
exact redirect before exchanging authorization code; tokens are never
placed in outgoing MCP URLs. OAuth provider interoperability depends on
the particular authorization server. If auto-registration is unsupported,
enter your provider-issued public client ID in the MCP profile.

Secrets are encrypted locally using Android Keystore AES-GCM and stored
only in app-private storage. Android backup is disabled, credentials are
never embedded in source, and HTTP redirects are never automatically
followed with credentials. MCP access is optional. The setup screen can
test a connection, inspect tools, copy details, disconnect and delete
profiles. Tools with side effects must be chosen by the user with
explicit per-call confirmation; model-generated text does not
automatically invoke MCP tools. An explicit button allows user-approved
tool results to be inserted into the chat.

The existing llama.cpp/Spark-X2.6 offline chat inference path is unchanged.
Android ARM64 build, JVM/mock-server tests and APK signing checks cover
the transport, security and settings UI. External OAuth provider
authorization and live account-specific MCP tool use need testing with
the user's own registered server/client; they cannot be conclusively
verified by unit tests alone.


### RC1 verification status and scope

- Android ARM64 debug package built and tests run on AILinux server; never
  assume on-device OAuth/provider interoperability before testing the
  exact server identity and registered callback URI.
- MCP tests cover legacy 2024 HTTP+SSE, 2025 handshake Streamable HTTP,
  stateless 2026-07-28, headers, user-approved tool calls, HTTP redirect
  security, OAuth PKCE state and issuer, one-use callbacks, dynamic
  registration and refresh token storage behavior.
- MCP is a manual tool console with per-call confirmation and optional
  display of results in CHAT; the GGUF language model does not autonomously
  pick tools, see MCP credentials or ingest untrusted tool output.
- OAuth uses a public client ID or dynamic registration where offered.
  Providers requiring CIMD (Client ID Metadata Documents), a registered
  Android signing certificate or provider-specific native redirect
  registration may need extra integration; no universal provider login
  is claimed. Avoid using a secret client key in a mobile application.
- The bundled debug APK is signed with the same *development test*
  certificate as the preceding AILinux-server Alpha releases so users may
  update in place without losing locally downloaded GGUF models. This is a
  release candidate for device QA, not an audited production security release.


## MCP Agent Bridge – RC2 (TriForce-style tool orchestration)

RC2 adds opt-in model-assisted tool proposals **without requiring a GGUF
model to implement the MCP transport itself**. Spark-X2.6-4B supports
function/tool-call formatting, but an on-device model's proposed action
is not a verified action. Only an actual MCP server response counts.

1. Under SETUP > MCP VERBINDUNGEN > + Server, choose the **AILinux TriForce**
   preset for https://api.ailinux.me/v1/mcp or enter another compatible
   HTTPS endpoint. For private operations, configure OAuth, a Bearer
   token or other server-accepted authentication and test the connection.
   The preset itself does NOT inject credentials or authorize privileged actions.
2. Load a GGUF under MODELLE, then open CHAT > MCP > KI + MCP aktivieren.
3. Select one MCP server and use the search box to find relevant tools
   among up to 250 discovered entries. Mark up to **five** tools.
   These are exposed to the model as a compact, dynamically composed
   catalog; token/secret values are **never** included.
4. Chat normally. If Spark proposes a matching function call using
   Spark's <tool_call> syntax or the JSON fallback, the application
   validates the tool name and argument types against the selected list.
5. Each request displays a **one-time consent dialog** showing the exact
   server URI, tool and all arguments. Only "Einmal ausführen" performs
   tools/call through the stored encrypted credentials.
6. A separate result receipt reports what the MCP server actually sent.
   The local model may summarize a bounded, untrusted copy. Failed or
   uncertain outcomes are **not** presented as completed tasks.

No tool call is executed when the model only writes text, even if it
asserts "I connected Discord." Its output then explicitly says "Nur
Modellantwort – kein MCP-Aufruf ausgeführt." Invalid, extra or unknown
tool arguments are rejected. Parallel, chained and silent tool calls
are deliberately disabled in RC2. Error responses from side-effecting
tool calls are **never automatically retried through another transport**.

The top "OFFLINE" chip changes to "LOKAL + MCP" only while the
user-enabled agent mode is active; LLM inference itself remains
fully local. Enabled tools are a session-only selection. They do
not persist as a durable "execute anything" permission after restarting.

The native Android CPU backend still uses the conservative 2048-token
context. The native decoder now reserves user-response space, shifts old
KV history before (never in the middle of) a new prompt, preserves the
system prompt and finalizes partial assistant history after Stop.
Oversized MCP requests are rejected instead of being silently truncated.
Keep selected tool schemas small; tool descriptions have
length caps. A model may occasionally fail to produce parseable
arguments; in that case use the manual MCP tool console. Tool-enabled
chat has a short result-summary step; if that cannot fit into native
context, the verified MCP receipt is still displayed.

**Live TriForce probe:** On 2026-10-23, unauthenticated, read-only
server/discover and tools/list requests to https://api.ailinux.me/v1/mcp
returned MCP SSE JSON-RPC responses with HTTP 200 and 91 advertised tools.
This does not mean all 91 tools are callable without a login/grant.

**Discord Voice caveat:** A voice channel can only be joined if the
configured MCP server actually exposes, authorizes and successfully
executes a matching voice-connect tool. The Android app itself is
not a persistent Discord voice client or full-duplex audio bridge.
This RC verifies protocol and orchestration behavior with deterministic
tests, not that a specific live Discord server was connected.

Android historical process-exit reason 16 is officially
ApplicationExitInfo.REASON_PACKAGE_UPDATED. RC2 now labels these
events "App-Update (kein Crash)" and distinguishes historical SIGABRT
tombstones from normal APK installations. Prior tombstones may still
appear in Android's process history; they are not new RC2 crashes.
