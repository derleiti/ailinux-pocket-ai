# AILinux Pocket AI

Native Android GGUF model manager and local AI chat. Designed for ARM64 phones.

**Next candidate: 2.1.0-RC3 (versionCode 26).** Android ARM64 pre-release. The developer confirmed that Spark-X2.6 chat works on the Pixel after Alpha 8 Jinja fixes; throughput and other GGUF architectures still need testing.

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

## Quick GitHub Actions checks

The **Android Fast Checks** workflow runs on pull requests and only executes
`app:testDebugUnitTest`. It avoids the native APK compilation and Android Lint
so code issues appear sooner. Superseded PR runs are cancelled automatically.
Gradle's dependency/task cache is enabled and only missing SDK components
are installed.

After the workflow is present on the default branch, **Actions → Android Fast
Checks → Run workflow → apk=true** builds and uploads a compact ARM64 debug APK
without the slower full Lint suite. For release candidates, still require the
complete **Android CI** workflow (unit tests, native APK, Lint and verification).
The full workflow also cancels outdated PR runs and reads the filename from
`versionName` rather than hardcoding an RC number.

**Important:** CI uses an ephemeral debug signing key. The resulting Actions
artifact is not guaranteed to install over a previous AILinux-signed APK
without losing app data. Official test releases use the maintained local
test-signing certificate; do not uninstall Pocket AI just to test a CI APK.

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


## RC3 - Spark-X2.6 native MCP tools + truthful live catalogue

RC2 presented MCP tool schemas as plain USER text, not via the model's
actual tool mechanism. Spark-X2.6 could consequently reply with generic
explanations, despite the MCP server advertising working tools. RC3 repairs
the native integration:

- Activating KI + MCP serializes up to five selected function schemas
  as OpenAI-compatible JSON. The Android JNI bridge validates them using
  llama.cpp common_json and common_chat_tools_parse_oaicompat, and supplies
  them through common_chat_templates_inputs.tools. The ORIGINAL Spark-X2.6
  GGUF Jinja template places them in its SYSTEM tools block.
- Normal user requests are sent as plain text, not padded with duplicate
  tool definitions. No OAuth, Bearer or API-key tokens enter the GGUF prompt.
- Changing the tool selection resets only the local decoded conversation and
  KV cache while keeping the loaded GGUF. A fresh system prefix with the
  new tool set is built before the next message. Switching MCP off removes
  the native tool section.
- The 2048-token context limits model tool definitions. If a selected
  schema does not fit, the app refuses it and asks for fewer tools instead.
  Each real MCP tools/call still requires fresh explicit user confirmation.
- Questions such as "Welche MCP Tools hat Discord?" or /mcp tools discord
  invoke the authenticated MCP server's real, read-only tools/list RPC
  directly. No LLM hallucination is used to answer an actual tool inventory.
  This works even without loading a GGUF, as long as a MCP profile exists.
- SETUP now offers separate AILinux TriForce and AILinux Discord MCP
  presets. The latter uses OAuth 2.0 + PKCE, user scope discord:read
  offline_access and Android localhost callback. The Discord bot token is
  NOT a user Discord MCP OAuth grant; login and permission approval are
  required separately.
- Discord currently advertises three account-scoped read-only tools:
  discord_connection_status, discord_authorized_guilds, discord_voice_status.
  The separate nova join/leave voice controls are not exported by that
  public read-only Discord MCP resource.
- Android REASON_PACKAGE_UPDATED process diagnostics now clarify that
  a previous phase=model_load_failed is a last-known process stage,
  not a new crash caused by the APK update.

### Verification

- JVM tests: native JSON tool schema serialization, authenticated MCP
  inventory display and safe error handling, OAuth and transport protocols.
- Host C++ regression: original Spark-X2.6 GGUF Jinja template with native
  common_json parsing and actual Jinja tool injection. Checks system-level
  function names and parameter schemas, incremental multi-turn rendering,
  thinking OFF, and removal of tools when deactivated.
- Android ARM64 build, Lint, APK/JNI/ABI/signing verification before release.

Correct template injection is necessary, but a small local 4B model may
still occasionally choose no tool or produce malformed arguments.
Specific Discord OAuth consent and actual Pixel/device inference must
be tested on the phone; a real tool result is required to claim success.


## RC4 - Grounded MCP Health Checks (no fake model successes)

