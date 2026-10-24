# AILinux Pocket AI RC10 — Native Model Toolbridge and safe Gemma 4 GGUF diagnostics

## Why RC10 exists

Pocket AI RC9 could inspect real MCP servers, bind functions to its Spark-X2.6
Jinja template and execute explicitly approved tools. However, after a real
server response it fed external data into the model using a synthetic USER
prompt, which is not an authentic assistant/tool/assistant transcript.
This release replaces that shortcut.

An additional real Pixel report showed that gemna-4-tiny.gguf failed BEFORE
inference with missing Gemma4 GGUF metadata: gemma4.context_length.

## MCP conformance

References:
- https://modelcontextprotocol.io/specification/2026-07-28/server/tools
- https://blog.modelcontextprotocol.io/posts/2026-07-28/
- https://github.com/ggml-org/llama.cpp/blob/master/docs/function-calling.md
- https://github.com/ggml-org/llama.cpp/blob/master/common/chat.h

Native GGUF tool bridge:
- Actual completed, approved MCP tools/call responses are now inserted via
  InferenceEngine.sendToolResult -> JNI processToolResult -> a real Jinja
  role=tool message. The next assistant turn is decoded with the same native
  llama.cpp KV cache. No false USER turn containing remote result data.
- A genuine native Spark assistant <tool_call> must be present and match the
  exact selected tool. JSON fallback models still get the REAL server receipt
  but no inappropriate native tool-role injection.
- The Jinja output and LOW thinking KV prefix are covered by host tests.
- One bounded native result (max 2.1 KiB UTF-8) can resume the local model.
  Remote text/structured content is sanitized, and images/audio/token
  credentials are not passed to the text-only GGUF.
- Modern MCP resultType=complete / input_required and tool-level isError
  are treated separately. Unknown resultType fails closed.
- input_required cannot trigger an automatic MRTR input continuation; the
  user sees an explicit warning. No repeated or hidden tool executions.
- A second proposed tool is blocked and the local transcript reset; denied,
  cancelled, network-failed or orphaned tool turns reset safely before the
  next prompt (or deactivate the agent if restoration fails).
- Exact live tools/list, tool description, JSON Schema and arguments are
  checked again AFTER user approval and before dispatch (TOCTOU mitigation).
- JSON Schema validation is bounded and rejects unsupported assertions
  (including refs, patterns and combinators) rather than ignoring them.
  Limits, required fields, enum values, nested arrays/objects and parameter
  types are enforced locally. SEP-2243 x-mcp-header annotations are validated.
- A real server receipt is always displayed; an ungrounded small-model claim
  cannot override a server-side MCP isError response.

Privacy and permissions:
- Every model-planned tool call still requires explicit user approval.
- Tool results are untrusted data, not privileged prompts. Remote content
  cannot introduce Spark role/control tags or execute another tool.
- This is the bridge from the ON-DEVICE model to existing remote MCP tools,
  not a publicly reachable inference endpoint on the Pixel.
- No new background service or phone-server tunnel is installed.

## Gemma 4: precise failure report

Pixel model filename: gemna-4-tiny.gguf; general.architecture = gemma4.
Native loader error: required gemma4.context_length metadata key missing.
- Existing GGUF metadata reader now rejects this exact defect BEFORE the
  expensive llama.cpp tensor loader and gives a specific actionable message.
- The original file is never patched. Do not invent 131072 or 262144 from
  the filename. The correct metadata and weights depend on the actual model
  and version, so a valid provider GGUF or reconversion is required.
- If metadata preflight itself fails but the native loader sees the missing
  key, the native failure message also includes the specific explanation.
- A GGUF header with an actual context value passes THIS check, but that
  alone cannot guarantee model compatibility or enough RAM.
- The user's downloaded file is on Pixel; no direct access to its bytes was
  available here. Only a synthetic header and the supplied native error
  have been tested, not the user's entire model file.

## Android process diagnostic clarity

ApplicationExitInfo.REASON_USER_REQUESTED (code 10) is labeled
"Vom Nutzer beendet / Aufgabe entfernt (kein Crash)".
A preceding system_prompt or generating phase is a last recorded state,
not proof of a JNI crash. Package update terminations remain separate.

## Testing and limitations

Host/CI Android JVM tests, Android Debug/Release lint, native AArch64 builds
and Spark original template regressions are required before publishing.
The actual Pixel, bot permissions, OAuth grant and a live device-native
assistant → tools/call → tool role → assistant loop still need hands-on
verification: simulated JVM/network and Jinja host tests are not proof of
on-device performance or successful user-granted tool execution.

## First test after installing RC10 over RC9

1. Keep Spark-X2.6 loaded, enable CHAT > MCP > KI + MCP with one read-only
   tool (e.g. Discord connection status or TriForce status), Thinking LOW.
2. Ask an explicit status question. Approve the exact tool dialog.
3. Confirm that Pocket AI first shows a real server receipt and only then
   displays a second, clearly labeled local model summary.
4. Reject a second tool proposal: it must not dispatch again.
5. Ask ordinary chat again: no orphaned tool role or broken KV state.
6. Try loading gemna-4-tiny.gguf: expect the precise missing required
   context metadata message, NOT an automatic patch or RAM claim.
