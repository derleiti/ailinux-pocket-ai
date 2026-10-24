package me.ailinux.pocket.mcp

import java.util.Locale

/**
 * Fail-closed routing for remote log-audit requests.
 *
 * Successful tools/list is NOT a log read. An absent log reader must not
 * invite an offline GGUF to invent completed diagnostics.
 * No host file/journal access, credentials or automatic tool calls here.
 */
object McpLogCheck {
    private val explicit = Regex(
        "^/mcp\\s+(?:logs?|logcheck|journal)(?:\\s+(?:triforce|ailinux))?\\s*$",
        RegexOption.IGNORE_CASE
    )
    private val namedTarget = Regex(
        """(?:\btri[\s-]?force\b|api\.ailinux\.me)""", RegexOption.IGNORE_CASE
    )
    private val logs = Regex(
        """\b(?:logs?|logdatei(?:en)?|journal(?:ctl)?|protokoll(?:e|en|dateien)?)\b""",
        RegexOption.IGNORE_CASE
    )
    private val actions = Regex(
        """\b(?:versuch\w*|prüf\w*|pruef\w*|durchsuch\w*|durchforst\w*|""" +
            """analysier\w*|untersuch\w*|such\w*|les\w*|lies|zeig\w*|check\w*|""" +
            """kontrollier\w*|auswert\w*|auditier\w*|scan\w*|show|read|analy[sz]e)\b""",
        RegexOption.IGNORE_CASE
    )
    private val explanation = Regex(
        """^(?:(?:was|warum|wieso|weshalb|how|why)\b|erklär\w*\b|""" +
            """(?:schreib|erstelle|formuliere)\b)""", RegexOption.IGNORE_CASE
    )
    private val positiveAudit = Regex(
        """(?:\b(?:logs?|logdateien?|protokoll\w*|journal)\b[^\n.!?]{0,150}""" +
            """(?:\b(?:sind|wurden|habe|hätten)\b[^\n.!?]{0,55})?""" +
            """(?:\bsauber\b|\bvollständig\b|\bausgewertet\b|\banalysiert\b|""" +
            """\büberprüft\b|\bdurchgearbeitet\b|\babgeschlossen\b|\bfehlerfrei\b)|""" +
            """\b(?:alle|keine)\b[^\n.!?]{0,50}(?:logs?|fehler|probleme)\b[^\n.!?]{0,70}""" +
            """(?:\b(?:gefunden|sauber|abgeschlossen|überprüft)\b))""",
        RegexOption.IGNORE_CASE
    )
    private val readToolNames = setOf(
        "logs", "logs_errors", "log_viewer", "journal", "journal_viewer", "read_logs"
    )

    fun requested(message: String): Boolean {
        val text = message.trim().replace(Regex("\\s+"), " ")
        if (text.length !in 3..280) return false
        if (explicit.matches(text)) return true
        if (explanation.containsMatchIn(text)) return false
        if (!namedTarget.containsMatchIn(text) || !logs.containsMatchIn(text)) return false
        return actions.containsMatchIn(text)
    }

    /** Only the known HTTPS TriForce endpoint is selected. */
    fun relevantProfile(profile: McpProfile): Boolean =
        McpQuickActions.matchesProfile(profile, McpQuickActions.Target.TRIFORCE)