RC3 correctly bound selected functions to Spark-X2.6's native GGUF Jinja
template, and direct MCP tools/list worked on the Pixel. However, a real
device test revealed that Spark could still claim it had checked MCP
endpoints and account access, despite producing neither a function call
nor a server receipt. This was a fabricated model assertion, not a
successful health check. RC4 prevents that error:

- CHAT > MCP > MCP Health-Check (echte Serverprüfung) provides a
  model-independent, read-only live connection test. No GGUF required.
- /mcp health, /mcp status, /mcp health discord, /mcp health triforce,
  MCP-Health-Check and natural German requests such as "Prüfe die MCP
  Verbindung" are routed directly to the configured MCP profile.
  If several profiles exist, choose the server.
- The Android UI refreshes OAuth credentials when configured, invokes
  the real MCP handshake (server/discover on 2026 endpoints or
  initialize on older servers), and calls tools/list.
- A passing report includes the EXACT profile endpoint, responding
  server name, MCP version, number of advertised tools and real request
  duration. Failure, no profile, missing OAuth consent or HTTP 401/403
  produce conspicuous non-success messages.
- CRUCIAL DISTINCTION: server/discover + tools/list confirms reachability
  for that configured profile. It does NOT prove that each individual
  tools/call has permission, that a Discord guild is authorized, that a
  voice channel is joined, or that other endpoints are working. Those
  need separate explicitly approved tool calls.
- A second truth gate suppresses strong unsupported model claims of
  completed Discord/health/server checks when no MCP call actually
  occurred. The chat instead offers /mcp health and explains that the
  model's assertion was not a result. Plain offline questions still work.
- Tests include the literal false Spark health-check response sent
  during device evaluation. A proof-status report can only be created
  from a successfully completed, authenticated live MCP inspection.

Verification: Android JVM tests, Android Lint, original Spark GGUF
Jinja host tests, APK signature continuity and the published release
asset are checked during packaging. The real Pixel and OAuth permission
scopes must still be validated after installation. The Android APK
is a debug-signed device-QA prerelease and should update RC3 in place.


## RC5 - Optional Spark-X2.6 Thinking LOW for native MCP tool planning

The Spark-X2.6 upstream template supports Thinking OFF, LOW and HIGH. RC4
proved the original GGUF Jinja tool section works with Thinking OFF,
but live Pixel tests showed the 4B model may still answer a server-status
question with fiction rather than choosing a tool. Thinking is a possible
improvement to model planning, NOT an alternative to actual MCP execution.

- CHAT > MCP > KI + MCP aktivieren > server > selected tools now offers
  **Thinking LOW (recommended)**, **AUS** and **HIGH**. LOW is the default
  for newly activated MCP model sessions; standalone offline chat retains
  the existing Thinking preference.
- The selected thinking mode is visible in the chat header (MCP LOW, MCP
  OFF or MCP HIGH). No more misleading OFFLINE badge when only the agent
  is disabled: the default now reads MCP AUS. Direct read-only MCP health
  and tools/list remain usable when the model-based agent is disabled.
- The Android JNI/llama.cpp wrapper supplies the requested reasoning
  effort to the original GGUF Jinja template using the JSON string
  reasoning_effort=low or high. Changing the MCP mode first resets the
  decoded native chat/system prefix on the serial inference dispatcher,
  so the stored KV-cache never has a mixture of thinking modes.
- A critical Spark-specific bug is fixed: when Thinking is enabled, the
  template ALREADY prefills the opening <think> token. Model output
  therefore starts with the reasoning BODY, followed by </think> and
  (optionally) a <tool_call>...</tool_call> block. Pocket AI now parses
  the final tool proposal AFTER </think>, rather than treating all
  output as an ordinary model answer.
- Native assistant history now stores completed reasoning separately in
  reasoning_content. This preserves the exact original GGUF/Jinja
  prefix when the next user turn is rendered.
- An incomplete thinking trace, an unexpected duplicate <think>, or a
  malformed tool proposal is NEVER dispatched as an MCP call. The model
  chat KV cache is reset after incomplete reasoning; the user can retry
  with shorter prompts or with Thinking AUS.
- Natural status requests such as "check triforce dev server status" now
  trigger a real MCP protocol health probe, even without the word MCP.
  This ONLY proves the configured MCP endpoint responded; it cannot
  certify the entire TriForce development server, host OS, database,
  other services or user-specific tool permissions.
