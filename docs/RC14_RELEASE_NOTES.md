# AILinux Pocket AI RC14 - MCP Native Binding Progress / Smoother UI

RC14 addresses the long local CPU prefill observed after activating
Auto-Bridge. Pixel screenshots and process records showed
phase=system_prompt with 3-4 GB RSS; those processes were ended by
the user and were not confirmed crashes or Android ANRs.

## Improvements

- Actual llama.cpp decoded and total SYSTEM tokens are exposed by a
  lock-free JNI progress query. Chat displays token count, percent,
  elapsed time and a horizontal progress bar updated every 350 ms.
- MCP STARTET is displayed until native binding really succeeds.
  MCP AUTO LOW is shown only after an actual ModelReady result.
- Chat typing and tab navigation remain usable while SYSTEM prefill
  uses the phone CPU. Send is gated until ModelReady.
- Status/progress is moved below the model header, and the POCKET AI
  title no longer wraps because of long progress text.
- Native prefill batch workers are capped at three instead of four
  to leave extra CPU capacity for the Android UI. Generation settings
  remain independent.
- The native KV cache reuses an identical already decoded SYSTEM
  prefix after switching away/back to an exactly matching prompt,
  tools JSON and Thinking mode. Any changes cause a safe full reset
  and prefill; failed KV tail trimming falls back to the full reset.
- More concise app-owned tool-discovery instructions and short
  descriptions for the three native meta-tools reduce first-use
  SYSTEM token prefill work, retaining the actual strict schema and
  explicit user-permission requirements.
- Process diagnostics contain last native MCP binding tokens and
  elapsed seconds, scoped to the current app process.
- Search results are formatted as a readable list of COMPLETE
  server-qualified tool IDs, server names and descriptions, without
  clipping the JSON. The model still receives the full, unmodified
  structured tool response from its local meta-tool.
- Real Pixel RC13 feedback already demonstrated 100 authorized
  tools across TriForce and Discord (six displayed samples).
  This establishes successful account-scoped discovery, NOT proof
  that the model executed a remote tool. RC14 keeps that distinction.

## Important limitations

First-use prefill still executes on the CPU of the Pixel, and can be
slow under thermal/memory load. Source tests and native ARM64 builds
cannot prove performance or responsiveness on the physical Pixel.
Do not confuse Android user task removal or an ordinary app update
with an actual ANR or crash.

## Testing

- Full Android/JVM regression suite and zero-error Lint
- ARM64 native C++ build and JNI symbol/export verification
- Native Spark Jinja THINKING and tools/role continuation tests
- Same Android signature as RC13 to support install-in-place
- Physical Pixel QA still required

## Next Pixel step

Install RC14 over RC13 without uninstalling. Bind AUTO-BRIDGE and
watch the native token bar; try navigating/typing during binding.
After ModelReady, repeat the exact same bridge selection to exercise
system KV reuse. Inspect the local prefill duration in process
diagnostics and test a real read-only MCP call.
