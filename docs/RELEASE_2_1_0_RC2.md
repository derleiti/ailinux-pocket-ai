# Pocket AI 2.1.0-RC2 — native LiteRT-LM MCP bridge

- Android versionCode 27 (upgrade from RC1 code 26).
- LiteRT-LM native structured ToolCall discovery and role=tool feedback.
- One compact 3-meta-tool selection with short discovery prompt and untrusted server instructions.
- Host verifies current tool, schema and explicit user approval; at most one real remote call.
- Raw server receipt displayed separately, no remote auto-retry after uncertain status.
- Google Tensor TPU, GPU and CPU use the same authorized tool-control path.
- Physical Pixel/Discord OAuth end-to-end tools/call tests remain open.
