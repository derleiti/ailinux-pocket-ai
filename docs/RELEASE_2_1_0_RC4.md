# Pocket AI 2.1.0-RC4 — real MCP discovery instead of model explanation

- Android versionCode 29, update-capable debug prerelease candidate.
- Short requests such as `MCP Tools` and `mcp werkzeuge` now use authenticated live tools/list even when AI auto-discovery is enabled.
- LiteRT native tool-binding instructions now explicitly prefer `mcp_search_tools(query)` for concrete external status/action requests over explaining tools or asking user for internal IDs.
- Native tool execution still requires schema, OAuth and individual user approval. No synthetic server receipt or automatic retry.
- Unit tests cover short-list requests and updated discovery contract.
- True Pixel inference and model-led tool-call E2E remain unverified; test on device before claiming reliability.