    /**
     * Grounded in one real tools/list response. Never present it as log data,
     * and never assume a tool that was reported yesterday still exists.
     */
    fun capabilityReport(profile: McpProfile, info: McpServerInfo): String {
        val exact = info.tools.filter { it.name.lowercase(Locale.ROOT) in readToolNames }
            .distinctBy { it.name.lowercase(Locale.ROOT) }
        val header = "🔎 ECHTE MCP-LOGZUGRIFFSPRÜFUNG · Live tools/list\n" +
            "Profil: " + plain(profile.title, 100) + "\n" +
            "Endpunkt: " + plain(profile.endpoint, 2048) + "\n" +
            "Server: " + plain(info.title, 100) + "\n" +
            "Gemeldete Tools: " + info.tools.size + "\n" +
            "Log-Lesewerkzeuge gefunden: " + exact.size + "\n\n"
        val warning = "\n\nWICHTIG: Nur die Werkzeugliste wurde wirklich abgefragt. " +
            "KEINE Logzeilen gelesen, KEINE Fehleranalyse durchgeführt und " +
            "KEINE Aussage über den Zustand der Server-Logs bestätigt."
        return if (exact.isEmpty()) {
            header + "⚠️ KEIN MCP-LOGLESEWERKZEUG VERFÜGBAR\n" +
                "Diese MCP-Verbindung bietet aktuell keinen der explizit freigegebenen " +
                "Log-Lesezugriffe an. Ein vorhandenes status-Tool ist kein Ersatz " +
                "für eine echte Logprüfung.\n" +
                "Spark-X2.6 kann fehlenden Logzugriff nicht durch Thinking ersetzen. " +
                "Für echte Logdaten ist ein zusätzliches sicheres, berechtigtes " +
                "MCP-Lesewerkzeug nötig. Für den echten Systemstatus " +
                "statt einer Logprüfung: /mcp quick triforce-status." + warning
        } else {
            header + "Mögliche Log-Lesewerkzeuge (NICHT ausgeführt):\n" +
                exact.joinToString("\n") { "• " + plain(it.name, 128) } + "\n" +
                "Die tatsächlichen Serverrechte, Argumente und Logdaten sind noch " +
                "NICHT geprüft. Werkzeug im MCP-Menü bewusst auswählen, " +
                "Argumente kontrollieren und den Aufruf einzeln bestätigen." + warning
        }
    }

    fun missingProfile(): String =
        "⚠️ Kein passendes TriForce-MCP-Profil gefunden.\n" +
        "Unter SETUP → MCP VERBINDUNGEN das Online-Profil " +
        "https://api.ailinux.me/v1/mcp hinzufügen.\nKeine Logdaten gelesen."

    fun failure(profile: McpProfile, error: Throwable): String {
        val detail = if (error is McpHttpFailure)
            "HTTP " + error.code + ", MCP-Zugriff nicht bestätigt."
        else
            "Verbindungs-/Protokollfehler (" + plain(error.javaClass.simpleName, 64) + ")."
        return "⚠️ MCP-LOGZUGRIFFSPRÜFUNG NICHT BESTANDEN\n" +
            "Profil: " + plain(profile.title, 100) + "\n" +
            "Grund: " + detail + "\nKeine Logdaten gelesen."
    }

    /**
     * Defense in depth if a paraphrased remote-log request escapes routing.
     * A plain local model answer is never evidence.
     */
    fun modelOnlyAuditClaim(prompt: String, reply: String): Boolean {
        if (!requested(prompt)) return false
        if (!positiveAudit.containsMatchIn(reply)) return false
        val denial = Regex(
            """(?:ich\s+kann\s+[^\n.!?]{0,70}\bnicht\b|""" +
                """\bnicht\s+sagen\b|""" +
                """\b(?:nicht|nie|noch\s+nicht|keine)\s+(?:wirklich\s+)?""" +
                """(?:ausgelesen|geprüft|analysiert|ausgewertet|durchgearbeitet)\b)""",
            RegexOption.IGNORE_CASE
        )
        return !denial.containsMatchIn(reply)
    }

    fun unverifiedModelClaim(): String =
        "⚠️ KEINE TRIFORCE-LOGPRÜFUNG AUSGEFÜHRT\n" +
        "Spark-X2.6 hat eine abgeschlossene Loganalyse behauptet, " +
        "aber keinen echten MCP-Logdatenbeleg geliefert.\n\n" +
        "Nutze /mcp logs triforce, um zuerst die tatsächlich verfügbaren " +
        "Log-Lesewerkzeuge am Online-MCP zu prüfen. Ein Health-Check " +
        "oder Thinking AN prüft keine Server-Logs."

    private fun plain(value: String, maximum: Int): String =
        value.replace(Regex("[\\u0000-\\u001F\\u007F]"), " ").take(maximum)
}
