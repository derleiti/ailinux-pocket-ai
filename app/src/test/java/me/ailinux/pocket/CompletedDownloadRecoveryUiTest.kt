package me.ailinux.pocket

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CompletedDownloadRecoveryUiTest {
    @Test fun journalCompleteMissingFinalIsNotAnInstalledFile() {
        val dir = Files.createTempDirectory("pocket-stale-completed").toFile()
        try {
            val name = "qwen3_4b_mixed_int4.litertlm"
            assertFalse(CompletedModelEvidence.present(dir, name, 5_000L))
            val journal = DownloadSchedulerState.describe(name, "complete",
                false, false, emptySet(), 0L, 5_000L, 5_000L, "",
                completedFilePresent = false)
            assertTrue(journal.label.contains("fehlt"))
            assertFalse(journal.canResume)
            val bad = File(dir, name)
            bad.writeBytes(ByteArray(4_999))
            assertFalse(CompletedModelEvidence.present(dir, name, 5_000L))
            assertTrue(bad.exists())
            assertEquals(4_999L, bad.length())
        } finally {
            dir.listFiles()?.forEach { it.delete() }
            dir.delete()
        }
    }

    @Test fun invalidCompletedJournalOffersReSelectionWithoutAutoDeleteOrReschedule() {
        val activity = File("src/main/java/me/ailinux/pocket/MainActivity.kt").readText()
        val library = activity.substringAfter("private fun refreshLibrary()")
            .substringBefore("private fun loadModel(")
        assertTrue(library.contains("val completedMismatch"))
        assertTrue(library.contains("!completedMismatch"))
        assertTrue(library.contains("it.state == \"complete\""))
        assertTrue(library.contains("Quelle erneut auswählen"))
        assertTrue(library.contains("browseRepository()"))
        assertFalse(library.contains("BackgroundModelDownloads.start("))
        assertFalse(library.contains("BackgroundModelDownloads.resume("))
        val partial = library.substringAfter("store.partials().forEach")
        assertTrue(partial.contains("journal?.state != \"complete\""))
        val update = activity.substringAfter("private fun updateBackgroundTransferUi()")
            .substringBefore("private fun browseRepository()")
        assertTrue(update.contains("CompletedModelEvidence.present(store.dir, name, entry.done)"))
        // The selected URL remains private and cannot be shown as model evidence.
        assertFalse(library.contains("rawToken"))
    }
}
