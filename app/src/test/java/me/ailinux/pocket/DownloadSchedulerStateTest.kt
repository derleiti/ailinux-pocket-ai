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
        done: Long = 0L,
        completedFilePresent: Boolean? = null
    ) = DownloadSchedulerState.describe(
        "Qwen3_1.7B.litertlm", state, wifiOnly,
        hasJob, reasons, age, done, 2_400_000_000L, "",
        completedFilePresent = completedFilePresent
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
        val historical = status(state = "complete")
        assertTrue(historical.label.contains("Dateibestand ungeprüft"))
        assertFalse(historical.label.contains("Installiert"))
        assertFalse(historical.canResume)
        val present = status(state = "complete", completedFilePresent = true)
        assertTrue(present.label.contains("Modelldatei vorhanden"))
        assertTrue(present.label.contains("kein erneuter SHA-256-Test"))
        val missing = status(state = "complete", completedFilePresent = false)
        assertTrue(missing.label.contains("fehlt"))
        assertFalse(missing.canResume)
        assertFalse(missing.active)
    }

    @Test fun staleCompletionDifferentiatesAbsentFileAndExistingWrongLength() {
        val absent = DownloadSchedulerState.describe(
            "qwen3_4b_mixed_int4.litertlm", "complete", false, false, emptySet(),
            0L, 5000L, 5000L, "", completedFilePresent = false,
            completedFileMissing = true)
        assertTrue(absent.label.contains("finale Modelldatei fehlt"))
        assertTrue(absent.label.contains("Quelle erneut auswählen"))
        assertFalse("Never auto-reschedule a journal on read", absent.canResume)

        val wrongLength = DownloadSchedulerState.describe(
            "qwen3_4b_mixed_int4.litertlm", "complete", false, false, emptySet(),
            0L, 5000L, 5000L, "", completedFilePresent = false,
            completedFileMissing = false)
        assertTrue(wrongLength.label.contains("falsche Größe"))
        assertTrue(wrongLength.label.contains("nichts überschrieben"))
        assertFalse(wrongLength.canResume)
    }

    @Test fun completedJournalInspectionIsPureAndNeverRemovesFailedFiles() {
        val dir = java.nio.file.Files.createTempDirectory("pocket-verified-file-").toFile()
        val name = "qwen3_4b_mixed_int4.litertlm"
        val file = java.io.File(dir, name)
        try {
            assertEquals(CompletedModelEvidence.Status.MISSING,
                CompletedModelEvidence.inspect(dir, name, 128))
            file.writeBytes(ByteArray(64))
            assertEquals(CompletedModelEvidence.Status.INVALID_SIZE,
                CompletedModelEvidence.inspect(dir, name, 128))
            assertEquals(64L, file.length())
            assertTrue(file.exists())
            assertEquals(CompletedModelEvidence.Status.PRESENT,
                CompletedModelEvidence.inspect(dir, name, 64))
            assertEquals(CompletedModelEvidence.Status.INVALID_SIZE,
                CompletedModelEvidence.inspect(dir, name, 4))
            assertEquals(CompletedModelEvidence.Status.PRESENT,
                CompletedModelEvidence.inspect(dir, name, 0))
            assertEquals(CompletedModelEvidence.Status.INVALID_NAME,
                CompletedModelEvidence.inspect(dir, "../" + name, 64))
            assertTrue(file.exists())
        } finally {
            file.delete()
            dir.delete()
        }
    }

    @Test fun completedJournalChecksRealFileAndExpectedBytesWithoutDeleting() {
        val dir = java.nio.file.Files.createTempDirectory("pocket-completed-").toFile()
        try {
            val name = "Qwen3_1.7B.litertlm"
            val file = java.io.File(dir, name)
            assertFalse(CompletedModelEvidence.present(dir, name, 64))
            file.writeBytes(ByteArray(64))
            assertTrue(CompletedModelEvidence.present(dir, name, 64))
            assertFalse(CompletedModelEvidence.present(dir, name, 65))
            assertTrue(CompletedModelEvidence.present(dir, name, 0))
            assertFalse(CompletedModelEvidence.present(dir, "../Qwen3_1.7B.litertlm", 64))
            assertFalse(CompletedModelEvidence.present(dir, "not-a-model.txt", 64))
            assertTrue(file.exists())
        } finally {
            dir.listFiles()?.forEach { it.delete() }
            dir.delete()
        }
    }

    @Test fun failedJobOffersExplicitRetry() {
        val state = status(state = "failed")
        assertTrue(state.canResume)
        assertTrue(state.label.contains("Fehlgeschlagen"))
    }
}
