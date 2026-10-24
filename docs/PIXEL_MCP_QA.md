# Pocket AI RC6 — Pixel MCP checklist

Last code-side verification: 2026-10-24. Static/JVM and host GGUF-template
tests are NOT substitutes for a real Pixel device with the actual OAuth account.

## 1. Spark-X2.6 natural tool-call A/B

1. Install the same-cert GitHub Release RC6 APK directly over RC5.
2. Load the previously downloaded Spark-X2.6 GGUF.
3. Open CHAT > MCP > KI + MCP aktivieren and choose Discord.
4. Select only discord_connection_status and Thinking LOW.
5. Ask the local model to use this tool and read the current account status.
6. Only count success if a specific MCP tool name appears in the approval
   dialog, you approve it, and an actual MCP server receipt is displayed.
   A plain Spark sentence claiming the server was checked is NOT success.
7. Reconnect with the same tool and Thinking AUS, then compare behavior.
   Try HIGH only if needed, as it increases token/runtime demand.
8. Repeat one natural-language status question after a second chat turn to
   catch multi-turn Jinja reasoning/KV-cache mismatch.

No active Pixel/Android Helper session or attached ADB device was available
at the time these RC6 changes were authored. Device success is NOT claimed.

## 2. Discord: account linked vs server-read grants

The read-only AILinux Discord connector showed one linked Discord identity
and ZERO approved server grants at the time of checking (2026-10-24).
That connector session and the Android OAuth profile may represent different
tokens/scopes, so the Pixel must ask the actual configured profile.

1. Confirm the Discord MCP OAuth login in Pocket AI SETUP.
2. Use /mcp quick discord-status, then /mcp quick discord-guilds.
3. Use /mcp quick discord-voice.
4. Inspect exact MCP responses. An available=false result with reason
   server_read_grant_required means this ACCOUNT has no server-specific
   permission to see Nova voice state. No voice bridge outage is implied.
5. Only the account owner can explicitly authorize the intended server/read
   grant in the relevant AILinux/Discord authorization flow. Do not grant it
   silently or reuse a bot token to bypass the account's consent.
6. Re-run all three read tools only after the grant has been approved.

## 3. Deterministic MCP quick actions, without Spark

From CHAT > MCP > MCP Schnellbefehle (ohne Spark), select a command.
The app only inserts it in the composer, so tap Send and approve the
exact live-discovered tool call separately.

Commands:
- /mcp quick discord-status
- /mcp quick discord-guilds
- /mcp quick discord-voice
- /mcp quick triforce-status
- /mcp quick notifications
- /mcp search linux
- /mcp code-search ModelExportCopy
- /mcp memory-search Brumo

The caller must confirm every tools/call, including read-only quick calls.
A server probe alone is not a user-grant check. Results are shown as raw
server receipts without a second LLM's invented interpretation.

## 4. Errors, interruptions and security

- Clear/expire a test OAuth login: expect authentication guidance, not an
  implicit fallback or access to the server's tools without credentials.
- Force a known disconnected endpoint in a test profile: expect a visible
  failure, not a health success.
- Deny the confirmation dialog: no tools/call is sent.
- Make an MCP server omit the selected tool/change required parameters:
  expect fail-closed validation before the user is asked to approve.
- Interrupt a sent tool call: no automatic replay, outcome unknown.
- Cancel Spark Thinking before the close tag: no MCP action; safe reset.
- Verify the Android header displays MCP AUS (not network OFFLINE) when the
  local model agent is disabled.

## 5. Data migration to a production-signed app

1. Under MODELS, tap Export for each private GGUF file.
2. Choose a durable destination via Android CreateDocument (Downloads,
   Drive or removable storage as appropriate).
3. Confirm reported full size; ideally independently hash/validate the saved
   GGUF and import it into a clean test install BEFORE deleting anything.
4. Keep a copy of model source links/sha256. The SAF export backs up model
   FILES, not Android Keystore credentials, downloaded partial files, chat
   state, or automatically all settings.
5. Reconfigure MCP OAuth credentials after migrating to a differently
   signed production app. Do not assume RC debug data can be retained by
   a simple over-the-top APK update.

## 6. Known optional future work

Foreground/WorkManager multi-gigabyte downloads that survive Android process
termination are NOT implemented. More GGUF architectures, model-loading
benchmarks and accelerated GPU inference remain experimental. Neither area
should be advertised as completed just because the ARM64 CPU RC build passes.


## RC7: Discord OAuth stale client recovery

