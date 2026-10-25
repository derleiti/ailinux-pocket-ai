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

    @Test fun twoStagePreflightDocumentsSingleHardTensorLimit() {
        val docs = File("../docs/RC18-DIAGNOSTIC-RELIABILITY-20261025.md").readText()
        assertTrue(docs.contains("6 GiB"))
        assertTrue(docs.contains("non-overridable"))
        assertTrue(docs.contains("before any engine shutdown"))
        assertFalse(docs.contains("max(3 GiB"))
        assertFalse(docs.contains("Only the soft Tensor warning may be overridden"))
    }
}