- The top badge previously said OFFLINE when the MCP model-agent session
  was disabled. It now reports MCP AUS and, when active, the real
  Thinking mode (MCP LOW, MCP OFF or MCP HIGH).

### How to compare on your Pixel

1. Install the RC5 APK directly over RC4 (do not uninstall; same test
   signing certificate preserves app-private downloaded GGUF models).
2. Load Spark-X2.6 Q4_K_M and select a connected MCP server under
   CHAT > MCP > KI + MCP aktivieren.
3. Select one read-only tool such as discord_connection_status, choose
   Thinking LOW and activate; the top should show MCP LOW.
4. Ask the model to use that exact tool and report the actual result.
   Success means a visible, explicit MCP permission dialog followed by
   a genuine server receipt, not merely an encouraging Spark response.
5. Disable/reconnect with Thinking AUS and compare the same request.
   Thinking HIGH is available but may need more output tokens and time.
6. Use /mcp health triforce and /mcp tools discord for deterministic,
   model-independent connection/inventory checks. Neither request
   should ever be treated as proof that unrelated services are healthy.

Host Jinja tests verify original Spark template rendering for OFF, LOW
and HIGH, including tool parameters and multi-turn reasoning reconstruction.
Android JVM tests also cover the actual observed misleading screenshot
request and malformed Thinking output. On-device sampling behavior must
still be tested: these checks do not imply guaranteed 4B tool selection.


## RC6 - Model-independent MCP quick actions and GGUF backup export

The tiny Spark 4B model should not need to reason about every simple tool
request. RC6 adds a direct, explicitly approved read-only path:

- CHAT > MCP > MCP Schnellbefehle (ohne Spark) offers simple account,
  grants, voice-state, TriForce system status, notifications and search
  commands. The chooser fills the input; nothing executes until Send.
- The direct path works even without loading a local GGUF. It uses the
  Android profile's actual authenticated endpoint and live tools/list,
  validates the exact tool and current input schema, asks for one-time
  explicit approval and shows the true raw tools/call server response.
- Fixed allowlists and exact HTTPS host/path checks prevent users' natural
  chat text from invoking arbitrary write-capable tools. Unrecognized
  commands or unexpected arguments fail closed, never fall back to a fake
  LLM result. There are no automatic retries after a dispatched tool.
- Discord's linked-account state is distinct from server-specific read
  grants. A voice-status result of server_read_grant_required is explained
  as missing permission, not a down server.
- Tests simulate HTTP 401/403, timeouts, altered tool schemas, server tool
  disappearance, consent denial and a request possibly completed before an
  HTTP error; none is counted as a verified action without its receipt.
- MODELS > Export creates a user-selected external GGUF backup with
  Android Storage Access Framework. Large files copy using a bounded
  128 KiB buffer with progress and cancel controls; no broad filesystem
  permission is required. Failed/cancelled exports may leave partial
  external files and are not valid backups.
- A manual, protected production Android-signing CI workflow is prepared
  with secret-only private signing inputs and a pinned certificate. No
  long-term production signing key has been configured or published.
  Development RC debug signing remains unchanged for in-place RC updates.

Documentation: docs/PIXEL_MCP_QA.md, docs/RELEASE_SIGNING.md.

Remaining external dependencies: an actual Pixel A/B tool-choice test,
account-specific Discord server grants, production signing secrets and QA of
the debug-to-production GGUF data migration. Android foreground downloads,
GPU acceleration and other untested GGUF architectures remain future work.


## RC7 - Discord OAuth client repair and remote-only MCP cleanup

Observed Pixel screenshot: the Discord OAuth server displayed "Unknown OAuth
client or redirect URI". Its production client registry contained no
matching Pocket AI registration. Public dynamic registration accepted a new
Android loopback callback (HTTP 201), and that registered client got a
correct redirect to login. The server's OAuth error page has been improved
to guide safe re-registration without reflecting unknown IDs.

- Old Discord public-client IDs (from pre-RC7 profiles without provenance)
  are automatically replaced by ONE new dynamic OAuth registration.
- The public client registration timestamp persists in the encrypted Android
  MCP profile to avoid repeated registrations and provider rate limits.
