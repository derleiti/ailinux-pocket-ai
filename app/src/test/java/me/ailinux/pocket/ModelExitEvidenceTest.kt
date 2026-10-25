package me.ailinux.pocket

import android.app.ApplicationExitInfo
import org.junit.Assert.*
import org.junit.Test

class ModelExitEvidenceTest {
    private val start = 1_600_000_000_000L
    private val now = start + 50_000L

    private fun match(reason: Int, status: Int = 0) =
        ModelExitEvidence.correlate(start, start + 15_000L, now, reason, status)

    @Test fun classifiesOnlyCorrelatedNativeFailure() {
        val result = match(ApplicationExitInfo.REASON_CRASH_NATIVE)
        assertEquals(ModelExitEvidence.Kind.NATIVE_CRASH, result?.kind)
        assertTrue(result!!.explanation().contains("JNI"))
        assertFalse(result.explanation().contains("Low Memory"))
    }

    @Test fun signalNineIsNeverClaimedAsProvenLowMemory() {
        val result = match(ApplicationExitInfo.REASON_SIGNALED, 9)!!
        assertEquals(ModelExitEvidence.Kind.SIGKILL, result.kind)
        assertTrue(result.explanation().contains("nicht bewiesen"))
        assertEquals(ModelExitEvidence.Kind.LOW_MEMORY,
            match(ApplicationExitInfo.REASON_LOW_MEMORY)?.kind)
        assertEquals(ModelExitEvidence.Kind.OTHER_SIGNAL,
            match(ApplicationExitInfo.REASON_SIGNALED, 6)?.kind)
    }

    @Test fun distinguishesPackageUpdateAndUserClosureFromCrash() {
        assertEquals(ModelExitEvidence.Kind.PACKAGE_UPDATED,
            match(ApplicationExitInfo.REASON_PACKAGE_UPDATED)?.kind)
        assertEquals(ModelExitEvidence.Kind.USER_REQUESTED,
            match(ApplicationExitInfo.REASON_USER_REQUESTED)?.kind)
        assertTrue(match(ApplicationExitInfo.REASON_USER_REQUESTED)!!.explanation()
            .contains("kein Crash-Nachweis"))
    }

    @Test fun neverClaimsAncientOrFutureExitToBeTheModelFailure() {
        val reason = ApplicationExitInfo.REASON_CRASH_NATIVE
        assertNull(ModelExitEvidence.correlate(0, start+1, now, reason, 0))
        assertNull(ModelExitEvidence.correlate(start, start-1, now, reason, 0))
        assertNull(ModelExitEvidence.correlate(start, now+30_000, now, reason, 0))
        assertNull(ModelExitEvidence.correlate(start, start+1, start+7L*60*60*1000,
            reason, 0))
    }

    @Test fun unknownIsNotMisrepresentedAsCrash() {
        val result = match(12345)!!
        assertEquals(ModelExitEvidence.Kind.UNKNOWN, result.kind)
        assertTrue(result.explanation().contains("keinen eindeutig"))
        assertTrue(ModelExitEvidence.newestExitSummary(12345, 1).contains("12345"))
    }
}
