package me.ailinux.pocket

import java.io.IOException

/** A *controlled refusal* before native initialization, not a LiteRT crash.
 * The exception contains only non-sensitive memory quantities.
 */
internal class ModelMemoryGuardException(
    val availableBytes: Long,
    val requiredBytes: Long,
    val androidLowMemory: Boolean = false
) : IOException("Android RAM safety guard blocked the model load")

/**
 * Android users should explicitly acknowledge risky multi-GB model loads.
 * This is not an estimate of the model's exact GGML allocation needs.
 */
object ModelLoadSafety {
    private const val GIB = 1024L * 1024 * 1024
    private const val RESERVE = 1L * GIB

    fun requiresConfirmation(modelSizeBytes: Long, availableBytes: Long): Boolean {
        if (modelSizeBytes <= 0) return false
        if (modelSizeBytes >= 3L * GIB) return true
        if (availableBytes <= RESERVE) return true
        // We need overhead for KV cache, tensors, Java/Android and other processes.
        return modelSizeBytes > ((availableBytes - RESERVE) * 3L) / 4L
    }

    /** After unloading the old engine, refuse a new multi-GB load if the
     * device still has too little immediately available memory. File size
     * is only a lower-bound proxy, not a native allocation prediction.
     */
    fun minimumAvailableAfterUnload(modelSizeBytes: Long): Long =
        if (modelSizeBytes <= 0L) RESERVE
        else maxOf(2L * GIB, modelSizeBytes / 2L + RESERVE)

    fun insufficientAfterUnload(modelSizeBytes: Long, availableBytes: Long): Boolean {
        if (modelSizeBytes <= 0L) return availableBytes <= RESERVE
        return availableBytes < minimumAvailableAfterUnload(modelSizeBytes)
    }

    /**
     * Tensor G5 models can allocate substantially more memory than their
     * on-disk .litertlm size during native initialization and prefill.
     * Android reported a real LOW_MEMORY exit with Spark-X at ~5.7 GB RSS.
     * This conservative preflight is a safety floor, NOT a promise that an
     * accepted load cannot subsequently hit memory pressure.
     */
    fun minimumAvailableForLiteRtModel(
        modelSizeBytes: Long,
        mayUseGoogleTensor: Boolean,
        androidLowMemoryThresholdBytes: Long = 0L
    ): Long {
        val basic = minimumAvailableAfterUnload(modelSizeBytes)
        if (!mayUseGoogleTensor) return basic
        val twoGiB = 2L * GIB
        fun addBounded(value: Long, extra: Long): Long =
            if (value > Long.MAX_VALUE - extra) Long.MAX_VALUE else value + extra
        val weights = modelSizeBytes.coerceAtLeast(0L)
        // Two actual Pixel Android REASON_LOW_MEMORY exits (RSS ~5.7 GB
        // and ~4.8 GB) followed consented Tensor-G5 experiments. The NPU
        // floor therefore must be HARD, not bypassable with a warning.
        // This heuristic is not a measured upper allocation bound.
        val approximateNativePeak = addBounded(
            if (weights > Long.MAX_VALUE / 2L) Long.MAX_VALUE else weights * 2L,
            twoGiB
        )
        return maxOf(
            basic,
            6L * GIB,
            approximateNativePeak,
            addBounded(androidLowMemoryThresholdBytes.coerceAtLeast(0L), twoGiB)
        )
    }

    /** Never suppress a GPU/CPU fallback merely because NPU is high-risk.
     * NPU-only AOT files still fail closed when their one compatible path
     * cannot safely start; this is not permission to recompile on-device.
     */
    internal fun allowedLiteRtAttempts(
        fileBytes: Long, availableBytes: Long,
        androidLowMemory: Boolean, lowMemoryThresholdBytes: Long,
        candidates: List<RuntimeHardware.Attempt>
    ): List<RuntimeHardware.Attempt> {
        if (androidLowMemory || insufficientAfterUnload(fileBytes, availableBytes))
            return emptyList()
        // Consent does not make an unsafe native allocation safe. Keep
        // GPU/CPU fallback for genuinely compatible graphs and reject NPU-
        // only graphs when Tensor memory is insufficient.
        val tensorAllowed = !insufficientForLiteRtModel(
            fileBytes, availableBytes, true, false, lowMemoryThresholdBytes)
        return candidates.distinct().filter {
            it != RuntimeHardware.Attempt.GOOGLE_TENSOR || tensorAllowed
        }
    }

    fun insufficientForLiteRtModel(
        modelSizeBytes: Long,
        availableBytes: Long,
        mayUseGoogleTensor: Boolean,
        androidLowMemory: Boolean,
        androidLowMemoryThresholdBytes: Long
    ): Boolean =
        androidLowMemory ||
            availableBytes < minimumAvailableForLiteRtModel(
                modelSizeBytes, mayUseGoogleTensor, androidLowMemoryThresholdBytes)

    /** User-visible safe explanation. It must not show server payloads,
     * raw exception text, file paths, model prompts or OAuth credentials.
     */
    internal fun explainRefusal(error: ModelMemoryGuardException): String =
        "Modellstart zum Schutz vor Speichermangel gestoppt. " +
            (if (error.androidLowMemory)
                "Android meldet akuten Speicherdruck (lowMemory=true), " +
                    "auch wenn eine freie Bytezahl angezeigt wird. "
            else "") +
            "Android meldet " + ModelStore.displaySize(error.availableBytes) +
            " freien RAM; die konservative Prüfung setzt mindestens " +
            ModelStore.displaySize(error.requiredBytes) + " voraus.\n\n" +
            "Die Grenze ist eine vorsichtige Schutzabschätzung, keine " +
            "garantierte Prognose für NPU-/GPU-Speicherverbrauch. " +
            "Andere Apps schließen oder ein kleineres Modell wählen. " +
            "Die heruntergeladenen Modelldateien bleiben unverändert."

    fun explain(modelSizeBytes: Long, availableBytes: Long): String {
        return "Das Modell belegt ${ModelStore.displaySize(modelSizeBytes)}. " +
            "Android meldet ${ModelStore.displaySize(availableBytes)} momentan verfügbaren RAM.\n\n" +
            "Das ist nur eine Abschätzung; die vollständige Inferenz benötigt " +
            "zusätzlichen Speicher für Kontext und Rechenpuffer. " +
            "Bei zu wenig RAM kann Android die App ohne Fehlermeldung beenden.\n\n" +
            "Empfohlen: ein Modell mit 0,5–2B Parametern und Q4-Quantisierung."
    }
}
