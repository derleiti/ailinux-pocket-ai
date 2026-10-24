# AILinux Pocket AI 2.1.0-RC9 · Android compatibility and unfinished work

**Candidate:** 2.1.0-RC9 / versionCode=34 / applicationId=me.ailinux.pocket
**Type:** arm64 developer/QA pre-release, **debug-signed**, NOT a production-signed Play Store release.
**Base:** RC8 plus integrated PR #11/12 safety fixes. Existing RC8 debug-signed
installations must use the same certificate. Check SHA-256 certificate fingerprint
before offering an in-place update.

## Phase status

| Phase | Status | Evidence and remaining work |
|---|---|---|
| 0. Review | Baseline reviewed | PR11/12 branch histories, CodeRabbit comments and local working tree inspected. Merge only on green CI. |
| 1. MCP | Partial, security corrections | Verified explicit readOnlyHint is required for host fallback; additionally audited tool names and fresh server schema plus consent. Real Pixel 10 Pro tools/call end-to-end test is still outstanding. No fake success from search. LiteRT receives sanitized bounded actual tool receipts. Modern input_required is not silently replayed. |
| 2. Downloads | New implementation, device QA outstanding | Model downloads move from MainActivity lifecycleScope to Android 14+ user-initiated JobScheduler and Android 13 WorkManager foreground worker. Journal tracks progress and allows manual resume/pause, HTTPS Range + If-Range, checksum validation and atomic .part completion. Test Doze, network changes and force-stop on hardware. |
| 3. Chat history | New implementation, device QA outstanding | Room 3 stores metadata and full user/assistant transcript. An end-side swipe drawer plus visible button supports new chat, search, pin, rename, archive, delete (non-current chat), and open. Opening a history resets native model context without claiming KV cache restoration. Database export schema v1. |
| 4. Background inference | **NOT DONE** | Existing GGUF/LiteRT inference is Activity-owned and onStop cancels generation. A foreground inference service, thermal controls, notifications and backend-specific display-off tests are still required. |
| 5. Model catalog | **NOT DONE** | Existing Hugging Face support retained; large-scale DB/Paging and Tensor G5 per-file compatibility verification require further work. |
| 6. Internationalization | **NOT DONE** | UI contains German hardcoded strings; do not advertise complete language support. |
| 7. Security/stability | Partial | No credentials sent to model by this change; app-private download journal; no retry after unknown MCP dispatch. Device-specific and concurrency tests remain. |
| 8. Release | QA-only pre-release | Gate on Gradle tests, lint, APK verification, signature digest and public asset SHA-256 redownload. Real Pixel E2E is not confirmed by server-side testing. |

## Security and lifecycle decisions

- The app remains the MCP client: no unverified model text produces a server receipt.
- annotations.readOnlyHint is a server-provided hint, not authority: host fallback additionally checks an allowlist, asks the user and revalidates live.
- UIDT schedules only from a visible, explicitly confirmed click. Android may interrupt even UIDT; force-stop is not promised to continue. The journal is recovery state, not execution proof.
- An incomplete download remains .part; a completed installed file is never overwritten on failure. A server ETag/Last-Modified protects byte-range resume when available.
- The Room transcript and native model KV state are separate. A previous conversation can be displayed, but continuing it does not reproduce an old native context.
- Successful JVM tests and signed APK packaging do not establish Google Tensor G5 hardware compatibility.

## References

- MCP 2026-07-28: https://modelcontextprotocol.io/specification/2026-07-28/server/tools
- Android UIDT: https://developer.android.com/develop/background-work/background-tasks/uidt
- Data transfer options: https://developer.android.com/develop/background-work/background-tasks/data-transfer-options
- Room: https://developer.android.com/training/data-storage/room
- Locale APIs: https://developer.android.com/guide/topics/resources/app-languages
- LiteRT-LM: https://developers.google.com/edge/litert-lm/android

## Pixel 10 Pro QA handoff

1. Install RC9 over RC8 WITHOUT uninstalling. Confirm existing models survive.
2. Authorize two MCP connections, select AUTO-BRIDGE.
3. Ask Brumo to get actual TriForce status; verify real tools/call result, approval,
   and follow-up behavior separately for GGUF and LiteRT.
4. Start a large model download; minimize app, change network, disable screen,
   pause/resume, then verify checksum and model header.
5. Create chats, swap models, restart/update, search, rename/archive/delete,
   and test end-swipe/back gesture. Validate 1000-message history.
6. Confirm LiteRT Tensor G5 behavior using specific model files and native logs.
