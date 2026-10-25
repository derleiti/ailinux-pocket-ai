# RC19 CI incident - 25 October 2026

This documents actual GitHub-run failures, not a verified Android device fix.

## Reproduction / root causes

- main and v2.1.0-RC19 pointed at commit 0dbd37a59fe1407dafc2f76ca483a70bd05d2a5a when inspected.
- Tag workflow https://github.com/derleiti/ailinux-pocket-ai/actions/runs/40223880018 stopped at "Check immutable release candidate tag against Gradle". The tag was v2.1.0-RC19 but that commit contained versionName = "2.1.0-RC18" and versionCode = 43; tag validation was CORRECT to refuse publishing. A valid versionCode 44 / versionName RC19 was in PR #57 branch commit 39410d0, but PR #57 was merged into its mistaken base branch qa/pocket-rc18-integrated-g5-mcp-20261025, NOT main. Main was tagged despite the mismatch. Never move or overwrite an existing public tag to paper over this mistake.
- Main workflow https://github.com/derleiti/ailinux-pocket-ai/actions/runs/40223702399 failed at :app:compileDebugKotlin. GitHub PR #54 branch merge commit 8f9a17a had literally appended both conflict alternatives and unquoted branch-name lines main and fix/pocket-rc18-mcp-architecture-guide-20261025 into McpArchitectureGuide.kt AND its Kotlin unit test. These source files were clean in preceding commit 949b608. NDK compiler warnings did not cause this failure.
- The published RC19 GitHub pre-release had zero assets. Do not claim an RC19 APK exists or was installed.

## Minimal repair and rollback

- Save Git reference backup/rc19-invalid-main-0dbd37a-20261025; implement on isolated fix/pocket-rc19-merge-conflict-ci-guard-20261025 worktree.
- Restore only the two corrupted Kotlin files to the proven clean state at 949b608, preserving the prior integrated MCP request semantics and regression cases.
- Add scripts/check_merge_artifacts.py with independent standard-library tests; run at checkout in both Android CI and Android Fast Checks BEFORE provisioning NDK/Gradle. Both workflows also reject release/pocket-* PRs targeting any branch other than main. This detects traditional conflict markers and bare branch labels in tracked source.
- Run Python tests, full Kotlin/JVM unit suite, Android Lint, signed debug ARM64 build, APK ABI/version/signature validation. Check diff/secrets and current GitHub review status before merge.
- Only merge a fresh PR after GitHub CI/reviews pass. Corrected main can be built on a branch (currently versionName RC18), but cannot retroactively repair the immutable bad RC19 tag run.
- A future candidate needs a NEW, unique version/tag (normally RC20 and versionCode >=45), an isolated version-bump PR, passing tag/Gradle preflight, matching installed-app signing certificate and real Pixel verification. Keep RC19 tag historical and mark its release metadata invalid instead of silently changing history.

## Why guard source before compiling

Unit tests/CI pass on source, but a later badly resolved merge can insert raw branch names in compile units. Checking only changed files or running one branch CI is not enough: main must be checked as the final merged tree. This early guard scans tracked source under app/src, inference/src, and scripts; it also rejects standard Git conflict delimiters. Unit tests include malformed lines known from this incident and legitimate valid Kotlin strings/comments to avoid false positives.
