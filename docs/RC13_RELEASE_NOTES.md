# AILinux Pocket AI RC13 - MCP Server Instructions and Discovery Contract

Pocket AI RC12 exposed the MCP model agent, but its client only retained
advertised tools, not optional natural-language instructions from
standard MCP server discovery. RC13 captures the missing field and
makes model / host responsibilities explicit.

## MCP protocol conformity

- Modern 2026-07-28: server/discover.result.instructions is captured.
- Handshake-era 2025-11-25: initialize.result.instructions is captured,
  including old HTTP+SSE compatibility clients.
- McpServerInfo now includes bounded server instructions without
  changing the existing tool catalogue or protocol transport.
- The trusted, app-owned system contract gives the local GGUF actual
  tool discovery instructions and separates tools/list from tools/call.
  In AUTO mode the model uses mcp_search_tools -> mcp_get_tool_details
  -> mcp_call_tool. The real action still requires explicit user
  consent and current server tool definition validation.
- Absent server instructions stay absent: no model guesswork.

## SETUP controls

- New MCP Server-Instructions anzeigen button under MCP VERBINDUNGEN.
- Preview local Pocket AI standard tool discovery rules even with no
  GGUF or authenticated MCP server.
- Or select an MCP profile to inspect its real authenticated discovery
  instructions; the read-only request does NOT execute any tools.
- An OFF-by-default checkbox allows bounded server guidance as
  UNTRUSTED reference data during AUTO-BRIDGE or manual agent setup.
- The status indicates how many safe server hints were included.

## UI responsiveness and local diagnostics

The user's 09:35:36 Android process report showed an ordinary
package update with exit status 0, not a crash. The intermittent
UI delays are a separate issue still needing a Pixel trace.

- Encrypted MCP profile reads for the SETUP list and the manual,
  AUTO, tools-list and server-instruction selection dialogs now
  execute off the UI thread.
- Durable model-loading process markers are committed from IO.
- The existing Android exit report now includes a local, in-memory
  foreground UI callback delay count and maximum observed delay.
- UI delay detection does not record prompt text, OAuth credentials,
  server tool results or network data. It does not prove an ANR.
- Real Pixel CPU saturation remains possible and requires a
  physical device trace to attribute.

## Privacy and security

- The host never trusts server instructions as a new security policy.
  A remote instruction cannot grant additional OAuth scopes,
  bypass user approval, inject Spark role tokens, or create tools.
- Bounded remote instructions: 4096 characters from wire protocol,
  at most 280 characters per source in the prompt and a 720-character
  combined quota, with redaction of URLs and key-like strings and
  basic rejection of role override / credential leakage attempts.
- Existing native tool feedback, authenticated tool lists, per-call
  approval and no automatic retry remain unchanged.
- In-place updates preserve downloaded GGUF models and Keystore data.

## References

- https://github.com/modelcontextprotocol/modelcontextprotocol/blob/main/schema/2026-07-28/schema.ts
- https://blog.modelcontextprotocol.io/posts/2025-11-03-using-server-instructions/

## Validation caveat

Android JVM tests cover modern / legacy discovery and prompt safety.
Native Spark/Jinja host tests cover tool continuation. Full Pixel
model+OAuth execution still needs hands-on testing. The anonymous
TriForce/Discord discovery requests returned HTTP 403 / HTTP 401,
so the actual private server instructions were not available.
