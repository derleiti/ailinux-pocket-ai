# AILinux Pocket AI 1.0.0-rc.11 - Progressive MCP Model Toolbridge

RC10 had native llama.cpp / Spark-X2.6 assistant-to-tool-to-assistant
continuation but required choosing up to five explicit tools manually.
RC11 introduces an OPTIONAL automated model-driven discovery pathway.

## What's new

- CHAT -> MCP -> AUTO-BRIDGE. Select up to four existing, authorized
  MCP server profiles such as TriForce and Discord.
- Account-specific live tools/list inventories are inspected via
  the original encrypted credentials. They are stored only in a
  short-lived in-memory session, with ten-minute maximum lifetime.
- The local GGUF sees exactly three meta-functions:
  mcp_search_tools, mcp_get_tool_details and mcp_call_tool.
- Deterministic small German/English search; six results maximum.
  Real tool IDs are namespaced by server to avoid name collisions.
- Model must use search then details before proposing a real tool call.
  Invalid JSON schemas, arguments, unrecognized IDs and mismatched
  live tool definitions are rejected before any network action.
- The approval UI shows the actual server, tool and args for every
  potentially mutating remote request. At most ONE remote call
  per user question, never automatically retried after a timeout.
- Authentic remote result is shown as raw server receipt, then passed
  through RC10's native GGUF role=tool bridge for summarization.
  Local meta-tool responses are also delivered as native tool-role turns.
- User rejection, error, incomplete input_required and cancellation
  safely end or reset the native tool session; no fictitious successes.
- With MCP Agent OFF, "mcp tool check", "check mcp tools" and
  "prüfe MCP Tools" route directly to real tools/list without Spark.
- Existing manual MCP selection, OAuth flows and direct quick commands
  are unchanged and remain available.

## Protocol reference

MCP tools specification:
https://modelcontextprotocol.io/specification/2026-07-28/server/tools

MCP Client Best Practices / Progressive Tool Discovery:
https://github.com/modelcontextprotocol/modelcontextprotocol/blob/main/docs/docs/2026-07-28/develop/clients/client-best-practices.mdx

llama.cpp function calling:
https://github.com/ggml-org/llama.cpp/blob/master/docs/function-calling.md

## Security and test status

No extra public MCP server or Pixel server port is installed.
Auth scopes remain in each original server profile and are never
copied into the model context. Every real tool operation requires
fresh per-call user consent and live revalidation of its schema.

Device tests remain necessary for real Spark-X2.6 multi-step tool
generation and Android browser OAuth callback. Full native host,
JVM, lint and APK verification must pass before public release.

## Pixel smoke test

Install over RC10, load Spark-X2.6, open CHAT -> MCP -> AUTO-BRIDGE,
select your authenticated Discord MCP and TriForce profiles.
Prompt "Brumo, pruefe den Discord Status mit dem MCP Werkzeug".
Confirm the real endpoint/name/args only after inspecting them.
Verify one real server receipt followed by native model summary.
Test rejection and an Agent-OFF "mcp tool check" separately.
