# Pocket AI AUTO-MCP regression and Pixel device acceptance

Status: planned real-device QA, NOT a completed Android test.
Target: next candidate after the publicly released 2.1.0-RC12.
Scope: local Gemma E2B LiteRT (Google Tensor G5) and separate GGUF session, when available.
Use only user-authorized read-only tools. Local s1/ namespace IDs are not server-issued IDs.
Never log credentials, private endpoints, prompt contents or original tool-result bodies.

## Preconditions
1. Confirm actual installed versionName/versionCode for package me.ailinux.pocket on device.
   Do not infer installed version from the GitHub releases page.
2. Verify the local model and the actual backend, then enable AUTO-MCP.
3. Verify the real authorized TriForce Workspace / DEV / Discord tool counts.
4. Correlate events by per-turn trace IDs, not only by local wall time (DST fallback).
5. Do not install an update during an active download or native model inference.

## On-device prompts and expected proof

| Case | Prompt | Acceptance |
|---|---|---|
| A | Erkläre mir in zwei Sätzen, was MCP ist. | Explanation only; no approval/dispatch/receipt |
| B | Welche MCP-Werkzeuge sind verfügbar? | Catalogue only; tools/list is NOT tools/call |
| C | Brumo, rufe über den TriForce DEV MCP den aktuellen Systemstatus ab. Suche das echte Werkzeug und frage vor dem Aufruf. | Correct DEV profile, SEARCH/DETAILS, explicit APPROVAL, AUTH_CHECK, SCHEMA_CHECK, DISPATCH, actual SERVER_RESULT |
| D | Prüfe, ob Discord verbunden ist. Nutze die echte MCP-Serverantwort. | Real discord_connection_status or specific fail-closed error |
| E | Check my Discord connection using MCP and show the verified server response. | Independent read-only request, actual approval and receipt |
| F | Wiederhole bitte den TriForce DEV Statusabruf mit dem MCP-Werkzeug. | New approval and new server receipt, not previous cached result |
| G | Bitte rufe mit MCP den TriForce-Status ab (Server nicht eindeutig). | Ask for specific profile if same-named status functions; no arbitrary dispatch |
| H | Den vorgeschlagenen Toolaufruf ablehnen. | APPROVAL_DENY and no DISPATCH |
| I | /mcp health | Protocol probe only, not proof of tools/call |
| J | Erkläre mir, wie ich eine Discord-Verbindung prüfen kann. | No external action |
| K | How do I check my Discord connection? | No external action |
| L | Prüfe den TriForce-Status und erkläre dann das bestätigte Ergebnis. | At most one approved real call and honest model continuation |

If LiteRT fails to emit FunctionCall, the app may display a clearly labelled
HOST_FALLBACK proposal for one audited read-only tool. This still needs
fresh consent and a real server receipt. Invalid native schema must be
classified; no fabricated tool calls or repeated uncertain writes.

For each case record: installed version, backend, catalogue size, selected
tool/profile, approval decision, trace ID, real DISPATCH and SERVER_RESULT
or explicit rejection, latency and whether the conversation continued.
Never include actual OAuth secrets, tool outputs or prompt text in the
shared diagnostic report.

## Device gate
The paired Helper was connected but the Android device was on its secure
lock screen at the time of the initial review. These steps are not marked
complete until the device is unlocked and actual UI tests run.
