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
