package me.ailinux.pocket

/**
 * Soft evidence for an unexpectedly slow *initial* native LiteRT MCP turn.
 * It does not interrupt JNI, imply a timeout, or authorize a retry.
 */
internal object LiteRtSlowPlan {
    const val NOTICE_AFTER_MS = 75_000L

    fun shouldWarn(startElapsedMs: Long, nowElapsedMs: Long, stillWaiting: Boolean): Boolean =
        stillWaiting && startElapsedMs >= 0L && nowElapsedMs >= startElapsedMs &&
            nowElapsedMs - startElapsedMs >= NOTICE_AFTER_MS
}
