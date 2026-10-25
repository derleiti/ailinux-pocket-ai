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
    // "per MCP tool call" describes the protocol, NOT an explicit tool
    // called "call". Keep explicit function names only when meaningful.
    private val genericActionWords = setOf(
        "call", "calling", "aufruf", "ausführen", "ausfuehren",
        "ausführung", "ausfuehrung", "request", "invoke", "toolcall",
        "aktion", "action", "method"
    )
    private val titleTokens = Regex("[\\p{L}\\p{N}]{3,}")
    private val genericServerWords = setOf("ailinux", "mcp", "server",
        "endpoint", "api", "tool", "tools", "service", "services")

    /**
     * When multiple authorized MCP servers publish "status", a free-text
     * request must identify a provider through a distinctive title token.
     * Never pick the first status tool from an arbitrary source.
     * The server field is from the authentic catalog, not model prose.
     */
    private fun disambiguateServer(
        candidates: List<JSONObject>, request: String
    ): JSONObject? {
        val requestedTokens = titleTokens.findAll(request.lowercase(Locale.ROOT))
            .map { it.value }.toSet()
        fun ownTokens(candidate: JSONObject) =
            titleTokens.findAll(candidate.optString("server").lowercase(Locale.ROOT))
                .map { it.value }.filter { it !in genericServerWords }.toSet()
        val matches = candidates.filter { candidate ->
            val others = candidates.filterNot { it === candidate }
                .flatMap { ownTokens(it) }.toSet()
            ownTokens(candidate).any { token ->
                token in requestedTokens && token !in others
            }
        }
        return matches.singleOrNull()
    }


    /** Safe, credential-free reason why host planning cannot suggest a tool. */
    enum class Rejection {
        EXPLANATION_ONLY, NO_EXTERNAL_TASK, NO_CATALOG_MATCH,
        AMBIGUOUS_TOOL, UNVERIFIED_READONLY, ARGUMENTS_UNAVAILABLE,
        TOOL_DETAILS_UNAVAILABLE
    }

    // The host knows only a small audited set of read-only contracts.
    // This selects a candidate name, NOT a permission to execute it.
    private fun intendedSafeTool(request: String): String? {
        val lower = request.lowercase(Locale.ROOT)
        return when {
            Regex("\\b(?:crawl|crawle|crawlen)\\b").containsMatchIn(lower) &&
                webUrl.containsMatchIn(request) -> "crawl"
            Regex("\\bdiscord\\b").containsMatchIn(lower) &&
                Regex("\\b(?:status|verbindung|verbindungsstatus|connection|connectionstatus)\\b")
                    .containsMatchIn(lower) -> "discord_connection_status"
            Regex("\\b(?:status|systemstatus|systemzustand|serverstatus|serverzustand)\\b")
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
        if (McpRequestRouting.isExplanationOnly(request)) return null
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

    fun propose(
        request: String,
        run: McpProgressiveBridge.Run,
        onRejected: (Rejection) -> Unit = {}
    ): McpProgressiveBridge.RemoteCall? {
        fun reject(why: Rejection): McpProgressiveBridge.RemoteCall? {
            onRejected(why)
            return null
        }
        if (McpRequestRouting.isExplanationOnly(request))
            return reject(Rejection.EXPLANATION_ONLY)
        if (!mentionsExternal.containsMatchIn(request))
            return reject(Rejection.NO_EXTERNAL_TASK)
        val terms = words.findAll(request.lowercase(Locale.ROOT)).map { it.value }
            .filter { it !in stop && !it.all(Char::isDigit) }.distinct().take(10).toList()
        val namedAlternatives = explicitTool.findAll(request)
            .map { it.groupValues[1].lowercase(Locale.ROOT) }
            .filterNot {
                it in setOf("https", "http", "url", "website") ||
                    it in genericActionWords
            }
            .distinct().take(2).toList()
        // An instruction mentioning two explicit tool alternatives is NOT
        // permission for the host to arbitrarily choose the first one.
        if (namedAlternatives.size > 1)
            return reject(Rejection.AMBIGUOUS_TOOL)
        val requestedName = namedAlternatives.singleOrNull()
            ?: intendedSafeTool(request)
        val query = requestedName ?: terms.joinToString(" ").take(120)
        if (query.isBlank()) return reject(Rejection.NO_CATALOG_MATCH)
        val response = run.search(query, originalRequest = request)
        if (response.optBoolean("isError"))
            return reject(Rejection.NO_CATALOG_MATCH)
        val payload = response.optJSONArray("content")?.optJSONObject(0)?.optString("text")
            ?: return reject(Rejection.NO_CATALOG_MATCH)
        val matches = JSONObject(payload).optJSONArray("matches")
            ?: return reject(Rejection.NO_CATALOG_MATCH)
        val candidates = (0 until matches.length()).mapNotNull { matches.optJSONObject(it) }
        if (candidates.isEmpty())
            return reject(Rejection.NO_CATALOG_MATCH)
        // Explicit function name or unambiguous single result only.
        val chosen = when {
            requestedName != null -> {
                val exact = candidates.filter {
                    it.optString("name").equals(requestedName, ignoreCase = true)
                }
                when (exact.size) {
                    0 -> null
                    1 -> exact.single()
                    else -> {
                        // Don't infer a unique tool when search(limit=6)
                        // might have omitted another server's same-name tool.
                        val totalExact = run.sources.sumOf { source ->
                            source.tools.count {
                                it.name.equals(requestedName, ignoreCase = true)
                            }
                        }
                        if (totalExact == exact.size)
                            disambiguateServer(exact, request)
                        else null
                    }
                }
            }
            candidates.size == 1 -> candidates.single()
            else -> candidates.filter { candidate ->
                val name = candidate.optString("name")
                val escaped = Regex.escape(name)
                Regex("(?i)(?:^|[^a-z0-9_])" + escaped + "(?:$|[^a-z0-9_])")
                    .containsMatchIn(request)
            }.singleOrNull()
        } ?: return reject(Rejection.AMBIGUOUS_TOOL)
        val id = chosen.optString("tool_id")
        val tool = run.tool(id)?.tool ?: return reject(Rejection.NO_CATALOG_MATCH)
        // Host fallback never guesses a side-effecting tool, even when the
        // caller might later approve it. Native model calls stay available.
        // An unknown tool cannot be classified read-only by its name.
        // The fallback is deliberately limited to exact audited contracts;
        // native tool calls still support all permitted MCP tools.
        if (tool.name !in setOf("crawl", "status", "discord_connection_status",
                "current_time", "models", "device_info") ||
            !tool.declaresReadOnly)
            return reject(Rejection.UNVERIFIED_READONLY)
        // Never guess mandatory parameters; allow a single HTTPS URL only for
        // a schema requiring exactly one URL-like string property.
        val schema = tool.schema
        val mandatory = schema.optJSONArray("required")
        val args = JSONObject()
        if (mandatory != null && mandatory.length() > 0) {
            if (mandatory.length() != 1)
                return reject(Rejection.ARGUMENTS_UNAVAILABLE)
            val field = mandatory.optString(0)
            if (field !in setOf("url","uri","target_url"))
                return reject(Rejection.ARGUMENTS_UNAVAILABLE)
            val prop = schema.optJSONObject("properties")?.optJSONObject(field)
                ?: return reject(Rejection.ARGUMENTS_UNAVAILABLE)
            if (prop.optString("type") != "string")
                return reject(Rejection.ARGUMENTS_UNAVAILABLE)
            val url = webUrl.find(request)?.value?.trimEnd('.', ',', ';', ')', ']', '}')
                ?: return reject(Rejection.ARGUMENTS_UNAVAILABLE)
            args.put(field,url)
        }
        val detail = run.details(id, request)
        if (detail.optBoolean("isError"))
            return reject(Rejection.TOOL_DETAILS_UNAVAILABLE)
        return run.prepareCall(id, args.toString(), request).copy(hostFallback = true)
    }
}
