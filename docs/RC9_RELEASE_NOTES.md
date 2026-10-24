# AILinux Pocket AI 1.0.0-rc.9
Android ARM64 test release — based on the Pocket AI RC8 application and merged PR #3 changes.

## Changes
- Editable llama.cpp sampling: temperature, top-k, top-p, min-p, repeat penalty, repeat-last-n.
- Balanced, precise and creative sampling presets; values persisted in Android preferences.
- Sampling now reaches llama.cpp JNI, and unchanged settings keep the sampler/repetition history.
- Includes the RC8 MCP log-routing behavior.

## Verification and limitations
- Tests: Gradle JVM unit tests, lint and debug ARM64 build.
- Artifact: AILinux-server debug-signed APK (not a production-certified APK).
- GitHub CI debug artifact uses runner-specific debug signing; only the release asset signed with the continuous AILinux development key is intended to update prior AILinux RCs in place.
- Android device inference smoke-test and automatic repetition-loop detection remain open.
