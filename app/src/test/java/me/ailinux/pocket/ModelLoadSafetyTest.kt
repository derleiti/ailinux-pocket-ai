package me.ailinux.pocket

import org.junit.Assert.*
import org.junit.Test

class ModelLoadSafetyTest {
    private val gib = 1024L * 1024 * 1024

    @Test fun flagsLargeQ8Models() {
        assertTrue(ModelLoadSafety.requiresConfirmation(4L * gib, 10L * gib))
    }
    @Test fun allowsSmallQuantizedModel() {
        assertFalse(ModelLoadSafety.requiresConfirmation(gib / 2, 6L * gib))
    }
    @Test fun preventsNearExhaustion() {
        assertTrue(ModelLoadSafety.requiresConfirmation(2L * gib, 2L * gib))
    }
    @Test fun handlesUnknownSize() {
        assertFalse(ModelLoadSafety.requiresConfirmation(0, 7L * gib))
    }
    @Test fun lowMemoryAfterUnloadBlocksOverlappingLargeModelTransition() {
        assertTrue(ModelLoadSafety.insufficientAfterUnload(3L * gib, 1100L * 1024 * 1024))
        assertTrue(ModelLoadSafety.insufficientAfterUnload(2L * gib, gib))
        assertFalse(ModelLoadSafety.insufficientAfterUnload(gib, 4L * gib))
    }
    @Test fun memoryGuardCarriesOnlyNumbersAndExplainsControlledRefusal() {
        val required = ModelLoadSafety.minimumAvailableAfterUnload(3L * gib)
        assertEquals(5L * gib / 2L, required)
        assertTrue(ModelLoadSafety.insufficientAfterUnload(3L * gib, required - 1))
        assertFalse(ModelLoadSafety.insufficientAfterUnload(3L * gib, required))
        assertTrue(ModelLoadSafety.insufficientAfterUnload(0L, gib))
        assertFalse(ModelLoadSafety.insufficientAfterUnload(0L, gib + 1))

        val refusal = ModelMemoryGuardException(1500L * 1024L * 1024L, required)
        val message = ModelLoadSafety.explainRefusal(refusal)
        assertTrue(message.contains("Speichermangel"))
        assertTrue(message.contains("RAM"))
        assertTrue(message.contains("Modelldateien bleiben unverändert"))
        assertFalse(message.contains("Bearer"))
        assertFalse(message.contains("https://"))
        assertFalse(message.contains("password"))
        assertFalse(message.contains("\\\\n"))
    }

    @Test fun failedModelLoadRefreshesLibrarySoNoStaleActiveModel() {
        val main = java.io.File("src/main/java/me/ailinux/pocket/MainActivity.kt").readText()
        val lite = main.substringAfter("private fun loadLiteRtModel(")
            .substringBefore("private fun showMcpAgentMenu()")
        assertTrue(lite.contains("throw ModelMemoryGuardException("))
        val cleanup = lite.substringAfter("} finally {")
        assertTrue("Re-enable buttons after failure", cleanup.indexOf("busy = false") >= 0)
        assertTrue("Refresh after clearing state", cleanup.indexOf("refreshLibrary()") >
            cleanup.indexOf("busy = false"))
    }

    @Test fun exposesReasonForWarning() {
        val explanation = ModelLoadSafety.explain(4L * gib, 8L * gib)
        assertTrue(explanation.contains("RAM"))
        assertTrue(explanation.contains("Q4"))
    }
}
