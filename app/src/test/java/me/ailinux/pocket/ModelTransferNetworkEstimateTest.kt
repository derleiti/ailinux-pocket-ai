package me.ailinux.pocket

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ModelTransferNetworkEstimateTest {
    @Test fun newModelCountsAllNetworkBytes() {
        val n = ModelTransferNetworkEstimate.forResume(2_056_729_520L, 0L)
        assertTrue(n.known)
        assertEquals(2_056_729_520L, n.expectedDownloadBytes)
        assertEquals(65_536L, n.minChunkBytes)
    }

    @Test fun partialDownloadEstimatesRemainingAndNotTheOriginalModelSize() {
        val n = ModelTransferNetworkEstimate.forResume(
            2_056_729_520L, 2_000_000_000L)
        assertEquals(56_729_520L, n.expectedDownloadBytes)
        assertTrue(n.minChunkBytes <= n.expectedDownloadBytes)
    }

    @Test fun completedPartOnlyNeedsSmallHttpsMetadataBudget() {
        val n = ModelTransferNetworkEstimate.forResume(
            2_056_729_520L, 2_056_729_520L)
        assertEquals(16_384L, n.expectedDownloadBytes)
        assertEquals(16_384L, n.minChunkBytes)
    }

    @Test fun almostCompleteStillProvidesSmallHeaderReserve() {
        val n = ModelTransferNetworkEstimate.forResume(2_056_729_520L, 2_056_729_515L)
        assertEquals(16_384L, n.expectedDownloadBytes)
    }

    @Test fun malformedOrUnknownSizeCannotClaimZeroRemaining() {
        assertFalse(ModelTransferNetworkEstimate.forResume(-1L, 120L).known)
        assertFalse(ModelTransferNetworkEstimate.forResume(1_000L, 1_001L).known)
        assertFalse(ModelTransferNetworkEstimate.forResume(1_000L, -1L).known)
        assertTrue(ModelTransferNetworkEstimate.forResume(1_000L, 1_001L).minChunkBytes > 0)
    }

    @Test fun schedulingUsesActualPartialNotEntireModelAgain() {
        val source = File("src/main/java/me/ailinux/pocket/ModelBackgroundDownloads.kt").readText()
        assertTrue(source.contains("ModelTransferNetworkEstimate.forResume("))
        assertTrue(source.contains("setMinimumNetworkChunkBytes(networkHint.minChunkBytes)"))
        assertTrue(source.contains("setEstimatedNetworkBytes(networkHint.expectedDownloadBytes, 0L)"))
        assertFalse(source.contains("setEstimatedNetworkBytes(model.bytes, 0L)"))
        assertTrue(source.contains("removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)"))
    }
}
