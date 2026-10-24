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
