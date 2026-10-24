package me.ailinux.pocket.mcp

import org.json.JSONObject
import java.net.URI
import java.util.Locale

/**
 * Explicit read-only MCP operations; bypass local GGUF for simple commands.
 * A local allowlist is not proof of server permission: live discovery, schema
 * validation, user consent, and the actual tool response are all required.
 */
object McpQuickActions {
    enum class Target { DISCORD, TRIFORCE }

    sealed interface Selection {
        data class Request(
            val target: Target,
            val toolName: String,
            val args: JSONObject = JSONObject()
        ) : Selection
        data class Invalid(val reason: String) : Selection
    }

    private val quick = Regex("^/mcp\\s+quick\\s+([a-z-]+)\\s*$", RegexOption.IGNORE_CASE)
    private val search = Regex(
        "^/mcp\\s+(search|code-search|memory-search)\\s+([^\\r\\n]{1,200})\\s*$",
        RegexOption.IGNORE_CASE
    )
    private val supported = mapOf(
        "discord-status" to ("discord_connection_status" to Target.DISCORD),
        "discord-guilds" to ("discord_authorized_guilds" to Target.DISCORD),
        "discord-voice" to ("discord_voice_status" to Target.DISCORD),
        "triforce-status" to ("status" to Target.TRIFORCE),
        "notifications" to ("notify_list" to Target.TRIFORCE)
    )
    private val searchTools = mapOf(
        "search" to "search",
        "code-search" to "code_search",
        "memory-search" to "memory_search"
    )

    fun requestedAction(message: String): Selection? {
        val text = message.trim()
        if (text.length !in 4..260) return null
        quick.matchEntire(text)?.let { match ->
            val alias = match.groupValues[1].lowercase(Locale.ROOT)
            val result = supported[alias]
                ?: return Selection.Invalid("Unbekannte Schnellaktion '" + alias + "'. " + help())
            return Selection.Request(result.second, result.first)
        }
        search.matchEntire(text)?.let { match ->
            val word = match.groupValues[1].lowercase(Locale.ROOT)
            val query = match.groupValues[2].trim()
            if (query.isBlank()) return Selection.Invalid("Suchbegriff fehlt.")
            return Selection.Request(
                Target.TRIFORCE, searchTools.getValue(word),
                JSONObject().put("query", query)
            )
        }
        if (text.startsWith("/mcp quick", true) ||
            text.startsWith("/mcp search", true) ||
            text.startsWith("/mcp code-search", true) ||
            text.startsWith("/mcp memory-search", true)) {
            return Selection.Invalid("Ungültiger MCP-Schnellbefehl. " + help())
        }

        // Only unambiguous account/permission/voice read requests qualify.
        // "Join Discord Voice", create, send, or delete is NEVER matched.
        val value = text.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
        if (!Regex("\\bdiscord\\b").containsMatchIn(value)) return null
        if (Regex("\\b(?:verbinde mich|beitreten|join|betreten|schreib|sende|send|lösche|delete|erstelle)\\b")
                .containsMatchIn(value)) return null
        if (value.startsWith("was ist ") || value.startsWith("erkläre ") ||
            value.startsWith("what is ") || value.startsWith("wie funktioniert ")) return null
        val ask = Regex(
            "\\b(?:prüf\\w*|pruef\\w*|check\\w*|zeig\\w*|list\\w*|welche\\w*|what|" +
                "show|status|ist|sind|habe|hat|available)\\b"
        )
        if (!ask.containsMatchIn(value)) return null
        if (Regex("\\b(?:voice|sprachchat|nova)\\b").containsMatchIn(value) &&
            Regex("\\b(?:status|prüf\\w*|pruef\\w*|check\\w*|läuft|online|aktiv)\\b")
                .containsMatchIn(value)) {
            return Selection.Request(Target.DISCORD, "discord_voice_status")
        }
        if (Regex("\\b(?:guilds?|freigaben?|berechtigten?\\s+servern?|autorisierte[nr]?\\s+servern?)\\b")
                .containsMatchIn(value)) {
            return Selection.Request(Target.DISCORD, "discord_authorized_guilds")
        }
        if (Regex("\\b(?:login|konto|account|verbunden|verknüpft|verbindung|connection|linked)\\b")
                .containsMatchIn(value)) {
            return Selection.Request(Target.DISCORD, "discord_connection_status")
        }
        return null
    }

