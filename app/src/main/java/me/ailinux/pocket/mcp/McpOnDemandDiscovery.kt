package me.ailinux.pocket.mcp

import org.json.JSONObject
import java.util.Locale

/**
 * Engine-independent fallback for models that reply with prose instead of tool calls.
 * Uses only live authorized tools/list snapshots. No side effects; the caller must
 * seek fresh consent and use the existing revalidated tools/call dispatcher.
 */
object McpOnDemandDiscovery {
    private val words = Regex("[\\p{L}\\p{N}_-]{3,}")
    private val stop = setOf("bitte","einen","einem","einer","diese","deine","deinen",
        "meine","meinen","mcp","tool","tools","verwende","benutze","nutze","zeige",
        "check","checke","prüfe","pruefe","über","ueber","with","using","the","und",
        "eine","einen","server","status","www","https")
    private val webUrl = Regex("https://[^\\s<>\"\']+", RegexOption.IGNORE_CASE)
    private val explicitTool = Regex("(?:tool|werkzeug|function)[\\s:='\"\\u0060]+([a-z][a-z0-9_.-]{2,})", RegexOption.IGNORE_CASE)
    private val mentionsExternal = Regex("\\b(?:mcp|tool|werkzeug|triforce|discord|crawl|status|api|server)\\b", RegexOption.IGNORE_CASE)

    // The host knows only a small audited set of read-only contracts.
    // This selects a candidate name, NOT a permission to execute it.
    private fun intendedSafeTool(request: String): String? {
        val lower = request.lowercase(Locale.ROOT)
        return when {
            Regex("\\b(?:crawl|crawle|crawlen)\\b").containsMatchIn(lower) &&
                webUrl.containsMatchIn(request) -> "crawl"
            Regex("\\bdiscord\\b").containsMatchIn(lower) &&
                Regex("\\b(?:status|verbindung|connection)\\b")
                    .containsMatchIn(lower) -> "discord_connection_status"
            Regex("\\b(?:status|systemstatus|serverzustand)\\b")
                .containsMatchIn(lower) &&
                Regex("\\b(?:mcp|triforce|tri-force|ailinux|server)\\b")
                    .containsMatchIn(lower) -> "status"
            else -> null
        }
    }

    /**
     * Tiny LiteRT models can produce prose instead of native function calls
     * after a previous tool turn. The host may prepare a proposal from the
     * actual manually selected tools, but MUST NOT dispatch it automatically.
     *
     * Synthetic s1 is a local index, not a server-issued tool identifier.
     * All risk/schema checks reuse the conservative progressive planner.
     */
    fun proposeSelected(
        request: String,
        active: McpAgentSession
    ): McpAgentBridge.Decision.Proposed? {
        if (active.progressiveSources.isNotEmpty() || active.tools.isEmpty()) return null
        if (active.tools.map { it.name }.distinct().size != active.tools.size) return null
        val source = McpProgressiveBridge.Source(
            id = "s1", profileId = active.profileId,
            endpoint = active.endpoint, title = active.serverName,
            tools = active.tools
        )
        val proposal = runCatching {
            propose(request, McpProgressiveBridge.Run(listOf(source)))
        }.getOrNull() ?: return null
        if (!proposal.hostFallback) return null
        return McpAgentBridge.Decision.Proposed(
            proposal.tool, JSONObject(proposal.arguments.toString())
        )
    }

    fun propose(request: String, run: McpProgressiveBridge.Run): McpProgressiveBridge.RemoteCall? {
        // Pure explanation is never an instruction to query a server.
        if (Regex("(?i)^\\s*(?:brumo[,!]?\\s+)?(?:erklär\\w*|erklaer\\w*|was\\s+(?:ist|bedeutet)|wie\\s+funktioniert|explain\\b)")
                .containsMatchIn(request)) return null
        if (!mentionsExternal.containsMatchIn(request)) return null
        val terms = words.findAll(request.lowercase(Locale.ROOT)).map { it.value }
            .filter { it !in stop && !it.all(Char::isDigit) }.distinct().take(10).toList()
        val requestedName = explicitTool.find(request)?.groupValues?.get(1)
            ?.takeUnless { it in setOf("https", "http", "url", "website") }
            ?: intendedSafeTool(request)
        val query = requestedName ?: terms.joinToString(" ").take(120)
        if (query.isBlank()) return null
        val response = run.search(query)
        if (response.optBoolean("isError")) return null
        val payload = response.optJSONArray("content")?.optJSONObject(0)?.optString("text") ?: return null
        val matches = JSONObject(payload).optJSONArray("matches") ?: return null
        val candidates = (0 until matches.length()).mapNotNull { matches.optJSONObject(it) }
        if (candidates.isEmpty()) return null
        // Explicit function name or unambiguous single result only.
        val chosen = when {
            requestedName != null -> candidates.singleOrNull {
                it.optString("name").equals(requestedName, ignoreCase = true)
            }
            candidates.size == 1 -> candidates.single()
            else -> candidates.filter { candidate ->
                val name = candidate.optString("name")
                val escaped = Regex.escape(name)
                Regex("(?i)(?:^|[^a-z0-9_])" + escaped + "(?:$|[^a-z0-9_])")
                    .containsMatchIn(request)
            }.singleOrNull()
        } ?: return null
        val id = chosen.optString("tool_id")
        val tool = run.tool(id)?.tool ?: return null
        // Host fallback never guesses a side-effecting tool, even when the
        // caller might later approve it. Native model calls stay available.
        // An unknown tool cannot be classified read-only by its name.
        // The fallback is deliberately limited to exact audited contracts;
        // native tool calls still support all permitted MCP tools.
        if (tool.name !in setOf("crawl", "status", "discord_connection_status",
                "current_time", "models", "device_info") ||
            !tool.declaresReadOnly) return null
        // Never guess mandatory parameters; allow a single HTTPS URL only for
        // a schema requiring exactly one URL-like string property.
        val schema = tool.schema
        val mandatory = schema.optJSONArray("required")
        val args = JSONObject()
        if (mandatory != null && mandatory.length() > 0) {
            if (mandatory.length() != 1) return null
            val field = mandatory.optString(0)
            if (field !in setOf("url","uri","target_url")) return null
            val prop = schema.optJSONObject("properties")?.optJSONObject(field) ?: return null
            if (prop.optString("type") != "string") return null
            val url = webUrl.find(request)?.value?.trimEnd('.', ',', ';', ')', ']', '}') ?: return null
            args.put(field,url)
        }
        val detail = run.details(id)
        if (detail.optBoolean("isError")) return null
        return run.prepareCall(id,args.toString()).copy(hostFallback = true)
    }
}
