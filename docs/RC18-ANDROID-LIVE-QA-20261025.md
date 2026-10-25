# RC18 Android live QA with paired Workspace Helper

Date: 2026-10-25; Berlin CET (UTC+01:00). Exact package on Android screen: me.ailinux.pocket / 2.1.0-RC18. Workspace Helper reported Android 17 and arm64-v8a; generic hardware label was "Pixel 10". Exact Pro SKU, installed versionCode and signer were **not independently read from PackageManager on device**. Do not infer installed commit from versionName alone.

## Direct device evidence (before this source fix is installed)

- Pocket AI launched successfully via the AILinux TriForce Workspace MCP Android app controls. Setup showed three saved MCP profiles. Initial state: MCP agent OFF, no local model loaded.
- Model screen listed 5 locally installed models occupying approximately 9.50 GB. Historical completed journals included Qwen3_1.7B.litertlm and qwen3_4b_mixed_int4.litertlm with missing / size-mismatched final files at first inspection. This data may subsequently change and must be re-read on device; no model or partial was deleted during QA.
- Loaded Qwen3-1.7B_dynamic_wi4b32_afp32.litertlm; app reported "LITERT BEREIT · GPU". The local no-MCP prompt asking for exactly "PONG" generated real text. On-device diagnostic showed MODEL_DONE OK, duration **21,947 ms** (approximately 19:12:09 CET).
- Activated AUTO-BRIDGE with three previously selected servers (Workspace, Discord, TriForce Dev). App showed "MCP AUTO LOW" and "LITERT GPU · MCP BEREIT".
- Sent the explicit no-tool prompt "Erkläre mir MCP, ohne externe Werkzeuge zu benutzen." Instead of app-owned local explanation, the installed RC18 routed it to "LITERT MCP · PLANUNG" and finished with "Keine Modellantwort erzeugt." No real external tool execution was reported for that prompt. This is the **reproduced regression** corrected by the new McpArchitectureGuide routing tests.
- Sent natural-language request for current TriForce Workspace system status. Pocket displayed a genuine "MCP-SERVER HAT GEANTWORTET" status tool response, explicitly marked "App-gestützt" (host-assisted). A later diagnostic showed MODEL_DONE OK at about 19:15:35 CET, duration **29,016 ms**. This confirms a received remote result, NOT successful native LiteRT FunctionCall and MODEL_FOLLOWUP. User-consent action was not independently captured on video and is not inferred from these logs.
- During later remote inspection another model load appeared to have failed ("LiteRT-LM konnte nicht gestartet werden"), but the model identity, exact trace and cause were **not reliably established**; no root-cause assertion or success claim is made on this observation.
- No installed models, chats, OAuth credentials or partial downloads were deleted. No APK installation or in-place update performed during this test session.

## Corrective code change

McpArchitectureGuide's conceptual route did not recognize explanation verbs such as "Erkläre / Explain", so an explicit no-tool conceptual question got sent to tiny LiteRT's MCP planner. Recognize those verbs **only with an explicit MCP subject**, keep non-MCP API questions on the normal model path and refuse local-help short-circuit when a separately requested positive action appears ("nutze den Status" / "and use the workspace status tool"). Preserve accurate app-owned provenance; local help is not a native ToolCall.

Regression added for the exact Pixel input and the mixed explanation-plus-live-action cases. Does NOT alter OAuth, remote tool approvals, network dispatch, no-duplicate rules, model weights, NPU dispatcher, signing configuration or downloads.

## Verification on combined RC18 source

- 439 JVM unit tests: all passed, zero skipped, failures or errors.
- Android lintDebug and assembleDebug completed successfully.
- APK verifier: arm64-v8a, manifest package me.ailinux.pocket, versionName 2.1.0-RC18, versionCode 43; native ABI and signing validation passed.
- Local APK SHA-256: bdc4402c25c744b358306418ec261fc095b6839545cc7b71433a60cf25ac833a
- Local signer certificate SHA-256: 125766c26dd400e39804c7b97c36682d8a2c16caa134c58745a02aca0bd51187, identical to the previously verified public RC17 signer. This is NOT independent proof of the installed device signer.
- Diff whitespace check clean.

## Remaining hardware and release gates

- The fix exists **only in source and locally built APK** until a separately approved in-place device install and retest. Verify the same no-tool prompt in a newly installed, same-signature build, preserving app data.
- Demonstrate explicit approval DENY followed by zero DISPATCH with same trace; and a separately approved DISPATCH -> SERVER_RESULT -> native MODEL_FOLLOWUP or honestly labelled host fallback.
- Verify Gemma 4 E2B Tensor G5 actual token generation and follow the official model SHA/source pin, without using only initialization success as evidence.
- Confirm source of the additional Android model-load error from monotonic durations/trace codes before touching GPU/NPU fallback paths. Complete UI background/download and Pixel memory acceptance separately.
- Open issues #19/#20/#21/#22/#25/#29 stay OPEN. A successful local/CI build does not prove on-device acceptance, and it is not permission to create a public RC18 tag/release.
