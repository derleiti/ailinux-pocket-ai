# Pocket AI 2.1.0-RC3 — MCP second-call guard

Android versionCode 28. Keeps LiteRT native ToolCall discovery, one-time user
approval, live-schema validation, tool role feedback, and explicit server receipts.

This candidate blocks a second remote-tool proposal *after* a real tools/call
receipt without discarding the first verified result or falling into an opaque
model exception. Tool role payload is bounded to 12000 characters to avoid
unbounded prompt growth; the original server receipt remains separately visible.

Automated unit and Android CI checks do not prove physical Pixel/GPU/NPU
functionality, Android OAuth, or authentic model-driven MCP E2E success.
These remain open in GitHub issue #1.
