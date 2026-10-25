package me.ailinux.pocket

import kotlinx.coroutines.CancellationException

/** A cancelled native model load must not enter the next backend attempt.
 * Cleanup is synchronous, bounded by the native API, and may not suppress
 * the original cancellation. Successful initialization is tracked by caller.
 */
internal object LiteRtNativeLoadGuard {
    fun rethrowIfCancelled(problem: Exception, cleanup: () -> Unit) {
        if (problem is CancellationException) {
            try { cleanup() } catch (_: Exception) { /* preserve original cancellation */ }
            throw problem
        }
    }
}
