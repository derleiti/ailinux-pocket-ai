# Pocket AI 1.0.0-rc.15 — Low Memory model-switch QA candidate

- In-place candidate, Android versionCode 26; continuing 1.0.0-rc.N. No stable production signing.
- Pixel evidence: Android REASON_LOW_MEMORY at 12:32:52 on 2026-10-24,
  ~2.94 GB RSS, last phase backend_ready; this is a real Android process exit.
- GGUF/LiteRT transitions invalidate old model handles BEFORE shutdown;
  avoid stale ACTIVE model state if teardown fails.
- Re-selecting an already running model does not reload multi-GB weights.
- Once old LiteRT/GGUF memory is released, query available RAM again
  and fail closed if the next model might exhaust available memory.
- Unit tests cover RAM thresholds.
- Physical Pixel model switching still needs verification; do not claim
  success until repeated E2B -> E4B -> E2B changes work on-device.
- LiteRT model-directed MCP tools remain disabled until a distinct approval,
  authenticated tool-result and no-retry path is tested.

Historical note: published v0.2.0-rc.1 remains unchanged.
Next candidate follows v1.0.0-rc.14 as v1.0.0-rc.15, Android versionCode 26.
