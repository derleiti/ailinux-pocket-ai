# Pocket AI: do not automatically probe Google Tensor for unknown LiteRT-LM models

Verified upstream premise (2026-10-25):
- https://developers.google.com/edge/litert/next/npu : Google Tensor SDK does not currently support on-device JIT compilation. It executes target-specific ahead-of-time (AOT) graphs.
- https://developers.google.com/edge/litert-lm/android : EngineConfig backend selection can use GPU, CPU or NPU, and a successful Engine.initialize() is not itself proof of successful generation.
- Tensor G5 dispatch and the official Google-Tensor-compiled Gemma 4 E2B file remain distinct, separately verified artifacts.

Live RC17/RC18 evidence:
- Spark-X2.5-1.7B_int8 and Qwen3.5-0.8B-VL were being sent to backend_google_tensor on a Pixel, with Android low-memory process exits in some runs.
- Other recent traces show fast TENSOR_BACKEND setup failures and long MCP planning timeouts. This branch does NOT claim the speculative Tensor attempt was the sole or proven cause.
- RuntimeHardware.forModelFile previously tried GOOGLE_TENSOR, GPU, CPU for every unknown model on Google Pixel 10/11 hardware, even if no AOT Tensor provenance existed.

Minimal source change:
- Known GPU community graph or explicit gpu marker: GPU then CPU.
- Explicit google_tensor, tensor_g5, npu, tpu filename marker: GOOGLE_TENSOR only (still an unverified candidate; native initialization and actual generation required).
- Unknown filename/provenance: GPU then CPU. Never treat a Pixel SoC as sufficient model compatibility evidence.
- Contradictory npu and gpu markers: reject before native allocation, requiring model selection/metadata review.
- On a non-Tensor device, explicitly Tensor-targeted files remain unsupported; no fake CPU/GPU compatibility.
- No changes to model bytes, downloads, chat history, MCP consent, runtime versions, tags, or user data.

This is a short-term candidate-selection guard. A complete per-model compatibility system should derive backend affinity from trusted LiteRT-LM ModelInfo/container metadata or signed upstream manifest/digest, not from filename only; renamed files are explicitly unverified.

Device acceptance remains necessary: original Gemma 4 E2B Google Tensor G5 file cold loads AND generates text on NPU; Qwen3 1.7B known GPU generates text; generic Spark/Qwen model either loads on GPU/CPU or fails with clear diagnostics, without an unsafe NPU attempt. Pixel battery/available memory must be sufficient before costly model tests. The unchanged six issues #19/#20/#21/#22/#25/#29 stay open.
