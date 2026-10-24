# Pocket AI 2.1.0-RC8 - Continue native MCP discovery to approved tools/call

- Native LiteRT and GGUF retain the same schema-validated MCP bridge.
- After mcp_search_tools or mcp_get_tool_details, a concrete external task may continue via the host's audited on-demand tool suggestion instead of ending with unverified prose.
- Pure explanation and overview requests do not silently trigger a server action. Prefer native model ToolCalls when present.
- Audited intents for TriForce status, Discord connection and crawl URLs improve the small-model fallback without enumerating all tools in a system prompt.
- Host only prepares candidates. The user must approve a specific tool and parameters, and the client checks server permissions and schema again before making at most one tools/call. Uncertain outcomes are not retried.
- Existing MCP catalog lifetime is per request. No model claims about tool execution are treated as receipts.
- Regression tests cover search -> details -> host plan, no remote dispatch before approval, read-only/parameter safety and both native inference paths.
- CPU/GPU/Tensor G5 model compatibility depends on actual hardware/model format. A physical-device MCP tool execution test is still required.
