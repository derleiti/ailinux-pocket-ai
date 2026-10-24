package me.ailinux.pocket.mcp

import java.util.Locale

/**
 * Decide whether a user explicitly requested a direct app diagnostic or wants
 * the selected local model to plan an authorized MCP task.
 *
 * Do not route a model request to a protocol-only health check merely because
 * it mentions TriForce, status or tools. No network action is performed here.
 */
object McpRequestRouting {
    private val directHealth = Regex(
        "(?:health[\\s_-]*check|gesundheitscheck|verbindungs[\\s_-]*(?:check|test|pruefung|prüfung)|" +
            "mcp[\\s_-]*ping)", RegexOption.IGNORE_CASE
    )
    private val negatedHealthOnly = Regex(
        "\\b(?:nicht\\s+nur|kein(?:e|en|er|es)?|ohne)\\s+" +
            "(?:ein(?:e|en|em|er|es)?\\s+)?" +
            "(?:health[\\s_-]*check|gesundheitscheck|verbindungs[\\s_-]*(?:check|test|pruefung|prüfung))",
        RegexOption.IGNORE_CASE
    )
    private val explanation = Regex(
        "^\\s*(?:brumo[,!]?[\\s]+)?(?:erklär\\w*|erklaer\\w*|" +
            "was\\s+bedeutet|was\\s+ist\\s+(?:ein|eine)\\b|" +
            "wie\\s+funktioniert|what\\s+(?:is|does)|explain\\b|describe\\b)",
        RegexOption.IGNORE_CASE
    )
    private val inventoryOnly = Regex(
        "^(?:(?:mcp|discord)\\s+)?(?:tools?|werkzeuge?|funktionen?)" +
            "(?:\\s+(?:mcp|discord))?$", RegexOption.IGNORE_CASE
    )
    private val inventoryQuestion = Regex(
        "\\b(?:welche|wie viele|zeig|zeige|zeigen|liste|list|show|available|verfügbar|vorhanden)\\b",
        RegexOption.IGNORE_CASE
    )
    private val externalTask = Regex(
        "\\b(?:nutze|benutze|verwende|benutz|mit deinen|mit den|ausführen|führe|" +
            "prüfe den|prüfe die|pruefe den|pruefe die|status|systemstatus|" +
            "konto|serverzustand|nachrichten|logs|suche|sende|starte|lies|lese)\\b",
        RegexOption.IGNORE_CASE
    )
    private val externalSubject = Regex(
        "\\b(?:mcp|triforce|tri[ -]?force|discord|server|werkzeuge?|tools?)\\b",
        RegexOption.IGNORE_CASE
    )

    /** Normal language tasks go to the chosen native MCP agent. */
    fun useModelAgent(message: String, agentActive: Boolean): Boolean {
        if (!agentActive) return false
        val normalized = message.trim().replace(Regex("\\s+"), " ")
        if (normalized.isBlank() || normalized.startsWith("/mcp", ignoreCase = true)) return false
        // An explicit MCP health check is still a direct app protocol probe.
        if (directHealth.containsMatchIn(normalized) &&
            !negatedHealthOnly.containsMatchIn(normalized)) return false
        val catalogue = McpToolCatalog.requestedFilter(normalized)
        if (catalogue != null &&
            (inventoryOnly.matches(normalized) ||
                (inventoryQuestion.containsMatchIn(normalized) &&
                    !externalTask.containsMatchIn(normalized)))) return false
        return true
    }

    /** A status/action request without native ToolCall must never look checked. */
    fun expectsToolEvidence(message: String): Boolean {
        val normalized = message.lowercase(Locale.ROOT)
        // Explaining a concept is not evidence that an external action ran.
        if (explanation.containsMatchIn(normalized)) return false
        return McpHealthCheck.requestedTarget(message) != null ||
            McpLogCheck.requested(message) ||
            McpQuickActions.requestedAction(message) is McpQuickActions.Selection.Request ||
            (externalSubject.containsMatchIn(normalized) &&
                externalTask.containsMatchIn(normalized))
    }
}
