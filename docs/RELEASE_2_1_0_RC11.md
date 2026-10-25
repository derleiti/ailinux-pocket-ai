# AILinux Pocket AI 2.1.0-RC11 - LiteRT selected-tool repeat-call QA

VersionName 2.1.0-RC11 / versionCode 36, Android arm64-v8a, Android 13+.
Use the same RC9/RC10-compatible QA signing key; never publish CI-generated
signatures as installable updates. No replacement of earlier release tags.

## Motivation: genuine Android reproduction

On the paired Android 17 device, Gemma 4 E2B with the GOOGLE_TENSOR
LiteRT backend successfully proposed a real MCP status tool call against
TriForce Workspace Streamable HTTP, received explicit user consent and
displayed the genuine server receipt. A later request for the same status
sometimes generated plain text instead of a native FunctionCall. RC10
correctly refused to fabricate an action, but its host-side fallback
was only enabled for progressive MCP catalog sessions.

## Fix

- The same conservative McpOnDemandDiscovery planner now also accepts a
  manually selected, small, authenticated MCP tool snapshot when LiteRT fails
  to emit a native FunctionCall in an explicitly external task.
- It returns a proposal only for one unambiguous audited tool marked
  readOnlyHint=true (boolean), an allowed name and validated empty
  or explicitly provided URL arguments. No hidden calls, no invented schema.
- The user still sees server/tool/parameters and must approve the exact
  single operation. Immediately before tools/call, original profile,
  live schema, full annotations and arguments are revalidated.
- Failure to obtain user consent or validate current capabilities prevents
  dispatch entirely. Post-dispatch cancellation or timeout is conservatively
  unconfirmed; the app never automatically repeats a side-effecting call.
- When the host performs the call, the chat explicitly labels it
  APP-GESTUETZT, not a native model function invocation; it shows the
  genuine server receipt, without claiming role=tool or further model
  summarization. LiteRT native tool calling remains the preferred path.
- Progressive MCP discovery and existing direct quick command routes
  remain unchanged.
- Local privacy-safe MCP/model diagnostics now display the newest event first,
  including on accessibility clients that truncate long TextView content.
  Reports never log credentials, raw prompts or tool result bodies.

## Automated validation

Regression tests cover repeated status requests after a native tool turn,
tool mismatch, explanation-only requests, missing/false risk annotations,
duplicate tool names, unknown/side-effecting tools, required parameters,
and progressive/direct mode isolation. Full test, Lint, native arm64 build
and signing checks must run on the exact merged/tagged commit.

## Required real device QA before claiming end-to-end repeat reliability

- Install RC11 over RC10, without clearing app data.
- Verify that all previous chats, models and MCP credentials survive.
- Load Gemma 4 E2B and confirm the actual GOOGLE_TENSOR runtime.
- Select TriForce Workspace status as the single permitted tool.
- Ask for status twice in separate turns. Each tool call needs independent
  approval. If the native model fails on the second request, verify that
  a clearly labelled APP-GESTUETZT proposal is offered instead.
- Deny one proposal and verify there is NO DISPATCH or server receipt.
- For approved execution, verify DISPATCH and actual SERVER_RESULT in the
  local diagnostic timeline. Never confuse tools/list with a call.
- Test GGUF separately when a working GGUF model is available.

Known outstanding issues: background inference, fully translated UI/RTL,
large-scale model index and complete Tensor G5 model compatibility matrix.
These are not claimed fixed in RC11.

## AUTO-MCP discovery and diagnostics improvements from live system trace

The user-provided RC10 Android system report recorded 172 tools across three
MCP servers and the following incomplete local sequence:
MCP SEARCH OK -> MCP DETAILS FAIL -> LITERT PLAN INFO. No tools/call or server
receipt occurred in that trace. This is a local discovery/schema issue, not
evidence of a remote MCP outage or LiteRT OOM.

The RC11 auto-bridge now:

- Normalizes German compound queries like TriForce-Systemzustand and
  Discord-Verbindungsstatus, splitting identifier separators and applying
  bounded locale-independent synonyms.
