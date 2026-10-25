# Pixel AUTO-MCP QA (25 Oct 2026) - live RC12 vs upcoming RC13/14

## Verified on the paired device

- Android 17, Google Tensor / Gemma 4 E2B LiteRT; Pocket AI SETUP reports
  installed 2.1.0-RC12. Do not substitute a GitHub release for the version
  displayed on the actual device.
- Three MCP profiles are present. AUTO LOW discovers 172 authentic server
  tools; Workspace Health-Check reported MCP 2026-07-28, Streamable HTTP,
  91 tools, approximately 172 ms. This proves discover/list, not tools/call.
- A prior Pocket AI conversation displayed an actual app-assisted status
  tools/call server receipt. A separate crawl request to TriForce DEV ended
  with HOST_FALLBACK / UNVERIFIED_READONLY and no tools/call. This was a
  controlled refusal, not a confirmed crawl result.
- A new prompt asking for Workspace status reached tool search and showed
  s1/status (Workspace) and s3/status (DEV). The Android screen subsequently
  moved to the lock screen, so this exact prompt's dispatch and result were
  not confirmed from its own turn.
- A live short request, "worspace status", returned only model prose asking
  for clarification rather than a real status. The original implementation
  did not treat Workspace shorthand/that typo as an external-tool request.
- An explicit "workspace status" user action must select only the Workspace
  source. The host may propose only an authenticated read-only status tool
  and still needs fresh user approval. No cross-server rerouting is allowed.

## Implementation and regression checks

The separate fix/pocket-rc14-workspace-intent-20261025 branch adds an exact
Workspace shorthand/observed "worspace" alias in:
- McpRequestRouting: real external status requires actual evidence
- McpOnDemandDiscovery: host discovery can choose the read-only status tool,
  normalizing only the observed server-name typo
- McpProgressiveBridge: source affinity filters both search and details/call
  to the explicitly intended Workspace server

No broad approximate spelling matcher was added; there is no automatic
execution or permission bypass. Test catalog includes duplicate status
names on Workspace and DEV, no-source ambiguity, explanation-only requests
and explicit source filtering. Local JVM regression tests/Lint are required;
a green JVM test is not a real Pixel server receipt.

## Next live acceptance on RC13 / follow-up

1. Verify installed version via the actual Android SETUP screen and check
   versionCode and existing model/chat/profile preservation. Never uninstall.
2. Confirm connected profiles and 172-tool AUTO discovery, token-budget
   sanity and the current loaded LiteRT backend.
3. Ask for Workspace status, then explicitly DEV status. Validate the
   expected source title/ID in approval and SERVER_RESULT, never from
   natural-language claim. Repeat at least twice.
4. Ask for Discord connection status using the Discord MCP source. Check
   auth state and user consent; inspect genuine server response.
5. Reject a proposed status call. Confirm there is NO DISPATCH afterward.
6. Ask for inventory and explanation-only questions. They must not call
   external tools.
7. Test the typo "worspace status": it should lead to a consent-based
   Workspace-only proposal, not silently execute or use DEV.
8. Verify crawl https://ailinux.me separately: if the server does not
   advertise a real boolean readOnlyHint=true, the host fallback should
   explain UNVERIFIED_READONLY instead of overriding permission checks.
   Inspect actual tools/list annotations to determine whether metadata is
   missing from the legacy DEV/SSE adapter.
9. Capture one per-turn diagnostic trace with SEARCH, DETAILS, PROPOSAL,
   APPROVAL_ACCEPT, AUTH_CHECK, SCHEMA_CHECK, DISPATCH, SERVER_RESULT.
   Redact tokens, raw tool results and any private chat contents.
10. Qwen download acceptance remains tracked in issue #27 and should be
    tested independently of AUTO-MCP.

Do not claim real device coverage for tests interrupted by Android keyguard,
the user changing foreground apps, or a pending GitHub release build.
