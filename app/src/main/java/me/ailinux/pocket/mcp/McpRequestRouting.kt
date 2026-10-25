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
            "wie\\s+(?:funktioniert|kann\\s+ich)|how\\s+(?:do|can)\\s+i|what\\s+(?:is|does)|explain\\b|describe\\b)",
        RegexOption.IGNORE_CASE
    )
    private val explanationVerb = Regex(
        "\\b(?:erklär\\p{L}*|erklaer\\p{L}*|explain\\p{L}*|" +
            "beschreib\\p{L}*|bedeutet|meaning)\\b", RegexOption.IGNORE_CASE
    )
    private val explicitExecution = Regex(
        "\\b(?:rufe|ruf|hole|hol|abrufen|abfragen|prüfe|pruefe|checke|check|verify|verifiziere|nachsehen|" +
            "nutze|benutze|verwende|führe|ausführen|sende|starte|crawl|" +
            "crawle|crawlen)\\b", RegexOption.IGNORE_CASE
    )

    /** Explanation-only questions must not generate host tool proposals. */
    fun isExplanationOnly(message: String): Boolean {
        val text = message.trim().replace(Regex("\\s+"), " ")
        return explanation.containsMatchIn(text) ||
            (explanationVerb.containsMatchIn(text) &&
                !explicitExecution.containsMatchIn(text))
    }

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
            "prüfe|pruefe|check|verify|verifiziere|nachsehen|kontrolliere|teste|prüfe den|prüfe die|pruefe den|pruefe die|status|systemstatus|" +
            "konto|serverzustand|nachrichten|logs|suche|sende|starte|lies|lese|" +
            "crawl|crawle|crawlen|abrufen|abfragen|rufe|ruf|fetch)\\b",
        RegexOption.IGNORE_CASE
    )
    /** A catalogue question plus "keine Werkzeuge ausführen" requests
     * discovery-only, not a server action. Strip ONLY narrowly scoped,
     * expressly negated tool-execution clauses. A separate positive action
     * (e.g. "prüfe den Status") must still reach the gated model agent.
     */
    private val negatedInventoryExecution = Regex(
        """\b(?:kein(?:e|en|er|es)?|ohne)\s+(?:extern(?:e|en|er|es)?\s+)?(?:mcp[-\s]+)?(?:tools?|werkzeuge?|funktionen?)\s+(?:zu\s+)?(?:ausführen|aufrufen|benutzen|verwenden|starten)\b""" +
            """|\b(?:tools?|werkzeuge?|funktionen?)\s+(?:bitte\s+)?nicht\s+(?:ausführen|aufrufen|benutzen|verwenden|starten)\b""" +
            """|\b(?:without|do\s+not|don't)\s+(?:executing|calling|running|using)\s+(?:any\s+)?(?:mcp\s+)?(?:tools?|functions?)\b""",
        RegexOption.IGNORE_CASE
    )
    private fun hasPositiveExternalTask(message: String): Boolean =
        externalTask.containsMatchIn(negatedInventoryExecution.replace(message, " "))

    private fun isReadOnlyInventory(message: String): Boolean =
        McpToolCatalog.requestedFilter(message) != null &&
            (inventoryOnly.matches(message) ||
                (inventoryQuestion.containsMatchIn(message) &&
                    !hasPositiveExternalTask(message)))

    private val externalSubject = Regex(
        "\\b(?:mcp|triforce|tri[ -]?force|discord|workspace|worspace|" +
            "arbeitsbereich|server|werkzeuge?|tools?)\\b",
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
        if (isReadOnlyInventory(normalized) ||
            McpArchitectureGuide.applies(normalized)) return false
        return true
    }

    /** A status/action request without native ToolCall must never look checked. */
    fun expectsToolEvidence(message: String): Boolean {
        val normalized = message.lowercase(Locale.ROOT)
        // Explaining a concept is not evidence that an external action ran.
        if (isExplanationOnly(normalized) || isReadOnlyInventory(normalized) ||
            McpArchitectureGuide.applies(normalized)) return false
        return McpHealthCheck.requestedTarget(message) != null ||
            McpLogCheck.requested(message) ||
            McpQuickActions.requestedAction(message) is McpQuickActions.Selection.Request ||
            (externalSubject.containsMatchIn(normalized) &&
                externalTask.containsMatchIn(normalized))
    }
}
