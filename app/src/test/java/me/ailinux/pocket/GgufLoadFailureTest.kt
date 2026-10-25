package me.ailinux.pocket

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException

class GgufLoadFailureTest {
    @Test fun allStartupPhasesHaveDistinctPublicDiagnosticCodes() {
        val categories = GgufLoadFailure.Phase.entries.map { phase ->
            val typed = GgufLoadFailure(phase, IOException("private source details"))
            assertEquals(typed.diagnosticCode, PocketDiagnostics.classify(typed))
            assertTrue(typed.message!!.contains(phase.name))
            typed.diagnosticCode
        }
        assertEquals(categories.size, categories.toSet().size)
        assertTrue(categories.all { it.name.startsWith("GGUF_") })
    }

    @Test fun projectorAndPostUnloadMemoryRefusalHaveSafeDistinctErrors() {
        val projector = GgufLoadFailure(
            GgufLoadFailure.Phase.METADATA, GgufProjectorException())
        assertEquals(PocketDiagnostics.ErrorCode.GGUF_PROJECTOR,
            PocketDiagnostics.classify(projector))
        val lowMemory = GgufLoadFailure(
            GgufLoadFailure.Phase.MEMORY_PREFLIGHT,
            ModelMemoryGuardException(1024, 2048, true))
        assertEquals(PocketDiagnostics.ErrorCode.GGUF_MEMORY_PREFLIGHT,
            PocketDiagnostics.classify(lowMemory))
        assertFalse(PocketDiagnostics.publicError(projector).contains("mmproj-"))
    }

    @Test fun originalNativeFailureDetailsNeverLeaveInStructuredDiagnosis() {
        val secret = "Bearer EXAMPLE_TOKEN https://internal.example.test/?token=PRIVATE"
        val typed = GgufLoadFailure(
            GgufLoadFailure.Phase.CHAT_TEMPLATE, IOException(secret))
        assertEquals("GgufLoadFailure / GGUF_TEMPLATE",
            PocketDiagnostics.publicError(typed))
        val event = PocketDiagnostics.Event(
            1780000000000L, "abc01234", "fedc5678",
            PocketDiagnostics.Domain.GGUF, PocketDiagnostics.Step.MODEL_LOAD,
            PocketDiagnostics.State.FAIL, durationMs = 1891L,
            code = PocketDiagnostics.classify(typed))
        assertEquals(1891L, PocketDiagnostics.parse(event.json())!!.durationMs)
        assertFalse(event.json().toString().contains(secret))
        assertFalse(event.line().contains("Bearer"))
        assertFalse(event.line().contains("https://"))
        assertTrue(event.line().contains("error=GGUF_TEMPLATE"))
    }

    @Test fun stageMarkersAndPostLoadCleanupArePresentInActualActivity() {
        val main = File("src/main/java/me/ailinux/pocket/MainActivity.kt").readText()
        val gguf = main.substringAfter(
            "private fun loadModel(file: File, acknowledgedLargeModel: Boolean = false)")
            .substringBefore("private fun loadLiteRtModel(")
        assertTrue(gguf.contains("var startupPhase = GgufLoadFailure.Phase.METADATA"))
        assertTrue(gguf.contains("startupPhase = GgufLoadFailure.Phase.NATIVE_MODEL_LOAD"))
        assertTrue(gguf.contains("startupPhase = GgufLoadFailure.Phase.MEMORY_PREFLIGHT"))
        assertTrue(gguf.contains("ModelLoadSafety.insufficientAfterUnload("))
        assertTrue(gguf.contains("if (ModelCompatibility.isProjectorFilename(file.name))"))
        assertTrue(gguf.contains("newGgufLoaded = true"))
        assertTrue(gguf.contains("startupPhase = GgufLoadFailure.Phase.CHAT_TEMPLATE"))
        assertTrue(gguf.contains("startupPhase = GgufLoadFailure.Phase.SAMPLING_SETUP"))
        assertTrue(gguf.contains("if (newGgufLoaded && ::engine.isInitialized)"))
        assertTrue(gguf.contains("withContext(NonCancellable + Dispatchers.IO)"))
        assertTrue(gguf.contains("engine.cleanUp()"))
        assertTrue(gguf.contains("GgufLoadFailure(startupPhase, error)"))
        assertTrue(gguf.contains("durationMs = SystemClock.elapsedRealtime() - modelLoadStarted"))
    }
}