- Gives exact tool-name matches and explicitly named source providers
  priority; no accidental substring boost for status inside
  Verbindungsstatus. The index was tested with 172 synthetic tools over
  TriForce, Discord and Workspace. Ambiguous matches yield no single
  recommended ID.
- Returns suggested_tool_id only for a unique high-confidence genuine
  search hit, explicitly marked NOT an authorization. Search always returns
  at most six summaries, never all 172 schemas.
- Allows a uniquely resolved, previously searched unprefixed tool name
  in local DETAILS (e.g. status -> s1/status), but CALL still requires the
  exact local namespaced ID and prior details validation. Wrong server prefixes
  or name collisions fail closed.
- Compacts long schema descriptions and other metadata WITHOUT discarding
  required/enum/const/nested validation constraints or a user parameter
  coincidentally named title/description/default. Full original schema
  remains on the host and is revalidated before dispatch.
- Rejects genuinely oversized validation schemas rather than silently
  truncating assertions; exposes CATALOG_EXPIRED, SCHEMA_TOO_LARGE and
  NOT_FOUND diagnostics. A model that omits tools/call records MODEL_TOOL_MISSING.
- Makes the process diagnostic explicitly distinguish native GGUF prefill
  from active LiteRT MCP sessions, and includes the most recent events first.

All these are source/test-backed improvements, NOT a substitute for live
RC11 AUTO-MCP testing on the paired Android device. Required tests:
(1) use the same 3-server AUTO selection with ~172 tools; (2) ask for
TriForce system status twice; (3) search and view details for a Discord tool;
(4) deny one action and confirm no dispatch; (5) approve a read-only action
and confirm DISPATCH -> SERVER_RESULT; (6) inspect diagnostic error categories
when the model proposes a wrong ID or a complex schema. Do not treat tools/list
or DETAILS success as a remote tool call.

## Review-identified intent boundary fixes

- "Kannst du mir den TriForce-Systemstatus erklären?" is an explanation-only
  question; it must not trigger even a proposed remote action. The same
  explanation check is shared by host planning and the expected-evidence UI.
- If the request explicitly mentions two different tools such as
  "Tool status oder Tool device_info", the host must not silently select
  the first option. The model may ask a clarification or present the
  authorized candidate list; the host does not dispatch a call.
- Both scenarios have reproducible JVM regression tests and were fixed
  after receiving the corresponding CodeRabbit review feedback.


## AUTO-MCP discovery and real 172-tool failure reproduction

The Android RC10 diagnostic export confirms 172 tools across three live
MCP servers and successful mcp_search_tools, followed by a LiteRT prose
response without mcp_get_tool_details or tools/call. The host must continue
an explicit safe read-only task after native planning fails.

Additional RC11 corrections:
- German compound terms such as Systemzustand/Verbindungsstatus and
  spelled-out provider names find actual catalog results rather than
  unrelated matching descriptions.
- Strictly local s1/status namespace identifiers are shown, and a
  uniquely matched tool name may be used for DETAILS only. CALL always
  requires the canonical ID from the approved search/details steps.
- Small-model detail previews remove descriptive schema metadata but
  preserve every JSON Schema constraint (required, enum, minLength,
  additionalProperties); full original schema is checked before dispatch.
- An unambiguous ranked result can expose suggested_tool_id as a read-only
  hint. This does not authorize or execute any call.
- Natural user phrasing "Pruefe den Truforce DEV MCP Status per MCP Tool Call"
  no longer interprets generic "tool call" as a tool named "call". When two
  real servers advertise "status", an explicit distinctive provider token
  (e.g. DEV) selects only that user's requested profile. Without a
  distinctive provider hint, the host abstains; no arbitrary cross-server
  dispatch or auto-approval.
- Automated test builds a synthetic three-server, 172-tool snapshot with
  duplicate status functions to reproduce the failure precisely.
- App process diagnostics distinguish GGUF Jinja prefill status from real
  LiteRT MCP binding and explain details errors using sanitized error codes.
All such AUTO-MCP plans still demand a fresh on-device approval and real
server receipt; a search result alone never counts as an action.
