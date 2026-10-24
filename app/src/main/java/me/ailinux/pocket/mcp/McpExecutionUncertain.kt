package me.ailinux.pocket.mcp

import java.io.IOException

/**
 * Only raised once an actual tools/call invocation was attempted. A remote
 * side effect may already have committed; no retry is permitted.
 *
 * Preflight auth/schema errors MUST remain ordinary exceptions so the UI
 * distinguishes a call that was never sent from one with unknown outcome.
 */
class McpExecutionUncertain(cause: Throwable) :
    IOException("MCP tools/call transmitted or attempted; server outcome not confirmed", cause)
