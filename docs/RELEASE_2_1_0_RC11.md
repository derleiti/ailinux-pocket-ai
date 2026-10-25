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
