package me.ailinux.pocket

import java.io.IOException

/** A *controlled refusal* before native initialization, not a LiteRT crash.
 * The exception contains only non-sensitive memory quantities.
 */
internal class ModelMemoryGuardException(
    val availableBytes: Long,
    val requiredBytes: Long
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

    /** User-visible safe explanation. It must not show server payloads,
     * raw exception text, file paths, model prompts or OAuth credentials.
     */
    internal fun explainRefusal(error: ModelMemoryGuardException): String =
        "Modellstart zum Schutz vor Speichermangel gestoppt. Android meldet " +
            ModelStore.displaySize(error.availableBytes) +
            " freien RAM; die konservative Prüfung benötigt mindestens " +
            ModelStore.displaySize(error.requiredBytes) + ".\n\n" +
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
