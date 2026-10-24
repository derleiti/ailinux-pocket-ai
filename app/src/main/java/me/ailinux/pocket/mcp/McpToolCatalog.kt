package me.ailinux.pocket.mcp

import java.net.URI
import java.util.Locale

/**
 * Read-only catalogue intent and display. Tool discovery MUST be performed by
 * McpConnection.inspect on the authenticated server; LLM answers never count
 * as evidence of which MCP tools are actually exposed to this account.
 */
object McpToolCatalog {
    private val explicit = Regex(
        "^/mcp\\s+(?:tools?|liste|list|inventory)(?:\\s+([\\p{L}\\p{N}_ .-]{1,60}))?\\s*$",
        RegexOption.IGNORE_CASE
    )
    private val subject = Regex("\\b(?:mcp|discord)\\b", RegexOption.IGNORE_CASE)
    private val tools = Regex("\\b(?:tools?|werkzeuge?|funktionen?|befehle?)\\b",
        RegexOption.IGNORE_CASE)
    private val listing = Regex(
        "\\b(?:welche|welcher|liste|list|anzeigen|zeig|zeige|zeigen|auflisten|verfügbar|vorhanden|gibt|stehen|hast|übersicht|show|available|what|which|check|prüf\\w*|pruef\\w*|teste?|kontrollier\\w*|inspect|verify)\\b",
        RegexOption.IGNORE_CASE
    )

    /**
     * null = regular LLM question
     * empty string = fetch unfiltered catalogue
     * "discord" = fetch catalogue, filtering on Discord-relevant tools
     */
    fun requestedFilter(message: String): String? {
        val text = message.trim().lowercase(Locale.ROOT)
            .replace(Regex("\\s+"), " ")
        if (text.length !in 3..250) return null
        explicit.matchEntire(text)?.let { return it.groupValues[1].trim() }
        // A short explicit catalogue request must not become a generic
        // LLM conversation, even when AUTO-BRIDGE is active.
        if (text.matches(Regex("(?:mcp\\s+)?(?:tools?|werkzeuge?|funktionen?)\\s+(?:mcp)?")) ||
            text.matches(Regex("mcp\\s+(?:tools?|werkzeuge?|funktionen?)"))) return ""
        val mentionsTools = subject.containsMatchIn(text) &&
            tools.containsMatchIn(text) && listing.containsMatchIn(text)
        val askingCapability = "mcp" in text && "discord" in text &&
            (text.startsWith("was kann") || text.startsWith("was bietet"))
        if (!mentionsTools && !askingCapability) return null
        return if (text.contains("discord")) "discord" else ""
    }

    fun isDiscordServer(profile: McpProfile): Boolean = runCatching {
        URI(profile.endpoint).host.equals("discord.ailinux.me", ignoreCase = true)
    }.getOrDefault(false)

    fun discordSetupHint(): String =
        "Für die AILinux-Discord-Tools ist ein eigenes Serverprofil nötig:\n" +
        "SETUP → MCP VERBINDUNGEN → + MCP-Server → AILinux Discord MCP.\n" +
        "Adresse: https://discord.ailinux.me/mcp\n" +
        "Anmeldung: OAuth 2.0 + PKCE, Berechtigung: discord:read.\n" +
        "Das ist nicht dieselbe Anmeldung wie TriForce oder der Discord-Bot."

    fun missingProfile(filter: String): String =
        if (filter.equals("discord", true)) {
            "⚠️ Kein Discord-MCP-Profil in Pocket AI eingerichtet.\n\n" +
                discordSetupHint()
        } else {
            "⚠️ Kein MCP-Server in Pocket AI eingerichtet.\n" +
                "Unter SETUP → MCP VERBINDUNGEN zuerst einen Server hinzufügen und anmelden."
        }

    fun loginRequired(profile: McpProfile): String =
        "🔒 MCP-Toolliste kann nicht gelesen werden – Anmeldung erforderlich.\n" +
        "Server: " + plain(profile.title, 100) + "\n" +
        "Endpunkt: " + plain(profile.endpoint, 2048) + "\n\n" +
        "Unter SETUP → MCP VERBINDUNGEN bei diesem Profil OAuth Login durchführen, " +
        "danach den Server testen.\nEs wurden keine MCP-Tools ausgeführt."

    fun format(
        profile: McpProfile,
        actual: McpServerInfo,
        filter: String
    ): String {
        val query = filter.trim().lowercase(Locale.ROOT).take(60)
        val matched = actual.tools.filter { tool ->
            query.isBlank() ||
                tool.name.contains(query, ignoreCase = true) ||
                tool.description.contains(query, ignoreCase = true)
        }.distinctBy { it.name }.sortedBy { it.name.lowercase(Locale.ROOT) }
        val intro = "🔌 ECHTE MCP-TOOLLISTE · Live tools/list\n" +
            "Serverprofil: " + plain(profile.title, 100) + "\n" +
            "Endpunkt: " + plain(profile.endpoint, 2048) + "\n" +
            "Server: " + plain(actual.title, 100) + "\n" +
            "Protokoll: " + plain(actual.protocol, 50) + "\n" +
            "Gemeldete Tools: " + actual.tools.size +
            if (query.isBlank()) "\n" else
                "\nFilter: " + plain(query, 60) + " · Treffer: " + matched.size + "\n"
        if (matched.isEmpty()) {
            return intro + "\nKeine passenden Tools für diese Verbindung gemeldet.\n" +
                "Das bedeutet nicht, dass andere MCP-Server diese Tools nicht anbieten." +
                if (query == "discord" && !isDiscordServer(profile))
                    "\n\n" + discordSetupHint()
                else ""
        }
        val names = matched.take(150).joinToString("\n") { tool ->
            val description = plain(tool.description, 115)
            "• " + plain(tool.name, 128) +
                if (description.isBlank()) "" else "\n  " + description
        }
        val shortened = if (matched.size > 150)
            "\n… " + (matched.size - 150) + " weitere; Filter präzisieren."
        else ""
        val scope = "\n\nDiese Liste stammt vom MCP-Server, nicht aus der Modellantwort." +
            " Die Anzeige allein führt kein Werkzeug aus und garantiert keine Schreibberechtigung."
        return intro + "\n" + names + shortened + scope
    }

    private fun plain(value: String, limit: Int): String =
        value.replace(Regex("[\\u0000-\\u001F\\u007F]"), " ").take(limit)
}
