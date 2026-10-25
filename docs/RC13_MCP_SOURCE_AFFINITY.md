# RC13: MCP server source affinity (on-device RC12 regression)

## Confirmed device reproduction

Android 17, local Gemma-4 E2B / LiteRT Google Tensor, Pocket AI RC12, three online
MCP servers. A user specifically requested **TriForce Workspace** system
status. mcp_search_tools returned s1/status (Workspace) and s3/status
(DEV), plus other tools. The native model selected mcp_get_tool_details
for s3/status instead of the requested Workspace server. The run finished
with INVALID_NATIVE_ARGUMENTS_SCHEMA / a reset or cancellation. No
DISPATCH -> SERVER_RESULT was observed for that request.

## Minimal host-side repair

The original *user request*, not the model's intermediate query, is now
passed to Run.search, Run.details and Run.prepareCall in both native
GGUF and LiteRT progressive tool bridges, plus the safe host fallback.
When the user explicitly names Workspace, DEV or Discord, search only
exposes those server sources to the model and the host rejects any schema
lookup or prepared call from a different server. If a request names two
distinct sources, the host fails closed and asks for a disambiguated task.

A lone generic "TriForce" is *not* treated as the unique Workspace
source: multiple TriForce servers can exist. Local s1/ IDs are
short-lived catalog indexes, never claims of server-issued global IDs.
The actual MCP connection, authorization, read-only hint, argument schema,
user approval and real server result remain the pre-existing authority.

## Verification

JVM regression cases cover duplicated status tools among 172 tools across
three sources, user-requested Workspace vs DEV, ambiguous requests,
details/prepareCall bypass, and safe read-only host proposal with zero
network dispatch. Run the complete Android unit suite, Lint and JNI
arm64 build before merging. After an in-place RC upgrade, repeat the
original user prompt and verify s1/status, approval, then genuine
DISPATCH -> SERVER_RESULT. Do not mark device E2E complete before that.
