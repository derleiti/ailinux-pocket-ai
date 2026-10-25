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
        assertTrue(load.contains("LiteRtBackendStages.Tracker()"))
        assertTrue(load.contains("phaseTimer.capture(phase, SystemClock.elapsedRealtime())"))
        assertTrue(load.contains("backend = stage.backend"))
        assertTrue(load.contains("modelLoadTrace"))
        // A failed Android setProcessStateSummary must never suppress the
        // independent engine-stage event. The allowlisted trace goes first.
        val stageCallback = load.substringAfter("allowedAttempts = safeCandidates) { phase ->")
            .substringBefore("\n                    }\n                }")
        assertTrue(stageCallback.contains("phaseTimer.capture(phase, SystemClock.elapsedRealtime())?.let"))
        assertTrue(stageCallback.contains("runCatching {"))
        assertTrue(stageCallback.indexOf("PocketDiagnostics.log(") >= 0)
        assertTrue(stageCallback.indexOf("PocketDiagnostics.log(") <
            stageCallback.indexOf("setProcessStateSummary("))
        assertFalse(load.contains("PocketDiagnostics.log(applicationContext, phase"))
        val native = File("src/main/java/me/ailinux/pocket/LiteRtRuntime.kt").readText()
        assertTrue(native.contains("onStage(\"failed_\" + attempt.name.lowercase"))
    }

    @Test fun timedNativeStagesExposeFailedInitializationAndConversationCost() {
        val t = LiteRtBackendStages.Tracker()
        val first = t.capture("backend_google_tensor", 30_000L)!!
        assertEquals(-1L, first.durationMs)
        assertEquals("TPU", first.evidence.backend)
        val error = t.capture("failed_google_tensor", 30_459L)!!
        assertEquals(459L, error.durationMs)
        assertEquals(PocketDiagnostics.Step.ENGINE_ERROR, error.evidence.step)

        // Switching candidates must start a new independent native phase.
        assertEquals(-1L, t.capture("backend_gpu", 30_480L)!!.durationMs)
        val convo = t.capture("conversation_gpu", 31_100L)!!
        assertEquals(620L, convo.durationMs)
        assertEquals(PocketDiagnostics.Step.CONVERSATION_INIT, convo.evidence.step)
        val ready = t.capture("ready_gpu", 31_172L)!!
        assertEquals(72L, ready.durationMs)
        assertEquals(PocketDiagnostics.Step.ENGINE_READY, ready.evidence.step)
        assertTrue(PocketDiagnostics.Event(
            1780000000000L, "ab12cd34", "beadcafe",
            PocketDiagnostics.Domain.LITERT, ready.evidence.step,
            ready.evidence.state, backend = ready.evidence.backend,
            durationMs = ready.durationMs
        ).line().contains("ms=72"))
    }

    @Test fun timingIgnoresUnknownStagesAndMonotonicClockRegression() {
        val timer = LiteRtBackendStages.Tracker()
        timer.capture("backend_google_tensor", 500L)
        assertNull(timer.capture("failed_google_tensor\\nBearer secret", 550L))
        assertEquals(300L, timer.capture("failed_google_tensor", 800L)!!.durationMs)
        assertEquals(-1L, timer.capture("backend_cpu", 100L)!!.durationMs)
        // A clock reset / invalid value is not logged as negative duration
        // or as a potentially huge unsigned difference.
        assertEquals(-1L, timer.capture("ready_cpu", 95L)!!.durationMs)
        assertEquals(-1L, timer.capture("failed_cpu", -1L)!!.durationMs)
        assertEquals(-1L, timer.capture("ready_cpu", 101L)!!.durationMs)
        val source = File("src/main/java/me/ailinux/pocket/MainActivity.kt").readText()
        val load = source.substringAfter("private fun loadLiteRtModel(")
            .substringBefore("private fun showMcpAgentMenu(")
        assertTrue(load.contains("LiteRtBackendStages.Tracker()"))
        assertTrue(load.contains("phaseTimer.capture(phase, SystemClock.elapsedRealtime())"))
        assertTrue(load.contains("durationMs = timed.durationMs"))
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
