# RC12: Android 17 UIDT pending connectivity on Pixel

## Grounded Android observation

Pocket AI 2.1.0-RC11, Android 17, stored download journal:

- Qwen3_1.7B.litertlm, Job ID 552669068
- queued, attempts 0, no prior stop reason (-1)
- JobScheduler pending reason: connectivity
- no confirmed HTTP worker start, byte progress or transfer error
- another Qwen GGUF transfer was explicitly paused

MCP HTTPS calls to authorized servers succeeded during the same general
session; that is evidence of network capability, not proof that the exact
download job's NetworkRequest matched an eligible network.

## Root cause candidate

A NetworkRequest.Builder contains NET_CAPABILITY_NOT_VPN by default.
Pocket AI RC11 requested NET_CAPABILITY_INTERNET without clearing NOT_VPN.
On Android devices where the active eligible network is VPN, UIDT can remain
queued with PENDING_JOB_REASON_CONSTRAINT_CONNECTIVITY while HTTPS works.
The Pixel's actual VPN transport was NOT reported by RC11, so this is
a plausible incompatibility and not an asserted root cause.

Official Android references:
- https://developer.android.com/develop/connectivity/network-ops/reading-network-state
- https://developer.android.com/reference/android/net/NetworkRequest.Builder
- https://developer.android.com/develop/background-work/background-tasks/uidt

## Change

- Remove only the default NOT_VPN capability from UIDT NetworkRequest.
- Keep required INTERNET and any user-selected Wi-Fi transport.
- Report privacy-safe boolean network flags: validated, internet, Wi-Fi,
  VPN and metered. No IP, SSID, DNS, endpoint or credentials.
- Distinguish a pending constraint with a validated active network from
  a genuinely missing validated network.
- Never automatically cancel or reschedule a queued UIDT job.
  A new explicitly initiated or confirmed retry must apply new JobInfo.

## Local verification and device gates

- Status regression tests for validated VPN with pending connectivity,
  offline network, and separation from Wi-Fi-only.
- Static wiring checks for NOT_VPN removal and no private data.
- Local JVM, Android Lint and ARM64 JNI build pass.

Still required on the Pixel:
1. Update without uninstalling RC11; chats/models/credentials must survive.
2. Inspect actual network capability flags and pending job reasons.
3. With VPN active, user-approved requeue creates a new JobInfo with
   VPN-compatible NetworkRequest; observe actual byte progress.
4. Try with/without VPN and Wi-Fi-only using an allowed data plan.
5. Lock screen, background app, interrupt network, resume .part with
   validator; verify final SHA-256/header before install.
6. Ensure no duplicate worker and no installed label before final checks.

Issue #27 stays open until actual transfer progress and completion.