- SETUP > Discord MCP > Discord OAuth reparieren resets ONLY the local
  public-client details and old tokens, registers a fresh public client,
  and opens the browser consent flow. Existing online guild and voice grants
  remain unaffected. The owner chooses when to run this destructive token
  reset; the UI asks for confirmation.
- Android callback uses http://127.0.0.1:18467/oauth/callback and is checked
  against the server's returned registered redirect before launching Chrome.
  The AILinux issuer is pinned to https://discord.ailinux.me for this preset.
- Explicitly chosen OAuth scopes are preserved, without silently increasing
  permissions. Other OAuth providers still support manually chosen client IDs.
- When the model-agent is off, the header now says AGENT AUS, not MCP AUS.
  The actual OAuth connection state is shown separately in SETUP.

**Pixel test**: Install RC7 OVER RC6 (same developer test signer), open
SETUP > MCP-Verbindungen > Discord MCP > Discord OAuth reparieren, authorize
the AILinux login/consent in Chrome, return via the local callback and run
/mcp quick discord-status, approving the actual tool. Do not uninstall the
app or delete your GGUF to repair login. On-device completion still needs
a user-controlled Pixel QA run; backend and fake Android tests are not proof
of a successful live browser callback.

**Zombie-PC cleanup**: The legacy local Discord Voice MCP service was
stopped and its user unit and two local Discord MCP source installations
deleted from zombie-pc. TriForce stayed active, and the separate online
https://discord.ailinux.me/mcp remained reachable. The source may still be
recovered from the Git history if explicitly reinstalled.

**Separate bot token issue**: Discord returned 401 for the protected bot
worker token. Reinstalling the bot via Discord does not rotate its token
on the Hetzner host. The worker's token must be securely provisioned on
the server (never shared over chat) before actual bot actions resume.


## RC8 - no fake TriForce log analyses when Spark Thinking is enabled

In RC7, a Pixel screenshot showed that a direct TriForce server MCP status
check worked (real server/discover / tools/list), but the separate question
"versuche die triforce server logs zu durchsuchen" was answered by the local
Spark-X2.6 4B model with unsupported assertions that all logs had been
reviewed and were clean.

Verified live through the public online https://api.ailinux.me/v1/mcp
tools/list: 91 tools were advertised, but NO logs, logs_errors,
log_viewer, journal, journal_viewer or read_logs tool. The status
tool is available but is NOT an equivalent log-read capability.

New fail-closed direct route:
- Natural TriForce log-audit questions and /mcp logs triforce now bypass
  the small GGUF entirely.
- Pocket AI performs an actual online tools/list on ONLY the configured
  https://api.ailinux.me/v1/mcp, and reports whether explicit
  log-reading tools are even advertised.
- If none exists, the app explicitly reports that zero log lines have been
  read and does not claim the logs are healthy or error-free.
- If a known log-reader becomes available later, Pocket AI lists its name
  but does NOT auto-invoke it: tool schema, rights and one-time user consent
  must first be verified independently.
- The MCP menu offers "TriForce: echten Log-Zugriff prüfen (ohne Spark)".
- Offline-model fallback detects and rejects the exact false log claim,
  while preserving truthful "cannot inspect logs" statements.
- SETUP explicitly explains that local Thinking ON/OFF has no authority
  to enable MCP tools. For genuine tool calls enable CHAT > MCP >
  KI + MCP aktivieren, select tools and approve each action separately.
- /mcp quick triforce-status remains available as a real read-only
  system status call, not a log audit.

RC8 still requires on-device QA for the original Pixel browser OAuth
callback; the tests and desktop host cannot claim otherwise. The separate
remote Discord MCP OAuth fix from RC7 is unaffected.


## RC11 - Progressive Tool Discovery

Pocket AI RC11 adds a model-controlled MCP Auto-Bridge on top of RC10's
native Spark-X2.6 assistant / tool / assistant Jinja support.

- Load a GGUF model, then open CHAT -> MCP -> AUTO-BRIDGE.
- Select up to four existing authenticated online MCP server profiles
  (TriForce / Discord), using the encrypted Android profile vault.
- The bridge retrieves each selected profile's actual account-specific
  MCP tools/list response. Nothing is uploaded to a new MCP server.
- Spark sees just THREE local functions:
  mcp_search_tools(query, limit?),
  mcp_get_tool_details(tool_id),
  mcp_call_tool(tool_id, arguments_json).
- Namespaced tool IDs distinguish same-name tools on different servers.
  Search results and schemas are bounded, local and session-scoped.
