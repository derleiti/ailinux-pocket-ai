package me.ailinux.pocket

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Static architecture regressions; hardware OS constraints need device QA. */
class BackgroundModelDownloadWiringTest {
    private val bg = File("src/main/java/me/ailinux/pocket/ModelBackgroundDownloads.kt").readText()
    private val main = File("src/main/java/me/ailinux/pocket/MainActivity.kt").readText()
    private val store = File("src/main/java/me/ailinux/pocket/ModelStore.kt").readText()
    private val manifest = File("src/main/AndroidManifest.xml").readText()

    @Test fun uidtReadsAndroidPendingReasonsOnSupportedApis() {
        assertTrue(bg.contains("scheduler.getPendingJobReasons(entry.id)"))
        assertTrue(bg.contains("scheduler.getPendingJobReason(entry.id)"))
        assertTrue(bg.contains("scheduler.getPendingJob(entry.id)"))
        assertTrue(bg.contains("PENDING_JOB_REASON_CONSTRAINT_CONNECTIVITY"))
        assertTrue(bg.contains("PENDING_JOB_REASON_BACKGROUND_RESTRICTION"))
        assertTrue(manifest.contains("android.permission.ACCESS_NETWORK_STATE"))
    }

    @Test fun onlyExplicitUiInteractionReschedulesAndNeverDeletesPartial() {
        assertTrue(main.contains("resumeBackgroundDownload(name, true)"))
        assertTrue(main.contains("resumeBackgroundDownload(name, false)"))
        assertTrue(bg.contains("require(requeueWaiting)"))
        assertTrue(bg.contains("scheduler.cancel(entry.id)"))
        assertTrue(bg.contains("RemoteModel(entry.name, entry.url"))
        val resumeBody = bg.substringAfter("fun resume(context:")
            .substringBefore("fun start(context:")
        assertFalse(resumeBody.contains(".delete()"))
        assertFalse(resumeBody.contains("onStartJob("))
        assertTrue(main.contains("Downloadjob neu einplanen?"))
    }

    @Test fun workerAndUidtTrackVerificationAndFailures() {
        assertTrue(bg.contains("onVerifying = {"))
        assertTrue(bg.contains("it.copy(state = \"verifying\")"))
        assertTrue(bg.contains("lastStopReason = params.stopReason"))
        assertTrue(bg.contains("setBackoffCriteria(30_000L"))
        assertTrue(store.contains("if (cancelled.get()) throw TransferCancelled()"))
        assertTrue(store.contains("progress(TransferProgress(offset, total))"))
        assertFalse("Never silently delete existing multi-GB model on hash failure",
            store.contains("if (error !is TransferCancelled) temp.delete()"))
        val downloadLifecycle = store.substringAfter("    fun download(")
            .substringBefore("    private fun verifyAndPublish(")
        assertFalse("Never delete even a short persisted .part on a failed transfer",
            downloadLifecycle.contains("temp.delete()"))
        assertTrue("Download retains an exclusive lease until network cleanup",
            downloadLifecycle.contains("lease.close()"))
        assertTrue("Model must still be verified before atomic publication",
            store.contains("validate(temp, name)"))
        assertTrue(store.contains("AtomicModelInstall.publish(temp, target)"))
        assertTrue(main.contains("MODELL-DOWNLOAD / JOBSCHEDULER"))
    }
}
