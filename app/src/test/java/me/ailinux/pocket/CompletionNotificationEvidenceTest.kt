package me.ailinux.pocket

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class CompletionNotificationEvidenceTest {
    @Test fun noFileCannotBeCalledDownloadedAndVerified() {
        val noFile = DownloadSchedulerState.completedNotification(false, true)
        assertTrue(noFile.contains("missing or size mismatch"))
        assertFalse(noFile.contains("Model present"))
        assertFalse(noFile.contains("verified"))
    }

    @Test fun fileLengthAloneDoesNotMeanHashVerification() {
        val noHash = DownloadSchedulerState.completedNotification(true, false)
        assertTrue(noHash.contains("SHA-256 unverified"))
        assertFalse(noHash.contains("checked during transfer"))
        val hashedAtTransfer = DownloadSchedulerState.completedNotification(true, true)
        assertTrue(hashedAtTransfer.contains("checked during transfer"))
        assertFalse(hashedAtTransfer.contains("checked now"))
        assertFalse(hashedAtTransfer.contains("SHA-256 unverified"))
        assertNotEquals(noHash, hashedAtTransfer)
    }

    @Test fun notificationUsesActualFilesAndJournalDigestNotJustCompleteFlag() {
        val code = File("src/main/java/me/ailinux/pocket/ModelBackgroundDownloads.kt").readText()
        val notification = code.substringAfter("internal object TransferNotification")
            .substringBefore("class ModelTransferPauseReceiver")
        assertFalse(notification.contains("\"Downloaded and verified\""))
        assertTrue(notification.contains("CompletedModelEvidence.present("))
        assertTrue(notification.contains("DownloadSchedulerState.completedNotification("))
        assertTrue(notification.contains("entry.hash?.matches("))
    }
}