- The actual remote call requires search and details first, a clear
  user confirmation with endpoint/name/arguments, live re-discovery and
  revalidation of the exact current schema, then at most ONE tools/call.
- The real server receipt is shown BEFORE native role=tool model feedback.
  On denial, cancellation, timeout or incomplete tool calls the local
  llama.cpp tool turn is reset instead of falsely claiming success.
- The catalogue expires after ten minutes. Auth and scope remain per
  original server profile, not per-model or globally cached credentials.
- Direct natural commands such as "mcp tool check" and "check mcp tools"
  now use real tools/list when the MCP agent is OFF.
- Existing manual MCP tool selection and direct quick commands continue.

This is model -> server-tool integration, NOT a remote inference endpoint
or new background service on the Pixel.

References:
https://modelcontextprotocol.io/specification/2026-07-28/server/tools
https://github.com/modelcontextprotocol/modelcontextprotocol/blob/main/docs/docs/2026-07-28/develop/clients/client-best-practices.mdx
https://github.com/ggml-org/llama.cpp/blob/master/docs/function-calling.md

No Pixel Android debug device is connected in the build environment.
Automated host/JVM tests cannot prove model latency or real on-device
multi-step native tool calling or OAuth consent; see the QA guide.


## RC12 - MCP Model Bridge controls in SETUP

The complete RC11 Progressive MCP Auto-Bridge remains supported.
RC12 fixes a UI discoverability gap reported after reinstalling Pocket AI:
server profiles were visible under SETUP but the model-agent/auto-bridge
controls existed only under CHAT -> MCP.

- SETUP -> MCP VERBINDUNGEN now includes an explicit
  "MCP MODELL-AGENT · AUTO-BRIDGE" section and live session status.
- Both CHAT -> MCP and SETUP -> KI + MCP / AUTO-BRIDGE open the
  SAME agent configuration and consent implementation.
- Separate "MCP-Tools live prüfen (ohne Modell)" option gives access
  to the real authenticated MCP catalogue even if GGUF is not loaded.
- Users may opt in to a selected manual or progressive bridge while
  a GGUF model is downloading. The badge reads "MCP VORBEREITET"
  until the model is loaded. Actual JNI binding occurs only on
  successful model startup.
- Upon model load, the status reflects the active MCP AUTO LOW or
  manual MCP mode; the source/permission checks from RC11 are unchanged.
- Android MCP menus and server checkbox selection now really render their
  choice lists (previous RC11 dialogs combined setMessage + setItems, hiding
  options under a text-only AlertDialog). The MCP quick-command and small
  offline-model pickers received the same fix.
- SETUP explains that clearing App Data / uninstalling erases private
  stored GGUFs and Keystore-protected MCP profiles, whereas installing
  a properly signed update over the existing app preserves data.

The Spark model weights are NOT bundled in the APK. The currently
verified official release family is Spark-X2.6-4B (2026-10-21).
Different Hugging Face quantizations are not necessarily newer
base models. No download is initiated by the settings UI changes.


## RC13 - MCP standard server instructions and tool discovery

The MCP protocol has an optional server instructions field which helps
clients explain HOW to use the available tools. Pocket AI through RC12
discarded that field, preserving only the tool catalogue. The local
Spark model therefore never received this source of guidance.

RC13 introduces TWO strictly separate sources of model guidance:

1. Trusted Pocket AI client contract: when a manual/Auto agent is
   activated, the app appends its own discovery rules to the system
   prompt: authenticated tool search, real tool feedback, separate
   human approval and at most one external tools/call per request.
   Auto-Bridge uses mcp_search_tools -> mcp_get_tool_details ->
   mcp_call_tool. A plain inventory query never needs an external
   server action. These rules remain active with NO optional server
   instructions present.

2. OPTIONAL untrusted hints: Pocket AI reads the actual
   server/discover.result.instructions for MCP 2026-07-28 and
   initialize.result.instructions on legacy MCP (including HTTP+SSE).
   Only a short sanitized, quote-delimited reference excerpt can enter
   Spark's model context, only with EXPLICIT user opt-in while choosing
   the authenticated MCP profile. OFF by default and session-scoped.
   Credentials, role tags and obvious attempts to override app policy
   are excluded. The UI shows how many safe hints were included.
   No server instructions are trusted policies or authorization grants.

