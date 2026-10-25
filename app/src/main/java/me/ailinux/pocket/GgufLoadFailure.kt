package me.ailinux.pocket

import java.io.IOException
import java.util.Locale

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
        Phase.NATIVE_MODEL_LOAD -> GgufNativeFailureCause.classify(cause)
        Phase.THINKING_SETUP -> PocketDiagnostics.ErrorCode.GGUF_THINKING
        Phase.CHAT_TEMPLATE -> PocketDiagnostics.ErrorCode.GGUF_TEMPLATE
        Phase.SAMPLING_SETUP -> PocketDiagnostics.ErrorCode.GGUF_SAMPLING
    }
}

/**
 * Native GGUF loader messages vary with llama.cpp ABI, architecture and backend.
 * The app exports ONLY a fixed category. Raw exception messages, file paths,
 * model names, arguments and stack traces stay out of diagnostics.
 *
 * Unknown patterns deliberately remain GGUF_NATIVE_LOAD. This categorization
 * is not a proof of the original root cause and must not trigger a retry.
 */
internal object GgufNativeFailureCause {
    private fun any(text: String, vararg markers: String): Boolean =
        markers.any { it in text }

    fun classify(problem: Throwable?): PocketDiagnostics.ErrorCode {
        var nested = problem
        // Avoid cycles or an unusually deep/untrusted exception chain.
        repeat(6) {
            val item = nested ?: return PocketDiagnostics.ErrorCode.GGUF_NATIVE_LOAD
            if (item is OutOfMemoryError || item is ModelMemoryGuardException)
                return PocketDiagnostics.ErrorCode.GGUF_NATIVE_MEMORY
            val msg = item.message.orEmpty().lowercase(Locale.ROOT).take(1600)
            when {
                any(msg, "std::bad_alloc", "out of memory", "not enough memory",
                    "cannot allocate memory", "could not allocate memory",
                    "failed to allocate memory", "memory allocation failed",
                    "failed to allocate tensor", "failed to allocate buffer") ->
                    return PocketDiagnostics.ErrorCode.GGUF_NATIVE_MEMORY
                any(msg, "unknown model architecture", "unsupported model architecture",
                    "unknown architecture", "unsupported architecture",
                    "model architecture is not supported") ->
                    return PocketDiagnostics.ErrorCode.GGUF_NATIVE_ARCH
                any(msg, "dlopen failed", "no ggml backends", "backend not found",
                    "failed to load backend", "ggml_backend_dl_load",
                    "libggml-", "unable to load native library") ->
                    return PocketDiagnostics.ErrorCode.GGUF_NATIVE_BACKEND
                any(msg, "invalid gguf", "bad magic", "invalid magic",
                    "corrupt gguf", "truncated gguf", "failed to read gguf",
                    "unexpected end of file while reading gguf") ->
                    return PocketDiagnostics.ErrorCode.GGUF_NATIVE_FORMAT
            }
            nested = item.cause.takeUnless { it === item }
        }
        return PocketDiagnostics.ErrorCode.GGUF_NATIVE_LOAD
    }
}
