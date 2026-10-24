# Pocket AI 2.1.0-RC7 — model-independent MCP discovery fallback

- Shared host-assisted MCP discovery for both LiteRT-LM and GGUF when native tool calls are absent.
- Based on account-authorized MCP tools/list snapshots, not model-generated names or full 172-tool prompts.
- Candidate must be unique or explicitly named; JSON schemas and parameters are validated. HTTPS query strings are preserved.
- The 10-minute catalog lifetime applies to each planning turn, not the entire UI connection. Remote calls revalidate live authentication, tool existence and schema.
- Exact audited read-only fallback contracts only; no name-based assumption of safe effects. Native tool calling remains available for other tools.
- Per-invocation approval, OAuth freshness, live schema revalidation, exactly one tools/call and no uncertain retry.
- App-generated plans and real receipts are explicitly distinguished from model tool calls.
- Native tool calling remains preferred, including normal selected tool execution.
- Device-specific Tensor G5 and generic GGUF E2E are still pending.
