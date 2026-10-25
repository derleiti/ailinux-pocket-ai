# Google Tensor G5 LiteRT NPU dispatcher (pinned upstream build)

This Android ARM64 binary is **third-party Google LiteRT code**, not original
AILinux code. Upstream Apache-2.0 terms are retained in `LICENSE`.
It is not a proprietary Google EdgeTPU firmware/driver; that must already
be present in a compatible Pixel system image.

## Source and build provenance

- Upstream LiteRT-LM: `google-ai-edge/LiteRT-LM`, tag `v0.18.0`, commit
  `b2f686e2ed4718fb84ec398a61dd59ca0f0aff27`.
- Exact LiteRT source pinned by this tag's `WORKSPACE`: commit
  `26895c9fbcc25c43faa8c1a98cd1fd28951602c3`.
- Build target:
  `@litert//litert/vendors/google_tensor/dispatch:dispatch_api_so`.
- Bazel **7.6.1** (official SHA-256
  `ac6249d1192aea9feaf49dfee2ab50c38cee2454b00cf29bbec985a11795c025`),
  Android NDK r29 beta1 `29.0.13113456`, Android ARM64, host GCC 15.
- One **BUILD-only** patch in `patches/disable-unused-google-tensor-profiling-hooks.patch`
  removes unused dependencies on Google's unavailable internal power-profiler
  library. Upstream's `dispatch_api.cc` already comments out the vendor hook
  and does not define `LITERT_GOOGLE_TENSOR_HAS_HOOKS`; core dispatch sources
  and exported ABI remain unmodified.
- Resulting ELF shared object SHA-256:
  `86df1958b1b2abd3b73ac2c52b7cc4ea06744526d220a9c620df856c2b3a0690`
  (527,472 bytes, AArch64, Android API 31, exports `LiteRtDispatchGetApi`).
- The app links LiteRT-LM `0.18.0` and LiteRT `2.3.0` from Google Maven.

### Reproduce

Clone **LiteRT-LM at v0.18.0** and **LiteRT at the exact source commit** above,
verify both SHAs before modifying anything, apply the bundled patch **only to
LiteRT**, then from the LiteRT-LM root build:

```sh
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_HOME=/path/to/android-sdk
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/29.0.13113456"
export TMPDIR=/path/to/workspace/tmp
bazel --output_user_root=/path/to/workspace/bazel-output build \
    --override_repository=litert=/path/to/checked-out-litert \
    --config=android_arm64 \
    --repo_env=CC=/usr/bin/gcc --repo_env=CXX=/usr/bin/g++ \
    --repo_env=TMPDIR="$TMPDIR" \
    --action_env=CC=/usr/bin/gcc --action_env=CXX=/usr/bin/g++ \
    --action_env=TMPDIR="$TMPDIR" \
    @litert//litert/vendors/google_tensor/dispatch:dispatch_api_so
```

Compare the output's SHA-256 with the checked-in JNI library, its ELF
architecture and exported `LiteRtDispatchGetApi`. `gradle :app:assembleDebug`
packages it into the signed APK; verify the extracted APK's bytes as well.

## Previous RC17 dispatcher and rollback

Previously the app shipped a 330,960-byte dispatcher from Google's sample
`litert-samples/samples/litert/google/sample_app_tpu/sample_app_tpu.apk`
for a **LiteRT-LM 0.12.0** integration (old SHA-256
`75d637c4de3cf60b464daf7ba85176f856779f3d2d6cc091c0392bb3776064ed`).
That exact binary remains retrievable from Git history and is separately
backed up in the development environment. The new source pin reduces an
obvious version mismatch, but **does not by itself prove working Tensor-G5
inference or demonstrate that the original ERROR=NOT_FOUND root cause is
resolved**. Real device testing with the unmodified installed Gemma E2B G5
bundle, Google Tensor G5, Android 17 and a correct runtime is required before
publishing a stable release or claiming an NPU fix.

Sources: https://github.com/google-ai-edge/LiteRT-LM/tree/v0.18.0 and
https://github.com/google-ai-edge/LiteRT/commit/26895c9fbcc25c43faa8c1a98cd1fd28951602c3
