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

    @Test fun tensorOnlyGuardPreservesOldSessionUntilExplicitUnloadAndRecheck() {
        val main = File("src/main/java/me/ailinux/pocket/MainActivity.kt").readText()
        val load = main.substringAfter("private fun loadLiteRtModel(")
            .substringBefore("private fun sendLiteRtMessage(")
        val early = load.indexOf("if (tensorRiskWarning && tensorOnly)")
        // The first nested acknowledgement is the explicit *recheck* dialog.
        // Use the subsequent generic large-model confirmation as the bound.
        val confirmation = load.indexOf("if (!acknowledgedLargeModel &&", early)
        val busy = load.indexOf("busy = true")
        val shutdown = load.indexOf("previousLiteRt.shutdown()")
        assertTrue("Tensor-only safeguard still precedes other model transitions",
            early in 0 until confirmation)
        assertTrue("All pre-unload decisions occur before busy state", early < busy)
        assertTrue("Old model is untouched until consent", early < shutdown)
        val guard = load.substring(early, confirmation)
        assertTrue("Only a previously loaded engine can offer recheck",
            guard.contains("ModelLoadSafety.canOfferTensorRecheckAfterUnload("))
        assertTrue("No forced silent unload", guard.contains(
            "Bisheriges Modell behalten"))
        assertTrue("Explicit consent to unload", guard.contains(
            "Entladen und neu prüfen"))
        assertTrue("Hard refusal when no engine can be released",
            guard.contains("ModelMemoryGuardException("))
        assertTrue(guard.contains("requiredBytes = tensorWarningFloor"))
        assertTrue(guard.contains("showError("))
        assertTrue(guard.contains("return"))
        assertFalse(guard.contains("activeModel ="))
        val post = load.indexOf("ModelLoadSafety.allowedLiteRtAttempts(", shutdown)
        assertTrue("Post-unload RAM check remains mandatory", post > shutdown)
        assertTrue("No native load on failed postcheck",
            post < load.indexOf("LiteRtRuntime.load(applicationContext", post))
        assertTrue(load.contains("if (safeCandidates.isEmpty())"))
        assertFalse(load.contains("explicitlyAcceptedTensorRisk = acknowledgedLargeModel"))
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
