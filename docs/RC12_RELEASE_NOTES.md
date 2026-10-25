# AILinux Pocket AI 2.1.0-RC12 (versionCode 37) - Android QA

## P0: model downloads queued behind VPN connectivity constraint

On the Pixel Android 17 test device, Qwen3_1.7B.litertlm
was queued with PENDING_JOB_REASON_CONSTRAINT_CONNECTIVITY,
zero executed transfer attempts, and no byte progress.
The Android system UI confirmed active WireGuard Internet connectivity.
RC11's UIDT NetworkRequest.Builder required the default NOT_VPN
capability even though a VPN is the intended Internet transport.

RC12 explicitly clears NET_CAPABILITY_NOT_VPN while retaining
INTERNET and the user's explicit Wi-Fi-only preference. The
JobScheduler/network diagnostic also reports privacy-safe booleans
for validated, Internet, Wi-Fi, VPN and metered; no SSID, IP, DNS,
token or model URL is included.

An in-place APK update alone may leave an old queued job with its
original JobInfo. The user should explicitly pause and then resume
the existing download to submit a new VPN-compatible UIDT job.
Do NOT delete models, .part files, validators or app data.

The pending job must not be called fixed until the Pixel shows a real
worker start, increasing downloaded byte count and ultimately verified
file installation. Android screen-off, network switch and force-stop
restrictions still apply; never claim unconditional background work.

# AILinux Pocket AI RC12 - Visible MCP Auto-Bridge Settings

RC12 addresses a Pixel usability regression in RC11: only MCP server
profiles appeared under SETUP while the model-agent/auto-bridge controls
were hidden exclusively under CHAT -> MCP. Clearing App Data / reinstalling
did not add those controls; it only deleted private downloaded models and
stored credentials.

## Screenshot root cause fixed

The Android screenshot at 09:30 exposed a second, more important RC11 bug:
"Pocket AI · MCP-Bridge" displayed only the explanation and "Schließen",
with no selection list. AppCompat AlertDialog setMessage competes
with setItems / setMultiChoiceItems for the dialog content panel.

RC12 uses a custom explanatory HEADER so the actual list and multi-server
checkboxes remain VISIBLE. The quick MCP command picker and the small
offline-GGUF picker were also corrected to remove the same incompatible
builder combination. This is not a model/backend error; the GUI was hiding
the controls.

## Changes
- A new visible **MCP MODELL-AGENT · AUTO-BRIDGE** section under
  SETUP -> MCP VERBINDUNGEN.
- **KI + MCP / AUTO-BRIDGE konfigurieren** opens the exact same
  authenticated agent menu available under CHAT -> MCP.
- **MCP-Tools live prüfen (ohne Modell)** shows the existing genuine
  per-profile tools/list, even when no GGUF has been downloaded or loaded.
- A live status label explains whether the agent is OFF, PREPARED
  while awaiting a GGUF, or actively bound in manual/AUTO mode.
- Select an authorized server and prepare an agent while the GGUF is
  still downloading. The local model binds the three progressive
  meta-tools only when model loading successfully completes.
- Explains what is deleted by Android App Data reset/uninstall vs
  what survives an in-place signed APK update.

## Maintained RC11 behavior
- Spark-X2.6 native Jinja tool-role bridge and progressive search -> details
  -> at most one explicitly user-approved actual MCP tools/call.
- OAuth and authorization remain per original server profile.
- Real tool responses appear separately and never imply authorization
  or success unless the real server result confirms it.
- No public phone-side inference/MCP server and no automatic remote actions.

## Spark models
This Android app update does not replace or include GGUF weights.
As of 2026-10-24, the official Spark project lists Spark-X2.6-4B
as the latest released series (global launch 2026-10-21).
Several quantizations and refreshed community GGUF files exist;
a different file upload date does not prove a newer base model.
Source: https://github.com/XHToken/Spark-X2.6

## Validation
- Android JVM regression tests and original Spark native tool tests
- Android debug/release build and lint
- Signed ARM64 debug APK checked against RC11 signer
- In-place install only; no direct Pixel ADB test was available on server.
