package me.ailinux.pocket

import java.io.IOException

/**
 * Captures only the phase of a GGUF startup failure.
 * The original exception is retained as cause for debugging in memory,
 * but must never be copied into exported diagnostics or user-visible strings.
 */
internal class GgufLoadFailure(
    val phase: Phase,
    cause: Throwable
) : IOException("GGUF start failed during " + phase.name, cause) {
    enum class Phase {
        METADATA,
        PREVIOUS_ENGINE_RELEASE,
        NATIVE_MODEL_LOAD,
        THINKING_SETUP,
        CHAT_TEMPLATE,
        SAMPLING_SETUP
    }

    val diagnosticCode: PocketDiagnostics.ErrorCode get() = when (phase) {
        Phase.METADATA -> PocketDiagnostics.ErrorCode.GGUF_METADATA
        Phase.PREVIOUS_ENGINE_RELEASE -> PocketDiagnostics.ErrorCode.GGUF_PREVIOUS_ENGINE
        Phase.NATIVE_MODEL_LOAD -> PocketDiagnostics.ErrorCode.GGUF_NATIVE_LOAD
        Phase.THINKING_SETUP -> PocketDiagnostics.ErrorCode.GGUF_THINKING
        Phase.CHAT_TEMPLATE -> PocketDiagnostics.ErrorCode.GGUF_TEMPLATE
        Phase.SAMPLING_SETUP -> PocketDiagnostics.ErrorCode.GGUF_SAMPLING
    }
}