### Settings

SETUP -> MCP VERBINDUNGEN -> MCP Server-Instructions anzeigen:
- Pocket AI - Standard Tool-Discovery-Regeln (lokal) previews the
  app-owned rules WITHOUT a GGUF or authenticated server.
- Choosing a configured server reads its true discovery metadata using
  that server's existing account credentials and reports absence
  honestly, rather than synthesizing fake server instructions.
- A separate OFF-by-default checkbox in AUTO-BRIDGE or manual tool
  selection can include bounded remote guidance as untrusted reference.

The previously installed GGUF files and Keystore-backed profiles are
retained by an in-place signed update. Nothing in this fix changes the
OAuth/consent or actual tools/call enforcement.

References:
- https://github.com/modelcontextprotocol/modelcontextprotocol/blob/main/schema/2026-07-28/schema.ts
- https://blog.modelcontextprotocol.io/posts/2025-11-03-using-server-instructions/


### RC13: GUI responsiveness and offline diagnostics

The Pixel process-exit entry at 09:35:36 contained
killDueToPackageUpdate, exit status 0; this is a NORMAL app update
termination, not evidence of a crash or Android ANR. The user
also reported intermittent GUI delays, a separate symptom.

To reduce possible main-thread blocking:
- MCP Keystore-backed profile list rendering executes on Dispatchers.IO
  and uses a generation guard to discard stale results.
- Manual MCP, Auto-Bridge, live tools/list and server-instructions
  chooser dialogs also decrypt the vault on background dispatchers.
- Durable SharedPreferences.commit() for the GGUF model-loading
  phase still completes BEFORE llama.cpp starts loading, but runs
  on IO rather than blocking the main thread.

A foreground-only local main-looper callback delay meter
is appended to SETUP -> Letzte App-Abstuerze pruefen. It counts
delayed UI callback dispatches >=650 ms during this app process.
This is NOT an ANR diagnosis. It records no prompts, tokens,
screenshots, device files or network data. Actual root cause
requires an on-device Android trace.


## RC14 - Native MCP prefill progress and responsive Auto-Bridge

The Pixel showed long MCP tools binding with intermittent GUI
unresponsiveness. Android's process exits were user-initiated during
phase=system_prompt with 3-4 GB RSS: NOT evidence of an app crash.
Only one UI-lag sample was available after restarting the app, so
that result was too limited to establish responsiveness.

The expensive part after authorized MCP tools/list discovery is the
local llama.cpp CPU decode of a new Jinja SYSTEM+tools prefix.

Changes:
- Exact native C++ progress counters for SYSTEM tokens, accessible
  through a lock-free atomic JNI query. The GUI shows decoded/total
  tokens, percent and elapsed seconds with a real progress bar,
  sampled every 350 ms without waiting on the llama dispatcher.
- The MCP chip says MCP STARTET during decoding, and switches to
  MCP AUTO LOW only after real successful native ModelReady.
- The chat input stays editable and navigation remains usable
  while binding; sending is disabled until the model is ready.
- Status/progress has its own full-width row. The POCKET AI title
  no longer wraps behind the model/MCP status labels.
- Batch prefill threads capped at 3 to reduce competition with
  Android's main UI thread. Generation thread limit unchanged.
- Exact system prompt, tool JSON and Thinking mode reuse the
  already decoded SYSTEM KV prefix when the same MCP bridge is
  selected again. Only the old conversation tail is removed.
  Any changed tool, prompt or mode forces normal full re-prefill;
  failed KV trimming safely falls back to the full reset path.
- More compact app-owned discovery instructions and the three
  local tool descriptions reduce first-bind model prefill tokens.
  Schema validation and per-remote-call approval remain enforced.
- Existing offline process diagnostics show the last bind token
  count and elapsed seconds for this same running app process.
- Model-side MCP search output still uses genuine structured
  results from authenticated tools/list, while the separate human
  display renders complete names instead of truncating 1250 bytes
  of raw JSON. A real RC13 Pixel check discovered 100 tools on
  two selected MCP servers; actual tools/call remains separately
  gated by owner confirmation.

This update does NOT change remote TriForce/Discord credentials,
user grants, tools/list transport, or the one-real-call limit.

The initial new system prefix may still require substantial CPU
time. Android unit tests and ARM64 native build do not prove
real device performance: a physical Pixel test is still needed.
