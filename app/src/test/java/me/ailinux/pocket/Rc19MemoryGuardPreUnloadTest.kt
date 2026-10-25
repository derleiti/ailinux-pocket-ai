package me.ailinux.pocket

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class Rc19MemoryGuardPreUnloadTest {
    @Test fun lowMemoryIsRejectedBeforeAnyNativeRuntimeShutdown() {
        val main = File("src/main/java/me/ailinux/pocket/MainActivity.kt").readText()
        val load = main.substringAfter(
            "private fun loadLiteRtModel(file: File, acknowledgedLargeModel: Boolean = false)")
            .substringBefore("private fun sendLiteRtMessage(")
        val early = load.indexOf("if (memory.lowMemory)")
        val unload = load.indexOf("previousLiteRt.shutdown()")
        val clear = load.indexOf("activeModel = null")
        assertTrue("lowMemory must be checked before unloading old engine", early in 0 until unload)
        assertTrue("lowMemory must be checked before clearing activeModel", early < clear)
        assertTrue(load.contains("if (safeCandidates.isEmpty())"))
        assertFalse(load.contains("explicitlyAcceptedTensorRisk = acknowledgedLargeModel"))
    }

    @Test fun tensorOnlyMemoryRefusalPreservesActiveModelEvenAfterAcknowledgement() {
        val main = File("src/main/java/me/ailinux/pocket/MainActivity.kt").readText()
        val load = main.substringAfter("private fun loadLiteRtModel(")
            .substringBefore("private fun sendLiteRtMessage(")
        val early = load.indexOf("if (tensorRiskWarning &&")
        val confirmation = load.indexOf("if (!acknowledgedLargeModel")
        assertTrue("Tensor-only refusal must run independently of acknowledgement",
            early in 0 until confirmation)
        assertTrue("Refuse before entering busy state", early < load.indexOf("busy = true"))
        assertTrue("Refuse before native shutdown", early < load.indexOf("previousLiteRt.shutdown()"))
        assertTrue("Keep activeModel", early < load.indexOf("activeModel = null"))
        val guard = load.substring(early, confirmation)
        assertTrue("Compatible GPU/CPU paths must remain available", guard.contains(
            "initialCandidates.all { it == RuntimeHardware.Attempt.GOOGLE_TENSOR }"))
        assertTrue(guard.contains("ModelMemoryGuardException("))
        assertTrue(guard.contains("requiredBytes = tensorWarningFloor"))
        assertTrue(guard.contains("showError("))
        assertTrue(guard.contains("return"))
        assertFalse(guard.contains("activeModel ="))
    }

    @Test fun twoStagePreflightDocumentsSingleHardTensorLimit() {
        val docs = File("../docs/RC18-DIAGNOSTIC-RELIABILITY-20261025.md").readText()
        assertTrue(docs.contains("6 GiB"))
        assertTrue(docs.contains("non-overridable"))
        assertTrue(docs.contains("before any engine shutdown"))
        assertFalse(docs.contains("max(3 GiB"))
        assertFalse(docs.contains("Only the soft Tensor warning may be overridden"))
    }
}
