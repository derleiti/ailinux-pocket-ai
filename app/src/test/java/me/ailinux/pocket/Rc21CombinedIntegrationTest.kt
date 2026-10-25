package me.ailinux.pocket

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * PR #69 / #70 / #71 modify overlapping loader and diagnostic code.
 * Ensure resolution preserves all three independent failure categories.
 */
class Rc21CombinedIntegrationTest {
    @Test fun nativeGgufCauseAndProjectorAndMemoryPreflightStayDistinct() {
        val projector = GgufLoadFailure(GgufLoadFailure.Phase.METADATA,
            GgufProjectorException())
        val memoryBeforeNative = GgufLoadFailure(GgufLoadFailure.Phase.MEMORY_PREFLIGHT,
            ModelMemoryGuardException(1L, 2L, true))
        val backend = GgufLoadFailure(GgufLoadFailure.Phase.NATIVE_MODEL_LOAD,
            IOException("outer", IOException("dlopen failed libggml-vulkan.so")))
        val memoryInsideNative = GgufLoadFailure(GgufLoadFailure.Phase.NATIVE_MODEL_LOAD,
            IOException("std::bad_alloc"))
        assertEquals(PocketDiagnostics.ErrorCode.GGUF_PROJECTOR,
            PocketDiagnostics.classify(projector))
        assertEquals(PocketDiagnostics.ErrorCode.GGUF_MEMORY_PREFLIGHT,
            PocketDiagnostics.classify(memoryBeforeNative))
        assertEquals(PocketDiagnostics.ErrorCode.GGUF_NATIVE_BACKEND,
            PocketDiagnostics.classify(backend))
        assertEquals(PocketDiagnostics.ErrorCode.GGUF_NATIVE_MEMORY,
            PocketDiagnostics.classify(memoryInsideNative))
        assertEquals("GgufLoadFailure / GGUF_NATIVE_BACKEND",
            PocketDiagnostics.publicError(backend))
        assertFalse(PocketDiagnostics.publicError(backend).contains("libggml"))
    }

    @Test fun preflightRefusesUnavailableLiteRtWithoutTearingDownOtherEngine() {
        val tensorOnly = RuntimeHardware.forModelFile(
            "gemma-google_tensor_g5.litertlm",
            listOf(RuntimeHardware.Attempt.CPU, RuntimeHardware.Attempt.GPU))
        assertTrue(tensorOnly.isEmpty())
        assertThrows(LiteRtBackendUnavailable::class.java) {
            LiteRtBackendPreflight.requireCompatible(tensorOnly)
        }

        val code = File("src/main/java/me/ailinux/pocket/MainActivity.kt").readText()
        val lite = code.substringAfter("private fun loadLiteRtModel(")
            .substringBefore("/** Both CHAT and SETUP")
        assertTrue(lite.indexOf("LiteRtBackendPreflight.requireCompatible(initialCandidates)") <
            lite.indexOf("previousModelUnloaded = true"))
        val gguf = code.substringAfter(
            "private fun loadModel(file: File, acknowledgedLargeModel: Boolean = false)")
            .substringBefore("private fun loadLiteRtModel(")
        assertTrue(gguf.indexOf("ModelCompatibility.isProjectorFilename(file.name)") <
            gguf.indexOf("previousModelUnloaded = true"))
        assertTrue(gguf.contains("startupPhase = GgufLoadFailure.Phase.MEMORY_PREFLIGHT"))
        assertTrue(gguf.contains("startupPhase = GgufLoadFailure.Phase.NATIVE_MODEL_LOAD"))
    }
}
