package me.ailinux.pocket

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class LiteRtBackendStagesTest {
    @Test fun stagesDifferentiateNativeInitializationFromConversationCreation() {
        val expected = mapOf(
            "backend_google_tensor" to Triple(
                PocketDiagnostics.Step.ENGINE_INIT, PocketDiagnostics.State.START, "TPU"),
            "conversation_google_tensor" to Triple(
                PocketDiagnostics.Step.CONVERSATION_INIT, PocketDiagnostics.State.START, "TPU"),
            "ready_google_tensor" to Triple(
                PocketDiagnostics.Step.ENGINE_READY, PocketDiagnostics.State.OK, "TPU"),
            "failed_google_tensor" to Triple(
                PocketDiagnostics.Step.ENGINE_ERROR, PocketDiagnostics.State.FAIL, "TPU"),
            "backend_gpu" to Triple(
                PocketDiagnostics.Step.ENGINE_INIT, PocketDiagnostics.State.START, "GPU"),
            "conversation_gpu" to Triple(
                PocketDiagnostics.Step.CONVERSATION_INIT, PocketDiagnostics.State.START, "GPU"),
            "ready_cpu" to Triple(
                PocketDiagnostics.Step.ENGINE_READY, PocketDiagnostics.State.OK, "CPU"),
            "failed_cpu" to Triple(
                PocketDiagnostics.Step.ENGINE_ERROR, PocketDiagnostics.State.FAIL, "CPU")
        )
        for ((raw, values) in expected) {
            val evidence = requireNotNull(LiteRtBackendStages.parse(raw)) { "Not parsed: $raw" }
            assertEquals(raw, values.first, evidence.step)
            assertEquals(raw, values.second, evidence.state)
            assertEquals(raw, values.third, evidence.backend)
        }
    }

    @Test fun unknownStageAndUntrustedIdentifiersDoNotReachPersistentLogs() {
        for (value in listOf(
            "backend_unknown", "ready_google_tensor_token", "backend_user_mcp",
            "ready_google_tensor;secret", "../backend_google_tensor",
            "failed_google_tensor\nuntrusted-private-field", "ready_gguf",
            "failed_npu", "", "backend_GOOGLE_TENSOR")) {
            assertNull(value, LiteRtBackendStages.parse(value))
        }
        val source = File("src/main/java/me/ailinux/pocket/MainActivity.kt").readText()
        val load = source.substringAfter("private fun loadLiteRtModel(")
            .substringBefore("private fun showMcpAgentMenu(")
        assertTrue(load.contains("LiteRtBackendStages.parse(phase)"))
        assertTrue(load.contains("backend = stage.backend"))
        assertTrue(load.contains("modelLoadTrace"))
        assertFalse(load.contains("PocketDiagnostics.log(applicationContext, phase"))
        val native = File("src/main/java/me/ailinux/pocket/LiteRtRuntime.kt").readText()
        assertTrue(native.contains("onStage(\"failed_\" + attempt.name.lowercase"))
    }

    @Test fun readyIsNotConfusedWithGeneratedTokensOrExternalDispatch() {
        val ready = LiteRtBackendStages.parse("ready_google_tensor")!!
        assertNotEquals(PocketDiagnostics.Step.MODEL_DONE, ready.step)
        assertNotEquals(PocketDiagnostics.Step.SERVER_RESULT, ready.step)
        assertNotEquals(PocketDiagnostics.Step.MODEL_FOLLOWUP, ready.step)
        val event = PocketDiagnostics.Event(
            1780000000000, "deadbeef", "aabbccdd",
            PocketDiagnostics.Domain.LITERT, ready.step,
            ready.state, backend = ready.backend
        )
        assertTrue(event.line().contains("LITERT/ENGINE_READY"))
        assertTrue(event.line().contains("backend=TPU"))
        assertFalse(event.json().toString().contains("Bearer"))
    }
}
