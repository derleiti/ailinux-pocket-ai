# Google Tensor G5 LiteRT NPU dispatch library

This Android ARM64 binary is distributed under the upstream Apache-2.0
license; see LICENSE in this directory. It is a third-party component,
NOT original AILinux code.

- Library: libLiteRtDispatch_GoogleTensor.so
- Architecture: AArch64 Android native shared library
- Extracted from the Google AI Edge LiteRT official sample:
  https://github.com/google-ai-edge/litert-samples/blob/main/samples/litert/google/sample_app_tpu/sample_app_tpu.apk
- Upstream Google sample source:
  https://github.com/google-ai-edge/litert-samples/tree/main/samples/litert/google/sample_app_tpu
- Upstream license:
  https://github.com/google-ai-edge/litert-samples/blob/main/LICENSE
- Extracted binary SHA-256:
  75d637c4de3cf60b464daf7ba85176f856779f3d2d6cc091c0392bb3776064ed
- Extracted binary size: 330960 bytes.
- Target in Google sample: LiteRT-LM 0.12.0 and LiteRT 2.3.0.
- Runtime relies on the device's proprietary Tensor TPU/EdgeTPU vendor driver.
  We do NOT redistribute proprietary device drivers or model weights.
- This does NOT accelerate GGUF. Compatible, compiled .litertlm and actual
  physical-device verification are required before claiming TPU success.

Source library remains unmodified. APK compacting may remove debug symbols
without affecting runtime functionality.
