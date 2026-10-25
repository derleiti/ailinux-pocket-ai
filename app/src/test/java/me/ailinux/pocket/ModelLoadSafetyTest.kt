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

    @Test fun initialAndroidLowMemoryRefusesSwitchBeforeUnloadingOldEngine() {
        val source = java.io.File("src/main/java/me/ailinux/pocket/MainActivity.kt").readText()
        val lite = source.substringAfter("private fun loadLiteRtModel(")
            .substringBefore("private fun showMcpAgentMenu(")
        val initialCheck = lite.indexOf("if (memory.lowMemory) {")
        val shutdown = lite.indexOf("previousLiteRt.shutdown()")
        val busy = lite.indexOf("busy = true")
        assertTrue("Early Android low-memory guard must be present", initialCheck >= 0)
        assertTrue("Keep the current engine and do not enter busy state",
            initialCheck < busy)
        assertTrue("Keep previous native runtime loaded", initialCheck < shutdown)
        val guard = lite.substring(initialCheck, lite.indexOf("val initialCandidates", initialCheck))
        assertTrue(guard.contains("androidLowMemory = true"))
        assertTrue(guard.contains("return"))
        assertFalse(guard.contains("activeModel = null"))
        // Preserve the later, independent post-unload OS-pressure check.
        assertTrue(lite.indexOf("currentMemory.lowMemory") > shutdown)
        assertTrue(ModelLoadSafety.allowedLiteRtAttempts(
            gib, 10L * gib, androidLowMemory = true,
            lowMemoryThresholdBytes = gib,
            candidates = listOf(RuntimeHardware.Attempt.GPU)
        ).isEmpty())
    }

    @Test fun tensorPreflightUsesRealAndroidLowMemoryKillAsConservativeFloor() {
        val smallModel = 900L * 1024L * 1024L
        val tensorRequired = ModelLoadSafety.minimumAvailableForLiteRtModel(
            smallModel, mayUseGoogleTensor = true)
        assertEquals(6L * gib, tensorRequired)
        assertTrue(ModelLoadSafety.insufficientForLiteRtModel(
            smallModel, 5L * gib, true, false, gib))
        assertFalse(ModelLoadSafety.insufficientForLiteRtModel(
            smallModel, 8L * gib, true, false, gib))
        // Android's OS lowMemory flag must override user opt-in.
        assertTrue(ModelLoadSafety.insufficientForLiteRtModel(
            smallModel, 15L * gib, true, true, gib))
        val osPressure = ModelMemoryGuardException(15L * gib, 6L * gib,
            androidLowMemory = true)
        val explanation = ModelLoadSafety.explainRefusal(osPressure)
        assertTrue(explanation.contains("lowMemory=true"))
        assertTrue(explanation.contains("Speicherdruck"))
    }

    @Test fun tensorPreflightScalesWithModelSizeAndAndroidLmkThreshold() {
        val gemmaBytes = 3_113_545_589L
        val minimum = ModelLoadSafety.minimumAvailableForLiteRtModel(
            gemmaBytes, true, androidLowMemoryThresholdBytes = gib)
        assertTrue(minimum >= gemmaBytes + 2L * gib)
        assertEquals(2L * gemmaBytes + 2L * gib, minimum)
        assertFalse(ModelLoadSafety.insufficientForLiteRtModel(
            gemmaBytes, minimum, true, false, gib))
        assertTrue(ModelLoadSafety.insufficientForLiteRtModel(
            gemmaBytes, minimum - 1, true, false, gib))
        val osLimit = ModelLoadSafety.minimumAvailableForLiteRtModel(
            gib, true, androidLowMemoryThresholdBytes = 9L * gib)
        assertEquals(11L * gib, osLimit)
        val cpuFloor = ModelLoadSafety.minimumAvailableForLiteRtModel(gib, false)
        assertEquals(ModelLoadSafety.minimumAvailableAfterUnload(gib), cpuFloor)
        // Garbage metadata must not overflow into a small accepted budget.
        assertEquals(Long.MAX_VALUE,
            ModelLoadSafety.minimumAvailableForLiteRtModel(Long.MAX_VALUE, true))
        assertEquals(Long.MAX_VALUE,
            ModelLoadSafety.minimumAvailableForLiteRtModel(gib, true, Long.MAX_VALUE))
    }

    @Test fun lowMemoryNpuDoesNotSuppressSupportedGpuFallback() {
        val both = listOf(RuntimeHardware.Attempt.GOOGLE_TENSOR,
            RuntimeHardware.Attempt.GPU, RuntimeHardware.Attempt.CPU)
        val fileBytes = 900L * 1024L * 1024L
        val allowed = ModelLoadSafety.allowedLiteRtAttempts(
            fileBytes, 3L * gib, false, gib, both)
        assertEquals(listOf(RuntimeHardware.Attempt.GPU, RuntimeHardware.Attempt.CPU),
            allowed)
        val tensorOnly = ModelLoadSafety.allowedLiteRtAttempts(
            fileBytes, 3L * gib, false, gib,
            listOf(RuntimeHardware.Attempt.GOOGLE_TENSOR))
        assertTrue(tensorOnly.isEmpty())
        // Android's own low-memory status blocks even GPU/CPU new loads.
        assertTrue(ModelLoadSafety.allowedLiteRtAttempts(
            fileBytes, 10L * gib, true, gib, both).isEmpty())
        assertEquals(both, ModelLoadSafety.allowedLiteRtAttempts(
            fileBytes, 8L * gib, false, gib, both))
        // A prior "Trotzdem laden" click is now just acknowledgement of
        // potentially large weights, NOT consent to bypass the Tensor
        // low-memory safety floor after two real Android LMK exits.
        assertEquals(listOf(RuntimeHardware.Attempt.GPU, RuntimeHardware.Attempt.CPU),
            ModelLoadSafety.allowedLiteRtAttempts(fileBytes, 3L * gib,
                false, gib, both))
        assertTrue(ModelLoadSafety.allowedLiteRtAttempts(fileBytes, 3L * gib,
            false, gib, listOf(RuntimeHardware.Attempt.GOOGLE_TENSOR)).isEmpty())
    }

    @Test fun runtimeReceivesMemoryFilteredCandidatesBeforeNativeInitialization() {
        val loader = java.io.File("src/main/java/me/ailinux/pocket/LiteRtRuntime.kt")
            .readText()
        val activity = java.io.File("src/main/java/me/ailinux/pocket/MainActivity.kt")
            .readText()
        assertTrue(loader.contains("allowedAttempts: List<RuntimeHardware.Attempt>?"))
        assertTrue(loader.contains("safeSet == null || it in safeSet"))
        val method = activity.substringAfter("private fun loadLiteRtModel(")
            .substringBefore("private fun showMcpAgentMenu()")
        assertTrue(method.contains("ModelLoadSafety.allowedLiteRtAttempts("))
        assertTrue(method.contains("allowedAttempts = safeCandidates"))
        assertTrue(method.contains("PocketDiagnostics.Step.BACKEND_SKIPPED"))
        assertFalse(method.contains("PocketDiagnostics.Step.BACKEND_RISK_ACCEPTED"))
        assertFalse(method.contains("explicitlyAcceptedTensorRisk"))
        assertTrue(method.contains("tensorRiskWarning"))
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
