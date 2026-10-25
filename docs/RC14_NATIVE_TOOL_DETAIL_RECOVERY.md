# RC14: local LiteRT MCP detail-context recovery

## Device evidence (Pixel 10 Pro, Android 17, Pocket AI RC13)

Observed 2026-10-25 05:19-05:20: `MCP/SEARCH OK`, `MCP/DETAILS OK`, followed by `MCP/SCHEMA_CHECK FAIL INVALID_NATIVE_ARGUMENTS_SCHEMA`; the LiteRT conversation reset succeeded but the host fallback rejected `AMBIGUOUS_TOOL`, without a server dispatch. A separate genuine approved read-only status call at 05:17 returned `MCP/SERVER_RESULT OK` in 383 ms. Other observed fallback failures include `UNVERIFIED_READONLY`; this must remain fail-closed unless the authenticated server declares `readOnlyHint=true` for the exact tool.

## Fix

- Retain the exact canonical local tool ID returned by SEARCH + DETAILS within the current ephemeral progressive MCP `Run`; no credential or chat text is written into persistent cache.
- After a malformed native FunctionCall, reset the LiteRT conversation, then try a **proposal only** using a previously detailed, uniquely source-matched, explicitly read-only tool.
- If multiple servers advertise `status`, require a source explicitly named by the original user; never select the first server or trust a model-invented ID.
- Reuse the full original schema and existing `prepareCall` validation; require independent approval and revalidate current auth, live tool definition and annotations immediately before `tools/call`.
- A real `SERVER_RESULT` remains the only receipt. No re-dispatch after an earlier call, no native continuation claimed for host-assisted paths.
- Add private `DETAIL_REUSE` diagnostic without printing prompt, arguments, endpoint, token or tool body.

## Automated tests

New `McpDetailedHostFallbackTest` checks three servers / 173 tools, detailed-tool survival, ambiguous identical tool names, mismatched server, unrelated detailed tool, false read-only hint, missing non-URL parameter, explanation-only intent and no repeat dispatch. Unit tests and Android Lint must pass on the exact merge commit.

## Real-device test (still required)

Install signed RC14 over RC13 without deleting data. With Gemma 4 E2B on Tensor G5 and AUTO LOW connected to Workspace/DEV/Discord, request `Brumo, prüfe den Workspace MCP Status`, then separately `Prüfe den DEV MCP Status`. If native tool arguments are malformed, expect `SCHEMA_CHECK FAIL`, `MODEL_RESET OK`, then a real **user-approved** `DETAIL_REUSE`, `PROPOSAL`, `DISPATCH`, `SERVER_RESULT` for that server. Without a source, duplicate `status` must remain ambiguous. For tools without verified read-only annotation, `UNVERIFIED_READONLY` is expected and no dispatch is allowed.

This patch does not claim all native JSON parser failures, background inference, complete localization or model compatibility are solved.