1. Install same-signer RC7 over RC6 (do not uninstall).
2. SETUP > MCP-Verbindungen > Discord MCP > Discord OAuth reparieren.
3. Confirm local OAuth reset; existing online server grants remain.
4. Complete AILinux authentication and approve the intended MCP scopes.
5. Chrome returns to the Android local loopback OAuth callback.
6. In SETUP, confirm OAuth: Token gespeichert, and test a real approved
   /mcp quick discord-status call.
7. The banner AGENT AUS only concerns native local GGUF tool planning;
   it does NOT report OAuth login state.
8. Discord bot token HTTP 401 is an independent server-side issue.

Do not expose OAuth authorization codes, tokens or secret credentials in
chat or logs. Pixel browser callback completion has not yet been verified
by the host-side automated tests.


## RC8: local Spark Thinking vs actual TriForce log access

Reproduce the original two-prompt Pixel scenario:

1. Thinking may be enabled in SETUP, but after saving you must reload the
   GGUF for the local inference mode to change.
2. With CHAT showing AGENT AUS, ask check triforce server mcp status.
   Expect genuine MCP protocol/tools-list health data; never interpret this
   as log validation.
3. Ask versuche die triforce server logs zu durchsuchen. RC8 must bypass
   Spark and request live tools/list from https://api.ailinux.me/v1/mcp.
4. If no authorized log reader is advertised, it MUST say no log data were
   read. It must not display any invented all logs clean assertion.
5. Run /mcp logs triforce with no GGUF loaded: expect the same capability
   report. No tool execution or direct host-file access is performed.
6. /mcp quick triforce-status is a separate explicit, approved status tool.
   Its result does not prove the machine's logs are clean.
7. To let a GGUF propose genuine MCP tools: CHAT > MCP > KI + MCP aktivieren,
   choose specific tools and a planning mode (LOW is recommended), and
   approve the exact tool/arguments before one real tools/call.

The desktop regression includes the literal screenshot claim and negative
cases (examples, hypothetical statements and honest lack-of-access replies).

## RC10: actual native model tool-role bridge

- Install RC10 directly OVER RC9 (same debug signer; do NOT uninstall).
- With Spark-X2.6 and exactly one known read-only MCP tool, select
  CHAT > MCP > KI + MCP aktivieren, Thinking LOW.
- Prompt the model for that tool. Confirm the exact tool/arguments in the
  one-time dialog. Verify that one real tools/call receipt appears BEFORE
  the local summarization.
- Reject a tool call and check that the model can continue a fresh chat.
  No tool execution or hidden replay is permitted.
- Force a network timeout/error and verify the app does not retry after
  dispatch; the receipt/status must not claim success.
- If a server returns isError, check that Spark's reply cannot change it
  into a successful report.
- If the server returns resultType=input_required, Pocket AI must not
  fulfill the input request automatically.
- An unrecognized GGUF with a JSON-only tool proposal is NOT given a
  native role=tool continuation with no matching native assistant call.
- Probe the Gemma4 GGUF "gemna-4-tiny.gguf": missing
  gemma4.context_length must be reported without modifying model files.
- Android process exit code 10 appears as a user-requested stop, not a
  native crash.


## RC11 - Progressive MCP model toolbridge

1. Update RC10 to RC11 in place (same debug signer); do NOT uninstall.
2. Load Spark-X2.6 and choose CHAT -> MCP -> AUTO-BRIDGE.
3. Select up to four profiles, including both TriForce and Discord when
   already signed in. Confirm that header reads MCP AUTO LOW.
4. Ask Brumo to find Discord MCP tools; expect a real local search result
   grounded in the selected authorized MCP tools/list inventories.
5. Ask for a Discord connection status via the actual tool. The model
   should search, request tool details, then propose a real call.
6. The approval dialog MUST show the REAL selected Discord endpoint,
   tool name and JSON arguments, not merely the synthetic mcp_call_tool.
7. Reject once: no real call, and the native context must recover.
8. Approve a read-only status tool once: verify the original MCP receipt
   before the separately labeled native model summary.
9. Revoke grants, change schemas, force timeout, or test malformed JSON.
   No silent retry, hidden extra calls, made-up results or authorizations.
10. When the agent is OFF, enter "mcp tool check" to verify direct
    deterministic tools/list bypasses GGUF generation.
11. Check outdated catalogue after 10 minutes, duplicate names from
    TriForce/Discord, unknown tools, input_required and server isError.
12. Test normal chat after interruption: no leftover native tool tag.

Real device, Chrome OAuth and live model multi-step QA are still required:
JVM and host simulation cannot confirm those on-device interactions.
