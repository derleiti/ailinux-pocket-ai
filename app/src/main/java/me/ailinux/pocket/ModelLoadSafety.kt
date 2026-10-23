package me.ailinux.pocket

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

    fun explain(modelSizeBytes: Long, availableBytes: Long): String {
        return "Das Modell belegt ${ModelStore.displaySize(modelSizeBytes)}. " +
            "Android meldet ${ModelStore.displaySize(availableBytes)} momentan verfügbaren RAM.\n\n" +
            "Das ist nur eine Abschätzung; die vollständige Inferenz benötigt " +
            "zusätzlichen Speicher für Kontext und Rechenpuffer. " +
            "Bei zu wenig RAM kann Android die App ohne Fehlermeldung beenden.\n\n" +
            "Empfohlen: ein Modell mit 0,5–2B Parametern und Q4-Quantisierung."
    }
}
