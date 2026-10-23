package me.ailinux.pocket.mcp

import java.net.URI
import java.util.Locale

/**
 * Deterministic, read-only MCP health check. Never ask a GGUF to decide
 * whether its own unverified prose counts as a network probe.
 *
 * Successful server/discover|initialize AND tools/list prove protocol reachability
 * for the supplied profile. They do NOT prove every tools/call permission,
 * linked Discord-account grants, or the condition of other endpoints.
 */
object McpHealthCheck {
    private val command = Regex(
        "^/mcp\\s+(?:health|healthcheck|check|status|ping|diagnose)" +
            "(?:\\s+(discord|triforce|ailinux|all))?\\s*$",
        RegexOption.IGNORE_CASE
    )
    private val health = Regex(
        "\\b(?:health[\\s_-]*check|gesundheitscheck|verbindungs[\\s_-]*(?:check|test|pruefung|prüfung)|" +
            "erreichbarkeit|zugangsverbindung|zugriffsberechtigungen)\\b",
        RegexOption.IGNORE_CASE
    )
    private val check = Regex(
        "\\b(?:prüf\\w*|pruef\\w*|check\\w*|test\\w*|kontrollier\\w*|" +
            "funktionier\\w*|ist|sind|läuft|laeuft)\\b",
        RegexOption.IGNORE_CASE
    )

    // Natural requests like "check triforce dev server status" intentionally
    // omit the word "MCP". Detect that named endpoint rather than forwarding
    // the question to the offline LLM to fabricate a server result.
    private val namedTriForce = Regex(
        "(?:\\btriforce\\b|\\btri[ -]?force\\b|api\\.ailinux\\.me)",
        RegexOption.IGNORE_CASE
    )
    private val statusSubject = Regex(
        "\\b(?:server|endpoint|status|state|health|healthcheck|connection|" +
            "verbindung|erreichbarkeit|zustand)\\b",
        RegexOption.IGNORE_CASE
    )
    private val statusRequest = Regex(
        "\\b(?:check\\w*|prüf\\w*|pruef\\w*|test\\w*|health|" +
            "status|zustand|erreichbar|funktioniert|läuft|laeuft)\\b",
        RegexOption.IGNORE_CASE
    )

    /** null: not a health request; empty: any configured server. */
    fun requestedTarget(message: String): String? {
        val text = message.trim().replace(Regex("\\s+"), " ")
        if (text.length !in 3..280) return null
        command.matchEntire(text)?.let {
            val name = it.groupValues[1].lowercase(Locale.ROOT)
            return if (name == "all") "" else if (name == "ailinux") "triforce" else name
        }
        val normalized = text.lowercase(Locale.ROOT)
        if (normalized.startsWith("was ist ein ") ||
            normalized.startsWith("was ist eine ") ||
            normalized.startsWith("erklär") ||
            normalized.startsWith("was bedeutet ") ||
            normalized.startsWith("what is a ") ||
            normalized.startsWith("what does ")) return null
        val namesTriForce = namedTriForce.containsMatchIn(text)
        val explicitMcp = normalized.contains("mcp")
        val namedStatusRequest = namesTriForce &&
            statusSubject.containsMatchIn(text) &&
            statusRequest.containsMatchIn(text)
        if (!explicitMcp && !namedStatusRequest) return null
        if (!namedStatusRequest &&
            !health.containsMatchIn(text) &&
            !(normalized.contains("verbindung") && check.containsMatchIn(text)) &&
            !(normalized.contains("status") && check.containsMatchIn(text))) return null
        return when {
            normalized.contains("discord") -> "discord"
            normalized.contains("triforce") || normalized.contains("api.ailinux.me") -> "triforce"
            else -> ""
        }
    }

    fun relevantProfile(profile: McpProfile, target: String): Boolean = when (target) {
        "discord" -> McpToolCatalog.isDiscordServer(profile)
        "triforce" -> runCatching {
            val uri = URI(profile.endpoint)
            uri.host.equals("api.ailinux.me", ignoreCase = true) &&
                uri.path.trimEnd('/').startsWith("/v1/mcp")
        }.getOrDefault(false)
        else -> true
    }

    fun missingProfile(target: String): String = when (target) {
        "discord" -> "⚠️ Kein Discord-MCP-Profil konfiguriert.\n\n" +
            McpToolCatalog.discordSetupHint()
        "triforce" -> "⚠️ Kein AILinux-TriForce-MCP-Profil konfiguriert.\n" +
            "Unter SETUP → MCP VERBINDUNGEN → Server hinzufügen → AILinux TriForce auswählen."
        else -> "⚠️ Keine MCP-Verbindung konfiguriert.\n" +
            "Bitte unter SETUP → MCP VERBINDUNGEN zuerst einen Server einrichten."
    }

    fun loginRequired(profile: McpProfile): String =
        "🔒 MCP-HEALTH-CHECK NICHT BESTANDEN: OAuth-Anmeldung fehlt.\n" +
        "Profil: " + clean(profile.title, 100) + "\n" +
        "Endpunkt: " + clean(profile.endpoint, 2048) + "\n" +
        "Unter SETUP → MCP VERBINDUNGEN das Profil mit OAuth anmelden.\n" +
        "MCP-Verbindung und Benutzerberechtigungen wurden nicht bestätigt."

