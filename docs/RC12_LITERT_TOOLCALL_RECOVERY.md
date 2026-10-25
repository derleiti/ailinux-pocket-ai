# RC11 Pixel AUTO-MCP findings and prospective RC12 fix

## Live Android 17 evidence (2026-10-25)
- The installed UI displays 2.1.0-RC11. Gemma 4 E2B LiteRT-LM reaches GOOGLE_TENSOR.
- AUTO-BRIDGE connects TriForce Workspace, Discord OAuth, TriForce DEV:
  172 discovered tools; six bounded search results, correct named source
  selected (s3/status). The model then loads the real details successfully.
- Before approval or tools/call, a later native followup fails:
  LITERT/MODEL_FOLLOWUP OK -> LITERT/MODEL_ERROR FAIL SCHEMA
  (trace b891318d, device local 03:00:48). Another explicitly targeted
  Workspace status prompt ends IllegalArgumentException/SCHEMA (event 24fbfa88).
- A non-action explanation request is correctly answered locally with no
  external dispatch. Independently, /mcp quick triforce-status shows approval;
  decline causes no call, accepting yields real TriForce server JSON response.
  Transport is therefore functioning; native tool argument/name validation is
  the newly narrowed failure boundary.

## Fix scope
- Classify native LiteRT tool proposals by name, JSON validity, argument
  size, schema and multiplicity WITHOUT logging input text, arguments or tokens.
- An invalid proposal must not escape as a generic LiteRT error and must not
  dispatch any server request. Record MCP/SCHEMA_CHECK FAIL and a sanitized
  category, then recreate ONLY the native conversation on the existing engine
  to discard a dangling ToolCall. Report that the previous KV context was lost.
- If eligible, a new explicitly approved read-only host fallback may be offered
  through the existing live server catalog. The original readOnlyHint,
  OAuth and schema validation remain mandatory. Unknown annotations block.
- Never silently normalize or guess required JSON arguments and never retry
  an uncertain external dispatch.

## Verification gates
Local JVM tests, lint and JNI build; CI and CodeRabbit; on-device repeat
with the same Gemma/Tensor model and all three MCP profiles. Verify
search -> details -> user approval -> DISPATCH -> SERVER_RESULT.
Verify rejection, explanation-only, invalid model call and ambiguous server
selection still issue no external request. Existing RC11 is not altered.
