# Android native inference bridge (vendored)

This module started from the official MIT-licensed
ggml-org/llama.cpp/examples/llama.android/lib at the git submodule pinned
under ../third_party/llama.cpp (commit 06230cb8775899cceef146c19cd447a947ecac5b).

The original JNI/Kotlin code is MIT licensed; see ../THIRD_PARTY_LICENSE_LLAMA_CPP.txt.
Changes in this fork:
- CMake loads the separately pinned upstream llama.cpp code without modifying it.
- The native llama log callback captures a bounded tail of WARN/ERROR messages
  while loading a model or preparing its context.
- The Kotlin bridge propagates those diagnostics as IOException instead of
  reporting every failed load as UnsupportedArchitectureException.

No model files, secrets, signing keys or telemetry are included.
Upstream source updates should be reviewed and merged into this module explicitly,
then verified with Android native ARM64 builds and device tests.
