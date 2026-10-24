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

/**
 * One-call, thread-safe dispatch state shared by Android UI and the IO worker.
 *
 * A coroutine can be cancelled during withContext(Dispatchers.IO) and its
 * CancellationException can replace the worker's result. Therefore a thrown
 * exception alone cannot prove whether a remote operation was attempted.
 */
class McpDispatchState {
    private val didAttempt = java.util.concurrent.atomic.AtomicBoolean(false)
    val attempted: Boolean get() = didAttempt.get()

    /** Call only AFTER the durable DISPATCH marker and BEFORE tools/call. */
    fun markAttempted() {
        didAttempt.set(true)
    }

    fun unconfirmed(error: Throwable): Boolean =
        attempted || error is McpExecutionUncertain
}
