package me.ailinux.pocket.mcp

/** App-owned, read-only explanation of its *actual* MCP integration.
 *
 * A very small local model can confuse a question about MCP architecture
 * with training or Tensor-NPU deployment. This narrowly scoped answer uses
 * only local implementation facts and current in-memory catalogue counts;
 * it never infers a remote capability or invokes tools/call.
 */
internal object McpArchitectureGuide {
    // Generic "tools" / "functions" questions are not necessarily about
    // Pocket AI MCP. Do not hijack unrelated API-design requests.
    private val subject = Regex("\\bmcp\\b", RegexOption.IGNORE_CASE)
    private val conceptual = Regex(
        "\\b(?:erklär\\p{L}*|erklaer\\p{L}*|explain\\p{L}*|beschreib\\p{L}*|" +
            "beibring\\p{L}*|lehr\\p{L}*|trainier\\p{L}*|teach|teaching|" +
            "integrier\\p{L}*|anbind\\p{L}*|einbind\\p{L}*|implement\\p{L}*|" +
            "implementing|integrat\\p{L}*|einbett\\p{L}*|aufbau|architektur|" +
            "architecture|scaling|skalier\\p{L}*|design|baue|bauen|" +
            "einsetz\\p{L}*|funktionier\\p{L}*)\\b",
        RegexOption.IGNORE_CASE
    )
    private val question = Regex(
        "\\b(?:wie|how|warum|why|erklär\\p{L}*|erklaer\\p{L}*|" +
            "explain|best\\s*practice|konzept|architektur|architecture)\\b",
        RegexOption.IGNORE_CASE
    )
    private val operation = Regex(
        "\\b(?:prüfe|pruefe|checke|check|verify|verifiziere|" +
            "rufe|abrufen|hole|nutze|benutze|verwende|(?:and\\s+use)|call|invoke|" +
            "sende|send|post|poste|schicke|schreibe|delete|write|read|lies|lese|lösche|loesche|ändere|aendere|" +
            "starte|execute|execute\\s+tool|fetch|deploy|restart)\\b",
        RegexOption.IGNORE_CASE
    )

    fun applies(request: String): Boolean {
        val text = request.trim().replace(Regex("\\s+"), " ")
        return text.length in 8..350 && !text.startsWith("/mcp", ignoreCase = true) &&
            subject.containsMatchIn(text) && conceptual.containsMatchIn(text) &&
            question.containsMatchIn(text) && !operation.containsMatchIn(text) &&
            McpToolCatalog.requestedFilter(text) == null
    }

    /** Only counts from an already authenticated in-memory snapshot are used.
     * Never print remote descriptions, instructions, tokens, endpoints or tool
     * argument data; this is NOT a fresh tools/list or server result.
     */
    fun explain(autoSourceToolCounts: List<Int>, manuallySelectedCount: Int): String {
        require(autoSourceToolCounts.all { it >= 0 } && manuallySelectedCount >= 0)
        val snapshot = when {
            autoSourceToolCounts.isNotEmpty() ->
                "AUTO-MCP-Snapshot: ${autoSourceToolCounts.size} Server, " +
                    "${autoSourceToolCounts.sumOf { it.toLong() }} Tooldefinitionen " +
                    "(zuletzt geladener Katalog; nicht live nachgeprüft)."
            manuallySelectedCount > 0 ->
                "Manuell gebundene Tooldefinitionen: $manuallySelectedCount. " +
                    "Kein aktueller AUTO-MCP-Katalog in dieser Sitzung."
            else -> "Aktuell kein MCP-Katalog an diese Sitzung gebunden."
        }
        return "ⓘ Pocket AI · MCP-Architekturhilfe aus der App (keine Modellantwort, " +
            "kein tools/call)\n\n" +
            "$snapshot\n\n" +
            "MCP-Werkzeuge werden einem 0,8B-Modell nicht einzeln antrainiert. " +
            "Pocket AI zeigt dem Modell im AUTO-Modus nur drei lokale Funktionen:\n" +
            "1. mcp_search_tools: aus dem angemeldeten tools/list-Katalog " +
            "wenige passende Werkzeuge suchen.\n" +
            "2. mcp_get_tool_details: das vollständige Eingabeschema erst " +
            "für den ausgewählten Toolnamen laden.\n" +
            "3. mcp_call_tool: nur einen Vorschlag erstellen. Die App prüft " +
            "Autorisierung, Schema und Berechtigungen und fragt vor jedem " +
            "echten Serveraufruf nach Zustimmung.\n\n" +
            "Erst DISPATCH und SERVER_RESULT belegen eine Serveraktion; " +
            "eine reine Modellantwort oder Tool-Suche nicht. Kleine Modelle " +
            "benötigen kurze Beschreibungen und begrenzte Toolresultate.\n\n" +
            "Eine frische Liste: /mcp tools. Ein echter Statusabruf benötigt " +
            "die ausdrückliche Freigabe."
    }
}
