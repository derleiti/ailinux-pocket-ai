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
