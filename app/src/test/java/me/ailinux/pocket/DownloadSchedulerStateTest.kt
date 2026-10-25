package me.ailinux.pocket

import org.junit.Assert.*
import org.junit.Test

class DownloadSchedulerStateTest {
    private fun status(
        state: String = "queued",
        hasJob: Boolean? = true,
        reasons: Set<DownloadSchedulerState.Reason> = emptySet(),
        wifiOnly: Boolean = false,
        age: Long = 30_000L,
        done: Long = 0L
    ) = DownloadSchedulerState.describe(
        "Qwen3_1.7B.litertlm", state, wifiOnly,
        hasJob, reasons, age, done, 2_400_000_000L, ""
    )

    @Test fun missingAndroidJobMustOfferExplicitResumeEvenBeforePartFileExists() {
        val state = status(hasJob = false)
        assertTrue(state.label.contains("Job verloren"))
        assertTrue(state.canResume)
        assertFalse(state.waitingForNetwork)
    }

    @Test fun wifiConstraintShowsWifiNotGenericNetworkError() {
        val state = status(wifiOnly = true,
            reasons = setOf(DownloadSchedulerState.Reason.NETWORK))
        assertTrue(state.label.contains("WLAN"))
        assertFalse(state.canResume)
        assertTrue(state.waitingForNetwork)
    }

    @Test fun genericConnectivityReasonShowsNetworkWait() {
        val state = status(reasons = setOf(DownloadSchedulerState.Reason.NETWORK))
        assertTrue(state.label.contains("Netzwerk"))
        assertTrue(state.waitingForNetwork)
        assertFalse(state.canResume)
    }

    @Test fun androidPolicyRestrictionNeverPretendsTheTransferIsRunning() {
        val state = status(reasons = setOf(DownloadSchedulerState.Reason.BACKGROUND_RESTRICTION))
        assertTrue(state.label.contains("Android"))
        assertFalse(state.label.contains("Läuft"))
        assertFalse(state.canResume)
    }

    @Test fun longUnexplainedWaitExposesUserInitiatedRequeue() {
        assertFalse(status(age = 6_000L).canResume)
        assertTrue(status(age = 120_000L).canResume)
        assertTrue(status(age = 120_000L).requiresConfirmation)
    }

    @Test fun pausedJobCanResumeEvenWhenZeroBytesTransferred() {
        val state = status(state = "paused", hasJob = false)
        assertTrue(state.canResume)
        assertTrue(state.label.contains("Pausiert"))
    }

    @Test fun runningJournalWithLostOsJobMustOfferRecovery() {
        val state = status(state = "running", hasJob = false, done = 1_234_567L)
        assertTrue(state.label.contains("Job verloren"))
        assertTrue(state.canResume)
        assertTrue(state.label.contains("MB"))
    }

    @Test fun transientJobCompletionDoesNotTriggerDuplicateRescheduling() {
        val state = status(state = "running", hasJob = false,
            age = 2_000L, done = 2_048L)
        assertFalse(state.canResume)
        assertTrue(state.label.contains("Download läuft"))
    }

    @Test fun runningJobWithSchedulerRegistrationStillReportsTransfer() {
        val state = status(state = "running", hasJob = true, done = 1_234_567L)
        assertTrue(state.label.contains("Download läuft"))
        assertFalse(state.canResume)
    }

    @Test fun executingSchedulerReasonIsNotAStalledJob() {
        val state = status(reasons = setOf(DownloadSchedulerState.Reason.EXECUTING),
            age = 120_000)
        assertTrue(state.label.contains("gestartet"))
        assertFalse(state.canResume)
    }

    @Test fun storagePendingReasonIsVisible() {
        val state = status(reasons = setOf(DownloadSchedulerState.Reason.STORAGE))
        assertTrue(state.label.contains("Speicher"))
        assertFalse(state.canResume)
    }

    @Test fun verifyingAndCompleteAreNotInstalledPrematurely() {
        assertTrue(status(state = "verifying").label.contains("Prüfung"))
        assertFalse(status(state = "verifying").canResume)
        assertTrue(status(state = "complete").label.contains("Installiert"))
    }

    @Test fun failedJobOffersExplicitRetry() {
        val state = status(state = "failed")
        assertTrue(state.canResume)
        assertTrue(state.label.contains("Fehlgeschlagen"))
    }
}
