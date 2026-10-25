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

    @Test fun ggufNativeCauseIsTypedWithoutExportingPrivateMessages() {
        val cases = listOf(
            "llama_model_load: std::bad_alloc" to
                PocketDiagnostics.ErrorCode.GGUF_NATIVE_MEMORY,
            "ggml_backend: failed to allocate tensor buffer" to
                PocketDiagnostics.ErrorCode.GGUF_NATIVE_MEMORY,
            "unknown model architecture" to
                PocketDiagnostics.ErrorCode.GGUF_NATIVE_ARCH,
            "dlopen failed: libggml-vulkan.so missing" to
                PocketDiagnostics.ErrorCode.GGUF_NATIVE_BACKEND,
            "invalid gguf magic header" to
                PocketDiagnostics.ErrorCode.GGUF_NATIVE_FORMAT,
            "llama_model_load: error loading model" to
                PocketDiagnostics.ErrorCode.GGUF_NATIVE_LOAD
        )
        cases.forEach { (nativeReason, expectedCode) ->
            val privateDetails = "Bearer PRIVATE_TOKEN https://private.example.test/?secret=X"
            val wrapped = GgufLoadFailure(GgufLoadFailure.Phase.NATIVE_MODEL_LOAD,
                IOException("outer failure", IOException(nativeReason + "; " + privateDetails)))
            assertEquals(nativeReason, expectedCode, wrapped.diagnosticCode)
            val published = PocketDiagnostics.publicError(wrapped)
            assertEquals("GgufLoadFailure / " + expectedCode.name, published)
            assertFalse(published.contains(privateDetails))
            assertFalse(published.contains("Bearer"))
            assertFalse(published.contains("https://"))
            val event = PocketDiagnostics.Event(
                1780000000000L, "babcabcd", "f0f0f0f0",
                PocketDiagnostics.Domain.GGUF, PocketDiagnostics.Step.MODEL_LOAD,
                PocketDiagnostics.State.FAIL, durationMs = 440L, code = wrapped.diagnosticCode)
            assertFalse(event.json().toString().contains(privateDetails))
            assertEquals(expectedCode, PocketDiagnostics.parse(event.json())!!.code)
        }
    }

    @Test fun outOfMemoryCauseIsClassifiedWithoutTextMatching() {
        val wrapped = GgufLoadFailure(GgufLoadFailure.Phase.NATIVE_MODEL_LOAD,
            IOException("native failure", OutOfMemoryError()))
        assertEquals(PocketDiagnostics.ErrorCode.GGUF_NATIVE_MEMORY,
            wrapped.diagnosticCode)
        assertEquals(PocketDiagnostics.ErrorCode.GGUF_NATIVE_LOAD,
            GgufNativeFailureCause.classify(IOException("generic JNI error")))
    }

    @Test fun lowMemoryGuardRunsBeforePreviousEngineUnloadAndNativeStart() {
        val main = File("src/main/java/me/ailinux/pocket/MainActivity.kt").readText()
        val loading = main.substringAfter(
            "private fun loadModel(file: File, acknowledgedLargeModel: Boolean = false)")
            .substringBefore("private fun loadLiteRtModel(")
        val guard = loading.indexOf("if (memory.lowMemory)")
        val cleanup = loading.indexOf("previous.shutdown()")
        val nativeLoad = loading.indexOf("engine.loadModel(file.absolutePath)")
        assertTrue(guard >= 0)
        assertTrue(cleanup > guard)
        assertTrue(nativeLoad > guard)
        assertTrue(loading.contains("androidLowMemory = true"))
        assertTrue(loading.contains("GGUF: Android meldet Speicherdruck"))
    }

    @Test fun stageMarkersAndPostLoadCleanupArePresentInActualActivity() {
        val main = File("src/main/java/me/ailinux/pocket/MainActivity.kt").readText()
        val gguf = main.substringAfter(
            "private fun loadModel(file: File, acknowledgedLargeModel: Boolean = false)")
            .substringBefore("private fun loadLiteRtModel(")
        assertTrue(gguf.contains("var startupPhase = GgufLoadFailure.Phase.METADATA"))
        assertTrue(gguf.contains("startupPhase = GgufLoadFailure.Phase.NATIVE_MODEL_LOAD"))
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
