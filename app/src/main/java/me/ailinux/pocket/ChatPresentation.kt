package me.ailinux.pocket

/** Chat stays focused on messages; model selection lives exclusively in MODELS. */
internal object ChatPresentation {
    fun modelLabel(modelFileName: String?): String =
        modelFileName?.takeIf { it.isNotBlank() }?.removeSuffix(".gguf")?.removeSuffix(".litertlm")
            ?: "Kein Modell geladen"

    fun emptyHint(hasActiveModel: Boolean): String =
        if (hasActiveModel) "Dein Modell ist bereit. Schreib einfach los."
        else "Unter MODELLE ein GGUF- oder LiteRT-LM-Modell laden."

    fun inputHint(hasActiveModel: Boolean): String =
        if (hasActiveModel) "Frag deine lokale KI..."
        else "Erst unter MODELLE ein Modell laden"
}
