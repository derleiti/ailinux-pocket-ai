package me.ailinux.pocket

/**
 * A privacy-safe, strictly allowlisted projection of native LiteRT stage
 * callbacks. This distinguishes Engine.initialize() from conversation
 * creation without recording model paths, prompts, JNI errors or arguments.
 *
 * The stage is only proof of where the code arrived; ENGINE_READY confirms
 * conversation creation, not generated tokens or NPU performance.
 */
internal object LiteRtBackendStages {
    data class Evidence(
        val step: PocketDiagnostics.Step,
        val state: PocketDiagnostics.State,
        val backend: String
    )

    data class TimedEvidence(val evidence: Evidence, val durationMs: Long)

    /**
     * One tracker per load attempt, called on the single native loader thread.
     * On ENGINE_INIT a new backend attempt begins. On CONVERSATION_INIT /
     * ENGINE_READY / ENGINE_ERROR the elapsed value measures the *preceding*
     * native phase. A failure in the JNI initialization can therefore be
     * distinguished from conversation creation without logging its message.
     *
     * Clock comes from SystemClock.elapsedRealtime at the Android call site,
     * not wall time (important on DST transition days). Unknown native stage
     * labels are never included in the persisted event.
     */
    class Tracker {
        private var previousPhaseAtMs = -1L

        fun capture(stage: String, elapsedRealtimeMs: Long): TimedEvidence? {
            val event = parse(stage) ?: return null
            if (elapsedRealtimeMs < 0L) {
                previousPhaseAtMs = -1L
                return TimedEvidence(event, -1L)
            }
            val elapsed = if (event.step == PocketDiagnostics.Step.ENGINE_INIT ||
                previousPhaseAtMs < 0L || elapsedRealtimeMs < previousPhaseAtMs) {
                -1L
            } else elapsedRealtimeMs - previousPhaseAtMs
            previousPhaseAtMs = elapsedRealtimeMs
            return TimedEvidence(event, elapsed)
        }
    }

    private val supported = Regex("^(backend|conversation|ready|failed)_(google_tensor|gpu|cpu)$")

    fun parse(stage: String): Evidence? {
        val match = supported.matchEntire(stage) ?: return null
        val kind = match.groupValues[1]
        val backend = when (match.groupValues[2]) {
            "google_tensor" -> "TPU"
            "gpu" -> "GPU"
            "cpu" -> "CPU"
            else -> return null
        }
        val (step, state) = when (kind) {
            "backend" -> PocketDiagnostics.Step.ENGINE_INIT to PocketDiagnostics.State.START
            "conversation" -> PocketDiagnostics.Step.CONVERSATION_INIT to PocketDiagnostics.State.START
            "ready" -> PocketDiagnostics.Step.ENGINE_READY to PocketDiagnostics.State.OK
            "failed" -> PocketDiagnostics.Step.ENGINE_ERROR to PocketDiagnostics.State.FAIL
            else -> return null
        }
        return Evidence(step, state, backend)
    }
}