    fun matchesProfile(profile: McpProfile, target: Target): Boolean = runCatching {
        val uri = URI(profile.endpoint)
        uri.scheme.equals("https", true) &&
            uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            uri.port in setOf(-1, 443) && when (target) {
            Target.DISCORD -> uri.host.equals("discord.ailinux.me", true) &&
                uri.path.trimEnd('/') == "/mcp"
            Target.TRIFORCE -> uri.host.equals("api.ailinux.me", true) &&
                uri.path.trimEnd('/') == "/v1/mcp"
        }
    }.getOrDefault(false)

    /** Exact advertised tool + live input-schema validation; fail closed. */
    fun validatedProposal(
        request: Selection.Request, info: McpServerInfo
    ): McpAgentBridge.Decision.Proposed {
        val correctTarget = when (request.toolName) {
            in searchTools.values, "status", "notify_list" -> Target.TRIFORCE
            "discord_connection_status", "discord_authorized_guilds",
            "discord_voice_status" -> Target.DISCORD
            else -> throw IllegalArgumentException(
                "Tool ist nicht in der Schnellaktions-Whitelist"
            )
        }
        require(request.target == correctTarget) {
            "MCP-Schnellaktion hat eine unpassende Server-Zuordnung"
        }
        val keys = request.args.keys().asSequence().toSet()
        if (request.toolName in searchTools.values) {
            require(keys == setOf("query") &&
                request.args.optString("query").length in 1..200) {
                "Nur ein Suchbegriff bis 200 Zeichen ist erlaubt"
            }
        } else {
            require(keys.isEmpty()) { "Dieses Lesewerkzeug akzeptiert keine Argumente" }
        }
        val tool = info.tools.singleOrNull { it.name == request.toolName }
            ?: throw IllegalStateException(
                "Der MCP-Server stellt '" + request.toolName + "' für dieses Konto nicht bereit."
            )
        val input = JSONObject()
            .put("mcp_tool", request.toolName)
            .put("arguments", request.args)
        val parsed = McpAgentBridge.parse(input.toString(), listOf(tool))
        return parsed as? McpAgentBridge.Decision.Proposed
            ?: throw IllegalArgumentException(
                "Der MCP-Server erwartet andere Pflichtparameter. Keine Aktion ausgeführt."
            )
    }

    fun help(): String =
        "Bekannt: /mcp quick discord-status | discord-guilds | discord-voice | " +
        "triforce-status | notifications; /mcp search BEGRIFF; " +
        "/mcp code-search BEGRIFF; /mcp memory-search BEGRIFF."

    fun unavailable(target: Target): String = when (target) {
        Target.DISCORD -> McpToolCatalog.missingProfile("discord")
        Target.TRIFORCE -> McpHealthCheck.missingProfile("triforce")
    }

    fun noOAuth(profile: McpProfile): String =
        McpHealthCheck.loginRequired(profile) +
            "\nDas gewünschte Werkzeug wurde nicht aufgerufen."

    fun displayReceipt(tool: McpTool, result: JSONObject): String {
        val raw = McpAgentBridge.receipt(tool, result)
        val reason = result.toString().lowercase(Locale.ROOT)
        val permission = if (tool.name == "discord_voice_status" &&
            reason.contains("server_read_grant_required")) {
            "\n\n🔒 Discord ist möglicherweise verbunden, aber für den Voice-Status " +
                "fehlt eine ausdrücklich genehmigte Server-Lesefreigabe. " +
                "Die Konto-Verknüpfung allein erteilt diese Berechtigung nicht."
        } else ""
        return "🔎 DIREKTE MCP-LESEABFRAGE · Live tools/call\n" +
            raw + permission + "\n\nOhne Modellbehauptung: Die Rohantwort stammt vom MCP-Endpunkt."
    }
}
