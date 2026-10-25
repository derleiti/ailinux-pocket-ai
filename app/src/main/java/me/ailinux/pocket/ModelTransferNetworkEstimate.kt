package me.ailinux.pocket

/**
 * Network usage hint for an Android user-initiated model transfer.
 *
 * A resumed UIDT job reports bytes STILL REQUIRED, not the size of the
 * original multi-GB model. JobScheduler may use this hint for planning.
 * A fully downloaded .part still needs a few KB for HTTPS range/HEAD
 * validation, while an unknown or inconsistent length is not guessed.
 *
 * No file modification, automatic requeue, or network side effect here.
 */
internal object ModelTransferNetworkEstimate {
    private const val HEADER_BUDGET_BYTES = 16L * 1024L
    private const val RESUMABLE_CHUNK_BYTES = 64L * 1024L
    const val UNKNOWN = -1L // JobInfo.NETWORK_BYTES_UNKNOWN

    data class Hint(val expectedDownloadBytes: Long, val minChunkBytes: Long) {
        val known: Boolean get() = expectedDownloadBytes >= 0L
    }

    /** A cached total is bound to the original download URL, not a filename. */
    internal fun knownTotalForSource(
        incomingSize: Long, priorSize: Long?, sameSource: Boolean
    ): Long = when {
        incomingSize > 0L -> incomingSize
        sameSource && priorSize != null && priorSize > 0L -> priorSize
        else -> UNKNOWN
    }

    fun forResume(totalBytes: Long, existingPartBytes: Long): Hint {
        if (totalBytes <= 0L || existingPartBytes < 0L ||
            existingPartBytes > totalBytes) {
            return Hint(UNKNOWN, RESUMABLE_CHUNK_BYTES)
        }
        val remaining = totalBytes - existingPartBytes
        val estimate = remaining.coerceAtLeast(HEADER_BUDGET_BYTES)
        // HEAD/416 may be the only remaining network operation. A small,
        // explicit minimum keeps that job schedulable without pretending
        // the full model must be downloaded again.
        return Hint(estimate, minOf(estimate, RESUMABLE_CHUNK_BYTES))
    }
}
