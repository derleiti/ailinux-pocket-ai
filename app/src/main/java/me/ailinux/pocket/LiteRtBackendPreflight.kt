package me.ailinux.pocket

/**
 * Unsupported model/device graph affinity is a preflight refusal, NOT a
 * native Tensor failure. Reject it before destroying a previously loaded
 * GGUF/LiteRT engine, even if the user opted into a large-model warning.
 */
internal class LiteRtBackendUnavailable : IllegalArgumentException(
    "LiteRT-Modell hat auf diesem Gerät keinen unterstützten Backendpfad. " +
        "Das bisher geladene Modell wurde nicht verändert."
)

internal object LiteRtBackendPreflight {
    fun requireCompatible(candidates: List<RuntimeHardware.Attempt>) {
        if (candidates.isEmpty()) throw LiteRtBackendUnavailable()
    }
}
