package me.ailinux.pocket

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class LiteRtBackendPreflightTest {
    @Test fun contradictoryOrUnavailableTensorGraphIsRejectedWithoutFallback() {
        val available = RuntimeHardware.candidates("Google", "Pixel 10 Pro", "Tensor G5")
        val conflicting = RuntimeHardware.forModelFile("gemma-Google_Tensor_G5-gpu.litertlm", available)
        assertTrue(conflicting.isEmpty())
        try {
            LiteRtBackendPreflight.requireCompatible(conflicting)
            fail("A contradictory GPU/NPU graph must not unload an existing engine")
        } catch (error: LiteRtBackendUnavailable) {
            assertEquals(PocketDiagnostics.ErrorCode.BACKEND_UNAVAILABLE,
                PocketDiagnostics.classify(error))
            assertFalse(error.message.orEmpty().contains("Bearer"))
        }
        val withoutNpu = RuntimeHardware.forModelFile(
            "gemma-4-E2B-it_Google_Tensor_G5.litertlm",
            listOf(RuntimeHardware.Attempt.CPU, RuntimeHardware.Attempt.GPU))
        assertTrue(withoutNpu.isEmpty())
        assertThrows(LiteRtBackendUnavailable::class.java) {
            LiteRtBackendPreflight.requireCompatible(withoutNpu)
        }
        LiteRtBackendPreflight.requireCompatible(listOf(RuntimeHardware.Attempt.GPU))
    }

    @Test fun unsafeModelIsRefusedBeforeBusyOrNativeRuntimeTeardown() {
        val source = File("src/main/java/me/ailinux/pocket/MainActivity.kt").readText()
        val load = source.substringAfter("private fun loadLiteRtModel(")
            .substringBefore("/** Both CHAT and SETUP")
        val earlyGuard = load.indexOf("LiteRtBackendPreflight.requireCompatible(initialCandidates)")
        val destructiveTransition = load.indexOf("previousModelUnloaded = true")
        assertTrue(earlyGuard > 0)
        assertTrue(destructiveTransition > earlyGuard)
        assertTrue(load.indexOf("busy = true") > earlyGuard)
        val denial = load.substringAfter("LiteRtBackendPreflight.requireCompatible(initialCandidates)")
            .substringBefore("val tensorMayBeUsed")
        assertTrue(denial.contains("showError("))
        assertTrue(denial.contains("return"))
        assertFalse(denial.contains("liteRtRuntime = null"))
        assertFalse(denial.contains("activeModel = null"))
    }
}
