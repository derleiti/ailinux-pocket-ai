# Pocket AI 2.1.0-RC1

Android versionCode 26, preserving upgrade ordering from the published
0.2.0-rc.1 (25) and 1.0.0-rc.14 (24). The previous releases remain intact.

Major feature candidate: Brumo, GGUF/llama.cpp, LiteRT-LM GPU/TPU/CPU,
Hugging Face catalogue, MCP discovery, cached reset, and low-memory
model-switch guard. The existing GGUF MCP model toolbridge remains available;
the LiteRT-LM model-directed tool calling is not yet enabled.

RC1 is a debug prerelease, **not** a claim that Pixel Tensor TPU or model
switching has been tested on physical hardware. Google Tensor dispatcher
is included, but on-device inference and OAuth/MCP tool receipts still need
verification. The public GitHub Actions APK has an ephemeral debug signer;
the release APK must be verified against the prior maintained test signer.