    /** Call only after a genuine authenticated server/discover or initialize plus tools/list. */
    fun success(profile: McpProfile, server: McpServerInfo, elapsedMs: Long): String {
        val transport = when (profile.transport) {
            McpTransport.LEGACY_SSE -> "Legacy HTTP+SSE"
            McpTransport.STREAMABLE_HTTP -> "Streamable HTTP"
            McpTransport.AUTO -> "MCP (automatische Transportwahl)"
        }
        return "🔌 ECHTER MCP-HEALTH-CHECK · SERVERANTWORT\n" +
            "Ergebnis: ERREICHBAR – MCP-Protokoll und tools/list haben geantwortet\n" +
            "Profil: " + clean(profile.title, 100) + "\n" +
            "Endpunkt: " + clean(profile.endpoint, 2048) + "\n" +
            "Server: " + clean(server.title, 100) + "\n" +
            "MCP-Version: " + clean(server.protocol, 48) + "\n" +
            "Transport: " + transport + "\n" +
            "Gemeldete Tools: " + server.tools.size + "\n" +
            "Prüfdauer: " + elapsedMs.coerceAtLeast(0) + " ms\n\n" +
            "BESTÄTIGT: Der konfigurierte Server war für diese MCP-Abfragen erreichbar.\n" +
            "NICHT BESTÄTIGT: Rechte für einzelne Tools, Discord-Serverfreigaben, " +
            "Voice-Verbindung oder weitere Endpunkte.\n" +
            "Auch Host-Betriebssystem, Hintergrunddienste, Entwicklungsserver, " +
            "Datenbanken und CI wurden NICHT geprüft.\n" +
            "Dafür sind separate, ausdrücklich freigegebene Diagnosetools nötig."
    }

    fun failure(profile: McpProfile, error: Throwable): String {
        val detail = when (error) {
            is McpHttpFailure -> when (error.code) {
                401 -> "HTTP 401: Anmeldung oder Token ungültig"
                403 -> "HTTP 403: Berechtigung für den MCP-Endpunkt fehlt"
                404 -> "HTTP 404: MCP-Endpunkt nicht gefunden"
                else -> "MCP-HTTP-Fehler " + error.code
            }
            is McpRpcFailure -> "MCP-JSON-RPC-Fehler " + error.code
            else -> "MCP-Verbindungs- oder Protokollprüfung fehlgeschlagen (" +
                clean(error.javaClass.simpleName, 80) + ")"
        }
        return "⚠️ MCP-HEALTH-CHECK NICHT BESTANDEN\n" +
            "Profil: " + clean(profile.title, 100) + "\n" +
            "Endpunkt: " + clean(profile.endpoint, 2048) + "\n" +
            "Ergebnis: " + detail + "\n" +
            "Keine Servergesundheit, Benutzerberechtigung oder erfolgreiche Aktion bestätigt."
    }

    /** Used when a small LLM claims a check without an actual MCP result. */
    fun modelOnlyClaim(text: String): Boolean {
        val lower = text.lowercase(Locale.ROOT)
        if (!Regex("mcp|discord|triforce|server|health|zugriffsberechtigung|endpunkt|voice")
                .containsMatchIn(lower)) return false
        return Regex(
            "(?:check\\s+(?:ist\\s+)?abgeschlossen|" +
                "alles\\s+läuft\\s+(?:in\\s+ordnung|gut|reibungslos|stabil)|" +
                "alle\\s+systeme\\s+(?:sind\\s+)?(?:bereit|stabil|online|gesund|in\\s+ordnung)|" +
                "server\\s+[^.\\n]{0,90}(?:ist|läuft)\\s+[^.\\n]{0,50}" +
                    "(?:online|bereit|gesund|stabil|im\\s+stand|reibungslos)|" +
                "verbindung\\s+[^.\\n]{0,130}(?:aktiv|hergestellt|bestätigt|geprüft)|" +
                "ich\\s+habe\\s+[^.\\n]{0,140}(?:geprüft|überprüft|verbunden|bestätigt)|" +
                "zugriffsberechtigungen\\s+[^.\\n]{0,90}(?:überprüft|geprüft))",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(text)
    }

    fun unverifiedModelClaim(): String =
        "⚠️ KEIN MCP-HEALTH-CHECK AUSGEFÜHRT\n" +
        "Das Sprachmodell hat einen externen Erfolg behauptet, aber kein MCP-Serverresultat geliefert. " +
        "Die unbelegte Aussage wurde nicht als Prüfbericht angezeigt.\n\n" +
        "Nutze /mcp health für eine echte Netzwerk-/Protokollprüfung. " +
        "Für Konto- und Discord-Berechtigungen ist ein separater Toolaufruf erforderlich."

    private fun clean(text: String, max: Int): String =
        text.replace(Regex("[\\u0000-\\u001F\\u007F]"), " ").take(max)
}
