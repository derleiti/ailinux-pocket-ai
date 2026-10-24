# Pocket AI 2.1.0-RC6: LiteRT model-load stability

- VersionCode 31; compatible Android ARM64 debug signing.
- Skip unnecessary Tensor NPU probes for models whose filenames explicitly specify GPU; continue GPU then CPU if supported by model.
- Tensor-NPU-specific filenames prioritize only Tensor backend rather than implying CPU/GPU compatibility. Generic names retain existing hardware-probe order.
- Create the LiteRT native MCP-enabled conversation once, immediately after model initialization, rather than creating and closing an extra conversation during model load.
- Persist individual LiteRT initialization stages in Android process exit summaries to distinguish backend initialization and conversation creation after an unexpected process exit. Stage recording is best effort and cannot break model loading.
- No device E2E validation or proof of memory root cause. Prior process log user task removals and updates are not crash evidence. No destructive changes to stored models.
