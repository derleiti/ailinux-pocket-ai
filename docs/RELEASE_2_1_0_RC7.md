# Pocket AI 2.1.0-RC7 — model-independent MCP discovery fallback

- Shared host-assisted MCP discovery for both LiteRT-LM and GGUF when native tool calls are absent.
- Based on account-authorized MCP tools/list snapshots, not model-generated names or full 172-tool prompts.
- Candidate must be unique or explicitly named; JSON schemas and parameters are validated.
- Bounded read-only-name tool selection; no arbitrary side-effecting fallback.
- Per-invocation approval, OAuth freshness, live schema revalidation, exactly one tools/call and no uncertain retry.
- App-generated plans and real receipts are explicitly distinguished from model tool calls.
- Native tool calling remains preferred, including normal selected tool execution.
- Device-specific Tensor G5 and generic GGUF E2E are still pending.
